import { useEffect, useState, Component, type ReactNode } from 'react';
import Login from './components/Login';
import Sidebar, { type Tab } from './components/Sidebar';
import { InsightsTab } from './components/InsightsTab';
import { InsightsDashboardTab } from './components/InsightsDashboardTab';
import { InsightsProcessingTab } from './components/InsightsProcessingTab';
import { ScorecardsTab } from './components/ScorecardsTab';
import { ReportsTab } from './components/ReportsTab';
import { SupervisorPortalTab } from './components/SupervisorPortalTab';
import { revokeSession } from './api/client';
import { authSessionFromToken } from './hooks/useAuthSession';
import type { InsightsDrillDownFilters } from './api/types';

// Resource keys do namespace RBAC granular `insights.*` — espelha o namespace
// `agents.*` da Plataforma de Agentes (ResourceCatalog.java). Mantido em
// sincronia manual com o backend e com os `resource` de Sidebar.tsx, mesma
// duplicação intencional já aceita entre Sidebar.tsx/PAGE_RESOURCE no Telecom.
const TAB_RESOURCE = {
  calls: 'insights.calls',
  dashboard: 'insights.dashboard',
  processing: 'insights.processing',
  scorecards: 'insights.scorecards',
  reports: 'insights.reports',
  uploads: 'insights.uploads',
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

  const [tab, setTab] = useState<Tab>('calls');
  const [pendingDrillDown, setPendingDrillDown] = useState<{ filters: InsightsDrillDownFilters; nonce: number } | null>(null);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

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

  const handleDrillDown = (filters: InsightsDrillDownFilters) => {
    setPendingDrillDown(prev => ({ filters, nonce: (prev?.nonce ?? 0) + 1 }));
    setTab('calls');
  };

  const handleDrillDownConsumed = () => setPendingDrillDown(null);

  if (!token) {
    return (
      <ErrorBoundary>
        <Login onLogin={handleLogin} />
      </ErrorBoundary>
    );
  }

  const TABS: { id: Tab }[] = [
    { id: 'calls' },
    { id: 'dashboard' },
    { id: 'processing' },
    { id: 'scorecards' },
    { id: 'reports' },
    { id: 'uploads' },
  ];
  const visibleTabs = TABS.filter(t => session.hasRead(TAB_RESOURCE[t.id]));
  const currentTab = visibleTabs.some(t => t.id === tab) ? tab : visibleTabs[0]?.id;

  return (
    <ErrorBoundary>
      <div className="app-layout">
        <Sidebar
          currentTab={currentTab ?? 'calls'}
          onNavigate={setTab}
          username={username}
          session={session}
          onLogout={handleSignOut}
          collapsed={sidebarCollapsed}
          onToggleCollapse={() => setSidebarCollapsed(c => !c)}
        />
        <main className={`main-content${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
          <div className="page-body">
            {currentTab === 'calls' && <InsightsTab pendingDrillDown={pendingDrillDown} onDrillDownConsumed={handleDrillDownConsumed} />}
            {currentTab === 'dashboard' && <InsightsDashboardTab onDrillDown={handleDrillDown} />}
            {currentTab === 'processing' && <InsightsProcessingTab onDrillDown={handleDrillDown} />}
            {currentTab === 'scorecards' && <ScorecardsTab canWrite={session.hasWrite('insights.scorecards')} />}
            {currentTab === 'reports' && <ReportsTab canWrite={session.hasWrite('insights.reports')} isAdmin={session.role === 'ADMIN'} />}
            {currentTab === 'uploads' && (
              <SupervisorPortalTab canWrite={session.hasWrite('insights.uploads')} isAdmin={session.role === 'ADMIN'} />
            )}
            {!currentTab && (
              <p style={{ color: 'var(--text-muted)' }}>Você não tem permissão de leitura em nenhuma aba do Insights.</p>
            )}
          </div>
        </main>
      </div>
    </ErrorBoundary>
  );
}
