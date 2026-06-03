import { useState, useEffect } from 'react';
import './App.css';
import Login from './components/Login';
import Sidebar, { type Page } from './components/Sidebar';
import Dashboard from './components/Dashboard';
import ModuloURA from './components/ModuloURA';
import ModuloConectividade from './components/ModuloConectividade';
import ModuloAlertas from './components/ModuloAlertas';
import Softphone from './components/Softphone';

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
    return <Login onLogin={handleLogin} />;
  }

  // ---- Autenticado: layout principal ----
  return (
    <div className="app-layout">
      <Sidebar
        currentPage={page}
        onNavigate={setPage}
        username={username}
        onLogout={handleSignOut}
      />

      <main className="main-content">
        {page === 'dashboard'  && <Dashboard />}
        {page === 'modulo1'    && <ModuloURA />}
        {page === 'modulo2'    && <ModuloConectividade />}
        {page === 'modulo3'    && <ModuloAlertas />}
      </main>

      {/* Softphone WebRTC — flutuante em todas as páginas */}
      <Softphone />
    </div>
  );
}
