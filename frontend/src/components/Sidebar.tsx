import type { ComponentType } from 'react';
import { useEffect, useState } from 'react';
import {
  LayoutDashboard, Headset, PhoneCall, AlertTriangle, Bot, Users, UsersRound,
  Settings, Terminal, ShieldCheck, KeyRound, ClipboardList,
  LogOut, BookOpen, Tag, Phone, Cable, Building2, Lightbulb,
  Wallet, Send, ChevronDown, ChevronRight,
} from 'lucide-react';
import { canRead } from '../api/client';
import { RELEASES } from '../data/releases';

const CURRENT_VERSION = RELEASES[RELEASES.length - 1].version;

type Page = 'dashboard' | 'modulo1' | 'insights' | 'modulo2' | 'modulo3' | 'masterdata' | 'users' | 'operadoras' | 'cadastro0800' | 'linhas' | 'settings' | 'audit' | 'logs' | 'security' | 'agents' | 'accessGroups' | 'docs' | 'release' | 'finUra' | 'finInsights' | 'finEnvios';

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

type IconType = ComponentType<{ size?: number; strokeWidth?: number }>;

interface NavLeaf {
  page: Page;
  icon: IconType;
  label: string;
  section: string;
  resource?: string;
  adminOnly?: boolean;
}

/** Item de menu com submenu (ex: Financeiro) — sem `page`/`resource` próprios; a
 * visibilidade do pai é derivada de ter ao menos um filho legível. */
interface NavParent {
  label: string;
  icon: IconType;
  section: string;
  children: NavLeaf[];
}

type NavEntry = NavLeaf | NavParent;

function isParent(entry: NavEntry): entry is NavParent {
  return 'children' in entry;
}

// resource espelha o catálogo de recursos do RBAC granular (ResourceCatalog.java
// / access_group_permissions) — manter em sincronia manual com o backend.
// Itens com adminOnly (em vez de resource) não têm resource_key próprio —
// o backend exige ROLE_ADMIN puro nesse endpoint (ver AccessGroupController).
const NAV_ITEMS: NavEntry[] = [
  { page: 'dashboard',  icon: LayoutDashboard, label: 'Dashboard',          section: 'GERAL',     resource: 'telecom.dashboard'    },
  { page: 'modulo1',    icon: Headset,         label: 'URA',                section: 'MÓDULOS',   resource: 'telecom.modulo1'      },
  { page: 'insights',   icon: Lightbulb,       label: 'Insights',           section: 'MÓDULOS',   resource: 'telecom.insights_link' },
  { page: 'modulo2',    icon: PhoneCall,       label: 'Conectividade',      section: 'MÓDULOS',   resource: 'telecom.modulo2'      },
  { page: 'modulo3',    icon: AlertTriangle,   label: 'Monitoramento',      section: 'MÓDULOS',   resource: 'telecom.modulo3'      },
  {
    label: 'Financeiro', icon: Wallet, section: 'MÓDULOS',
    children: [
      { page: 'finUra',      icon: Headset,   label: 'URA',                  section: 'MÓDULOS', resource: 'financeiro.ura'      },
      { page: 'finInsights', icon: Lightbulb, label: 'Insights',             section: 'MÓDULOS', resource: 'financeiro.insights' },
      { page: 'finEnvios',   icon: Send,      label: 'Análise Sob Demanda',  section: 'MÓDULOS', resource: 'financeiro.envios'   },
    ],
  },
  { page: 'agents',     icon: Bot,             label: 'Agentes',            section: 'MÓDULOS',   resource: 'telecom.agents_link' },
  { page: 'users',      icon: UsersRound,      label: 'Usuários',           section: 'CADASTROS', resource: 'telecom.users'        },
  { page: 'masterdata', icon: Users,           label: 'Clientes',           section: 'CADASTROS', resource: 'telecom.masterdata'   },
  { page: 'operadoras', icon: Building2,       label: 'Operadoras',         section: 'CADASTROS', resource: 'telecom.operadoras'   },
  { page: 'linhas',     icon: Cable,           label: 'Linhas',             section: 'CADASTROS', resource: 'telecom.linhas'       },
  { page: 'cadastro0800', icon: Phone,         label: '0800',               section: 'CADASTROS', resource: 'telecom.0800'         },
  { page: 'settings',   icon: Settings,        label: 'Configurações',      section: 'SISTEMA',   resource: 'telecom.settings'     },
  { page: 'logs',       icon: Terminal,        label: 'Logs',               section: 'SISTEMA',   resource: 'telecom.logs'         },
  { page: 'security',   icon: ShieldCheck,     label: 'Segurança',          section: 'SISTEMA',   resource: 'telecom.security'     },
  { page: 'accessGroups', icon: KeyRound,      label: 'Grupos de Acesso',   section: 'SISTEMA',   adminOnly: true                  },
  { page: 'audit',      icon: ClipboardList,   label: 'Auditoria',          section: 'SISTEMA',   resource: 'telecom.audit'        },
  { page: 'docs',       icon: BookOpen,        label: 'Documentação',       section: 'SISTEMA',   resource: 'telecom.docs'         },
  { page: 'release',    icon: Tag,             label: 'Release',           section: 'SISTEMA',   resource: 'telecom.release'      },
];

