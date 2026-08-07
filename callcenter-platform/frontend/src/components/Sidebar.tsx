import type { ComponentType } from 'react';
import { useState } from 'react';
import {
  Users, ListOrdered, Tags, Disc, LogOut, LayoutDashboard, MonitorPlay, Workflow,
  PhoneCall, BarChart3, ListChecks, ClipboardList, FileText,
} from 'lucide-react';
import type { AuthSession } from '../hooks/useAuthSession';

type Tab = 'agentes' | 'filas' | 'skills' | 'gravacoes' | 'desktop' | 'supervisao' | 'fluxos'
  | 'insightsChamadas' | 'insightsDashboard' | 'insightsProcessamento' | 'insightsScorecards' | 'insightsReports';

interface SidebarProps {
  currentTab: Tab;
  onNavigate: (tab: Tab) => void;
  username: string;
  session: AuthSession;
  onLogout: () => void;
  collapsed: boolean;
  onToggleCollapse: () => void;
}

// resource espelha o namespace RBAC granular callcenter.* (ResourceCatalog.java /
// access_group_permissions) — manter em sincronia manual com TAB_RESOURCE em App.tsx,
// mesma duplicação intencional já aceita entre Sidebar.tsx e PAGE_RESOURCE no Telecom.
const NAV_ITEMS: { tab: Tab; icon: ComponentType<{ size?: number; strokeWidth?: number }>; label: string; resource: string }[] = [
  { tab: 'agentes', icon: Users,       label: 'Agentes', resource: 'callcenter.agentes' },
  { tab: 'filas',   icon: ListOrdered, label: 'Filas',   resource: 'callcenter.filas'   },
  { tab: 'skills',  icon: Tags,        label: 'Skills',  resource: 'callcenter.skills'  },
  { tab: 'gravacoes', icon: Disc,      label: 'Gravações', resource: 'callcenter.gravacoes' },
  { tab: 'desktop',   icon: LayoutDashboard, label: 'Desktop do Agente', resource: 'callcenter.desktop' },
  { tab: 'supervisao', icon: MonitorPlay, label: 'Supervisão', resource: 'callcenter.supervisao' },
  { tab: 'fluxos', icon: Workflow, label: 'Fluxos', resource: 'callcenter.fluxos' },
  { tab: 'insightsChamadas', icon: PhoneCall, label: 'Insights — Chamadas', resource: 'callcenter.insights.calls' },
  { tab: 'insightsDashboard', icon: BarChart3, label: 'Insights — Dashboard', resource: 'callcenter.insights.dashboard' },
  { tab: 'insightsProcessamento', icon: ListChecks, label: 'Insights — Processamento', resource: 'callcenter.insights.processing' },
  { tab: 'insightsScorecards', icon: ClipboardList, label: 'Insights — Fichas de Qualidade', resource: 'callcenter.insights.scorecards' },
  { tab: 'insightsReports', icon: FileText, label: 'Insights — Relatórios', resource: 'callcenter.insights.reports' },
];

export default function Sidebar({ currentTab, onNavigate, username, session, onLogout, collapsed, onToggleCollapse }: SidebarProps) {
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

      {/* Logo — clique colapsa/expande o menu */}
      <div className="sidebar-logo" onClick={onToggleCollapse} style={{ cursor: 'pointer' }}>
        <div className="logo-mark">
          <div className="logo-icon">A★</div>
          <span className="logo-text">AsteriskIA</span>
        </div>
        <div className="logo-version">Call Center</div>
      </div>

      {/* Nav */}
      <nav className="sidebar-nav">
        {visibleItems.map(item => {
          const Icon = item.icon;
          return (
            <div
              key={item.tab}
              className={`nav-item ${currentTab === item.tab ? 'active' : ''}`}
              onClick={() => onNavigate(item.tab)}
              role="button"
              tabIndex={0}
              title={isEffectivelyCollapsed ? item.label : undefined}
              onKeyDown={e => e.key === 'Enter' && onNavigate(item.tab)}
            >
              <span className="nav-icon"><Icon size={17} strokeWidth={1.75} /></span>
              <span>{item.label}</span>
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

export type { Tab };
