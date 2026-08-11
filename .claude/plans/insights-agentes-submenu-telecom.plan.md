# Plano — Submenus indentados para INSIGHTS e AGENTES na Sidebar do Telecom

**Data:** 2026-07-31
**Status:** aguardando aprovação
**Escopo:** somente frontend (3 SPAs). Sem backend, sem migration Flyway, sem novo `resource_key`.

---

## 1. Requisito

Hoje, na Sidebar do Telecom, **Insights** e **Agentes** são itens *folha*: clicar abre
`InsightsPage.tsx` / `AgentesPage.tsx`, que são iframes de tela cheia (`/insights/`, `/agents/`)
com a **sidebar própria da SPA** embutida. Resultado visual: duas navegações laterais em sequência.

O usuário quer que, **quando o acesso vem do login pela página principal** (shell do Telecom),
esses dois módulos virem itens **pai com submenu indentado**, exatamente no padrão do menu
**Financeiro** (`NavParent` + `children[]`, chevron, `.nav-submenu`/`.nav-subitem`).

**Restrição explícita:** quem faz login **direto** em `https://app.voiphash.com.br/insights` ou
`/agents` continua vendo o comportamento atual, sem nenhuma alteração — sidebar própria da SPA,
mesmas abas, mesmo layout.

---

## 2. Decisões tomadas pelo usuário (2026-07-31)

| # | Decisão | Escolha |
|---|---------|---------|
| 1 | Como a SPA detecta que está embutida | **Automático** via `window.self !== window.top` — sem query param, sem mudança no `src` do iframe |
| 2 | Navegação interna da SPA (ex: drill-down Dashboard → Chamadas) reflete no submenu | **Sim**, canal `postMessage` bidirecional; o Telecom atualiza item ativo + hash |
| 3 | Badge de contagem de alertas do Agentes no submenu do Telecom | **Sim**, replicado via o mesmo canal |
| 4 | Usuário com `telecom.*_link` mas nenhuma permissão de aba | **Esconde o menu inteiro** (mesma regra do Financeiro: pai visível só com ≥1 filho legível) |

---

## 3. Estado atual (levantado no código)

| Arquivo | Fato relevante |
|---------|----------------|
| `frontend/src/components/Sidebar.tsx:38-51,63-70,146-192` | Padrão `NavParent`/`NavLeaf`/`isParent()` já existe e é genérico; Financeiro é o único usuário hoje. Auto-expansão do pai da página atual já implementada (`useEffect`, linhas 114-117) |
| `frontend/src/App.css:279-295` | `.nav-submenu` / `.nav-subitem` / `.sidebar.collapsed .nav-submenu` já estilizados — **nenhum CSS novo na Sidebar** |
| `frontend/src/App.tsx:97-118,125-140,192` | `PAGE_RESOURCE`, lista `valid` de hashes e roteamento por `window.location.hash` |
| `frontend/src/components/InsightsPage.tsx` / `AgentesPage.tsx` | 19 linhas cada; iframe fixo, sem props, sem ref |
| `insights-platform/frontend/src/App.tsx:66,106-128` | `tab` em `useState`, `TAB_RESOURCE`, sidebar sempre renderizada. 6 abas |
| `agents-platform/frontend/src/App.tsx:56,108-132` | `page` em `useState`, `PAGE_RESOURCE`, `alertCount` de `useAgentsAlerts`. 8 abas |
| `*/App.css:73-75,170-172` | `.main-content { margin-left: var(--sidebar-width) }` — precisa ser zerado no modo embutido |
| `package.json` das 3 SPAs | **Sem vitest/jest** — não há harness de teste de frontend no projeto |

---

## 4. Desenho da solução

### 4.1 Novas páginas no Telecom (14 ids)

```
insCalls | insDashboard | insProcessing | insScorecards | insReports | insUploads
agDashboard | agAgents | agServers | agKnowledge | agLogs | agAlerts | agSecrets | agLlm
```

Submenu resultante (rótulos e ícones **copiados das sidebars das SPAs**, para não divergir):

```
MÓDULOS
  URA
  ▾ Insights                    (Lightbulb)
      Chamadas                  insights.calls
      Dashboard de Tendências   insights.dashboard
      Processamento             insights.processing
      Fichas                    insights.scorecards
      Relatórios                insights.reports
      Meus Envios               insights.uploads
  Conectividade
  Monitoramento
  ▾ Financeiro                  (inalterado)
  ▾ Agentes                     (Bot)
      Dashboard                 agents.dashboard
      Agentes                   agents.agents
      Servidores                agents.servers
      Base de Conhecimento      agents.knowledge
      Logs                      agents.logs
      Alertas  ⑶               agents.reports   ← badge de contagem
      Secrets                   agents.secrets
      Config. IA                agents.llm
```

**RBAC:** cada filho exige `canRead(<resource da aba>)` **e** o pai exige o link
(`telecom.insights_link` / `telecom.agents_link`). Ou seja, a visibilidade do filho é
`canRead(link) && canRead(tab)`. Nenhum `resource_key` novo — todos já estão no
`ResourceCatalog.java` e já vêm na claim `perm`. **Sem migration.**

