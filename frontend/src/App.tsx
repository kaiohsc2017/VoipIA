import { useState, useEffect, Component, type ReactNode, lazy, Suspense } from 'react';
import './App.css';
import Login from './components/Login';
import Sidebar, { type Page } from './components/Sidebar';
import ModuloLogs from './components/ModuloLogs';
import ModuloSeguranca from './components/ModuloSeguranca';

// ─── Lazy imports — cada módulo vira um chunk separado ───────────────────────
// O React cria um chunk JS separado para cada componente lazy.
// O chunk só é baixado quando o usuário navega para aquela página.
const Dashboard          = lazy(() => import('./components/Dashboard'));
const ModuloURA          = lazy(() => import('./components/ModuloURA'));
const ModuloConectividade= lazy(() => import('./components/ModuloConectividade'));
const ModuloAlertas      = lazy(() => import('./components/ModuloAlertas'));
const Softphone          = lazy(() => import('./components/Softphone'));
const MasterData         = lazy(() => import('./components/MasterData'));
const Users              = lazy(() => import('./components/Users'));
const Settings           = lazy(() => import('./components/Settings'));
const Auditoria          = lazy(() => import('./components/Auditoria'));

// ─── ErrorBoundary ─────────────────────────────────────────────────────────────
// Evita que erros em componentes filhos desmontem toda a árvore React (tela em branco).
// React 18 sem ErrorBoundary: qualquer exceção em render/useEffect → root vazio.
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
              background: '#7c3aed', color: '#fff', cursor: 'pointer', fontSize: '0.875rem',
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

export default function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('asteriskia_token'));
  const [username, setUsername] = useState<string>(() => localStorage.getItem('asteriskia_user') ?? '');
  const pageFromHash = (): Page => {
    const hash = window.location.hash.replace('#', '').trim() as Page;
    const valid: Page[] = ['dashboard','modulo1','modulo2','modulo3','masterdata','users','settings','audit','logs','security'];
    return valid.includes(hash) ? hash : 'dashboard';
  };
  const [page, setPage] = useState<Page>(pageFromHash);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // Escuta evento de logout forçado (token expirado / 401)
  useEffect(() => {
    const handleLogout = () => handleSignOut();
    window.addEventListener('asteriskia:logout', handleLogout);
    return () => window.removeEventListener('asteriskia:logout', handleLogout);
  }, []);

  // Sincroniza page com o hash da URL (botões voltar/avançar do browser)
  useEffect(() => {
    const onHashChange = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const handleLogin = (t: string, user: string) => {
    setToken(t);
    setUsername(user);
    setPage(pageFromHash());
  };

  const handleSignOut = () => {
    localStorage.removeItem('asteriskia_token');
    localStorage.removeItem('asteriskia_user');
    setToken(null);
    setUsername('');
  };

  // ---- Não autenticado: tela de login ----
  if (!token) {
    return (
      <ErrorBoundary>
        <Login onLogin={handleLogin} />
      </ErrorBoundary>
    );
  }

  // ---- Autenticado: layout principal ----
  return (
    <ErrorBoundary>
      <div className="app-layout">
        <Sidebar
          currentPage={page}
          onNavigate={(p) => { setPage(p); window.location.hash = p; }}
          username={username}
          onLogout={handleSignOut}
          collapsed={sidebarCollapsed}
          onToggleCollapse={() => setSidebarCollapsed(c => !c)}
        />

        <main className={`main-content${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
          <Suspense fallback={<PageLoader />}>
            <ErrorBoundary>
              {page === 'dashboard'  && <Dashboard />}
              {page === 'modulo1'    && <ModuloURA />}
              {page === 'modulo2'    && <ModuloConectividade />}
              {page === 'modulo3'    && <ModuloAlertas />}
              {page === 'masterdata' && <MasterData />}
              {page === 'users'      && <Users />}
              {page === 'settings'   && <Settings />}
              {page === 'audit'      && <Auditoria />}
              {page === 'logs'       && <ModuloLogs />}
              {page === 'security'   && <ModuloSeguranca />}
            </ErrorBoundary>
          </Suspense>
        </main>

        {/* Softphone WebRTC — flutuante em todas as páginas */}
        <Suspense fallback={null}>
          <ErrorBoundary><Softphone /></ErrorBoundary>
        </Suspense>
      </div>
    </ErrorBoundary>
  );
}
