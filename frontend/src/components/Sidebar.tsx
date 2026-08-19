import type { ComponentType } from 'react';
import { useEffect, useRef, useState } from 'react';
import {
  LayoutDashboard, Headset, Lightbulb, Wallet, PhoneForwarded, UsersRound,
  Settings, KeyRound, ClipboardList, Tag, LogOut, ChevronDown, ChevronRight,
  PhoneCall, TrendingUp, FileText, Upload, Send, Users, ListOrdered, Tags,
  Disc, MonitorPlay, Workflow,
} from 'lucide-react';
import { canRead } from '../api/client';
import { RELEASES } from '../data/releases';

const CURRENT_VERSION = RELEASES[RELEASES.length - 1].version;

type Page = 'dashboard' | 'modulo1' | 'users' | 'settings' | 'audit' | 'accessGroups' | 'release'
  | 'finUra' | 'finInsights' | 'finEnvios'
  | 'insCalls' | 'insDashboard' | 'insProcessing' | 'insScorecards' | 'insReports' | 'insUploads'
  | 'ccAgentes' | 'ccFilas' | 'ccSkills' | 'ccGravacoes' | 'ccDesktop' | 'ccSupervisao' | 'ccFluxos';

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
  badgeCount?: number;
}

interface NavParent {
  label: string;
  icon: IconType;
  section: string;
  children: NavLeaf[];
  linkResource?: string;
}

type NavEntry = NavLeaf | NavParent;

function isParent(entry: NavEntry): entry is NavParent {
  return 'children' in entry;
}

const NAV_ITEMS: NavEntry[] = [
  { page: 'dashboard',  icon: LayoutDashboard, label: 'Dashboard',          section: 'GERAL',     resource: 'telecom.dashboard'    },
  { page: 'modulo1',    icon: Headset,         label: 'URA',                section: 'MÓDULOS',   resource: 'telecom.modulo1'      },
  {
    label: 'Insights', icon: Lightbulb, section: 'MÓDULOS',
    linkResource: 'telecom.insights_link',
    children: [
      { page: 'insCalls',      icon: PhoneCall,     label: 'Chamadas',                section: 'MÓDULOS', resource: 'insights.calls'      },
      { page: 'insDashboard',  icon: TrendingUp,    label: 'Dashboard de Tendências', section: 'MÓDULOS', resource: 'insights.dashboard'  },
      { page: 'insProcessing', icon: Settings,      label: 'Processamento',           section: 'MÓDULOS', resource: 'insights.processing' },
      { page: 'insScorecards', icon: ClipboardList, label: 'Fichas',                  section: 'MÓDULOS', resource: 'insights.scorecards' },
      { page: 'insReports',    icon: FileText,      label: 'Relatórios',              section: 'MÓDULOS', resource: 'insights.reports'    },
      { page: 'insUploads',    icon: Upload,        label: 'Meus Envios',             section: 'MÓDULOS', resource: 'insights.uploads'    },
    ],
  },
  {
    label: 'Financeiro', icon: Wallet, section: 'MÓDULOS',
    children: [
      { page: 'finUra',      icon: Headset,   label: 'URA',                  section: 'MÓDULOS', resource: 'financeiro.ura'      },
      { page: 'finInsights', icon: Lightbulb, label: 'Insights',             section: 'MÓDULOS', resource: 'financeiro.insights' },
      { page: 'finEnvios',   icon: Send,      label: 'Análise Sob Demanda',  section: 'MÓDULOS', resource: 'financeiro.envios'   },
    ],
  },
  {
    label: 'Call Center', icon: PhoneForwarded, section: 'MÓDULOS',
    linkResource: 'telecom.callcenter_link',
    children: [
      { page: 'ccAgentes', icon: Users,       label: 'Agentes', section: 'MÓDULOS', resource: 'callcenter.agentes' },
      { page: 'ccFilas',   icon: ListOrdered, label: 'Filas',   section: 'MÓDULOS', resource: 'callcenter.filas'   },
      { page: 'ccSkills',  icon: Tags,        label: 'Skills',  section: 'MÓDULOS', resource: 'callcenter.skills'  },
      { page: 'ccGravacoes', icon: Disc,      label: 'Gravações', section: 'MÓDULOS', resource: 'callcenter.gravacoes' },
      { page: 'ccDesktop',   icon: LayoutDashboard, label: 'Desktop do Agente', section: 'MÓDULOS', resource: 'callcenter.desktop' },
      { page: 'ccSupervisao', icon: MonitorPlay, label: 'Supervisão', section: 'MÓDULOS', resource: 'callcenter.supervisao' },
      { page: 'ccFluxos',  icon: Workflow,   label: 'Fluxos',  section: 'MÓDULOS', resource: 'callcenter.fluxos' },
    ],
  },
  { page: 'users',        icon: UsersRound,    label: 'Usuários',           section: 'CADASTROS', resource: 'telecom.users'        },
  { page: 'settings',     icon: Settings,      label: 'Configurações',      section: 'SISTEMA',   resource: 'telecom.settings'     },
  { page: 'accessGroups', icon: KeyRound,      label: 'Grupos de Acesso',   section: 'SISTEMA',   adminOnly: true                  },
  { page: 'audit',        icon: ClipboardList, label: 'Auditoria',          section: 'SISTEMA',   resource: 'telecom.audit'        },
  { page: 'release',      icon: Tag,           label: 'Release',            section: 'SISTEMA',   resource: 'telecom.release'      },
];

