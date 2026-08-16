# Plan: Insights ganha menu lateral (sidebar), tabs somem do topo

**Status:** aprovado, pronto para implementação — nenhuma fase iniciada ainda.
**Origem:** pedido livre — "a tela de Insights deve ter o mesmo submenu lateral que a tela de
Agentes, com as opções que hoje aparecem como abas no cabeçalho; depois de criar o menu lateral,
os botões devem sumir do cabeçalho, seguindo o mesmo padrão dos Agentes."
**Complexidade:** Small/Medium (1 componente novo + 1 arquivo reescrito + 1 dependência nova)

## Como a tela de Insights está hoje
`insights-platform/frontend/src/App.tsx:112-155` — sem sidebar. Layout: um `<header
className="topbar">` fixo (título "💡 Insights", subtítulo, username, botão "Sair") e, logo abaixo,
dentro de `<main className="page-body">`, uma fileira horizontal de 5 `<button>` (`.btn
.btn-primary`/`.btn-ghost`) que trocam a aba ativa (`calls`, `dashboard`, `processing`, `costs`,
`costsDashboard`). RBAC granular já filtra essas 5 abas via `session.hasRead(TAB_RESOURCE[t.id])`
(`App.tsx:14-20,109`) — não muda nada no backend.

## Decisão de design: espelhar o `Sidebar.tsx` do Telecom, não o UMD dos Agentes
O pedido compara com "a tela de Agentes", mas a Plataforma de Agentes é React 18 **UMD sem build
step** (`agents-platform/frontend/index.html`) — tecnologia diferente da SPA de Insights (Vite +
TSX, copiada do Telecom: `Login.tsx`, `client.ts`, `useAuthSession.ts` já seguem esse padrão).
Achado decisivo: `insights-platform/frontend/src/App.css` **já contém, sem uso**, uma cópia
completa do design system de sidebar do Telecom (`.sidebar`, `.sidebar-logo`, `.sidebar-nav`,
`.nav-item`/`.nav-item.active`, `.nav-section-label`, `.sidebar-footer`, `.user-info`,
`.user-avatar`, `.btn-logout`, inclusive `.sidebar.collapsed`) — herdado do Telecom quando o CSS
base foi copiado para a SPA (ver `.claude/plans/insights-spa-independente.plan.md`). Resultado
visual pedido (lista de opções na lateral, sem os botões no topo) é o mesmo; a via mais barata e
consistente com "padrão de código já existente" é replicar `frontend/src/components/Sidebar.tsx`
(mesmo stack, CSS já pronto), não reescrever o dicionário de ícones SVG manuais dos Agentes.

**Diferença assumida deliberadamente:** o Sidebar do Telecom suporta colapsar (clique no logo +
hover-expand), o dos Agentes não. Como o CSS de colapso já existe pronto no `App.css` da SPA
(custo zero) e é comportamento já validado em produção no Telecom, o plano **inclui** o colapso —
ajustável depois se o usuário preferir a versão estática dos Agentes.

## Patterns to Mirror
| Categoria | Origem | Padrão |
|---|---|---|
| Componente Sidebar completo (logo/nav/footer, colapso, hover-expand) | `frontend/src/components/Sidebar.tsx` (126 linhas) | Estrutura inteira a replicar, adaptando `Page`→`Tab` e `role/perms`→`session` |
| RBAC no item de nav | `Sidebar.tsx:53-55` (`canRead(role, perms, item.resource!)`) | Adaptar para `session.hasRead(TAB_RESOURCE[item.tab])`, já disponível via `useAuthSession` |
| Duplicação App↔Sidebar do resource map | `frontend/src/App.tsx:96-104` (`PAGE_RESOURCE`) + `Sidebar.tsx:30-49` (`resource` por item) | Manter `TAB_RESOURCE` em `App.tsx` (já existe) e resources equivalentes dentro do novo `Sidebar.tsx` — mesma duplicação intencional já aceita no Telecom |
| Layout App+Sidebar+main-content | `frontend/src/App.tsx:182-197` | `<div className="app-layout"><Sidebar .../><main className="main-content...">...</main></div>` |
| Export do tipo de navegação a partir do Sidebar | `Sidebar.tsx:125` (`export type { Page }`) | Novo `Sidebar.tsx` exporta `type Tab`; `App.tsx` importa de lá em vez de declarar localmente |
| Ícones | `Sidebar.tsx:3-7` (`lucide-react`) | Adicionar `lucide-react` ao `insights-platform/frontend/package.json` (mesma versão do Telecom, `^1.23.0`) |

