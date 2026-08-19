import { useEffect, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Maximize2,
  Lock,
  LogOut,
  Headphones,
} from 'lucide-react';
import api, { getErrorMessage } from '../api/client';
import type {
  AgentStateView,
  CcPauseReason,
  InteractionView,
  DesktopSummaryView,
  DesktopCallHistoryItem,
  ContactHistoryItem,
  ContactProfileView,
  DesktopTrendPoint,
  DesktopScheduleView,
  DesktopQualityView,
  DesktopRankingView,
  DesktopEvaluationDetailView,
  CoachingPlanView,
} from '../api/types';
import { useSipPhone } from '../hooks/useSipPhone';
import type { ShellCallAction, ShellCallState } from '../hooks/useShellBridge';
import { OperationSidebar } from './desktop/OperationSidebar';
import { KpiStrip } from './desktop/KpiStrip';
import { OverviewTab } from './desktop/OverviewTab';
import { InteractionsTable } from './desktop/InteractionsTable';
import { ProductivityPanel } from './desktop/ProductivityPanel';
import { SchedulePanel } from './desktop/SchedulePanel';
import { QualityPanel } from './desktop/QualityPanel';
import { RankingPanel } from './desktop/RankingPanel';

interface DesktopAgenteTabProps {
  isEmbedded: boolean;
  callState: ShellCallState | null;
  sendCallAction: (action: ShellCallAction) => void;
  onNavigateToTab?: (tab: string) => void;
  onLogout?: () => void;
  username?: string;
}

const POLL_INTERVAL_MS = 5000;
const PROFILE_POLL_INTERVAL_MS = 6000;

