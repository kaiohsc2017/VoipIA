type Page = 'dashboard' | 'modulo1' | 'modulo2' | 'modulo3';

interface SidebarProps {
  currentPage: Page;
  onNavigate: (page: Page) => void;
  username: string;
  onLogout: () => void;
}

const NAV_ITEMS: { page: Page; icon: string; label: string; section: string }[] = [
  { page: 'dashboard', icon: '📊', label: 'Dashboard', section: 'GERAL' },
  { page: 'modulo1',   icon: '🎫', label: 'URA / Jira',        section: 'MÓDULOS' },
  { page: 'modulo2',   icon: '📞', label: 'Conectividade',     section: 'MÓDULOS' },
  { page: 'modulo3',   icon: '🚨', label: 'Alertas Zabbix',    section: 'MÓDULOS' },
];

export default function Sidebar({ currentPage, onNavigate, username, onLogout }: SidebarProps) {
  let lastSection = '';

  return (
    <aside className="sidebar">
      {/* Logo */}
      <div className="sidebar-logo">
        <div className="logo-mark">
          <div className="logo-icon">A★</div>
          <span className="logo-text">AsteriskIA</span>
        </div>
        <div className="logo-version">v1.0 — Painel de Controle</div>
      </div>

      {/* Nav */}
      <nav className="sidebar-nav">
        {NAV_ITEMS.map(item => {
          const showSection = item.section !== lastSection;
          lastSection = item.section;
          return (
            <div key={item.page}>
              {showSection && (
                <div className="nav-section-label">{item.section}</div>
              )}
              <div
                className={`nav-item ${currentPage === item.page ? 'active' : ''}`}
                onClick={() => onNavigate(item.page)}
                role="button"
                tabIndex={0}
                onKeyDown={e => e.key === 'Enter' && onNavigate(item.page)}
              >
                <span className="nav-icon">{item.icon}</span>
                <span>{item.label}</span>
              </div>
            </div>
          );
        })}
      </nav>

      {/* Footer */}
      <div className="sidebar-footer">
        <div className="user-info">
          <div className="user-avatar">
            {username.charAt(0).toUpperCase()}
          </div>
          <div>
            <div className="user-name">{username}</div>
            <div className="user-role">Administrador</div>
          </div>
        </div>
        <button className="btn-logout" onClick={onLogout}>
          <span>🚪</span>
          <span>Sair</span>
        </button>
      </div>
    </aside>
  );
}

export type { Page };