## Arquivos a criar/alterar
| Arquivo | Ação | Motivo |
|---|---|---|
| `insights-platform/frontend/package.json` | UPDATE | Adiciona dependência `lucide-react` (ícones dos itens de nav) |
| `insights-platform/frontend/src/components/Sidebar.tsx` | CREATE | Componente sidebar completo, espelhando `frontend/src/components/Sidebar.tsx`, com os 5 itens do Insights e RBAC via `session.hasRead` |
| `insights-platform/frontend/src/App.tsx` | UPDATE | Remove `<header className="topbar">` e a fileira de botões; renderiza `<Sidebar>` + `<main className="main-content...">`; importa `type Tab` do novo `Sidebar.tsx` em vez de declará-lo localmente; adiciona estado `sidebarCollapsed` |
| `frontend/src/data/releases.ts` | UPDATE | Nova versão (obrigatório) |

## Tarefas (fases)

### Fase 1 — Componente `Sidebar.tsx` da SPA de Insights
- Criar `insights-platform/frontend/src/components/Sidebar.tsx`, adaptado de
  `frontend/src/components/Sidebar.tsx`:
  - Props: `currentTab: Tab`, `onNavigate: (tab: Tab) => void`, `username: string`, `session:
    AuthSession` (importado de `../hooks/useAuthSession`), `onLogout: () => void`, `collapsed:
    boolean`, `onToggleCollapse: () => void`.
  - `NAV_ITEMS`: 5 itens (sem `section`, já que é um grupo único — sem `nav-section-label`):
    `calls` (ícone `PhoneCall`, "Chamadas"), `dashboard` (`TrendingUp`, "Dashboard de Tendências"),
    `processing` (`Settings`, "Processamento"), `costs` (`DollarSign`, "Custos IA"),
    `costsDashboard` (`BarChart3`, "Dashboard de Custos") — mesmos `resource` de `TAB_RESOURCE` em
    `App.tsx:14-20` (note que `costs` e `costsDashboard` compartilham `insights.costs`, já assim
    hoje).
  - Filtro RBAC: `NAV_ITEMS.filter(item => session.hasRead(item.resource))`.
  - Logo: `VoipIA` + subtítulo `Insights` (sem número de versão — a SPA de Insights não
    importa `releases.ts` do Telecom, não inventar essa dependência cruzada).
  - Footer: avatar (inicial do username), nome, `session.role === 'ADMIN' ? 'Administrador' :
    'Usuário'`, botão "Sair" chamando `onLogout`.
  - Colapso: mesmo `useState(hoverExpanded)` + `isEffectivelyCollapsed` do Telecom.
  - Exportar `export type { Tab }`.
- **Validar:** `npx tsc --noEmit` (ainda vai falhar até a Fase 2 remover o `type Tab` duplicado do
  `App.tsx` — ok, phase intermediária).

### Fase 2 — `App.tsx`: sidebar substitui header+tabs
- `import Sidebar, { type Tab } from './components/Sidebar';` — remover a declaração local `type
  Tab = keyof typeof TAB_RESOURCE;` (mantém `TAB_RESOURCE` para o cálculo de `visibleTabs`/
  `currentTab`, igual ao Telecom mantém `PAGE_RESOURCE` em paralelo ao `Sidebar`).
