import { useState, useEffect, Component, type ReactNode } from 'react';
import './App.css';
import Login from './components/Login';
import Sidebar, { type Page } from './components/Sidebar';
import Dashboard from './components/Dashboard';
import ModuloURA from './components/ModuloURA';
import ModuloConectividade from './components/ModuloConectividade';
import ModuloAlertas from './components/ModuloAlertas';
import Softphone from './components/Softphone';

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

// ─── App ───────────────────────────────────────────────────────────────────────

export default function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('asteriskia_token'));
  const [username, setUsername] = useState<string>(() => localStorage.getItem('asteriskia_user') ?? '');
  const [page, setPage] = useState<Page>('dashboard');

  // Escuta evento de logout forçado (token expirado / 401)
  useEffect(() => {
    const handleLogout = () => handleSignOut();
    window.addEventListener('asteriskia:logout', handleLogout);
    return () => window.removeEventListener('asteriskia:logout', handleLogout);
  }, []);

  const handleLogin = (t: string, user: string) => {
    setToken(t);
    setUsername(user);
    setPage('dashboard');
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
          onNavigate={setPage}
          username={username}
          onLogout={handleSignOut}
        />

        <main className="main-content">
          {page === 'dashboard'  && <ErrorBoundary><Dashboard /></ErrorBoundary>}
          {page === 'modulo1'    && <ErrorBoundary><ModuloURA /></ErrorBoundary>}
          {page === 'modulo2'    && <ErrorBoundary><ModuloConectividade /></ErrorBoundary>}
          {page === 'modulo3'    && <ErrorBoundary><ModuloAlertas /></ErrorBoundary>}
        </main>

        {/* Softphone WebRTC — flutuante em todas as páginas */}
        <ErrorBoundary><Softphone /></ErrorBoundary>
      </div>
    </ErrorBoundary>
  );
}