type VisibleEntry = NavLeaf | (Omit<NavParent, 'children'> & { children: NavLeaf[] });

export default function Sidebar({ currentPage, onNavigate, username, role, perms, onLogout, collapsed, onToggleCollapse }: SidebarProps) {
  let lastSection = '';
  const isLeafVisible = (leaf: NavLeaf) => leaf.adminOnly ? role === 'ADMIN' : canRead(role, perms, leaf.resource!);

  const visibleItems: VisibleEntry[] = NAV_ITEMS.map(item => {
    if (isParent(item)) {
      const children = item.children.filter(isLeafVisible);
      return children.length > 0 ? { ...item, children } : null;
    }
    return isLeafVisible(item) ? item : null;
  }).filter((entry): entry is VisibleEntry => entry !== null);

  // Submenus abertos manualmente pelo usuário. Auto-expande via useEffect (abaixo) quando a
  // navegação entra numa página filha — não via OR direto no isExpanded, para não brigar com
  // o toggle manual: OR'ar currentPage aqui faria o clique de colapsar não ter efeito nenhum
  // enquanto o usuário estivesse numa página filha (o chevron giraria, mas o submenu nunca
  // fecharia).
  const [expandedParents, setExpandedParents] = useState<Set<string>>(new Set());
  const toggleParent = (label: string) => {
    setExpandedParents(prev => {
      const next = new Set(prev);
      if (next.has(label)) next.delete(label); else next.add(label);
      return next;
    });
  };

  useEffect(() => {
    const parent = NAV_ITEMS.find(item => isParent(item) && item.children.some(c => c.page === currentPage));
    if (parent) setExpandedParents(prev => new Set(prev).add(parent.label));
  }, [currentPage]);

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
          <div className="logo-icon">A★</div>
          <span className="logo-text">AsteriskIA</span>
        </div>
        <div className="logo-version">{CURRENT_VERSION} — Painel de Controle</div>
      </div>

      {/* Nav */}
      <nav className="sidebar-nav">
        {visibleItems.map(item => {
          const showSection = item.section !== lastSection;
          lastSection = item.section;

          if (isParent(item)) {
            const isExpanded = expandedParents.has(item.label);
            const submenuId = `nav-submenu-${item.label}`;
            const ParentIcon = item.icon;
            return (
              <div key={item.label}>
                {showSection && (
                  <div className="nav-section-label">{item.section}</div>
                )}
                <div
                  className="nav-item"
                  onClick={() => toggleParent(item.label)}
                  role="button"
                  tabIndex={0}
                  aria-expanded={isExpanded}
                  aria-controls={submenuId}
                  title={isEffectivelyCollapsed ? item.label : undefined}
                  onKeyDown={e => e.key === 'Enter' && toggleParent(item.label)}
                >
                  <span className="nav-icon"><ParentIcon size={17} strokeWidth={1.75} /></span>
                  <span style={{ flex: 1 }}>{item.label}</span>
                  {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                </div>
                {isExpanded && (
                  <div className="nav-submenu" id={submenuId}>
                    {item.children.map(child => {
                      const ChildIcon = child.icon;
                      return (
                        <div
                          key={child.page}
                          className={`nav-item nav-subitem ${currentPage === child.page ? 'active' : ''}`}
                          onClick={() => onNavigate(child.page)}
                          role="button"
                          tabIndex={0}
                          title={isEffectivelyCollapsed ? child.label : undefined}
                          onKeyDown={e => e.key === 'Enter' && onNavigate(child.page)}
                        >
                          <span className="nav-icon"><ChildIcon size={15} strokeWidth={1.75} /></span>
                          <span>{child.label}</span>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          }

          const Icon = item.icon;
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
                title={isEffectivelyCollapsed ? item.label : undefined}
                onKeyDown={e => e.key === 'Enter' && onNavigate(item.page)}
              >
                <span className="nav-icon"><Icon size={17} strokeWidth={1.75} /></span>
                <span>{item.label}</span>
              </div>
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