- Adicionar `const [sidebarCollapsed, setSidebarCollapsed] = useState(false);`.
- Trocar o bloco `<div className="app-layout" style={{ flexDirection: 'column' }}>...</div>`
  (linhas 114-154) por:
  ```tsx
  <div className="app-layout">
    <Sidebar
      currentTab={currentTab ?? 'calls'}
      onNavigate={setTab}
      username={username}
      session={session}
      onLogout={handleSignOut}
      collapsed={sidebarCollapsed}
      onToggleCollapse={() => setSidebarCollapsed(c => !c)}
    />
    <main className={`main-content${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>
      <div className="page-body">
        {currentTab === 'calls' && <InsightsTab .../>}
        {currentTab === 'dashboard' && <InsightsDashboardTab .../>}
        {currentTab === 'processing' && <InsightsProcessingTab />}
        {currentTab === 'costs' && <InsightsCostsTab />}
        {currentTab === 'costsDashboard' && <InsightsCostsDashboardTab />}
        {!currentTab && <p style={{ color: 'var(--text-muted)' }}>Você não tem permissão de leitura em nenhuma aba do Insights.</p>}
      </div>
    </main>
  </div>
  ```
  (`.app-layout` volta a usar `display:flex` em linha, sem o override `flexDirection: 'column'`
  que só existia por não haver sidebar; `.main-content`/`.page-body` já vêm prontos do `App.css`.)
- **Validar:** `cd insights-platform/frontend && npx tsc --noEmit && npm run build`.

### Fase 3 — Dependência e instalação
- Adicionar `"lucide-react": "^1.23.0"` em `insights-platform/frontend/package.json`
  (`dependencies`, mesma versão do Telecom).
- Rodar `npm install` dentro de `insights-platform/frontend/` para atualizar o `package-lock.json`.
- **Validar:** `npm run build` sem erro de módulo não encontrado.

### Fase 4 — Release notes + validação final
- Nova entrada em `frontend/src/data/releases.ts` (próxima versão após v1.33).
- **Validar (local, sem deploy ainda):**
  ```bash
  cd insights-platform/frontend && npm install && npx tsc --noEmit && npm run build
  cd /opt/VoipIA/frontend && npx tsc --noEmit
  ```
- Deploy (só depois de validado localmente, com confirmação antes de mexer em produção):
  ```bash
  docker compose build frontend
  docker compose up -d --build frontend
  docker compose ps
  curl -I https://app.voiphash.com.br/insights/
  ```

## Riscos
| Risco | Prob. | Mitigação |
|---|---|---|
| Componentes internos das 5 abas (ex: `InsightsTab`) tinham CSS ajustado pressupondo o `header.topbar` acima — algum espaçamento pode ficar diferente sem ele | Média | `page-body` já tem padding próprio (`App.css`); revisar visualmente após deploy (sem acesso a browser nesta sessão — pedir para o usuário conferir) |
| `lucide-react` não instalado ainda na SPA de Insights — build quebra até `npm install` rodar | Alta (garantido) | Fase 3 é obrigatória antes do build final; `tsc --noEmit` vai falhar antes disso, é esperado |
| Duplicação do mapa de resources entre `Sidebar.tsx` e `TAB_RESOURCE` do `App.tsx` pode divergir no futuro | Baixa | Mesmo risco aceito hoje no Telecom entre `Sidebar.tsx` e `PAGE_RESOURCE`; não é regressão nova |
| RBAC: nenhuma mudança de backend — `insights.*` já existe (V37) | — | Não há risco de migration aqui, é troca de UI pura |

## Aceite
- [ ] Tela de Insights mostra sidebar lateral fixa (estilo Telecom/Agentes) com os 5 itens
      (Chamadas, Dashboard de Tendências, Processamento, Custos IA, Dashboard de Custos)
- [ ] Item ativo destacado na sidebar; clicar troca o conteúdo principal
- [ ] RBAC granular `insights.*` continua funcionando — itens sem permissão não aparecem
- [ ] Cabeçalho antigo (header + fileira de botões) removido — não sobra nenhum botão de
      navegação fora da sidebar
- [ ] Logout e nome do usuário aparecem no rodapé da sidebar, não mais no topo
- [ ] `tsc --noEmit` e `npm run build` limpos nas duas SPAs; release notes atualizado

## Retomada em outra sessão
Para continuar este trabalho a partir de qualquer sessão nova, peça para ler este arquivo
(`.claude/plans/insights-sidebar-lateral.plan.md`) e seguir a partir da última fase marcada como
concluída em "Tarefas (fases)" — nenhuma fase foi iniciada ainda.
