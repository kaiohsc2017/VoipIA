type Page = 'dashboard' | 'modulo1' | 'modulo2' | 'modulo3' | 'masterdata' | 'users' | 'settings' | 'audit' | 'logs' | 'security' | 'agents';

interface SidebarProps {
  currentPage: Page;
  onNavigate: (page: Page) => void;
  username: string;
  role: 'ADMIN' | 'USER';
  onLogout: () => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
}

// adminOnly reflete exatamente o que SecurityConfig.java exige com hasRole("ADMIN")
// (/users/**, /settings/**, /logs/**, /security/**) — manter em sincronia manual.
const NAV_ITEMS: { page: Page; icon: string; label: string; section: string; external?: string; adminOnly?: boolean }[] = [
  { page: 'dashboard',  icon: '📊', label: 'Dashboard',          section: 'GERAL'     },
  { page: 'modulo1',    icon: '🎫', label: 'URA',                section: 'MÓDULOS'   },
  { page: 'modulo2',    icon: '📞', label: 'Conectividade',      section: 'MÓDULOS'   },
  { page: 'modulo3',    icon: '🚨', label: 'Monitoramento',      section: 'MÓDULOS'   },
  { page: 'agents',     icon: '🤖', label: 'Agentes',            section: 'MÓDULOS',  external: '/agents/' },
  { page: 'masterdata', icon: '👤', label: 'Clientes',           section: 'CADASTROS' },
  { page: 'users',      icon: '👥', label: 'Usuários e Ramais',  section: 'CADASTROS', adminOnly: true },
  { page: 'settings',   icon: '🔧', label: 'Configurações',      section: 'SISTEMA',   adminOnly: true },
  { page: 'logs',       icon: '🖥️', label: 'Logs',               section: 'SISTEMA',   adminOnly: true },
  { page: 'security',   icon: '🛡️', label: 'Segurança',          section: 'SISTEMA',   adminOnly: true },
  { page: 'audit',      icon: '🔐', label: 'Auditoria',          section: 'SISTEMA'   },
];

export default function Sidebar({ currentPage, onNavigate, username, role, onLogout, collapsed, onToggleCollapse }: SidebarProps) {
  let lastSection = '';
  const visibleItems = NAV_ITEMS.filter(item => !item.adminOnly || role === 'ADMIN');

  return (
    <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>

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
        {visibleItems.map(item => {
          const showSection = item.section !== lastSection;
          lastSection = item.section;
          return (
            <div key={item.page}>
              {showSection && (
                <div className="nav-section-label">{item.section}</div>
              )}
              {item.external ? (
                <a
                  href={item.external}
                  target="_blank"
                  rel="noopener noreferrer"
                  className={`nav-item ${currentPage === item.page ? 'active' : ''}`}
                  title={collapsed ? item.label : undefined}
                  style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '8px' }}
                >
                  <span className="nav-icon">{item.icon}</span>
                  <span>{item.label}</span>
                  {!collapsed && <span style={{ marginLeft: 'auto', fontSize: '10px', opacity: 0.5 }}>↗</span>}
                </a>
              ) : (
                <div
                  className={`nav-item ${currentPage === item.page ? 'active' : ''}`}
                  onClick={() => onNavigate(item.page)}
                  role="button"
                  tabIndex={0}
                  title={collapsed ? item.label : undefined}
                  onKeyDown={e => e.key === 'Enter' && onNavigate(item.page)}
                >
                  <span className="nav-icon">{item.icon}</span>
                  <span>{item.label}</span>
                </div>
              )}
            </div>
          );
        })}
      </nav>

      {/* Footer: toggle + usuário + sair */}
      <div className="sidebar-footer">
        {/* Botão de recolher — parte do footer, sem posição absoluta */}
        <button
          className="sidebar-toggle-btn"
          onClick={onToggleCollapse}
          title={collapsed ? 'Expandir menu' : 'Recolher menu'}
        >
          <span className="toggle-icon">{collapsed ? '▶' : '◀'}</span>
          <span className="toggle-label">Recolher</span>
        </button>

        <div className="user-info">
          <div className="user-avatar">
            {username.charAt(0).toUpperCase()}
          </div>
          <div>
            <div className="user-name">{username}</div>
            <div className="user-role">{role === 'ADMIN' ? 'Administrador' : 'Usuário'}</div>
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
