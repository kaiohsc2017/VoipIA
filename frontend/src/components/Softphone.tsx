/**
 * Softphone.tsx — Softphone WebRTC embutido no navegador usando JsSIP.
 * Conecta ao Asterisk via proxy WebSocket do Nginx (/asterisk-ws).
 * Em produção: wss://app.voiphash.com.br/asterisk-ws
 * Em dev local: ws://localhost:8088/ws
 */
import { useEffect, useRef, useState } from 'react';
import JsSIP from 'jssip';

// ─── Config ─────────────────────────────────────────────────────────────────
// Em produção o Nginx faz proxy /asterisk-ws → asterisk:8088/ws
// Detecta automaticamente o protocolo (wss em HTTPS, ws em HTTP)
const getWsUrl = () => {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const host  = window.location.host;
  return import.meta.env.VITE_ASTERISK_WS ?? `${proto}://${host}/asterisk-ws`;
};

const getSipDomain = () => window.location.hostname;


/** Obtém ramal do usuário logado ou usa 9001 como padrão */
const getUserExtension = (): string => {
  try {
    const token = localStorage.getItem('asteriskia_token');
    if (!token) return '9001';
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.extension ? String(payload.extension) : '9001';
  } catch { return '9001'; }
};

/** Senha do ramal baseada no número (padrão: webrtcXXXXpass) */
const getExtPassword = (ext: string) =>
  import.meta.env.VITE_SIP_PASSWORD ?? `webrtc${ext}pass`;

type CallState = 'idle' | 'calling' | 'incoming' | 'active' | 'ended';
type RegState  = 'unregistered' | 'registering' | 'registered' | 'failed';

// JsSIP session type
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type SipSession = any;