type VisibleEntry = NavLeaf | (Omit<NavParent, 'children'> & { children: NavLeaf[] });

export default function Sidebar({ currentPage, onNavigate, username, role, perms, onLogout, collapsed, onToggleCollapse }: SidebarProps) {
  let lastSection = '';
  const isLeafVisible = (leaf: NavLeaf) => leaf.adminOnly ? role === 'ADMIN' : canRead(role, perms, leaf.resource!);

  const visibleItems: VisibleEntry[] = NAV_ITEMS.map(item => {
    if (isParent(item)) {
      const hasLink = item.linkResource ? canRead(role, perms, item.linkResource) : true;
      const children = hasLink ? item.children.filter(isLeafVisible) : [];
      return children.length > 0 ? { ...item, children } : null;
    }
    return isLeafVisible(item) ? item : null;
  }).filter((entry): entry is VisibleEntry => entry !== null);

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

  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const active = navRef.current?.querySelector('.nav-item.active, .nav-subitem.active');
    active?.scrollIntoView({ block: 'nearest' });
  }, [currentPage, expandedParents]);

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
          <div className="logo-icon">V★</div>
          <span className="logo-text">VoipIA</span>
        </div>
        <div className="logo-version">{CURRENT_VERSION} — Painel de Controle</div>
      </div>

      <nav className="sidebar-nav" ref={navRef}>
        {visibleItems.map(item => {
          const showSection = item.section !== lastSection;
          lastSection = item.section;

          if (isParent(item)) {
            const isExpanded = expandedParents.has(item.label);
            const isAnyChildActive = item.children.some(c => c.page === currentPage);
            const Icon = item.icon;
            return (
              <div key={item.label}>
                {showSection && (
                  <div className="nav-section-label">{item.section}</div>
                )}
                <div
                  className={`nav-item nav-parent ${isAnyChildActive ? 'child-active' : ''}`}
                  onClick={() => toggleParent(item.label)}
                  role="button"
                  tabIndex={0}
                  title={isEffectivelyCollapsed ? item.label : undefined}
                  onKeyDown={e => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      toggleParent(item.label);
                    }
                  }}
                >
                  <span className="nav-icon"><Icon size={17} strokeWidth={1.75} /></span>
                  <span style={{ flex: 1 }}>{item.label}</span>
                  <span className="nav-chevron">
                    {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                  </span>
                </div>
                {isExpanded && !isEffectivelyCollapsed && (
                  <div className="nav-subitems">
                    {item.children.map(child => {
                      const ChildIcon = child.icon;
                      const isChildActive = currentPage === child.page;
                      const badgeCount = child.badgeCount ?? 0;
                      return (
                        <div
                          key={child.page}
                          className={`nav-subitem ${isChildActive ? 'active' : ''}`}
                          onClick={() => onNavigate(child.page)}
                          role="button"
                          tabIndex={0}
                          onKeyDown={e => e.key === 'Enter' && onNavigate(child.page)}
                        >
                          <span className="nav-icon"><ChildIcon size={15} strokeWidth={1.75} /></span>
                          <span style={{ flex: 1 }}>{child.label}</span>
                          {badgeCount > 0 && (
                            <span className="nav-subitem-badge" title={`${badgeCount} pendentes`}>
                              {badgeCount}
                            </span>
                          )}
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