> Efeito colateral aceito (decisão 4): quem tem o link mas nenhuma aba deixa de ver o item.
> Hoje esse usuário vê o item, abre o iframe e recebe "Você não tem permissão de leitura em
> nenhuma aba" — um beco sem saída.

### 4.2 Ponte shell ↔ iframe (`postMessage`)

O iframe **não pode ser remontado** a cada troca de aba (recarregaria a SPA inteira, ~1s de
tela branca). Duas consequências de projeto:

1. No `App.tsx` do Telecom, todas as 6 páginas `ins*` renderizam **o mesmo elemento**
   `<InsightsPage tab={…} />` (idem `ag*` → `<AgentesPage tab={…} />`) — mesma posição na
   árvore React ⇒ o `<iframe>` permanece montado e só recebe prop nova.
2. A troca de aba viaja por `postMessage`, não pelo `src`.

**Contrato de mensagens** (mesma origem; `targetOrigin`/validação de `event.origin` sempre
`window.location.origin`):

```
shell → iframe : { source: 'asteriskia-shell', type: 'navigate', tab: string }
iframe → shell : { source: 'asteriskia-insights' | 'asteriskia-agents', type: 'ready' }
                 { source: …,                    type: 'tabChanged',  tab: string }
                 { source: 'asteriskia-agents',  type: 'alertCount',  count: number }
```

- **Corrida de boot:** o shell não pode postar antes de a SPA montar o listener. A SPA (só em
  modo embutido) posta `ready` no mount; o shell responde com o `navigate` da aba corrente e
  marca o iframe como pronto. Mudanças de prop antes do `ready` ficam pendentes num ref.
- **Loop de eco:** o shell só posta `navigate` quando a aba desejada difere da última aba
  confirmada pela SPA; a SPA só posta `tabChanged` quando a aba difere da última recebida
  por `navigate`. Guardas em `useRef` nos dois lados.
- **Mensagem desconhecida / origem diferente:** ignorada silenciosamente (nenhum `throw`,
  nenhum log de payload — evita ruído e não vaza conteúdo de terceiros no console).

### 4.3 Modo embutido dentro da SPA

`const isEmbedded = window.self !== window.top` (avaliado uma vez, fora do render).

- `isEmbedded === true` → **não renderiza `<Sidebar />`**; `<main>` recebe a classe nova
  `embedded` (CSS: `margin-left: 0`). O restante da SPA (abas, RBAC, drill-down, WebSocket de
  alertas) fica **idêntico**.
- `isEmbedded === false` → caminho de hoje, byte por byte. É o caso do login direto em
  `/insights` / `/agents`, que a restrição do usuário protege.
- A tela de **Login** da SPA não muda: se o token não estiver no `localStorage` (não deveria
  acontecer no fluxo embutido, que compartilha origem/`localStorage`), o login aparece dentro
  do iframe como já acontece hoje.

---

## 5. Arquivos afetados

### Novos (2)

| Arquivo | Conteúdo |
|---------|----------|
| `insights-platform/frontend/src/hooks/useShellBridge.ts` | Hook: detecta embutido, posta `ready`/`tabChanged`, escuta `navigate`, guardas de eco. Recebe `(currentTab, onNavigate)` e devolve `{ isEmbedded, notifyAlertCount? }` |
| `agents-platform/frontend/src/hooks/useShellBridge.ts` | Mesmo hook, adaptado ao tipo `Page` e com `notifyAlertCount` |

> Duplicação intencional entre as duas SPAs — mesmo precedente já aceito no projeto para
> `api/client.ts` e `AuthedAudio.tsx` (builds Vite independentes, sem pacote compartilhado).
> Criar um pacote npm interno só para ~60 linhas seria pior (YAGNI).

### Modificados (7)

| Arquivo | Mudança |
|---------|---------|
| `frontend/src/components/Sidebar.tsx` | +14 valores no type `Page`; `Insights` e `Agentes` viram `NavParent` com `children[]`; `NavLeaf` ganha `badgeCount?: number` opcional; nova prop `agentsAlertCount: number`; render do badge no `.nav-subitem` (mesmo estilo inline já usado na sidebar da SPA de Agentes) |
| `frontend/src/App.tsx` | `PAGE_RESOURCE` +14 entradas; lista `valid` +14 hashes; back-compat: hash legado `insights`/`agents` resolve para o **primeiro filho legível**; render consolidado (`page.startsWith('ins')` → 1 `<InsightsPage>`; `ag*` → 1 `<AgentesPage>`); estado `agentsAlertCount`; handler `onTabChange` que faz `setPage` + `window.location.hash` |
| `frontend/src/components/InsightsPage.tsx` | Props `{ tab, onTabChange }`; `useRef` no iframe; `postMessage` no mount-ready e a cada mudança de `tab`; listener de `tabChanged` |
| `frontend/src/components/AgentesPage.tsx` | Idem + prop `onAlertCount` |
| `insights-platform/frontend/src/App.tsx` | `useShellBridge`; `{!isEmbedded && <Sidebar …/>}`; classe `embedded` no `<main>` |
| `agents-platform/frontend/src/App.tsx` | Idem + `notifyAlertCount(alertCount)` |
| `insights-platform/frontend/src/App.css` + `agents-platform/frontend/src/App.css` | `.main-content.embedded { margin-left: 0 }` (uma regra em cada) |
| `frontend/src/data/releases.ts` | Entrada `v1.46` (obrigatório em toda entrega) |