export default function Softphone() {
  const extension   = getUserExtension();
  const wsUrl       = getWsUrl();
  const sipDomain   = getSipDomain();
  const sipUri      = `sip:${extension}@${sipDomain}`;
  const sipPassword = getExtPassword(extension);

  const [open,      setOpen]      = useState(false);
  const [callState, setCallState] = useState<CallState>('idle');
  const [regState,  setRegState]  = useState<RegState>('unregistered');
  const [dialInput, setDialInput] = useState('');
  const [muted,     setMuted]     = useState(false);
  const [logLines,  setLogLines]  = useState<string[]>([]);
  const [duration,  setDuration]  = useState(0);

  const uaRef          = useRef<JsSIP.UA | null>(null);
  const sessionRef     = useRef<SipSession>(null);
  const remoteRef      = useRef<HTMLAudioElement | null>(null);
  const timerRef       = useRef<ReturnType<typeof setInterval> | null>(null);
  const callStateRef   = useRef<CallState>('idle');
  const dialTimerRef   = useRef<ReturnType<typeof setTimeout> | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const wsGraceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // TURN server — UDP, TCP e TLS para máxima compatibilidade com firewalls corporativos
  const turnBase = import.meta.env.VITE_TURN_URL ?? '';
  const turnUser = import.meta.env.VITE_TURN_USER ?? 'asteriskia';
  const turnCred = import.meta.env.VITE_TURN_CREDENTIAL ?? '';
  const turnEntries = turnBase ? [
    { urls: turnBase,                                  username: turnUser, credential: turnCred },
    { urls: `${turnBase}?transport=tcp`,               username: turnUser, credential: turnCred },
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
    JsSIP.debug.enable('JsSIP:*');
    const socket = new JsSIP.WebSocketInterface(wsUrl);
    const ua = new JsSIP.UA({
      sockets: [socket],
      uri: sipUri,
      password: sipPassword,
      display_name: `Ramal ${extension}`,
      register: true,
      register_expires: 90,
      user_agent: 'AsteriskIA-Softphone/1.0',
      // Desabilita session timers (evita re-INVITE desnecessário)
      session_timers: false,
      // Recuperação de conexão mais rápida
      connection_recovery_min_interval: 2,
      connection_recovery_max_interval: 10,
    });

    ua.on('registered',   () => { setRegState('registered');    log('Ramal registrado ✓'); });
    ua.on('unregistered', () => { setRegState('unregistered');  log('Ramal desregistrado'); });
    ua.on('registrationFailed', () => { setRegState('failed');  log('Falha no registro'); });
    ua.on('connected', () => {
      log('WebSocket conectado');
      // WS voltou a tempo — cancela o encerramento da chamada por queda de sinalização
      if (wsGraceTimerRef.current) {
        clearTimeout(wsGraceTimerRef.current);
        wsGraceTimerRef.current = null;
        log('WebSocket reconectado — chamada mantida ✓');
      }
    });
    ua.on('disconnected', () => {
      log('WebSocket desconectado — reconectando…');
      const cs = callStateRef.current;
      // O RTP trafega por UDP/ICE, independente do WebSocket de sinalização — uma
      // queda passageira do WS (comum em redes móveis) não derruba o áudio. Em vez
      // de encerrar na hora, aguarda o JsSIP reconectar (connection_recovery_*)
      // e só encerra se a sinalização não voltar dentro da janela de tolerância.
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
            setTimeout(() => { callStateRef.current = 'idle'; setCallState('idle'); }, 2000);
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
        session.on('ended',   () => endSession('Chamada encerrada'));
        session.on('failed',  () => endSession('Chamada falhou'));
        session.on('peerconnection', () => attachRemoteAudio(session));
      } else {
        // Sainte: 'peerconnection' disparou antes de newRTCSession — acessa PC diretamente
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const pc = (session as any).connection as RTCPeerConnection | null;
        if (pc) {
          log(`[3] PeerConnection (ICE=${pc.iceGatheringState})`);
          pc.addEventListener('icegatheringstatechange', () =>
            log(`[ICE-PC] ${pc.iceGatheringState}`)
          );
        }

        let iceReadyCb: (() => void) | null = null;
        let iceDone = false;

        // Força envio do INVITE após 15s se ICE não completar (STUN/TURN ainda pendente)
        const iceTimeout = setTimeout(() => {
          if (!iceDone) {
            if (iceReadyCb) {
              iceDone = true;
              log('[ICE] timeout 15s — forçando INVITE com candidatos disponíveis');
              iceReadyCb();
            } else {
              log('[ICE] timeout 15s — nenhum candidato ainda (createOffer travado?)');
            }
          }
        }, 15000);

        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        session.on('icecandidate', (data: any) => {
          iceReadyCb = data.ready;
          const type = (data.candidate?.type) ?? 'null';
          log(`[ICE] candidato: ${type}`);
          // IP público (srflx/relay) disponível → envia INVITE imediatamente
          if (type === 'srflx' || type === 'relay') {
            iceDone = true;
            clearTimeout(iceTimeout);
            log('[ICE] IP público confirmado — enviando INVITE');
            data.ready();
          }
        });

        session.on('connecting', () => log('[2.5] connecting — SDP em preparação'));
        session.on('sending', () => {
          iceDone = true;
          clearTimeout(iceTimeout);
          log('[2.8] INVITE enviado →');
        });
      }
    });

    ua.start();
    uaRef.current = ua;
    setRegState('registering');
    log('Conectando ao Asterisk…');

    // Re-registra quando a aba volta ao foco — previne WebSocket morto após throttling do browser
    const handleVisibility = () => {
      if (!document.hidden && uaRef.current?.isRegistered() === false) {
        log('Aba retomada — re-registrando…');
        uaRef.current.register();
      }
    };
    document.addEventListener('visibilitychange', handleVisibility);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibility);
      if (wsGraceTimerRef.current) { clearTimeout(wsGraceTimerRef.current); wsGraceTimerRef.current = null; }
      ua.stop();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function attachRemoteAudio(session: SipSession) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const getPC = (): RTCPeerConnection | null => (session as any).connection ?? null;

    const tryAttach = () => {
      const pc = getPC();
      if (!pc || !remoteRef.current) return;

      // Attach tracks já existentes (chegaram antes do listener)
      pc.getReceivers().forEach(r => {
        if (r.track?.kind === 'audio' && remoteRef.current) {
          const stream = new MediaStream([r.track]);
          remoteRef.current.srcObject = stream;
          remoteRef.current.play().catch(() => {/* autoplay policy */});
        }
      });

      // Escuta tracks futuros
      pc.addEventListener('track', (e: RTCTrackEvent) => {
        if (e.track.kind === 'audio' && remoteRef.current) {
          remoteRef.current.srcObject = e.streams[0] ?? new MediaStream([e.track]);
          remoteRef.current.play().catch(() => {/* autoplay policy */});
        }
      });

      // ICE state changes — reconecta mídia se necessário
      pc.addEventListener('iceconnectionstatechange', () => {
        const state = pc.iceConnectionState;
        log(`ICE: ${state}`);
        if (state === 'connected' || state === 'completed') {
          // Re-tenta attach após ICE conectar
          setTimeout(tryAttach, 100);
        }
      });
    };

    // Tenta imediatamente e também após peerconnection estar pronto
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
    setTimeout(() => updateCallState('idle'), 2000);
  }

  async function dial() {
    if (!uaRef.current || !dialInput.trim() || callState !== 'idle') return;
    if (!uaRef.current.isRegistered()) {
      log('Aguardando registro SIP — tente novamente em instantes');
      return;
    }

    // Adquire o stream aqui e passa diretamente ao JsSIP — evita getUserMedia interno travar
    log('[MIC] Solicitando microfone…');
    let localStream: MediaStream;
    try {
      localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      log('[MIC] Microfone OK ✓');
    } catch (err: unknown) {
      const msg = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
      log(`[MIC] ERRO — ${msg}`);
      return;
    }
    localStreamRef.current = localStream;

    const sipHost = sipDomain;
    const target = dialInput.trim().includes('@')
      ? `sip:${dialInput.trim()}`
      : `sip:${dialInput.trim()}@${sipHost}`;

    log(`[1] Iniciando: ${target}`);

    let session: SipSession;
    try {
      session = uaRef.current.call(target, {
        mediaStream: localStream,
        rtcOfferConstraints: { offerToReceiveAudio: true, offerToReceiveVideo: false },
        pcConfig: rtcConfig,
      });
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      log(`[ERRO] ua.call falhou: ${msg}`);
      return;
    }

    log(`[2] Sessão criada`);
    sessionRef.current = session;
    updateCallState('calling');

    // Timeout de 30s — libera UI se o INVITE nunca receber resposta
    dialTimerRef.current = setTimeout(() => {
      dialTimerRef.current = null;
      try { sessionRef.current?.terminate(); } catch { /* já encerrada */ }
      endSession('Falhou: Sem resposta (timeout 30s)');
    }, 30_000);

    session.on('progress', () => log('[4] Chamando…'));
    session.on('confirmed', () => {
      // Chamada atendida — cancela o timeout de "sem resposta", senão ele
      // encerra a ligação sozinho aos 30s mesmo com a chamada já ativa
      if (dialTimerRef.current) { clearTimeout(dialTimerRef.current); dialTimerRef.current = null; }
      updateCallState('active');
      startTimer();
      log('Chamada conectada ✓');
    });
    session.on('ended',  () => endSession('Chamada encerrada'));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    session.on('failed', (e: any) => {
      const cause   = e?.cause ?? 'desconhecido';
      const code    = e?.message?.status_code ?? '';
      const reason  = e?.message?.reason_phrase ?? '';
      const origin  = e?.originator ?? '';
      endSession(`Falhou: ${cause}${code ? ` [${code} ${reason}]` : ''}${origin ? ` (${origin})` : ''}`);
    });
    attachRemoteAudio(session);
  }


  function answer() {
    if (!sessionRef.current) return;
    sessionRef.current.answer({ 
      mediaConstraints: { audio: true, video: false },
      pcConfig: rtcConfig 
    });
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
    else        { session.mute({ audio: true });   setMuted(true);  log('Mute ativado'); }
  }

  const keys = ['1','2','3','4','5','6','7','8','9','*','0','#'];
  function pressKey(k: string) {
    if (callState === 'active') {
      // Durante chamada: * e # enviam DTMF, números também
      try { sessionRef.current?.sendDTMF(k); } catch { /* não suportado */ }
      log(`DTMF: ${k}`);
    } else if (callState === 'idle') {
      // Em idle: * e # não são válidos em ramal — ignora
      if (k === '*' || k === '#') return;
      setDialInput(v => v + k);
    }
  }

  const fmtDur = `${String(Math.floor(duration / 60)).padStart(2, '0')}:${String(duration % 60).padStart(2, '0')}`;

  const regBadge = {
    registered:   { color: '#68d391', label: 'Registrado' },
    registering:  { color: '#f6ad55', label: 'Conectando…' },
    unregistered: { color: '#94a3b8', label: 'Offline' },
    failed:       { color: '#fc8181', label: 'Erro' },
  }[regState];

  const callBadge = {
    idle:     null,
    calling:  { color: '#f6ad55', label: '📞 Chamando…' },
    incoming: { color: '#7c3aed', label: '📲 Chamada Entrante' },
    active:   { color: '#68d391', label: `🟢 ${fmtDur}` },
    ended:    { color: '#94a3b8', label: 'Encerrada' },
  }[callState];

  return (
    <>
      <audio ref={remoteRef} autoPlay playsInline style={{ display: 'none' }} />

      <button
        onClick={() => setOpen(o => !o)}
        style={{
          position: 'fixed', bottom: 24, right: 24, zIndex: 1000,
          width: 54, height: 54, borderRadius: '50%',
          background: callState === 'active' ? 'linear-gradient(135deg,#22c55e,#16a34a)'
            : callState === 'incoming' ? 'linear-gradient(135deg,#7c3aed,#4f46e5)'
            : 'linear-gradient(135deg,#3b82f6,#2563eb)',
          border: 'none', cursor: 'pointer',
          boxShadow: '0 4px 20px rgba(59,130,246,0.4)',
          fontSize: 22, display: 'flex', alignItems: 'center', justifyContent: 'center',
          animation: callState === 'incoming' ? 'pulse 1s infinite' : 'none',
          transition: 'transform 0.2s',
        }}
        title="Softphone WebRTC"
      >
        {callState === 'active' ? '📵' : callState === 'incoming' ? '📲' : '📞'}
      </button>

      {open && (
        <div style={{
          position: 'fixed', bottom: 90, right: 24, zIndex: 1000,
          width: 300,
          background: 'rgba(15,23,42,0.97)',
          backdropFilter: 'blur(16px)',
          border: '1px solid rgba(148,163,184,0.15)',
          borderRadius: 16,
          boxShadow: '0 24px 60px rgba(0,0,0,0.5)',
          overflow: 'hidden',
          fontFamily: 'Inter, sans-serif',
        }}>
          <div style={{
            background: 'linear-gradient(135deg,#1e1b4b,#172554)',
            padding: '14px 18px',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <div>
              <div style={{ fontWeight: 700, fontSize: '0.9rem', color: '#e2e8f0' }}>
                📞 Ramal {extension}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
                <div style={{ width: 7, height: 7, borderRadius: '50%', background: regBadge.color }} />
                <span style={{ fontSize: '0.74rem', color: '#94a3b8' }}>{regBadge.label}</span>
              </div>
            </div>
            {callBadge && (
              <span style={{
                fontSize: '0.78rem', fontWeight: 600, color: callBadge.color,
                background: `${callBadge.color}20`, padding: '3px 10px', borderRadius: 20,
                border: `1px solid ${callBadge.color}40`,
              }}>
                {callBadge.label}
              </span>
            )}
          </div>

          <div style={{ padding: '16px 16px 12px' }}>
            <input
              type="text" value={dialInput}
              onChange={e => setDialInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && dial()}
              placeholder="Ramal ou número…"
              style={{
                width: '100%', boxSizing: 'border-box',
                background: 'rgba(30,41,59,0.8)',
                border: '1px solid rgba(148,163,184,0.2)',
                borderRadius: 10, padding: '10px 14px',
                color: '#e2e8f0', fontSize: '1rem',
                letterSpacing: 2, textAlign: 'center', outline: 'none', marginBottom: 12,
              }}
            />
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 6, marginBottom: 14 }}>
              {keys.map(k => (
                <button key={k} onClick={() => pressKey(k)} style={{
                  background: 'rgba(30,41,59,0.6)',
                  border: '1px solid rgba(148,163,184,0.1)',
                  borderRadius: 8, padding: '10px 0',
                  color: '#cbd5e1', fontSize: '1rem', fontWeight: 600, cursor: 'pointer',
                }}>{k}</button>
              ))}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              {callState === 'idle' && (
                <ActionBtn color="#22c55e" onClick={dial} disabled={!dialInput.trim()} label="📞 Ligar" />
              )}
              {callState === 'incoming' && (
                <>
                  <ActionBtn color="#22c55e" onClick={answer} label="✓ Atender" />
                  <ActionBtn color="#ef4444" onClick={hangup} label="✕ Rejeitar" />
                </>
              )}
              {(callState === 'calling' || callState === 'active') && (
                <>
                  <ActionBtn color="#ef4444" onClick={hangup} label="📵 Encerrar" />
                  <ActionBtn color={muted ? '#7c3aed' : '#475569'} onClick={toggleMute}
                    label={muted ? '🔇 Muted' : '🎙 Mute'} />
                </>
              )}
              {dialInput && callState === 'idle' && (
                <ActionBtn color="#475569" onClick={() => setDialInput('')} label="⌫" />
              )}
            </div>
            {logLines.length > 0 && (
              <div style={{
                marginTop: 12, maxHeight: 80, overflowY: 'auto',
                background: 'rgba(0,0,0,0.25)', borderRadius: 8, padding: '6px 10px',
              }}>
                {logLines.map((l, i) => (
                  <div key={i} style={{ fontSize: '0.7rem', color: '#64748b', lineHeight: '1.6', fontFamily: 'monospace' }}>{l}</div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}

function ActionBtn({ color, onClick, disabled, label }: {
  color: string; onClick: () => void; disabled?: boolean; label: string;
}) {
  return (
    <button onClick={onClick} disabled={disabled} style={{
      flex: 1, padding: '10px 0',
      background: disabled ? 'rgba(30,41,59,0.4)' : `${color}20`,
      border: `1px solid ${disabled ? 'rgba(148,163,184,0.1)' : color + '50'}`,
      borderRadius: 10, color: disabled ? '#4b5563' : color,
      fontWeight: 600, fontSize: '0.82rem',
      cursor: disabled ? 'not-allowed' : 'pointer',
    }}>{label}</button>
  );
}
