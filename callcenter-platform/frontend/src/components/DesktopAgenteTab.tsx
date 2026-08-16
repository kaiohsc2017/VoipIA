import { useEffect, useRef, useState } from 'react';
import { PhoneCall, Mic, MicOff, PhoneOff, Sparkles } from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type {
  AgentStateView, CcPauseReason, CcDisposition, InteractionView,
  DesktopSummaryView, DesktopCallHistoryItem,
  ContactHistoryItem, ContactProfileView, DesktopTrendPoint,
  DesktopScheduleView, DesktopQualityView, DesktopRankingView,
} from '../api/types';
import { useSipPhone } from '../hooks/useSipPhone';
import type { ShellCallAction, ShellCallState } from '../hooks/useShellBridge';
import { PresenceBar } from './desktop/PresenceBar';
import { KpiStrip } from './desktop/KpiStrip';
import { InteractionsTable } from './desktop/InteractionsTable';
import { ProductivityPanel } from './desktop/ProductivityPanel';
import { SchedulePanel } from './desktop/SchedulePanel';
import { QualityPanel } from './desktop/QualityPanel';
import { RankingPanel } from './desktop/RankingPanel';

interface DesktopAgenteTabProps {
  isEmbedded: boolean;
  callState: ShellCallState | null;
  sendCallAction: (action: ShellCallAction) => void;
}

const POLL_INTERVAL_MS = 5000;
const PROFILE_POLL_INTERVAL_MS = 6000;

const RISK_LABEL: Record<'baixo' | 'medio' | 'alto', string> = {
  baixo: 'Baixo',
  medio: 'Médio',
  alto: 'Alto',
};

function riskLevel(risco: number | undefined): 'baixo' | 'medio' | 'alto' {
  if (risco == null) return 'baixo';
  if (risco >= 0.66) return 'alto';
  if (risco >= 0.33) return 'medio';
  return 'baixo';
}

