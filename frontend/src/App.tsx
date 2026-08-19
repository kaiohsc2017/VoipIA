import { useState, useEffect, Component, type ReactNode, lazy, Suspense } from 'react';
import './App.css';
import Login from './components/Login';
import Sidebar, { type Page } from './components/Sidebar';
import { revokeSession } from './api/client';
import { authSessionFromToken } from './hooks/useAuthSession';

// ─── Lazy imports ─────────────────────────────────────────────────────────────
const Dashboard      = lazy(() => import('./components/Dashboard'));
const ModuloURA      = lazy(() => import('./components/ModuloURA'));
const Softphone      = lazy(() => import('./components/Softphone'));
const Users          = lazy(() => import('./components/Users'));
const Settings       = lazy(() => import('./components/Settings'));
const Auditoria      = lazy(() => import('./components/Auditoria'));
const AccessGroups   = lazy(() => import('./components/AccessGroups'));
const Release        = lazy(() => import('./components/Release'));
const InsightsPage   = lazy(() => import('./components/InsightsPage'));
const CallCenterPage = lazy(() => import('./components/CallCenterPage'));
const Financeiro     = lazy(() => import('./components/Financeiro'));

// ─── ErrorBoundary ─────────────────────────────────────────────────────────────
class ErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
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
          background: 'var(--bg-primary, #0f172a)', color: 'var(--text-primary, #e2e8f0)',
          fontFamily: 'Inter, sans-serif',
        }}>
          <div style={{ fontSize: '2rem' }}>⚠️</div>
          <h2 style={{ margin: 0 }}>Erro inesperado</h2>
          <p style={{ color: '#94a3b8', fontSize: '0.875rem' }}>
            {(this.state.error as Error).message}
          </p>
          <button
            style={{
              padding: '8px 20px', borderRadius: 8, border: 'none',
              background: '#007aff', color: '#fff', cursor: 'pointer', fontSize: '0.875rem',
            }}
            onClick={() => { this.setState({ error: null }); window.location.reload(); }}
          >
            Recarregar página
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

// ─── Spinner de carregamento de página ────────────────────────────────────────
function PageLoader() {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      height: '100%', gap: 12, color: 'var(--text-muted)',
      fontSize: '0.9rem',
    }}>
      <div className="spinner" />
      Carregando…
    </div>
  );
}

// ─── App ───────────────────────────────────────────────────────────────────────

const PAGE_RESOURCE: Partial<Record<Page, string>> = {
  dashboard:     'telecom.dashboard',
  modulo1:       'telecom.modulo1',
  users:         'telecom.users',
  settings:      'telecom.settings',
  audit:         'telecom.audit',
  release:       'telecom.release',
  finUra:        'financeiro.ura',
  finInsights:   'financeiro.insights',
  finEnvios:     'financeiro.envios',
  insCalls:      'insights.calls',
  insDashboard:  'insights.dashboard',
  insProcessing: 'insights.processing',
  insScorecards: 'insights.scorecards',
  insReports:    'insights.reports',
  insUploads:    'insights.uploads',
  ccAgentes:     'callcenter.agentes',
  ccFilas:       'callcenter.filas',
  ccSkills:      'callcenter.skills',
  ccGravacoes:   'callcenter.gravacoes',
  ccDesktop:     'callcenter.desktop',
  ccSupervisao:  'callcenter.supervisao',
  ccFluxos:      'callcenter.fluxos',
};

const LINK_RESOURCE: Partial<Record<Page, string>> = {
  insCalls: 'telecom.insights_link', insDashboard: 'telecom.insights_link', insProcessing: 'telecom.insights_link',
  insScorecards: 'telecom.insights_link', insReports: 'telecom.insights_link', insUploads: 'telecom.insights_link',
  ccAgentes: 'telecom.callcenter_link', ccFilas: 'telecom.callcenter_link', ccSkills: 'telecom.callcenter_link',
  ccGravacoes: 'telecom.callcenter_link', ccDesktop: 'telecom.callcenter_link', ccSupervisao: 'telecom.callcenter_link',
  ccFluxos: 'telecom.callcenter_link',
};

const INSIGHTS_SUBPAGES: Page[] = ['insCalls', 'insDashboard', 'insProcessing', 'insScorecards', 'insReports', 'insUploads'];
const CALLCENTER_SUBPAGES: Page[] = ['ccAgentes', 'ccFilas', 'ccSkills', 'ccGravacoes', 'ccDesktop', 'ccSupervisao', 'ccFluxos'];
const FINANCEIRO_SUBPAGES: Page[] = ['finUra', 'finInsights', 'finEnvios'];

const INSIGHTS_PAGE_TO_TAB: Record<string, string> = {
  insCalls: 'calls', insDashboard: 'dashboard', insProcessing: 'processing',
  insScorecards: 'scorecards', insReports: 'reports', insUploads: 'uploads',
};
const INSIGHTS_TAB_TO_PAGE: Record<string, Page> = {
  calls: 'insCalls', dashboard: 'insDashboard', processing: 'insProcessing',
  scorecards: 'insScorecards', reports: 'insReports', uploads: 'insUploads',
};

