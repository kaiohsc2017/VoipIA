import { useEffect, useState, Component, type ReactNode } from 'react';
import Login from './components/Login';
import Sidebar, { type Page } from './components/Sidebar';
import { DashboardTab } from './components/DashboardTab';
import { AgentsTab } from './components/AgentsTab';
import { ServersTab } from './components/ServersTab';
import { KnowledgeTab } from './components/KnowledgeTab';
import { LogsTab } from './components/LogsTab';
import { AlertsTab } from './components/AlertsTab';
import { SecretsTab } from './components/SecretsTab';
import { LlmSettingsTab } from './components/LlmSettingsTab';
import api from './api/client';
import { authSessionFromToken } from './hooks/useAuthSession';
import { useAgentsAlerts } from './hooks/useAgentsAlerts';
import { useShellBridge } from './hooks/useShellBridge';
import type { PaginatedResponse, ServerEntry } from './api/types';

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
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('voipia_token') ?? localStorage.getItem('asteriskia_token'));
  const [username, setUsername] = useState<string>(() => localStorage.getItem('voipia_user') ?? localStorage.getItem('asteriskia_user') ?? '');
  const session = authSessionFromToken(token);

  const [page, setPage] = useState<Page>('dashboard');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [servers, setServers] = useState<ServerEntry[]>([]);
  const alertCount = useAgentsAlerts(!!token);

  // Escuta logout forçado (token expirado / 401 no backend de Agentes) —
  // mesmo padrão do Telecom/Insights.
  useEffect(() => {
    const handleLogout = () => handleSignOut();
    window.addEventListener('voipia:logout', handleLogout);
    window.addEventListener('asteriskia:logout', handleLogout);
    return () => {
      window.removeEventListener('voipia:logout', handleLogout);
      window.removeEventListener('asteriskia:logout', handleLogout);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Lista de servidores carregada uma vez — usada pelo formulário de Agentes
  // (checkboxes de servidor alvo), mesmo padrão do app legado (App:1309-1317).
  useEffect(() => {
    if (!token) return;
    api.get<PaginatedResponse<ServerEntry> | ServerEntry[]>('/api/servers/')
      .then(({ data }) => setServers(Array.isArray(data) ? data : data.items))
      .catch(() => setServers([]));
  }, [token]);

  const handleLogin = (t: string, user: string) => {
    setToken(t);
    setUsername(user);
  };

  const handleSignOut = () => {
    localStorage.removeItem('voipia_token');
    localStorage.removeItem('voipia_user');
    localStorage.removeItem('asteriskia_token');
    localStorage.removeItem('asteriskia_user');
    setToken(null);
    setUsername('');
  };

  // Hooks e cálculos sempre na mesma ordem, independente de `token` — nunca depois
  // do early return de login abaixo (React quebra a árvore de hooks se o número
  // de chamadas mudar entre renders do mesmo componente, ex: no instante do login).
  const canWriteAgents = session.hasWrite('agents.agents');
  const canWriteServers = session.hasWrite('agents.servers');
  const canWriteKnowledge = session.hasWrite('agents.knowledge');
  const canWriteSecrets = session.hasWrite('agents.secrets');
  const canWriteLlm = session.hasWrite('agents.llm');

  // Página sem permissão de leitura acessada (ex: estado preso de antes do
  // login trocar de usuário) — volta pro dashboard, mesmo critério do app
  // legado (canOpenPage, index.html:1360).
  const PAGE_RESOURCE: Record<Page, string> = {
    dashboard: 'agents.dashboard',
    agents: 'agents.agents',
    servers: 'agents.servers',
    knowledge: 'agents.knowledge',
    logs: 'agents.logs',
    reports: 'agents.reports',
    secrets: 'agents.secrets',
    llm: 'agents.llm',
  };
  const currentPage: Page = (page in PAGE_RESOURCE && session.hasRead(PAGE_RESOURCE[page])) ? page : 'dashboard';

  const { isEmbedded, notifyAlertCount } = useShellBridge(currentPage, (p) => setPage(p as Page));

  useEffect(() => {
    notifyAlertCount(alertCount);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [alertCount]);

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
            currentPage={currentPage}
            onNavigate={setPage}
            username={username}
            session={session}
            onLogout={handleSignOut}
            collapsed={sidebarCollapsed}
            onToggleCollapse={() => setSidebarCollapsed(c => !c)}
            alertCount={alertCount}
          />
        )}
        <main className={`main-content${isEmbedded ? ' embedded' : sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
          {currentPage === 'dashboard' && <DashboardTab />}
          {currentPage === 'agents' && <AgentsTab servers={servers} canWrite={canWriteAgents} />}
          {currentPage === 'servers' && <ServersTab canWrite={canWriteServers} />}
          {currentPage === 'knowledge' && <KnowledgeTab canWrite={canWriteKnowledge} />}
          {currentPage === 'logs' && <LogsTab />}
          {currentPage === 'reports' && <AlertsTab />}
          {currentPage === 'secrets' && <SecretsTab canWrite={canWriteSecrets} />}
          {currentPage === 'llm' && <LlmSettingsTab canWrite={canWriteLlm} />}
        </main>
      </div>
    </ErrorBoundary>
  );
}
