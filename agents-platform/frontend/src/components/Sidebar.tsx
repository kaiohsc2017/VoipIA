import type { ComponentType } from 'react';
import { useState } from 'react';
import {
  LayoutDashboard, Bot, Server, BookOpen, Terminal, Bell, KeyRound, Settings, LogOut,
} from 'lucide-react';
import type { AuthSession } from '../hooks/useAuthSession';

export type Page = 'dashboard' | 'agents' | 'servers' | 'knowledge' | 'logs' | 'reports' | 'secrets' | 'llm';

interface SidebarProps {
  currentPage: Page;
  onNavigate: (page: Page) => void;
  username: string;
  session: AuthSession;
  onLogout: () => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
  alertCount: number;
}

// resource espelha o namespace RBAC granular agents.* (ResourceCatalog.java /
// access_group_permissions) — mesmo padrão do Sidebar.tsx do Telecom/Insights.
const NAV_ITEMS: { page: Page; icon: ComponentType<{ size?: number; strokeWidth?: number }>; label: string; resource: string }[] = [
  { page: 'dashboard', icon: LayoutDashboard, label: 'Dashboard',           resource: 'agents.dashboard' },
  { page: 'agents',    icon: Bot,             label: 'Agentes',            resource: 'agents.agents'    },
  { page: 'servers',   icon: Server,          label: 'Servidores',         resource: 'agents.servers'   },
  { page: 'knowledge', icon: BookOpen,        label: 'Base de Conhecimento', resource: 'agents.knowledge' },
  { page: 'logs',      icon: Terminal,        label: 'Logs',               resource: 'agents.logs'      },
  { page: 'reports',   icon: Bell,            label: 'Alertas',            resource: 'agents.reports'   },
  { page: 'secrets',   icon: KeyRound,        label: 'Secrets',            resource: 'agents.secrets'   },
  { page: 'llm',       icon: Settings,        label: 'Config. IA',         resource: 'agents.llm'       },
];

export default function Sidebar({ currentPage, onNavigate, username, session, onLogout, collapsed, onToggleCollapse, alertCount }: SidebarProps) {
  const visibleItems = NAV_ITEMS.filter(item => session.hasRead(item.resource));

  // Expansão temporária ao passar o mouse — só entra em ação quando o menu
  // está no estado fixo "colapsado"; não altera o estado `collapsed` do App.tsx.
  const [hoverExpanded, setHoverExpanded] = useState(false);
  const isEffectivelyCollapsed = collapsed && !hoverExpanded;

  return (
    <aside
      className={`sidebar${isEffectivelyCollapsed ? ' collapsed' : ''}`}
      onMouseEnter={() => { if (collapsed) setHoverExpanded(true); }}
      onMouseLeave={() => setHoverExpanded(false)}
    >
      <div className="sidebar-logo" onClick={onToggleCollapse} style={{ cursor: 'pointer' }}>
        <div className="logo-mark">
          <div className="logo-icon">A★</div>
          <span className="logo-text">VoipIA</span>
        </div>
        <div className="logo-version">Agentes</div>
      </div>

      <nav className="sidebar-nav">
        {visibleItems.map(item => {
          const Icon = item.icon;
          return (
            <div
              key={item.page}
              className={`nav-item ${currentPage === item.page ? 'active' : ''}`}
              onClick={() => onNavigate(item.page)}
              role="button"
              tabIndex={0}
              title={isEffectivelyCollapsed ? item.label : undefined}
              onKeyDown={e => e.key === 'Enter' && onNavigate(item.page)}
            >
              <span className="nav-icon"><Icon size={17} strokeWidth={1.75} /></span>
              <span>{item.label}</span>
              {item.page === 'reports' && alertCount > 0 && (
                <span style={{
                  marginLeft: 'auto', background: 'var(--clr-danger)', color: '#fff',
                  fontSize: 10, fontWeight: 700, padding: '1px 6px', borderRadius: 99,
                }}>
                  {alertCount}
                </span>
              )}
            </div>
          );
        })}
      </nav>

      <div className="sidebar-footer">
        <div className="user-info">
          <div className="user-avatar">
            {username.charAt(0).toUpperCase()}
          </div>
          <div>
            <div className="user-name">{username}</div>
            <div className="user-role">{session.role === 'ADMIN' ? 'Administrador' : 'Usuário'}</div>
          </div>
        </div>
        <button className="btn-logout" onClick={onLogout}>
          <LogOut size={15} />
          <span>Sair</span>
        </button>
      </div>
    </aside>
  );
}