const CALLCENTER_PAGE_TO_TAB: Record<string, string> = {
  ccAgentes: 'agentes', ccFilas: 'filas', ccSkills: 'skills',
  ccGravacoes: 'gravacoes', ccDesktop: 'desktop', ccSupervisao: 'supervisao', ccFluxos: 'fluxos',
};
const CALLCENTER_TAB_TO_PAGE: Record<string, Page> = {
  agentes: 'ccAgentes', filas: 'ccFilas', skills: 'ccSkills',
  gravacoes: 'ccGravacoes', desktop: 'ccDesktop', supervisao: 'ccSupervisao', fluxos: 'ccFluxos',
};

const FINANCEIRO_PAGE_TO_TAB: Record<string, string> = {
  finUra: 'ura', finInsights: 'insights', finEnvios: 'envios',
};

export default function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('voipia_token'));
  const [username, setUsername] = useState<string>(() => localStorage.getItem('voipia_user') ?? '');
  const [role, setRole] = useState<'ADMIN' | 'USER'>(() => authSessionFromToken(localStorage.getItem('voipia_token')).role);
  const [perms, setPerms] = useState<Record<string, string>>(() => authSessionFromToken(localStorage.getItem('voipia_token')).perms);
  const pageFromHash = (): Page => {
    const hash = window.location.hash.replace('#', '').trim() as Page;
    const valid: Page[] = [
      'dashboard','modulo1','users','settings','audit','accessGroups','release',
      ...INSIGHTS_SUBPAGES,
      ...CALLCENTER_SUBPAGES,
      ...FINANCEIRO_SUBPAGES,
    ];
    if (!valid.includes(hash)) return 'dashboard';
    const session = authSessionFromToken(localStorage.getItem('voipia_token'));
    if (hash === 'accessGroups') return session.role === 'ADMIN' ? hash : 'dashboard';
    const resource = PAGE_RESOURCE[hash];
    if (resource && !session.hasRead(resource)) return 'dashboard';
    const link = LINK_RESOURCE[hash];
    if (link && !session.hasRead(link)) return 'dashboard';
    return hash;
  };
  const [page, setPage] = useState<Page>(pageFromHash);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  const navigateTo = (p: Page) => { setPage(p); window.location.hash = p; };

  useEffect(() => {
    const handleLogout = () => handleSignOut();
    window.addEventListener('voipia:logout', handleLogout);
    return () => window.removeEventListener('voipia:logout', handleLogout);
  }, []);

  useEffect(() => {
    const onHashChange = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    const rawHash = window.location.hash.replace('#', '').trim();
    if (rawHash !== page) window.location.hash = page;
  }, [page]);

  const handleLogin = (t: string, user: string) => {
    setToken(t);
    setUsername(user);
    const session = authSessionFromToken(t);
    setRole(session.role);
    setPerms(session.perms);
    setPage(pageFromHash());
  };

  const handleSignOut = () => {
    localStorage.removeItem('voipia_token');
    localStorage.removeItem('voipia_user');
    revokeSession();
    setToken(null);
    setUsername('');
    setRole('USER');
    setPerms({});
  };

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
        <Sidebar
          currentPage={page}
          onNavigate={navigateTo}
          username={username}
          role={role}
          perms={perms}
          onLogout={handleSignOut}
          collapsed={sidebarCollapsed}
          onToggleCollapse={() => setSidebarCollapsed(c => !c)}
        />

        <main className={`main-content${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
          <Suspense fallback={<PageLoader />}>
            <ErrorBoundary>
              {page === 'dashboard'    && <Dashboard />}
              {page === 'modulo1'      && <ModuloURA />}
              {page === 'users'        && <Users />}
              {page === 'settings'     && <Settings />}
              {page === 'audit'        && <Auditoria />}
              {page === 'accessGroups' && <AccessGroups />}
              {page === 'release'      && <Release />}
              {INSIGHTS_SUBPAGES.includes(page) && (
                <InsightsPage
                  tab={INSIGHTS_PAGE_TO_TAB[page] ?? 'calls'}
                  onTabChange={(t) => { const p = INSIGHTS_TAB_TO_PAGE[t]; if (p) navigateTo(p); }}
                />
              )}
              {CALLCENTER_SUBPAGES.includes(page) && (
                <CallCenterPage
                  tab={CALLCENTER_PAGE_TO_TAB[page] ?? 'agentes'}
                  onTabChange={(t) => { const p = CALLCENTER_TAB_TO_PAGE[t]; if (p) navigateTo(p); }}
                />
              )}
              {FINANCEIRO_SUBPAGES.includes(page) && (
                <Financeiro
                  scope={(FINANCEIRO_PAGE_TO_TAB[page] ?? 'ura') as 'ura' | 'insights' | 'envios'}
                />
              )}
            </ErrorBoundary>
          </Suspense>
        </main>

        <Suspense fallback={null}>
          <ErrorBoundary><Softphone /></ErrorBoundary>
        </Suspense>
      </div>
    </ErrorBoundary>
  );
}
