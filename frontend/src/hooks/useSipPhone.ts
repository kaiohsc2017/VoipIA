/**
 * useSipPhone.ts — lógica de registro/chamada SIP via JsSIP, extraída de Softphone.tsx (Fase 13
 * do plano Call Center Parte III) para ser reusável pelo painel de chamada do Desktop do Agente
 * quando a SPA do Call Center roda fora do shell (D10-A — nunca dois UAs no mesmo ramal).
 *
 * Resolução de credencial, em ordem (D9-A):
 *   1. Agente de call center autenticado → GET /callcenter/agentes/me/sip-credentials
 *      (ramal 4xxx + secret próprio, rotacionável, nunca em bundle JS).
 *   2. Sem agente de call center, mas com claim `extension` no JWT → VITE_SIP_PASSWORD
 *      (comportamento legado dos ramais 9xxx do softphone do Telecom).
 *   3. Nenhum dos dois → não registra. Estado explícito 'no-extension', nunca um fallback
 *      silencioso pra outro ramal (achado de segurança do plano original — Softphone.tsx caía
 *      pra '9001' hardcoded sem claim, registrando no ramal de outra pessoa).
 */
import { useEffect, useRef, useState, type RefObject } from 'react';
import JsSIP from 'jssip';
import api, { decodeTokenPayload } from '../api/client';

export type CallState = 'idle' | 'calling' | 'incoming' | 'active' | 'ended';
export type RegState = 'unregistered' | 'registering' | 'registered' | 'failed' | 'no-extension';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type SipSession = any;

const getWsUrl = () => {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const host = window.location.host;
  return import.meta.env.VITE_ASTERISK_WS ?? `${proto}://${host}/asterisk-ws`;
};

const getSipDomain = () => window.location.hostname;

interface ResolvedCredentials {
  extension: string;
  password: string;
}

async function resolveCredentials(): Promise<ResolvedCredentials | null> {
  try {
    const { data } = await api.get<{ extension: string; secret: string }>('/callcenter/agentes/me/sip-credentials');
    return { extension: data.extension, password: data.secret };
  } catch {
    // 404 sem vínculo de agente de call center — cai pro ramal legado (softphone 9xxx), não é erro.
  }

  const token = localStorage.getItem('voipia_token');
  if (!token) return null;
  const claimExtension = decodeTokenPayload(token).extension;
  const legacyPassword = import.meta.env.VITE_SIP_PASSWORD;
  if (claimExtension && legacyPassword) {
    return { extension: String(claimExtension), password: legacyPassword };
  }
  return null;
}

export interface SipPhoneApi {
  extension: string | null;
  regState: RegState;
  callState: CallState;
  muted: boolean;
  duration: number;
  dialInput: string;
  setDialInput: (v: string) => void;
  logLines: string[];
  remoteAudioRef: RefObject<HTMLAudioElement | null>;
  dial: (target?: string) => Promise<void>;
  answer: () => void;
  hangup: () => void;
  toggleMute: () => void;
  pressKey: (k: string) => void;
}

