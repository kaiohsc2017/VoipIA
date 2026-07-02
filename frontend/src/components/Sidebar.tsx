import { canRead } from '../api/client';

type Page = 'dashboard' | 'modulo1' | 'modulo2' | 'modulo3' | 'masterdata' | 'users' | 'settings' | 'audit' | 'logs' | 'security' | 'agents' | 'accessGroups';

interface SidebarProps {
  currentPage: Page;
  onNavigate: (page: Page) => void;
  username: string;
  role: 'ADMIN' | 'USER';
  perms: Record<string, string>;
  onLogout: () => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
}

// resource espelha o catálogo de recursos do RBAC granular (ResourceCatalog.java
// / access_group_permissions) — manter em sincronia manual com o backend.
// Itens com adminOnly (em vez de resource) não têm resource_key próprio —
// o backend exige ROLE_ADMIN puro nesse endpoint (ver AccessGroupController).
const NAV_ITEMS: { page: Page; icon: string; label: string; section: string; external?: string; resource?: string; adminOnly?: boolean }[] = [
  { page: 'dashboard',  icon: '📊', label: 'Dashboard',          section: 'GERAL',     resource: 'telecom.dashboard'    },
  { page: 'modulo1',    icon: '🎫', label: 'URA',                section: 'MÓDULOS',   resource: 'telecom.modulo1'      },
  { page: 'modulo2',    icon: '📞', label: 'Conectividade',      section: 'MÓDULOS',   resource: 'telecom.modulo2'      },
  { page: 'modulo3',    icon: '🚨', label: 'Monitoramento',      section: 'MÓDULOS',   resource: 'telecom.modulo3'      },
  { page: 'agents',     icon: '🤖', label: 'Agentes',            section: 'MÓDULOS',   resource: 'telecom.agents_link', external: '/agents/' },
  { page: 'masterdata', icon: '👤', label: 'Clientes',           section: 'CADASTROS', resource: 'telecom.masterdata'   },
  { page: 'users',      icon: '👥', label: 'Usuários e Ramais',  section: 'CADASTROS', resource: 'telecom.users'        },
  { page: 'settings',   icon: '🔧', label: 'Configurações',      section: 'SISTEMA',   resource: 'telecom.settings'     },
  { page: 'logs',       icon: '🖥️', label: 'Logs',               section: 'SISTEMA',   resource: 'telecom.logs'         },
  { page: 'security',   icon: '🛡️', label: 'Segurança',          section: 'SISTEMA',   resource: 'telecom.security'     },
  { page: 'accessGroups', icon: '🔑', label: 'Grupos de Acesso', section: 'SISTEMA',   adminOnly: true                  },
  { page: 'audit',      icon: '🔐', label: 'Auditoria',          section: 'SISTEMA',   resource: 'telecom.audit'        },
];

export default function Sidebar({ currentPage, onNavigate, username, role, perms, onLogout, collapsed, onToggleCollapse }: SidebarProps) {
  let lastSection = '';
  const visibleItems = NAV_ITEMS.filter(item =>
    item.adminOnly ? role === 'ADMIN' : canRead(role, perms, item.resource!)
  );

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