**Não muda:** `Caddyfile`, `frontend/nginx.conf`, `frontend/Dockerfile`, backend Java,
FastAPI, `ResourceCatalog.java`, `AccessGroups.tsx`, `SecurityConfig.java`, nenhum SQL.

---

## 6. Fases de execução

| Fase | Entrega | Verificação |
|------|---------|-------------|
| **1** | Ponte nas SPAs: `useShellBridge.ts` ×2, modo embutido no `App.tsx` ×2, CSS `.embedded` ×2 | `tsc --noEmit` + `npm run build` nas duas SPAs; `/insights` e `/agents` diretos continuam idênticos |
| **2** | Wrappers do Telecom: `InsightsPage.tsx` / `AgentesPage.tsx` com props + `postMessage` | `tsc --noEmit` no Telecom |
| **3** | Sidebar + roteamento do Telecom: `Page` +14, os dois `NavParent`, badge, `PAGE_RESOURCE`, hashes, back-compat | `tsc --noEmit` + `npm run build` |
| **4** | Release notes `v1.46` | Arquivo atualizado |
| **5** | Revisão: `ecc:react-reviewer` + `ecc:security-reviewer` em paralelo (foco: origem do `postMessage`, vazamento de dado entre frames, `useEffect`/cleanup, eco infinito) | CRITICAL/HIGH corrigidos antes do commit |
| **6** | Deploy: `docker compose up -d --build frontend` (o Dockerfile já builda as 3 SPAs) + `docker compose ps` | `curl -I https://app.voiphash.com.br/insights/` e `/agents/` → 200 |

---

## 7. Riscos e mitigações

| Risco | Mitigação |
|-------|-----------|
| Iframe recarrega a cada clique no submenu (tela branca) | Elemento único por SPA no `App.tsx` (§4.2, item 1) — validar no browser que o `<iframe>` não remonta |
| Corrida: shell posta antes da SPA montar o listener | Handshake `ready` + fila de 1 posição em `useRef` |
| Eco infinito `navigate` ⇄ `tabChanged` | Guardas de última-aba-conhecida nos dois lados |
| `postMessage` para origem errada / de origem errada | `targetOrigin = window.location.origin` no envio, `event.origin !== window.location.origin` descarta na recepção. Sem dado sensível no payload (só nome de aba e um inteiro) |
| Sidebar colapsada + submenu | Já coberto por `.sidebar.collapsed .nav-submenu { display: none }` |
| Favoritos antigos `#insights` / `#agents` | Resolvem para o primeiro filho legível (back-compat explícita) |
| Sidebar do Telecom fica longa (14 filhos a mais) | Submenus nascem **fechados**; só abrem por clique ou auto-expansão da página atual (comportamento do Financeiro) |

---

## 8. Lacunas declaradas

- **Sem teste automatizado.** Nenhuma das 3 SPAs tem vitest/jest configurado; a regra de
  cobertura ≥80% do projeto não é aplicável aqui sem introduzir um harness novo, o que está
  fora do escopo pedido. Validação = `tsc --noEmit` + `npm run build` + roteiro manual no
  navegador (§9). Se você quiser o harness de teste, é uma entrega separada.
- **Validação visual depende de navegador.** O roteiro manual precisa ser executado por você
  ou com o Chrome DevTools MCP disponível na VPS (indisponível em sessões anteriores).

---

## 9. Roteiro de validação manual (pós-deploy)

1. Login em `https://app.voiphash.com.br/` como ADMIN → Sidebar mostra `▸ Insights` e
   `▸ Agentes` com chevron, no mesmo recuo do Financeiro.
2. Expandir Insights → clicar nas 6 abas: conteúdo troca **sem** piscar/recarregar; a sidebar
   interna do Insights **não** aparece; sem faixa branca à esquerda.
3. Dashboard de Tendências → clicar num drill-down → destaque do submenu salta para
   "Chamadas" e o hash da URL vira `#insCalls`.
4. Agentes → aba "Alertas" mostra o badge com a mesma contagem de antes.
5. Colapsar a sidebar (clique no logo) → submenus somem; hover reexpande.
6. Recarregar em `#insReports` → cai direto em Relatórios.
7. Abrir `#insights` (hash legado) → cai na primeira aba legível.
8. **Regressão do requisito:** abrir `https://app.voiphash.com.br/insights` em aba nova e fazer
   login ali → sidebar própria do Insights presente, layout idêntico ao de hoje. Repetir em
   `/agents`.
9. Login com usuário não-ADMIN de grupo restrito → só as abas permitidas aparecem no submenu;
   usuário sem nenhuma aba não vê o item pai.