export function DesktopAgenteTab({
  isEmbedded,
  callState,
  sendCallAction,
  onNavigateToTab,
  onLogout,
  username = 'Kaio',
}: DesktopAgenteTabProps) {
  const standalonePhone = useSipPhone(!isEmbedded);
  const [dialValue, setDialValue] = useState('');
  const [moduleMenuOpen, setModuleMenuOpen] = useState(false);

  const status = isEmbedded
    ? (callState?.status ?? 'idle')
    : standalonePhone.callState === 'active'
    ? 'active'
    : standalonePhone.callState === 'calling' || standalonePhone.callState === 'incoming'
    ? 'ringing'
    : 'idle';

  const remote = isEmbedded ? callState?.remote ?? '' : standalonePhone.dialInput;
  const durationSeconds = isEmbedded
    ? callState?.durationSeconds ?? 0
    : standalonePhone.duration;
  const muted = isEmbedded ? callState?.muted ?? false : standalonePhone.muted;
  const isIncoming = !isEmbedded && standalonePhone.callState === 'incoming';

  const doAnswer = () => {
    if (isEmbedded) sendCallAction({ action: 'answer' });
    else standalonePhone.answer();
  };

  const doHangup = () => {
    if (isEmbedded) sendCallAction({ action: 'hangup' });
    else standalonePhone.hangup();
  };

  const doMute = () => {
    if (isEmbedded) sendCallAction({ action: muted ? 'unmute' : 'mute' });
    else standalonePhone.toggleMute();
  };

  const doDtmf = (k: string) => {
    if (isEmbedded) sendCallAction({ action: 'dtmf', payload: k });
    else standalonePhone.pressKey(k);
  };

  const doDial = () => {
    if (!dialValue.trim()) return;
    if (isEmbedded) sendCallAction({ action: 'dial', payload: dialValue.trim() });
    else void standalonePhone.dial(dialValue.trim());
    setDialValue('');
  };

  const [state, setState] = useState<AgentStateView | null>(null);
  const [interaction, setInteraction] = useState<InteractionView | null>(null);
  const [pauseReasons, setPauseReasons] = useState<CcPauseReason[]>([]);
  const [, setError] = useState('');

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

    api
      .get<ContactHistoryItem[]>(`/callcenter/interactions/${interactionId}/contact-history-unified`)
      .then(({ data }) => {
        if (!cancelled) setContactHistory(data);
      })
      .catch(() => {});

    const loadProfile = () => {
      api
        .get<ContactProfileView>(`/callcenter/interactions/${interactionId}/contact-profile`)
        .then(({ data }) => {
          if (cancelled) return;
          profileStatusRef.current = data.status;
          setProfile(data);
        })
        .catch(() => {});
    };
    loadProfile();
    const profileTimer = setInterval(() => {
      if (profileStatusRef.current !== 'READY') {
        loadProfile();
      }
    }, PROFILE_POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(profileTimer);
    };
  }, [interaction?.id, identitySam]);

  type TabKey = 'overview' | 'interacoes' | 'produtividade' | 'escala' | 'qualidade' | 'ranking';
  const [activeTab, setActiveTab] = useState<TabKey>('overview');
  const [selectedPeriod, setSelectedPeriod] = useState<'hoje' | '7d' | '30d'>('hoje');

  const [summary, setSummary] = useState<DesktopSummaryView | null>(null);
  const [history, setHistory] = useState<DesktopCallHistoryItem[]>([]);
  const [trends, setTrends] = useState<DesktopTrendPoint[]>([]);
  const [schedule, setSchedule] = useState<DesktopScheduleView | null>(null);
  const [quality, setQuality] = useState<DesktopQualityView | null>(null);
  const [evaluations, setEvaluations] = useState<DesktopEvaluationDetailView[]>([]);
  const [coachingPlans, setCoachingPlans] = useState<CoachingPlanView[]>([]);
  const [ranking, setRanking] = useState<DesktopRankingView | null>(null);
  const [tabLoading, setTabLoading] = useState(false);

  const loadQualityData = () => {
    setTabLoading(true);
    Promise.all([
      api.get<DesktopQualityView>('/callcenter/desktop/me/qualidade'),
      api.get<DesktopEvaluationDetailView[]>('/callcenter/desktop/me/avaliacoes'),
      api.get<CoachingPlanView[]>('/callcenter/desktop/me/coaching'),
    ])
      .then(([qualRes, evalRes, coachRes]) => {
        setQuality(qualRes.data);
        setEvaluations(evalRes.data);
        setCoachingPlans(coachRes.data);
      })
      .catch((err) => setError(getErrorMessage(err, 'Erro ao carregar dados de qualidade.')))
      .finally(() => setTabLoading(false));
  };

  useEffect(() => {
    let cancelled = false;
    setTabLoading(true);

    if (activeTab === 'overview') {
      api
        .get<DesktopSummaryView>('/callcenter/desktop/me/resumo')
        .then(({ data }) => {
          if (!cancelled) setSummary(data);
        })
        .finally(() => {
          if (!cancelled) setTabLoading(false);
        });
      api
        .get<DesktopCallHistoryItem[]>('/callcenter/desktop/me/historico')
        .then(({ data }) => {
          if (!cancelled) setHistory(data);
        })
        .catch(() => {});
    } else if (activeTab === 'interacoes') {
      api
        .get<DesktopCallHistoryItem[]>('/callcenter/desktop/me/historico')
        .then(({ data }) => {
          if (!cancelled) setHistory(data);
        })
        .finally(() => {
          if (!cancelled) setTabLoading(false);
        });
    } else if (activeTab === 'produtividade') {
      api
        .get<DesktopTrendPoint[]>('/callcenter/desktop/me/tendencia?dias=14')
        .then(({ data }) => {
          if (!cancelled) setTrends(data);
        })
        .finally(() => {
          if (!cancelled) setTabLoading(false);
        });
    } else if (activeTab === 'escala') {
      api
        .get<DesktopScheduleView>('/callcenter/desktop/me/escala')
        .then(({ data }) => {
          if (!cancelled) setSchedule(data);
        })
        .finally(() => {
          if (!cancelled) setTabLoading(false);
        });
    } else if (activeTab === 'qualidade') {
      loadQualityData();
    } else if (activeTab === 'ranking') {
      api
        .get<DesktopRankingView>('/callcenter/desktop/me/ranking')
        .then(({ data }) => {
          if (!cancelled) setRanking(data);
        })
        .finally(() => {
          if (!cancelled) setTabLoading(false);
        });
    }

    return () => {
      cancelled = true;
    };
  }, [activeTab]);

  const loadState = () => {
    api
      .get<AgentStateView>('/callcenter/agent-state/me')
      .then(({ data }) => setState(data))
      .catch(() => setState(null));
    api
      .get<InteractionView | null>('/callcenter/interactions/current')
      .then(({ data }) => setInteraction(data))
      .catch(() => setInteraction(null));
  };

  useEffect(() => {
    loadState();
    api
      .get<CcPauseReason[]>('/callcenter/agent-state/pause-reasons')
      .then(({ data }) => setPauseReasons(data))
      .catch(() => setPauseReasons([]));
    const id = setInterval(loadState, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

  const setAgentState = (newState: string, pauseReasonId?: number | null) => {
    setError('');
    api
      .post('/callcenter/agent-state/me', {
        state: newState,
        pauseReasonId: pauseReasonId ?? null,
      })
      .then(() => loadState())
      .catch((err) => setError(getErrorMessage(err, 'Erro ao atualizar estado.')));
  };

  const handleFilterHistory = (de?: string, ate?: string) => {
    let url = '/callcenter/desktop/me/historico';
    if (de && ate) {
      url += `?de=${de}&ate=${ate}`;
    }
    setTabLoading(true);
    api
      .get<DesktopCallHistoryItem[]>(url)
      .then(({ data }) => setHistory(data))
      .finally(() => setTabLoading(false));
  };

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  };

  return (
    <div className="w-full h-screen flex flex-col bg-[#0c101c] select-none overflow-hidden font-sans">
      {/* ─── Topbar Escura (estilo Claude Mockup) ─── */}
      <header className="h-11 bg-[#090d16] border-b border-slate-800/80 px-4 flex items-center justify-between text-xs text-slate-300 z-50 flex-shrink-0">
        {/* Lado Esquerdo: Seletor de Módulo / Título */}
        <div className="relative">
          <button
            onClick={() => setModuleMenuOpen(!moduleMenuOpen)}
            className="flex items-center gap-2 px-2.5 py-1 rounded-lg hover:bg-slate-800/80 transition-colors text-slate-200 font-medium"
          >
            <div className="w-5 h-5 rounded bg-indigo-600 text-white flex items-center justify-center shadow-xs">
              <Headphones size={13} />
            </div>
            <span className="font-semibold text-sm">Espaço de Trabalho do Agente</span>
            <ChevronDown size={14} className="text-slate-400" />
          </button>

          {/* Menu Dropdown de navegação entre módulos */}
          {moduleMenuOpen && (
            <div className="absolute top-full left-0 mt-1.5 w-56 bg-[#1e293b] border border-slate-700 rounded-xl shadow-2xl py-1.5 z-50 text-slate-200">
              <div className="px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-slate-400 border-b border-slate-700/60 mb-1">
                Módulos do Sistema
              </div>
              {[
                { id: 'desktop', label: '🎧 Desktop do Agente (Ativo)' },
                { id: 'supervisao', label: '📊 Supervisão ao Vivo' },
                { id: 'agentes', label: '👥 Gestão de Agentes' },
                { id: 'filas', label: '📞 Filas de Atendimento' },
                { id: 'skills', label: '⚡ Skills e Roteamento' },
                { id: 'gravacoes', label: '🎙️ Gravações e Cobrowsing' },
                { id: 'insightsDashboard', label: '📈 Insights & Scorecards' },
              ].map((mod) => (
                <button
                  key={mod.id}
                  onClick={() => {
                    setModuleMenuOpen(false);
                    if (onNavigateToTab) onNavigateToTab(mod.id);
                  }}
                  className={`w-full text-left px-3 py-1.5 text-xs hover:bg-slate-700/70 transition-colors flex items-center justify-between ${
                    mod.id === 'desktop' ? 'text-indigo-400 font-bold bg-slate-800/60' : 'text-slate-300'
                  }`}
                >
                  <span>{mod.label}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Lado Direito: Ações rápidas de Usuário & Tela cheia */}
        <div className="flex items-center gap-3">
          <div className="w-7 h-7 rounded-full bg-purple-600 text-white font-bold text-xs flex items-center justify-center shadow-xs">
            {username.slice(0, 1).toUpperCase()}
          </div>
          <button
            onClick={() => {}}
            className="flex items-center gap-1.5 px-2 py-1 rounded bg-slate-800/60 hover:bg-slate-800 text-slate-300 text-xs border border-slate-700/60 transition-colors"
            title="Compartilhamento seguro"
          >
            <Lock size={12} className="text-slate-400" />
            <span>Share</span>
          </button>
          <button
            onClick={toggleFullscreen}
            className="p-1.5 rounded hover:bg-slate-800 text-slate-400 hover:text-white transition-colors"
            title="Tela cheia"
          >
            <Maximize2 size={14} />
          </button>
          {onLogout && (
            <button
              onClick={onLogout}
              className="p-1.5 rounded hover:bg-red-950/40 text-slate-400 hover:text-red-400 transition-colors"
              title="Sair do sistema"
            >
              <LogOut size={14} />
            </button>
          )}
        </div>
      </header>

      {/* ─── Corpo Principal em 2 Colunas Exatas ─── */}
      <div className="flex-1 flex overflow-hidden">
        {/* ─── Coluna Esquerda Fixa (Sidebar Dark) ─── */}
        <OperationSidebar
          state={state}
          interaction={interaction}
          pauseReasons={pauseReasons}
          onStateChange={setAgentState}
          status={status}
          remote={remote}
          durationSeconds={durationSeconds}
          muted={muted}
          isIncoming={isIncoming}
          dialValue={dialValue}
          setDialValue={setDialValue}
          doDial={doDial}
          doAnswer={doAnswer}
          doHangup={doHangup}
          doMute={doMute}
          doDtmf={doDtmf}
          contactHistory={contactHistory}
          profile={profile}
        />

        {/* ─── Área Principal à Direita (Workspace Analítico) ─── */}
        <main className="flex-1 bg-[#f8fafc] overflow-y-auto p-6 space-y-6">
          {/* Cabeçalho do Espaço de Trabalho */}
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div>
              <h1 className="text-2xl font-bold text-slate-900 tracking-tight">Espaço de trabalho</h1>
              <p className="text-xs text-slate-500 mt-0.5 font-medium capitalize">
                {new Intl.DateTimeFormat('pt-BR', {
                  weekday: 'long',
                  day: 'numeric',
                  month: 'long',
                  year: 'numeric',
                }).format(new Date())}
                {' · '}
                {schedule?.shiftLabel || (schedule?.shiftStart && schedule?.shiftEnd ? `turno ${schedule.shiftStart}–${schedule.shiftEnd}` : 'turno regular')}
              </p>
            </div>

            <div className="flex items-center gap-2">
              {/* Seletor Diário */}
              <div className="flex items-center gap-1 bg-white border border-slate-200 rounded-lg px-2 py-1 shadow-xs">
                <button className="p-0.5 text-slate-400 hover:text-slate-700">
                  <ChevronLeft size={14} />
                </button>
                <span className="text-xs font-semibold text-slate-800 px-2">
                  Hoje - {new Date().toLocaleDateString('pt-BR')}
                </span>
                <button className="p-0.5 text-slate-400 hover:text-slate-700">
                  <ChevronRight size={14} />
                </button>
              </div>

              {/* Atalhos de Período */}
              <div className="flex items-center gap-1 bg-white border border-slate-200 rounded-lg p-1 shadow-xs">
                <button
                  onClick={() => setSelectedPeriod('7d')}
                  className={`px-2.5 py-1 text-xs rounded font-medium transition-colors ${
                    selectedPeriod === '7d'
                      ? 'bg-slate-900 text-white shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  7 dias
                </button>
                <button
                  onClick={() => setSelectedPeriod('30d')}
                  className={`px-2.5 py-1 text-xs rounded font-medium transition-colors ${
                    selectedPeriod === '30d'
                      ? 'bg-slate-900 text-white shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  30 dias
                </button>
              </div>
            </div>
          </div>

          {/* Faixa de 6 KPIs com Sparklines */}
          <KpiStrip summary={summary} />

          {/* Navegação por Sub-Abas */}
          <div className="flex items-center gap-6 border-b border-slate-200 text-xs overflow-x-auto pb-px">
            {[
              { id: 'overview', label: 'Visão geral' },
              { id: 'interacoes', label: 'Interações' },
              { id: 'produtividade', label: 'Produtividade' },
              { id: 'escala', label: 'Escala & aderência' },
              { id: 'qualidade', label: 'Qualidade' },
              { id: 'ranking', label: 'Ranking' },
            ].map((t) => (
              <button
                key={t.id}
                onClick={() => setActiveTab(t.id as TabKey)}
                className={`pb-3 font-semibold transition-all border-b-2 whitespace-nowrap ${
                  activeTab === t.id
                    ? 'border-indigo-600 text-indigo-600 font-bold'
                    : 'border-transparent text-slate-500 hover:text-slate-800'
                }`}
              >
                {t.label}
              </button>
            ))}
          </div>

          {/* Conteúdo Dinâmico da Sub-Aba Ativa */}
          <div className="pt-2">
            {activeTab === 'overview' && (
              <OverviewTab
                summary={summary}
                recentCalls={history}
                onNavigateTab={(tab) => {
                  if (tab === 'interacoes') setActiveTab('interacoes');
                  else if (tab === 'qualidade') setActiveTab('qualidade');
                  else if (tab === 'escala') setActiveTab('escala');
                }}
              />
            )}

            {activeTab === 'interacoes' && (
              <InteractionsTable
                history={history}
                loading={tabLoading}
                onFilterChange={handleFilterHistory}
              />
            )}

            {activeTab === 'produtividade' && (
              <ProductivityPanel
                trends={trends}
                loading={tabLoading}
                onDaysChange={(days) => {
                  api
                    .get<DesktopTrendPoint[]>(`/callcenter/desktop/me/tendencia?dias=${days}`)
                    .then(({ data }) => setTrends(data))
                    .catch(() => {});
                }}
              />
            )}

            {activeTab === 'escala' && (
              <SchedulePanel schedule={schedule} loading={tabLoading} />
            )}

            {activeTab === 'qualidade' && (
              <QualityPanel
                quality={quality}
                evaluations={evaluations}
                coachingPlans={coachingPlans}
                loading={tabLoading}
                onOpenAppeal={() => {}}
                onUpdateCoachingStatus={() => {}}
              />
            )}

            {activeTab === 'ranking' && (
              <RankingPanel ranking={ranking} loading={tabLoading} />
            )}
          </div>
        </main>
      </div>
    </div>
  );
}
