import { useEffect, useState, Component, type ReactNode } from 'react';
import Login from './components/Login';
import Sidebar, { type Tab } from './components/Sidebar';
import { AgentesTab } from './components/AgentesTab';
import { FilasTab } from './components/FilasTab';
import { SkillsTab } from './components/SkillsTab';
import { GravacoesTab } from './components/GravacoesTab';
import { DesktopAgenteTab } from './components/DesktopAgenteTab';
import { SupervisaoTab } from './components/SupervisaoTab';
import { FluxosTab } from './components/FluxosTab';
import { InsightsChamadasTab } from './components/InsightsChamadasTab';
import { InsightsDashboardTab } from './components/InsightsDashboardTab';
import { InsightsProcessamentoTab } from './components/InsightsProcessamentoTab';
import { ScorecardsViewTab } from './components/ScorecardsViewTab';
import { ReportsTab } from './components/ReportsTab';
import { ChatTab } from './components/ChatTab';
import { ReportsQueueTab } from './components/ReportsQueueTab';
import { ConfiguracoesTab } from './components/ConfiguracoesTab';
import { PesquisasTab } from './components/PesquisasTab';
import { KbTab } from './components/KbTab';
import { IaAgentsTab } from './components/IaAgentsTab';
import { revokeSession } from './api/client';
import { authSessionFromToken } from './hooks/useAuthSession';
import { useShellBridge } from './hooks/useShellBridge';
import type { CcInsightsDrillDownFilters } from './api/types';

// Resource keys do namespace RBAC granular `callcenter.*` — mantido em
// sincronia manual com o backend (ResourceCatalog.java) e com os `resource`
// de Sidebar.tsx, mesma duplicação intencional já aceita entre Sidebar.tsx
// e PAGE_RESOURCE no Telecom.
const TAB_RESOURCE = {
  agentes: 'callcenter.agentes',
  filas: 'callcenter.filas',
  skills: 'callcenter.skills',
  gravacoes: 'callcenter.gravacoes',
  desktop: 'callcenter.desktop',
  supervisao: 'callcenter.supervisao',
  fluxos: 'callcenter.fluxos',
  insightsChamadas: 'callcenter.insights.calls',
  insightsDashboard: 'callcenter.insights.dashboard',
  insightsProcessamento: 'callcenter.insights.processing',
  insightsScorecards: 'callcenter.insights.scorecards',
  insightsReports: 'callcenter.insights.reports',
  chat: 'callcenter.chat',
  reports: 'callcenter.reports',
  pesquisas: 'callcenter.config',
  kb: 'callcenter.kb',
  iaAgentes: 'callcenter.ia_agentes',
  configuracoes: 'callcenter.config',
} as const;