export function DesktopAgenteTab({ isEmbedded, callState, sendCallAction }: DesktopAgenteTabProps) {
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
  const [selectedDisposition, setSelectedDisposition] = useState<number | ''>('');
  const [error, setError] = useState('');

  const [contactHistory, setContactHistory] = useState<ContactHistoryItem[]>([]);
  const [profile, setProfile] = useState<ContactProfileView | null>(null);

  const identitySam = interaction?.identity?.samAccountName;
  const profileStatusRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    setContactHistory([]);
    setProfile(null);
    profileStatusRef.current = undefined;
    if (!interaction?.id || !identitySam) return;
    let cancelled = false;
    const interactionId = interaction.id;

    api.get<ContactHistoryItem[]>(`/callcenter/interactions/${interactionId}/contact-history-unified`)
      .then(({ data }) => { if (!cancelled) setContactHistory(data); })
      .catch(() => { });

    const loadProfile = () => {
      api.get<ContactProfileView>(`/callcenter/interactions/${interactionId}/contact-profile`)
        .then(({ data }) => {
          if (cancelled) return;
          profileStatusRef.current = data.status;
          setProfile(data);
        })
        .catch(() => { });
    };
    loadProfile();
    const profileTimer = setInterval(() => {
      if (profileStatusRef.current !== 'READY') {
        loadProfile();
      }
    }, PROFILE_POLL_INTERVAL_MS);

    return () => { cancelled = true; clearInterval(profileTimer); };
  }, [interaction?.id, identitySam]);

  const [activeTab, setActiveTab] = useState<'overview' | 'interacoes' | 'produtividade' | 'escala' | 'qualidade' | 'ranking'>('overview');
  const [summary, setSummary] = useState<DesktopSummaryView | null>(null);
  const [history, setHistory] = useState<DesktopCallHistoryItem[]>([]);
  const [trends, setTrends] = useState<DesktopTrendPoint[]>([]);
  const [schedule, setSchedule] = useState<DesktopScheduleView | null>(null);
  const [quality, setQuality] = useState<DesktopQualityView | null>(null);
  const [ranking, setRanking] = useState<DesktopRankingView | null>(null);
  const [tabLoading, setTabLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setTabLoading(true);

    if (activeTab === 'overview') {
      api.get<DesktopSummaryView>('/callcenter/desktop/me/resumo')
        .then(({ data }) => { if (!cancelled) setSummary(data); })
        .finally(() => { if (!cancelled) setTabLoading(false); });
    } else if (activeTab === 'interacoes') {
      api.get<DesktopCallHistoryItem[]>('/callcenter/desktop/me/historico')
        .then(({ data }) => { if (!cancelled) setHistory(data); })
        .finally(() => { if (!cancelled) setTabLoading(false); });
    } else if (activeTab === 'produtividade') {
      api.get<DesktopTrendPoint[]>('/callcenter/desktop/me/tendencia?dias=7')
        .then(({ data }) => { if (!cancelled) setTrends(data); })
        .finally(() => { if (!cancelled) setTabLoading(false); });
    } else if (activeTab === 'escala') {
      api.get<DesktopScheduleView>('/callcenter/desktop/me/escala')
        .then(({ data }) => { if (!cancelled) setSchedule(data); })
        .finally(() => { if (!cancelled) setTabLoading(false); });
    } else if (activeTab === 'qualidade') {
      api.get<DesktopQualityView>('/callcenter/desktop/me/qualidade')
        .then(({ data }) => { if (!cancelled) setQuality(data); })
        .finally(() => { if (!cancelled) setTabLoading(false); });
    } else if (activeTab === 'ranking') {
      api.get<DesktopRankingView>('/callcenter/desktop/me/ranking')
        .then(({ data }) => { if (!cancelled) setRanking(data); })
        .finally(() => { if (!cancelled) setTabLoading(false); });
    }

    return () => { cancelled = true; };
  }, [activeTab]);

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
  }, []);

  const setAgentState = (newState: string, pauseReasonId?: number | null) => {
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

  const handleFilterHistory = (de?: string, ate?: string) => {
    let url = '/callcenter/desktop/me/historico';
    if (de && ate) {
      url += `?de=${de}&ate=${ate}`;
    }
    setTabLoading(true);
    api.get<DesktopCallHistoryItem[]>(url)
      .then(({ data }) => setHistory(data))
      .finally(() => setTabLoading(false));
  };

  return (
    <div className="p-4 sm:p-6 max-w-7xl mx-auto space-y-6">
      <PresenceBar
        agentName={state?.agentName || 'Agente'}
        extension={state?.sipExtension}
        currentState={current}
        currentPauseReasonId={state?.pauseReasonId}
        stateSeconds={state?.secondsInState || 0}
        pauseReasons={pauseReasons}
        onStateChange={(st, prId) => setAgentState(st, prId)}
      />

      {error && (
        <div className="p-4 rounded-xl bg-red-500/10 border border-red-500/20 text-red-600 dark:text-red-400 text-xs font-medium">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        <div className="lg:col-span-4 space-y-6">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <PhoneCall size={16} className="text-indigo-600 dark:text-indigo-400" />
                <h3 className="font-semibold text-slate-900 dark:text-slate-100 text-sm">Softphone</h3>
              </div>
              {status === 'active' && (
                <span className="text-xs font-mono font-medium text-slate-500">
                  {`${String(Math.floor(durationSeconds / 60)).padStart(2, '0')}:${String(durationSeconds % 60).padStart(2, '0')}`}
                </span>
              )}
            </div>

            {status === 'idle' ? (
              <div className="flex items-center gap-2">
                <input
                  className="flex-1 px-3 py-2 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  placeholder="Ramal ou número"
                  value={dialValue}
                  onChange={e => setDialValue(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') doDial(); }}
                />
                <button
                  className="px-4 py-2 text-xs font-medium bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg transition-colors disabled:opacity-50"
                  onClick={doDial}
                  disabled={!dialValue.trim()}
                >
                  Discar
                </button>
              </div>
            ) : (
              <div className="space-y-3">
                <div className="text-xs font-medium text-slate-700 dark:text-slate-300 flex items-center gap-2">
                  <span>{status === 'ringing' ? (isIncoming ? '📲 Chamada entrante' : '📞 Chamando…') : '🟢 Em chamada'}</span>
                  {remote && <span className="font-mono text-slate-400">— {remote}</span>}
                </div>
                <div className="flex items-center gap-2">
                  {status === 'ringing' && isIncoming && (
                    <button onClick={doAnswer} className="px-3 py-1.5 bg-emerald-600 text-white text-xs font-medium rounded-lg">
                      Atender
                    </button>
                  )}
                  <button onClick={doHangup} className="px-3 py-1.5 bg-red-600 text-white text-xs font-medium rounded-lg flex items-center gap-1">
                    <PhoneOff size={14} /> Encerrar
                  </button>
                  {status === 'active' && (
                    <button onClick={doMute} className="px-3 py-1.5 border border-slate-200 dark:border-slate-700 text-xs font-medium rounded-lg flex items-center gap-1">
                      {muted ? <MicOff size={14} /> : <Mic size={14} />} {muted ? 'Sem mudo' : 'Mudo'}
                    </button>
                  )}
                </div>
                {status === 'active' && (
                  <div className="grid grid-cols-3 gap-1.5 pt-2 max-w-[180px]">
                    {['1','2','3','4','5','6','7','8','9','*','0','#'].map(k => (
                      <button key={k} onClick={() => doDtmf(k)} className="py-1 bg-slate-50 dark:bg-slate-800 text-xs font-mono rounded hover:bg-slate-100">
                        {k}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>

          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm space-y-3">
            <h3 className="font-semibold text-slate-900 dark:text-slate-100 text-sm flex items-center gap-2">
              <PhoneCall size={16} className="text-slate-500" /> Atendimento em Curso
            </h3>
            {interaction ? (
              <div className="space-y-1.5 text-xs">
                <div><span className="text-slate-400">Fila:</span> <strong className="text-slate-700 dark:text-slate-300">{interaction.queueName ?? '—'}</strong></div>
                <div><span className="text-slate-400">ANI:</span> <strong className="font-mono text-slate-700 dark:text-slate-300">{interaction.ani ?? '—'}</strong></div>
                <div><span className="text-slate-400">Entrada:</span> <span className="font-mono text-slate-600 dark:text-slate-400">{interaction.queuedAt ? new Date(interaction.queuedAt).toLocaleTimeString('pt-BR') : '—'}</span></div>
              </div>
            ) : (
              <p className="text-xs text-slate-400 italic">Nenhum atendimento em andamento no momento.</p>
            )}
          </div>

          {interaction?.identity && (
            <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-xl p-5 shadow-sm space-y-4">
              <h3 className="font-semibold text-slate-900 dark:text-slate-100 text-sm flex items-center gap-2">
                <Sparkles size={16} className="text-amber-500" /> Copiloto de IA
              </h3>
              <div className="space-y-3">
                <div>
                  <h4 className="text-xs font-medium text-slate-500">Histórico de Atendimentos</h4>
                  <div className="mt-1 space-y-1 text-xs font-mono text-slate-600 dark:text-slate-400">
                    {contactHistory.map(item => (
                      <div key={`${item.channel}-${item.referenceId}`}>
                        [{item.channel === 'voz' ? 'Voz' : 'Chat'}] {item.startedAt ? new Date(item.startedAt).toLocaleDateString('pt-BR') : '—'} · {item.dispositionLabel ?? 'sem tabulação'}
                      </div>
                    ))}
                  </div>
                </div>

                {profile && (
                  <div className="pt-2 border-t border-slate-100 dark:border-slate-800 space-y-2">
                    <p className="text-xs text-slate-800 dark:text-slate-200 font-medium">{profile.resumoPerfil}</p>
                    <div className="text-[11px] text-slate-400">
                      Risco: <span className="font-semibold text-slate-600">{RISK_LABEL[riskLevel(profile.riscoEscalonamento)]}</span>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {isAcw && (
            <div className="bg-white dark:bg-slate-900 border border-amber-500/30 rounded-xl p-5 shadow-sm space-y-3">
              <h3 className="font-semibold text-amber-600 dark:text-amber-400 text-sm">Tabulação da Chamada</h3>
              <select
                className="w-full px-3 py-2 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900"
                value={selectedDisposition}
                onChange={e => setSelectedDisposition(e.target.value ? Number(e.target.value) : '')}
              >
                <option value="">— Selecione a tabulação —</option>
                {dispositions.map(d => <option key={d.id} value={d.id}>{d.label}</option>)}
              </select>
              <button
                className="w-full py-2 bg-indigo-600 text-white text-xs font-medium rounded-lg disabled:opacity-50"
                disabled={selectedDisposition === ''}
                onClick={submitDisposition}
              >
                Concluir Atendimento
              </button>
            </div>
          )}
        </div>

        <div className="lg:col-span-8 space-y-6">
          <div className="flex border-b border-slate-200 dark:border-slate-800 gap-2 overflow-x-auto pb-1">
            {[
              { id: 'overview', label: 'Visão Geral' },
              { id: 'interacoes', label: 'Interações' },
              { id: 'produtividade', label: 'Produtividade' },
              { id: 'escala', label: 'Escala' },
              { id: 'qualidade', label: 'Qualidade' },
              { id: 'ranking', label: 'Ranking' },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={`px-4 py-2 text-xs font-medium rounded-t-lg transition-colors border-b-2 whitespace-nowrap ${
                  activeTab === tab.id
                    ? 'border-indigo-600 text-indigo-600 dark:text-indigo-400 font-semibold'
                    : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {activeTab === 'overview' && <KpiStrip summary={summary} />}
          {activeTab === 'interacoes' && (
            <InteractionsTable history={history} loading={tabLoading} onFilterChange={handleFilterHistory} />
          )}
          {activeTab === 'produtividade' && <ProductivityPanel trends={trends} loading={tabLoading} />}
          {activeTab === 'escala' && <SchedulePanel schedule={schedule} loading={tabLoading} />}
          {activeTab === 'qualidade' && <QualityPanel quality={quality} loading={tabLoading} />}
          {activeTab === 'ranking' && <RankingPanel ranking={ranking} loading={tabLoading} />}
        </div>
      </div>
    </div>
  );
}
