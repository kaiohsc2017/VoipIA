import type { ComponentType } from 'react';
import { useState } from 'react';
import {
  LayoutDashboard, Headset, PhoneCall, AlertTriangle, Bot, Users, UsersRound,
  Settings, Terminal, ShieldCheck, KeyRound, ClipboardList,
  LogOut, ExternalLink, BookOpen, Tag, Phone, Cable, Building2,
} from 'lucide-react';
import { canRead } from '../api/client';
import { RELEASES } from '../data/releases';

const CURRENT_VERSION = RELEASES[RELEASES.length - 1].version;

type Page = 'dashboard' | 'modulo1' | 'modulo2' | 'modulo3' | 'masterdata' | 'users' | 'operadoras' | 'cadastro0800' | 'linhas' | 'settings' | 'audit' | 'logs' | 'security' | 'agents' | 'accessGroups' | 'docs' | 'release';

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
const NAV_ITEMS: { page: Page; icon: ComponentType<{ size?: number; strokeWidth?: number }>; label: string; section: string; external?: string; resource?: string; adminOnly?: boolean }[] = [
  { page: 'dashboard',  icon: LayoutDashboard, label: 'Dashboard',          section: 'GERAL',     resource: 'telecom.dashboard'    },
  { page: 'modulo1',    icon: Headset,         label: 'URA',                section: 'MÓDULOS',   resource: 'telecom.modulo1'      },
  { page: 'modulo2',    icon: PhoneCall,       label: 'Conectividade',      section: 'MÓDULOS',   resource: 'telecom.modulo2'      },
  { page: 'modulo3',    icon: AlertTriangle,   label: 'Monitoramento',      section: 'MÓDULOS',   resource: 'telecom.modulo3'      },
  { page: 'agents',     icon: Bot,             label: 'Agentes',            section: 'MÓDULOS',   resource: 'telecom.agents_link', external: '/agents/' },
  { page: 'masterdata', icon: Users,           label: 'Clientes',           section: 'CADASTROS', resource: 'telecom.masterdata'   },
  { page: 'users',      icon: UsersRound,      label: 'Usuários e Ramais',  section: 'CADASTROS', resource: 'telecom.users'        },
  { page: 'operadoras', icon: Building2,       label: 'Operadoras',         section: 'CADASTROS', resource: 'telecom.operadoras'   },
  { page: 'cadastro0800', icon: Phone,         label: '0800',               section: 'CADASTROS', resource: 'telecom.0800'         },
  { page: 'linhas',     icon: Cable,           label: 'Linhas',             section: 'CADASTROS', resource: 'telecom.linhas'       },
  { page: 'settings',   icon: Settings,        label: 'Configurações',      section: 'SISTEMA',   resource: 'telecom.settings'     },
  { page: 'logs',       icon: Terminal,        label: 'Logs',               section: 'SISTEMA',   resource: 'telecom.logs'         },
  { page: 'security',   icon: ShieldCheck,     label: 'Segurança',          section: 'SISTEMA',   resource: 'telecom.security'     },
  { page: 'accessGroups', icon: KeyRound,      label: 'Grupos de Acesso',   section: 'SISTEMA',   adminOnly: true                  },
  { page: 'audit',      icon: ClipboardList,   label: 'Auditoria',          section: 'SISTEMA',   resource: 'telecom.audit'        },
  { page: 'docs',       icon: BookOpen,        label: 'Documentação',       section: 'SISTEMA',   resource: 'telecom.docs'         },
  { page: 'release',    icon: Tag,             label: 'Release',            section: 'SISTEMA',   resource: 'telecom.release'      },
];

export default function Sidebar({ currentPage, onNavigate, username, role, perms, onLogout, collapsed, onToggleCollapse }: SidebarProps) {
  let lastSection = '';
  const visibleItems = NAV_ITEMS.filter(item =>
    item.adminOnly ? role === 'ADMIN' : canRead(role, perms, item.resource!)
  );

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

      {/* Logo — clique colapsa/expande o menu */}
      <div className="sidebar-logo" onClick={onToggleCollapse} style={{ cursor: 'pointer' }}>
        <div className="logo-mark">
          {/* Ícone equalizer — SVG inline para não depender de arquivo de imagem */}
          <svg className="logo-svg-icon" width="32" height="32" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
            <defs>
              <linearGradient id="lgIcon" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#818cf8"/>
                <stop offset="100%" stopColor="#6366f1"/>
              </linearGradient>
            </defs>
            {/* Barras do equalizer com alturas variadas */}
            <rect x="2"  y="14" width="4" height="12" rx="2" fill="url(#lgIcon)" opacity="0.7"/>
            <rect x="8"  y="8"  width="4" height="18" rx="2" fill="url(#lgIcon)"/>
            <rect x="14" y="4"  width="4" height="22" rx="2" fill="url(#lgIcon)"/>
            <rect x="20" y="10" width="4" height="16" rx="2" fill="url(#lgIcon)" opacity="0.9"/>
            <rect x="26" y="16" width="4" height="10" rx="2" fill="url(#lgIcon)" opacity="0.6"/>
          </svg>
          {!collapsed && <span className="logo-text">AsteriskIA</span>}
        </div>
        {!collapsed && <div className="logo-version">{CURRENT_VERSION} — Painel de Controle</div>}
      </div>

      {/* Nav */}
      <nav className="sidebar-nav">
        {visibleItems.map(item => {
          const showSection = item.section !== lastSection;
          lastSection = item.section;
          const Icon = item.icon;
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
                  title={isEffectivelyCollapsed ? item.label : undefined}
                  style={{ textDecoration: 'none', display: 'flex', alignItems: 'center', gap: '8px' }}
                >
                  <span className="nav-icon"><Icon size={17} strokeWidth={1.75} /></span>
                  <span>{item.label}</span>
                  {!isEffectivelyCollapsed && <ExternalLink size={12} style={{ marginLeft: 'auto', opacity: 0.5 }} />}
                </a>
              ) : (
                <div
                  className={`nav-item ${currentPage === item.page ? 'active' : ''}`}
                  onClick={() => onNavigate(item.page)}
                  role="button"
                  tabIndex={0}
                  title={isEffectivelyCollapsed ? item.label : undefined}
                  onKeyDown={e => e.key === 'Enter' && onNavigate(item.page)}
                >
                  <span className="nav-icon"><Icon size={17} strokeWidth={1.75} /></span>
                  <span>{item.label}</span>
                </div>
              )}
            </div>
          );
        })}
      </nav>

      {/* Footer: usuário + sair */}
      <div className="sidebar-footer">
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
          <LogOut size={15} />
          <span>Sair</span>
        </button>
      </div>
    </aside>
  );
}

export type { Page };