// ─── ErrorBoundary — evita tela em branco em caso de exceção de render ──────
class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('[ErrorBoundary] Erro capturado:', error, info.componentStack);
  }

  render() {
    if (this.state.error) {
      return (
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          justifyContent: 'center', height: '100vh', gap: 16,
        }}>
          <div style={{ fontSize: '2rem' }}>⚠️</div>
          <h2 style={{ margin: 0 }}>Erro inesperado</h2>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
            {(this.state.error as Error).message}
          </p>
          <button className="btn btn-primary" onClick={() => { this.setState({ error: null }); window.location.reload(); }}>
            Recarregar página
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

export default function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('asteriskia_token'));
  const [username, setUsername] = useState<string>(() => localStorage.getItem('asteriskia_user') ?? '');
  const session = authSessionFromToken(token);

  const [tab, setTab] = useState<Tab>('agentes');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [pendingDrillDown, setPendingDrillDown] = useState<{ filters: CcInsightsDrillDownFilters; nonce: number } | null>(null);

  const handleDrillDown = (filters: CcInsightsDrillDownFilters) => {
    setPendingDrillDown(prev => ({ filters, nonce: (prev?.nonce ?? 0) + 1 }));
    setTab('insightsChamadas');
  };

  const handleDrillDownConsumed = () => setPendingDrillDown(null);

  // Escuta logout forçado (token expirado / 401) — mesmo padrão do Telecom.
  useEffect(() => {
    const handleLogout = () => handleSignOut();
    window.addEventListener('asteriskia:logout', handleLogout);
    return () => window.removeEventListener('asteriskia:logout', handleLogout);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleLogin = (t: string, user: string) => {
    setToken(t);
    setUsername(user);
  };

  const handleSignOut = () => {
    localStorage.removeItem('asteriskia_token');
    localStorage.removeItem('asteriskia_user');
    revokeSession();
    setToken(null);
    setUsername('');
  };

  // Hooks sempre chamados na mesma ordem, independente de `token` — nunca depois
  // do early return de login abaixo (React quebra a árvore de hooks se o número
  // de chamadas mudar entre renders do mesmo componente, ex: no instante do login).
  const TABS: { id: Tab }[] = [
    { id: 'agentes' },
    { id: 'filas' },
    { id: 'skills' },
    { id: 'gravacoes' },
    { id: 'desktop' },
    { id: 'supervisao' },
    { id: 'fluxos' },
    { id: 'insightsChamadas' },
    { id: 'insightsDashboard' },
    { id: 'insightsProcessamento' },
    { id: 'insightsScorecards' },
    { id: 'insightsReports' },
    { id: 'chat' },
    { id: 'reports' },
    { id: 'pesquisas' },
    { id: 'kb' },
    { id: 'iaAgentes' },
    { id: 'configuracoes' },
  ];
  const visibleTabs = TABS.filter(t => session.hasRead(TAB_RESOURCE[t.id]));
  const currentTab = visibleTabs.some(t => t.id === tab) ? tab : visibleTabs[0]?.id;

  const { isEmbedded, callState, sendCallAction } = useShellBridge(currentTab ?? 'agentes', (t) => setTab(t as Tab));

  if (!token) {
    return (
      <ErrorBoundary>
        <Login onLogin={handleLogin} />
      </ErrorBoundary>
    );
  }

  return (
    <ErrorBoundary>
      <div className="app-layout">
        {!isEmbedded && (
          <Sidebar
            currentTab={currentTab ?? 'agentes'}
            onNavigate={setTab}
            username={username}
            session={session}
            onLogout={handleSignOut}
            collapsed={sidebarCollapsed}
            onToggleCollapse={() => setSidebarCollapsed(c => !c)}
          />
        )}
        <main className={`main-content${isEmbedded ? ' embedded' : sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
          <div className="page-body">
            {currentTab === 'agentes' && <AgentesTab canWrite={session.hasWrite('callcenter.agentes')} canReadRamalSecret={session.hasRead('callcenter.ramais')} />}
            {currentTab === 'filas' && <FilasTab canWrite={session.hasWrite('callcenter.filas')} />}
            {currentTab === 'skills' && <SkillsTab canWrite={session.hasWrite('callcenter.skills')} />}
            {currentTab === 'gravacoes' && <GravacoesTab
              canWrite={session.hasWrite('callcenter.gravacoes')}
              canReadCobrowsing={session.hasRead('callcenter.cobrowsing')}
              canWriteCobrowsing={session.hasWrite('callcenter.cobrowsing')}
            />}
            {currentTab === 'desktop' && (
              <DesktopAgenteTab isEmbedded={isEmbedded} callState={callState} sendCallAction={sendCallAction} />
            )}
            {currentTab === 'supervisao' && <SupervisaoTab canWrite={session.hasWrite('callcenter.supervisao')} canRedirect={session.hasWrite('callcenter.supervisao.redirect')} />}
            {currentTab === 'fluxos' && <FluxosTab canWrite={session.hasWrite('callcenter.fluxos')} />}
            {currentTab === 'insightsChamadas' && (
              <InsightsChamadasTab pendingDrillDown={pendingDrillDown} onDrillDownConsumed={handleDrillDownConsumed} />
            )}
            {currentTab === 'insightsDashboard' && <InsightsDashboardTab onDrillDown={handleDrillDown} />}
            {currentTab === 'insightsProcessamento' && <InsightsProcessamentoTab onDrillDown={handleDrillDown} />}
            {currentTab === 'insightsScorecards' && <ScorecardsViewTab />}
            {currentTab === 'insightsReports' && (
              <ReportsTab canWrite={session.hasWrite('callcenter.insights.reports')} isAdmin={session.role === 'ADMIN'} />
            )}
            {currentTab === 'chat' && <ChatTab isAdmin={session.role === 'ADMIN'} />}
            {currentTab === 'reports' && <ReportsQueueTab isAdmin={session.role === 'ADMIN'} />}
            {currentTab === 'pesquisas' && <PesquisasTab canWrite={session.hasWrite('callcenter.config')} />}
            {currentTab === 'kb' && <KbTab canWrite={session.hasWrite('callcenter.kb')} />}
            {currentTab === 'iaAgentes' && <IaAgentsTab canWrite={session.hasWrite('callcenter.ia_agentes')} />}
            {currentTab === 'configuracoes' && <ConfiguracoesTab canWrite={session.hasWrite('callcenter.config')} />}
            {!currentTab && (
              <p style={{ color: 'var(--text-muted)' }}>Você não tem permissão de leitura em nenhuma aba do Call Center.</p>
            )}
          </div>
        </main>
      </div>
    </ErrorBoundary>
  );
}
