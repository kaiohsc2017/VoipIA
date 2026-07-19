import { useEffect, useState, Component, type ReactNode } from 'react';
import Login from './components/Login';
import { InsightsTab } from './components/InsightsTab';
import { InsightsDashboardTab, type InsightsDrillDownFilters } from './components/InsightsDashboardTab';
import { InsightsProcessingTab } from './components/InsightsProcessingTab';
import { InsightsCostsTab } from './components/InsightsCostsTab';
import { InsightsCostsDashboardTab } from './components/InsightsCostsDashboardTab';
import { revokeSession } from './api/client';
import { authSessionFromToken } from './hooks/useAuthSession';

// Resource keys do namespace RBAC granular `insights.*` — espelha o namespace
// `agents.*` da Plataforma de Agentes (ResourceCatalog.java). Mantido em
// sincronia manual com o backend, mesmo padrão do restante do sistema.
const TAB_RESOURCE = {
  calls: 'insights.calls',
  dashboard: 'insights.dashboard',
  processing: 'insights.processing',
  costs: 'insights.costs',
  costsDashboard: 'insights.costs',
} as const;

type Tab = keyof typeof TAB_RESOURCE;

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

  const TABS: { id: Tab; label: string }[] = [
    { id: 'calls', label: '📋 Chamadas' },
    { id: 'dashboard', label: '📈 Dashboard de Tendências' },
    { id: 'processing', label: '⚙️ Processamento' },
    { id: 'costs', label: '💰 Custos IA' },
    { id: 'costsDashboard', label: '📈 Dashboard de Custos' },
  ];
  const visibleTabs = TABS.filter(t => session.hasRead(TAB_RESOURCE[t.id]));
  const currentTab = visibleTabs.some(t => t.id === tab) ? tab : visibleTabs[0]?.id;

  return (
    <ErrorBoundary>
      <div className="app-layout" style={{ flexDirection: 'column' }}>
        <header className="topbar" style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '12px 24px', borderBottom: '1px solid var(--border-glass)',
          background: 'var(--bg-glass)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <strong>💡 Insights</strong>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
              Transcrição e análise de IA das gravações do call center
            </span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>{username}</span>
            <button className="btn btn-ghost" onClick={handleSignOut}>Sair</button>
          </div>
        </header>

        <main className="page-body" style={{ flex: 1, overflow: 'auto' }}>
          <div style={{ marginBottom: 20, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {visibleTabs.map(t => (
              <button
                key={t.id}
                className={`btn ${currentTab === t.id ? 'btn-primary' : 'btn-ghost'}`}
                onClick={() => setTab(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>

          {currentTab === 'calls' && <InsightsTab pendingDrillDown={pendingDrillDown} onDrillDownConsumed={handleDrillDownConsumed} />}
          {currentTab === 'dashboard' && <InsightsDashboardTab onDrillDown={handleDrillDown} />}
          {currentTab === 'processing' && <InsightsProcessingTab />}
          {currentTab === 'costs' && <InsightsCostsTab />}
          {currentTab === 'costsDashboard' && <InsightsCostsDashboardTab />}
          {!currentTab && (
            <p style={{ color: 'var(--text-muted)' }}>Você não tem permissão de leitura em nenhuma aba do Insights.</p>
          )}
        </main>
      </div>
    </ErrorBoundary>
  );
}