export function useSipPhone(): SipPhoneApi {
  const [extension, setExtension] = useState<string | null>(null);
  const [callState, setCallState] = useState<CallState>('idle');
  const [regState, setRegState] = useState<RegState>('registering');
  const [dialInput, setDialInput] = useState('');
  const [muted, setMuted] = useState(false);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [duration, setDuration] = useState(0);

  const uaRef = useRef<JsSIP.UA | null>(null);
  const sessionRef = useRef<SipSession>(null);
  const remoteRef = useRef<HTMLAudioElement | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const callStateRef = useRef<CallState>('idle');
  const dialTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const wsGraceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const turnBase = import.meta.env.VITE_TURN_URL ?? '';
  const turnUser = import.meta.env.VITE_TURN_USER ?? 'asteriskia';
  const turnCred = import.meta.env.VITE_TURN_CREDENTIAL ?? '';
  const turnEntries = turnBase ? [
    { urls: turnBase, username: turnUser, credential: turnCred },
    { urls: `${turnBase}?transport=tcp`, username: turnUser, credential: turnCred },
    { urls: turnBase.replace('turn:', 'turns:').replace(':3478', ':5349'), username: turnUser, credential: turnCred },
  ] : [];
  const rtcConfig = {
    iceServers: [
      { urls: import.meta.env.VITE_STUN_URL || 'stun:stun.l.google.com:19302' },
      { urls: 'stun:stun1.l.google.com:19302' },
      ...turnEntries,
    ],
  };

  const log = (msg: string) => {
    const ts = new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    setLogLines(prev => [`[${ts}] ${msg}`, ...prev].slice(0, 20));
  };

  const updateCallState = (state: CallState) => {
    callStateRef.current = state;
    setCallState(state);
  };

  useEffect(() => {
    let cancelled = false;

    resolveCredentials().then(creds => {
      if (cancelled) return;
      if (!creds) {
        setRegState('no-extension');
        log('Sem ramal atribuído — softphone não vai registrar.');
        return;
      }
      setExtension(creds.extension);

      if (import.meta.env.DEV) JsSIP.debug.enable('JsSIP:*');
      const sipUri = `sip:${creds.extension}@${getSipDomain()}`;
      const socket = new JsSIP.WebSocketInterface(getWsUrl());
      const ua = new JsSIP.UA({
        sockets: [socket],
        uri: sipUri,
        password: creds.password,
        display_name: `Ramal ${creds.extension}`,
        register: true,
        register_expires: 90,
        user_agent: 'VoipIA-Softphone/1.0',
        session_timers: false,
        connection_recovery_min_interval: 2,
        connection_recovery_max_interval: 10,
      });

      ua.on('registered', () => { setRegState('registered'); log('Ramal registrado ✓'); });
      ua.on('unregistered', () => { setRegState('unregistered'); log('Ramal desregistrado'); });
      ua.on('registrationFailed', () => { setRegState('failed'); log('Falha no registro'); });
      ua.on('connected', () => {
        log('WebSocket conectado');
        if (wsGraceTimerRef.current) {
          clearTimeout(wsGraceTimerRef.current);
          wsGraceTimerRef.current = null;
          log('WebSocket reconectado — chamada mantida ✓');
        }
      });
      ua.on('disconnected', () => {
        log('WebSocket desconectado — reconectando…');
        const cs = callStateRef.current;
        if ((cs === 'calling' || cs === 'active' || cs === 'incoming') && !wsGraceTimerRef.current) {
          wsGraceTimerRef.current = setTimeout(() => {
            wsGraceTimerRef.current = null;
            const stillOngoing = callStateRef.current;
            if (stillOngoing === 'calling' || stillOngoing === 'active' || stillOngoing === 'incoming') {
              if (dialTimerRef.current) { clearTimeout(dialTimerRef.current); dialTimerRef.current = null; }
              try { sessionRef.current?.terminate(); } catch { /* já encerrada */ }
              callStateRef.current = 'ended';
              setCallState('ended');
              setMuted(false);
              log('Chamada interrompida — WebSocket não reconectou a tempo');
              idleTimerRef.current = setTimeout(() => {
                idleTimerRef.current = null;
                callStateRef.current = 'idle';
                setCallState('idle');
              }, 2000);
            }
          }, 10_000);
        }
      });

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      ua.on('newRTCSession', (e: any) => {
        const session: SipSession = e.session;
        sessionRef.current = session;

        if (session.direction === 'incoming') {
          setCallState('incoming');
          log(`Chamada de: ${session.remote_identity?.display_name || session.remote_identity?.uri?.user}`);
          session.on('ended', () => endSession('Chamada encerrada'));
          session.on('failed', () => endSession('Chamada falhou'));
          session.on('peerconnection', () => attachRemoteAudio(session));
        } else {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const pc = (session as any).connection as RTCPeerConnection | null;
          let iceReadyCb: (() => void) | null = null;
          let iceDone = false;
          const iceTimeout = setTimeout(() => {
            if (!iceDone && iceReadyCb) { iceDone = true; iceReadyCb(); }
          }, 15000);
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          session.on('icecandidate', (data: any) => {
            iceReadyCb = data.ready;
            const type = data.candidate?.type ?? 'null';
            if (type === 'srflx' || type === 'relay') {
              iceDone = true;
              clearTimeout(iceTimeout);
              data.ready();
            }
          });
          session.on('sending', () => { iceDone = true; clearTimeout(iceTimeout); log('[INVITE] enviado'); });
          void pc;
        }
      });

      ua.start();
      uaRef.current = ua;
      setRegState('registering');
      log('Conectando ao Asterisk…');

      const handleVisibility = () => {
        if (!document.hidden && uaRef.current?.isRegistered() === false) {
          log('Aba retomada — re-registrando…');
          uaRef.current.register();
        }
      };
      document.addEventListener('visibilitychange', handleVisibility);

      (ua as unknown as { __cleanup?: () => void }).__cleanup = () => {
        document.removeEventListener('visibilitychange', handleVisibility);
      };
    });

    return () => {
      cancelled = true;
      if (wsGraceTimerRef.current) { clearTimeout(wsGraceTimerRef.current); wsGraceTimerRef.current = null; }
      if (dialTimerRef.current) { clearTimeout(dialTimerRef.current); dialTimerRef.current = null; }
      if (idleTimerRef.current) { clearTimeout(idleTimerRef.current); idleTimerRef.current = null; }
      if (timerRef.current) clearInterval(timerRef.current);
      localStreamRef.current?.getTracks().forEach(t => t.stop());
      localStreamRef.current = null;
      const ua = uaRef.current;
      if (ua) {
        (ua as unknown as { __cleanup?: () => void }).__cleanup?.();
        ua.stop();
      }
    };
    // Registro SIP só no mount/desmonte — mesma justificativa de Softphone.tsx original.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function attachRemoteAudio(session: SipSession) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const getPC = (): RTCPeerConnection | null => (session as any).connection ?? null;
    const tryAttach = () => {
      const pc = getPC();
      if (!pc || !remoteRef.current) return;
      pc.getReceivers().forEach(r => {
        if (r.track?.kind === 'audio' && remoteRef.current) {
          remoteRef.current.srcObject = new MediaStream([r.track]);
          remoteRef.current.play().catch(() => {/* autoplay policy */});
        }
      });
      pc.addEventListener('track', (e: RTCTrackEvent) => {
        if (e.track.kind === 'audio' && remoteRef.current) {
          remoteRef.current.srcObject = e.streams[0] ?? new MediaStream([e.track]);
          remoteRef.current.play().catch(() => {/* autoplay policy */});
        }
      });
      pc.addEventListener('iceconnectionstatechange', () => {
        if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
          setTimeout(tryAttach, 100);
        }
      });
    };
    tryAttach();
    setTimeout(tryAttach, 500);
    setTimeout(tryAttach, 1500);
  }

  function startTimer() {
    setDuration(0);
    timerRef.current = setInterval(() => setDuration(d => d + 1), 1000);
  }

  function endSession(msg: string) {
    if (dialTimerRef.current) { clearTimeout(dialTimerRef.current); dialTimerRef.current = null; }
    if (wsGraceTimerRef.current) { clearTimeout(wsGraceTimerRef.current); wsGraceTimerRef.current = null; }
    if (timerRef.current) clearInterval(timerRef.current);
    localStreamRef.current?.getTracks().forEach(t => t.stop());
    localStreamRef.current = null;
    sessionRef.current = null;
    updateCallState('ended');
    setMuted(false);
    log(msg);
    idleTimerRef.current = setTimeout(() => {
      idleTimerRef.current = null;
      updateCallState('idle');
    }, 2000);
  }

  async function dial(target?: string) {
    const raw = (target ?? dialInput).trim();
    if (!uaRef.current || !raw || callStateRef.current !== 'idle') return;
    if (!uaRef.current.isRegistered()) { log('Aguardando registro SIP — tente novamente em instantes'); return; }

    let localStream: MediaStream;
    try {
      localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    } catch (err: unknown) {
      log(`[MIC] ERRO — ${err instanceof Error ? err.message : String(err)}`);
      return;
    }
    localStreamRef.current = localStream;

    const sipTarget = raw.includes('@') ? `sip:${raw}` : `sip:${raw}@${getSipDomain()}`;
    let session: SipSession;
    try {
      session = uaRef.current.call(sipTarget, {
        mediaStream: localStream,
        rtcOfferConstraints: { offerToReceiveAudio: true, offerToReceiveVideo: false },
        pcConfig: rtcConfig,
      });
    } catch (err: unknown) {
      log(`[ERRO] ua.call falhou: ${err instanceof Error ? err.message : String(err)}`);
      return;
    }

    sessionRef.current = session;
    updateCallState('calling');
    dialTimerRef.current = setTimeout(() => {
      dialTimerRef.current = null;
      try { sessionRef.current?.terminate(); } catch { /* já encerrada */ }
      endSession('Falhou: Sem resposta (timeout 30s)');
    }, 30_000);

    session.on('progress', () => log('Chamando…'));
    session.on('confirmed', () => {
      if (dialTimerRef.current) { clearTimeout(dialTimerRef.current); dialTimerRef.current = null; }
      updateCallState('active');
      startTimer();
      log('Chamada conectada ✓');
    });
    session.on('ended', () => endSession('Chamada encerrada'));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    session.on('failed', (e: any) => {
      endSession(`Falhou: ${e?.cause ?? 'desconhecido'}`);
    });
    attachRemoteAudio(session);
  }

  function answer() {
    if (!sessionRef.current) return;
    sessionRef.current.answer({ mediaConstraints: { audio: true, video: false }, pcConfig: rtcConfig });
    sessionRef.current.on('confirmed', () => { updateCallState('active'); startTimer(); log('Chamada atendida'); });
    attachRemoteAudio(sessionRef.current);
  }

  function hangup() {
    try { sessionRef.current?.terminate(); } catch { /* já encerrada */ }
    endSession('Chamada encerrada pelo usuário');
  }

  function toggleMute() {
    const session = sessionRef.current;
    if (!session) return;
    if (muted) { session.unmute({ audio: true }); setMuted(false); log('Mute desativado'); }
    else { session.mute({ audio: true }); setMuted(true); log('Mute ativado'); }
  }

  function pressKey(k: string) {
    if (callState === 'active') {
      try { sessionRef.current?.sendDTMF(k); } catch { /* não suportado */ }
      log(`DTMF: ${k}`);
    } else if (callState === 'idle') {
      if (k === '*' || k === '#') return;
      setDialInput(v => v + k);
    }
  }

  return {
    extension, regState, callState, muted, duration, dialInput, setDialInput,
    logLines, remoteAudioRef: remoteRef, dial, answer, hangup, toggleMute, pressKey,
  };
}
