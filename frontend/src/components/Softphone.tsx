/**
 * Softphone.tsx — Softphone WebRTC embutido no shell do Telecom (flutuante em todas as páginas).
 * Fase 13 do plano Call Center Parte III: a lógica de registro/chamada SIP saiu para
 * hooks/useSipPhone.ts (D9-A — credencial resolvida por agente de call center primeiro, ramal
 * legado 9xxx depois, nunca um fallback silencioso). Este componente é só a UI + a ponte com o
 * painel de chamada do Desktop do Agente (D10-A — este é o ÚNICO UA SIP do shell; o iframe do
 * Call Center nunca instancia o próprio quando embutido, só quando aberto direto em /callcenter/).
 */
import { useEffect, useRef, useState } from 'react';
import { useSipPhone } from '../hooks/useSipPhone';
import { publishCallState, subscribeCallAction, type CallStatus } from '../lib/callBridge';

const toBridgeStatus = (callState: string): CallStatus => {
  if (callState === 'active') return 'active';
  if (callState === 'calling' || callState === 'incoming') return 'ringing';
  return 'idle';
};

export default function Softphone() {
  const phone = useSipPhone();
  const [open, setOpen] = useState(false);
  const lastDialTarget = useRef('');

  // Publica o estado da chamada para o Desktop do Agente (via CallCenterPage.tsx → postMessage)
  // toda vez que algo relevante muda — D10-A, o painel do iframe só reflete, nunca instancia UA.
  useEffect(() => {
    publishCallState({
      status: toBridgeStatus(phone.callState),
      remote: lastDialTarget.current,
      durationSeconds: phone.duration,
      muted: phone.muted,
    });
  }, [phone.callState, phone.duration, phone.muted]);

  // Recebe comandos do Desktop do Agente (answer/hangup/mute/dtmf/dial) — mesma tripla validação
  // de origem já aplicada no lado do CallCenterPage.tsx antes de chegar aqui via callBridge.
  useEffect(() => {
    return subscribeCallAction(action => {
      switch (action.action) {
        case 'answer': phone.answer(); break;
        case 'hangup': case 'reject': phone.hangup(); break;
        case 'mute': case 'unmute': phone.toggleMute(); break;
        case 'dtmf': phone.pressKey(action.payload); break;
        case 'dial':
          lastDialTarget.current = action.payload;
          void phone.dial(action.payload);
          break;
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const keys = ['1','2','3','4','5','6','7','8','9','*','0','#'];
  function pressKey(k: string) { phone.pressKey(k); }

  const fmtDur = `${String(Math.floor(phone.duration / 60)).padStart(2, '0')}:${String(phone.duration % 60).padStart(2, '0')}`;

  const regBadge = {
    registered:   { color: '#34c759', label: 'Registrado' },
    registering:  { color: '#ff9f0a', label: 'Conectando…' },
    unregistered: { color: '#94a3b8', label: 'Offline' },
    failed:       { color: '#ff6b6b', label: 'Erro' },
    'no-extension': { color: '#94a3b8', label: 'Sem ramal' },
  }[phone.regState];

  const callBadge = {
    idle:     null,
    calling:  { color: '#ff9f0a', label: '📞 Chamando…' },
    incoming: { color: '#007aff', label: '📲 Chamada Entrante' },
    active:   { color: '#34c759', label: `🟢 ${fmtDur}` },
    ended:    { color: '#94a3b8', label: 'Encerrada' },
  }[phone.callState];

  // Sem ramal atribuído (nem agente de call center, nem claim legado) — não mostra a bolha
  // flutuante. Antes registrava silenciosamente em '9001' (ramal de outra pessoa); agora, sem
  // credencial resolvida, simplesmente não há softphone para este usuário.
  if (phone.regState === 'no-extension') return null;

  return (
    <>
      <audio ref={phone.remoteAudioRef} autoPlay playsInline style={{ display: 'none' }} />

      <button
        onClick={() => setOpen(o => !o)}
        style={{
          position: 'fixed', bottom: 24, right: 24, zIndex: 1000,
          width: 54, height: 54, borderRadius: '50%',
          background: phone.callState === 'active' ? 'linear-gradient(135deg, var(--clr-success), var(--clr-success))'
            : phone.callState === 'incoming' ? 'linear-gradient(135deg,#007aff,#4da8ff)'
            : 'linear-gradient(135deg,#007aff,#4da8ff)',
          border: 'none', cursor: 'pointer',
          boxShadow: '0 4px 20px rgba(0,122,255,0.4)',
          fontSize: 22, display: 'flex', alignItems: 'center', justifyContent: 'center',
          animation: phone.callState === 'incoming' ? 'pulse 1s infinite' : 'none',
          transition: 'transform 0.2s',
        }}
        title="Softphone WebRTC"
      >
        {phone.callState === 'active' ? '📵' : phone.callState === 'incoming' ? '📲' : '📞'}
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
                📞 Ramal {phone.extension ?? '—'}
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
              type="text" value={phone.dialInput}
              onChange={e => phone.setDialInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && phone.dial()}
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
              {phone.callState === 'idle' && (
                <ActionBtn color="#34c759" onClick={() => phone.dial()} disabled={!phone.dialInput.trim()} label="📞 Ligar" />
              )}
              {phone.callState === 'incoming' && (
                <>
                  <ActionBtn color="#34c759" onClick={phone.answer} label="✓ Atender" />
                  <ActionBtn color="#ff6b6b" onClick={phone.hangup} label="✕ Rejeitar" />
                </>
              )}
              {(phone.callState === 'calling' || phone.callState === 'active') && (
                <>
                  <ActionBtn color="#ff6b6b" onClick={phone.hangup} label="📵 Encerrar" />
                  <ActionBtn color={phone.muted ? '#007aff' : '#475569'} onClick={phone.toggleMute}
                    label={phone.muted ? '🔇 Muted' : '🎙 Mute'} />
                </>
              )}
              {phone.dialInput && phone.callState === 'idle' && (
                <ActionBtn color="#475569" onClick={() => phone.setDialInput('')} label="⌫" />
              )}
            </div>
            {phone.logLines.length > 0 && (
              <div style={{
                marginTop: 12, maxHeight: 80, overflowY: 'auto',
                background: 'rgba(0,0,0,0.25)', borderRadius: 8, padding: '6px 10px',
              }}>
                {phone.logLines.map((l, i) => (
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
