import { useEffect, useState } from 'react';
import { PhoneCall, Coffee, Circle, Mic, MicOff, PhoneOff } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type { AgentStateView, CcPauseReason, CcDisposition, InteractionView } from '../api/types';
import { useSipPhone } from '../hooks/useSipPhone';
import type { ShellCallAction, ShellCallState } from '../hooks/useShellBridge';

interface DesktopAgenteTabProps {
  isEmbedded: boolean;
  callState: ShellCallState | null;
  sendCallAction: (action: ShellCallAction) => void;
}

const STATE_LABEL: Record<string, string> = {
  DISPONIVEL: 'Disponível',
  EM_ATENDIMENTO: 'Em atendimento',
  ACW: 'Pós-atendimento (ACW)',
  PAUSA: 'Em pausa',
  OFFLINE: 'Offline',
};

const POLL_INTERVAL_MS = 5000;

/**
 * DesktopAgenteTab — estados do agente, interação em curso e tabulação (Fase 4). O screen pop
 * de dados do AD (nome/BU/cargo/gestor) ainda não está disponível — a Fase 1 (AD) segue pendente
 * de dados reais de conexão com o Domain Controller; esta tela mostra só o que já existe
 * (fila/ANI/horários) até o AD ser conectado.
 */
export function DesktopAgenteTab({ isEmbedded, callState, sendCallAction }: DesktopAgenteTabProps) {
  // D10-A: embutido no shell, o único UA SIP é o Softphone.tsx do Telecom — este painel só
  // reflete o estado recebido via bridge e envia comandos. Fora do shell (SPA aberta direto em
  // /callcenter/), instancia o próprio useSipPhone — nunca os dois ao mesmo tempo.
  const standalonePhone = useSipPhone(!isEmbedded);
  const [dialValue, setDialValue] = useState('');

  const status = isEmbedded
    ? (callState?.status ?? 'idle')
    : (standalonePhone.callState === 'active' ? 'active'
      : standalonePhone.callState === 'calling' || standalonePhone.callState === 'incoming' ? 'ringing'
      : 'idle');
  const remote = isEmbedded ? (callState?.remote ?? '') : standalonePhone.dialInput;
  const durationSeconds = isEmbedded ? (callState?.durationSeconds ?? 0) : standalonePhone.duration;
  const muted = isEmbedded ? (callState?.muted ?? false) : standalonePhone.muted;
  const isIncoming = !isEmbedded && standalonePhone.callState === 'incoming';

  const doAnswer = () => { isEmbedded ? sendCallAction({ action: 'answer' }) : standalonePhone.answer(); };
  const doHangup = () => { isEmbedded ? sendCallAction({ action: 'hangup' }) : standalonePhone.hangup(); };
  const doMute = () => { isEmbedded ? sendCallAction({ action: muted ? 'unmute' : 'mute' }) : standalonePhone.toggleMute(); };
  const doDtmf = (k: string) => { isEmbedded ? sendCallAction({ action: 'dtmf', payload: k }) : standalonePhone.pressKey(k); };
  const doDial = () => {
    if (!dialValue.trim()) return;
    if (isEmbedded) sendCallAction({ action: 'dial', payload: dialValue.trim() });
    else void standalonePhone.dial(dialValue.trim());
    setDialValue('');
  };

  const [state, setState] = useState<AgentStateView | null>(null);
  const [interaction, setInteraction] = useState<InteractionView | null>(null);
  const [pauseReasons, setPauseReasons] = useState<CcPauseReason[]>([]);
  const [dispositions, setDispositions] = useState<CcDisposition[]>([]);
  const [selectedPauseReason, setSelectedPauseReason] = useState<number | ''>('');
  const [selectedDisposition, setSelectedDisposition] = useState<number | ''>('');
  const [error, setError] = useState('');

  const loadState = () => {
    api.get<AgentStateView>('/callcenter/agent-state/me')
      .then(({ data }) => setState(data))
      .catch(() => setState(null));
    api.get<InteractionView | null>('/callcenter/interactions/current')
      .then(({ data }) => setInteraction(data))
      .catch(() => setInteraction(null));
  };

  useEffect(() => {
    loadState();
    api.get<CcPauseReason[]>('/callcenter/agent-state/pause-reasons').then(({ data }) => setPauseReasons(data)).catch(() => setPauseReasons([]));
    api.get<CcDisposition[]>('/callcenter/interactions/dispositions').then(({ data }) => setDispositions(data)).catch(() => setDispositions([]));
    const id = setInterval(loadState, POLL_INTERVAL_MS);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setAgentState = (newState: string, pauseReasonId?: number) => {
    setError('');
    api.post('/callcenter/agent-state/me', { state: newState, pauseReasonId: pauseReasonId ?? null })
      .then(() => loadState())
      .catch(err => setError(getErrorMessage(err, 'Erro ao atualizar estado.')));
  };

  const submitDisposition = () => {
    if (selectedDisposition === '') return;
    setError('');
    api.post('/callcenter/interactions/disposition', { dispositionId: selectedDisposition })
      .then(() => { setSelectedDisposition(''); loadState(); })
      .catch(err => setError(getErrorMessage(err, 'Erro ao tabular a interação.')));
  };

  const current = state?.state ?? 'OFFLINE';
  const isAcw = current === 'ACW';
  const isEmAtendimento = current === 'EM_ATENDIMENTO';

  return (
    <>
      <div className="page-header">
        <h1>Desktop do Agente</h1>
        <p>Estado, atendimento em curso e tabulação</p>
      </div>
      <div className="page-body">
        {error && <div className="alert alert-error" style={{ marginBottom: 16 }}>{error}</div>}

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="flex items-center justify-between">
            <div className="flex items-center" style={{ gap: 8 }}>
              <Circle size={12} fill="currentColor" />
              <strong>{STATE_LABEL[current] ?? current}</strong>
              {state?.pauseReasonLabel && <span style={{ color: 'var(--text-muted)' }}>— {state.pauseReasonLabel}</span>}
            </div>
          </div>

          <div className="flex items-center" style={{ gap: 8, marginTop: 16, flexWrap: 'wrap' }}>
            <button className="btn btn-primary btn-sm" disabled={isEmAtendimento || isAcw}
              onClick={() => setAgentState('DISPONIVEL')}>
              Disponível
            </button>
            <select className="form-input" style={{ width: 200 }} value={selectedPauseReason}
              disabled={isEmAtendimento || isAcw}
              onChange={e => setSelectedPauseReason(e.target.value ? Number(e.target.value) : '')}>
              <option value="">— Motivo de pausa —</option>
              {pauseReasons.map(r => <option key={r.id} value={r.id}>{r.label}</option>)}
            </select>
            <button className="btn btn-ghost btn-sm" disabled={isEmAtendimento || isAcw || selectedPauseReason === ''}
              onClick={() => setAgentState('PAUSA', selectedPauseReason === '' ? undefined : selectedPauseReason)}>
              <Coffee size={14} /> Entrar em pausa
            </button>
            <button className="btn btn-ghost btn-sm" disabled={isEmAtendimento || isAcw}
              onClick={() => setAgentState('OFFLINE')}>
              Offline
            </button>
          </div>
        </div>

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="flex items-center justify-between" style={{ marginBottom: 12 }}>
            <div className="flex items-center" style={{ gap: 8 }}>
              <PhoneCall size={16} />
              <strong>Softphone</strong>
            </div>
            {status === 'active' && (
              <span style={{ color: 'var(--text-muted)', fontSize: '.85rem' }}>
                {`${String(Math.floor(durationSeconds / 60)).padStart(2, '0')}:${String(durationSeconds % 60).padStart(2, '0')}`}
              </span>
            )}
          </div>

          {status === 'idle' ? (
            <div className="flex items-center" style={{ gap: 8 }}>
              <input className="form-input" style={{ flex: 1 }} placeholder="Ramal ou número"
                value={dialValue} onChange={e => setDialValue(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') doDial(); }} />
              <button className="btn btn-primary btn-sm" onClick={doDial} disabled={!dialValue.trim()}>Discar</button>
            </div>
          ) : (
            <>
              <div className="flex items-center" style={{ gap: 8, marginBottom: 12 }}>
                <span>{status === 'ringing' ? (isIncoming ? '📲 Chamada entrante' : '📞 Chamando…') : '🟢 Em chamada'}</span>
                {remote && <span style={{ color: 'var(--text-muted)' }}>— {remote}</span>}
              </div>
              <div className="flex items-center" style={{ gap: 8, flexWrap: 'wrap' }}>
                {status === 'ringing' && isIncoming && (
                  <button className="btn btn-primary btn-sm" onClick={doAnswer}>Atender</button>
                )}
                <button className="btn btn-ghost btn-sm" onClick={doHangup}><PhoneOff size={14} /> Encerrar</button>
                {status === 'active' && (
                  <button className="btn btn-ghost btn-sm" onClick={doMute}>
                    {muted ? <MicOff size={14} /> : <Mic size={14} />} {muted ? 'Sem mudo' : 'Mudo'}
                  </button>
                )}
              </div>
              {status === 'active' && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6, maxWidth: 180, marginTop: 12 }}>
                  {['1','2','3','4','5','6','7','8','9','*','0','#'].map(k => (
                    <button key={k} className="btn btn-ghost btn-sm" onClick={() => doDtmf(k)}>{k}</button>
                  ))}
                </div>
              )}
            </>
          )}
        </div>

        <div className="card" style={{ marginBottom: 16 }}>
          <div className="flex items-center" style={{ gap: 8, marginBottom: 12 }}>
            <PhoneCall size={16} />
            <strong>Atendimento em curso</strong>
          </div>
          {interaction ? (
            <div className="form-grid">
              {interaction.direction === 'OUTBOUND' ? (
                <div><span style={{ color: 'var(--text-muted)' }}>Chamada de saída para:</span> {interaction.ani ?? '—'}</div>
              ) : (
                <>
                  <div><span style={{ color: 'var(--text-muted)' }}>Fila:</span> {interaction.queueName ?? '—'}</div>
                  <div><span style={{ color: 'var(--text-muted)' }}>ANI:</span> {interaction.ani ?? '—'}</div>
                </>
              )}
              <div><span style={{ color: 'var(--text-muted)' }}>{interaction.direction === 'OUTBOUND' ? 'Discada às:' : 'Na fila desde:'}</span> {interaction.queuedAt ? new Date(interaction.queuedAt).toLocaleTimeString('pt-BR') : '—'}</div>
              <div><span style={{ color: 'var(--text-muted)' }}>Atendida às:</span> {interaction.answeredAt ? new Date(interaction.answeredAt).toLocaleTimeString('pt-BR') : '—'}</div>
            </div>
          ) : (
            <p style={{ color: 'var(--text-muted)' }}>Nenhuma chamada em atendimento.</p>
          )}
          <p style={{ color: 'var(--text-muted)', fontSize: '.8rem', marginTop: 12 }}>
            Dados do Active Directory (nome, BU, cargo, gestor) ainda não disponíveis nesta tela —
            a integração com o Domain Controller está pendente.
          </p>
        </div>

        {isAcw && (
          <div className="card">
            <strong>Tabulação da chamada</strong>
            <div className="flex items-center" style={{ gap: 8, marginTop: 12 }}>
              <select className="form-input" style={{ width: 250 }} value={selectedDisposition}
                onChange={e => setSelectedDisposition(e.target.value ? Number(e.target.value) : '')}>
                <option value="">— Selecione a tabulação —</option>
                {dispositions.map(d => <option key={d.id} value={d.id}>{d.label}</option>)}
              </select>
              <button className="btn btn-primary btn-sm" disabled={selectedDisposition === ''}
                onClick={submitDisposition}>
                Concluir
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
