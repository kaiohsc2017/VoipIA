# Plan: Migrar Agentes de React UMD (single-file) para Vite + TS (padrão Insights)

**Status:** aprovado, pronto para implementação — nenhuma fase iniciada ainda.
**Origem:** pedido livre — "projete a reestruturação da página de AGENTES para seguir o padrão de
desenvolvimento mais atual que foi utilizado no menu INSIGHTS."
**Complexidade:** Large (8 páginas + auth + WebSocket + CRUDs completos — maior migração de
frontend já feita neste projeto; recomenda-se implementar em múltiplas sessões/lotes, não de uma
vez só)

## Estado atual (achados da pesquisa)

### Frontend (`agents-platform/frontend/`)
- **1688 linhas**, um único `index.html` — React 18 UMD (`React.createElement`, sem JSX, sem
  build), CSS inline em `<style>` (linhas 10-109), tudo dentro de uma tag `<script>`.
- `agents-platform/frontend/js/` — só `react.production.min.js` + `react-dom.production.min.js`
  vendorizados manualmente. **Não há lib de gráfico** (as barras de disponibilidade do Dashboard
  são `<div>` com `width: pct+'%'`, CSS puro).
- **8 páginas** (`pageMap`, `index.html:1347-1356`; nav em `1294-1303`): `dashboard` (126 linhas,
  271-396), `agents` (122 linhas, 858-979, + `AgentForm` 243 linhas/613-855 + `LogModal` 85
  linhas/526-610 — página mais complexa do app), `servers` (119 linhas, 982-1100), `knowledge` (66
  linhas, 1103-1168, upload de PDF via `FormData`), `logs` (77 linhas, 1172-1248, console
  auto-scroll), `reports`/Alertas (39 linhas, 1251-1289, a mais simples), `secrets` (124 linhas,
  400-523), `llm` (209 linhas, 1386-1594, 2ª mais complexa).
- Compartilhado: `api` wrapper fetch genérico (194-203), `_apiFetch` com tratamento de 401
  (172-193), `Ico`/`ICONS` (SVG paths manuais, 209-243), `SBadge` (245-253), `Confirm` modal
  genérico (255-268). **Não há** spinner nem tabela genérica reutilizável — cada página reimplementa.
- **Auth**: `Login` própria no arquivo (1601-1673, `POST /api/v1/auth/login` no backend Telecom,
  não no de Agentes) — **hoje recusa 2FA** ("2FA não suportado neste painel", linha 1621), mesmo
  se o usuário tiver ativado (lacuna a corrigir nesta migração, confirmado com o usuário).
  `_decodeTokenPayload`/`getRole`/`getPermissions`/`canRead`/`canWrite` (122-162) — mesma lógica já
  usada em Telecom/Insights, resources com prefixo `agents.*` (dashboard/agents/servers/knowledge/
  logs/reports/secrets/llm). **Hoje só `Agents` e `Servers` recebem `isAdmin`/gating de escrita**
  — Knowledge/Secrets/LLM sempre mostram os botões de escrita independente de permissão (o backend
  bloqueia certo via `require_permission`, mas a UI não esconde — lacuna a corrigir, confirmado).
- **WebSocket**: aberto em `App` (1319-1340) — antes de conectar, busca um "streaming token" de
  60s via `POST {TELECOM_API}/auth/streaming-token` **no backend Java**, depois abre
  `wss://.../agents/ws/alerts?token=<streaming-token>` (não usa o JWT principal de 8h, por design
  de segurança já documentado no CLAUDE.md). Mensagens `level:'error'|'warning'` incrementam
  `alertCount` (badge no item de nav "Alertas"). **Sem reconexão automática** hoje — mantido como
  está (não é objetivo desta migração "consertar" isso, só trocar a tecnologia).

### Backend (`agents-platform/backend/`) — **fica exatamente como está, nenhuma mudança**
- Prefixo real das rotas: o Caddy faz `strip_prefix /agents` em `@agents-api`/`@agents-ws`
  (`Caddyfile:44-63`), então o FastAPI só conhece `/api/agents`, `/api/servers`,
  `/api/executions`, `/api/reports`, `/api/knowledge`, `/api/llm`, `/api/system`, `/ws/...` — sem
  o prefixo `/agents`. O client novo deve montar `baseURL: '/agents'` (relativo, same-origin) e
  manter os paths internos (`/api/agents/...` etc) exatamente como o app atual já usa.
- **Sem envelope de resposta** — cada endpoint retorna o formato que quiser. Paginação é
  `offset`/`limit` (não `page`/`size` como no Telecom/Insights), resposta `{items, total, limit,
  offset}` **sem `totalPages`** (o cliente precisa derivar). Alguns endpoints retornam array puro
  sem paginação (`/agents/{id}/memory`, `/executions/alerts`, `/executions/{id}/logs`,
  `/knowledge/search`, `/system/agents/{id}/secrets`).
- **Erros**: `{"detail": "mensagem"}` (401/403/404/400/413 manuais) OU `{"detail": [...]}` (422
  automático do Pydantic, array de objetos) — o client novo precisa tratar os dois formatos,
  diferente do padrão do Telecom/Insights.
- RBAC: `require_permission(resource_key, action)` (dependency factory, `auth.py:24-41`) — mesma
  claim `perm`/`role` do JWT compartilhado, resources `agents.*`. `require_admin` puro em
  execuções/logs de execução (podem vazar DSN/senha em mensagem de erro) e retenção de dados.
- WS auth: `_ws_auth` (`main.py:133-148`) só aceita o streaming-token (`scope=stream`, 60s) —
  **isso precisa continuar vindo do backend Telecom** (`POST /api/v1/auth/streaming-token`), não
  do backend de Agentes.

### Infra (Dockerfile/nginx/Caddyfile) — achado importante: quase nada precisa mudar
- `frontend/nginx.conf:66-70` (`location /agents/`) **já é idêntico** ao padrão SPA de
  `location /insights/` (`alias` + `try_files $uri $uri/ /agents/index.html`) — **nenhuma edição
  necessária no nginx.conf**.
- `Caddyfile` (`@agents-api` 44-53, `@agents-ws` 56-63, `@agents-ui` 67-74) roteia por path, não
  depende de como o frontend é construído — **nenhuma edição necessária no Caddyfile**.
- `frontend/Dockerfile:77-78` hoje faz `COPY` direto de `agents-platform/frontend/index.html` e
  `.../js/` (sem build) — **isso sim precisa mudar**: adicionar um estágio `agents-builder`
  espelhando `insights-builder` (`Dockerfile:48-62`) e trocar as 2 linhas de `COPY` por uma só,
  `COPY --from=agents-builder /app/dist /usr/share/nginx/html/agents` (mesmo padrão da linha 81).
- `.gitignore` precisa de `agents-platform/frontend/node_modules/` e `.../dist/` (mesmo padrão das
  linhas 47-48 já existentes para Insights).

## Decisões confirmadas com o usuário
1. **2FA no login de Agentes**: corrigir — reusar o mesmo `Login.tsx` do Insights/Telecom (que já
   trata 2FA corretamente), em vez de portar a limitação atual.
2. **Gating de escrita em Knowledge/Secrets/LLM**: corrigir — aplicar o mesmo padrão `canWrite()`
   que `Agents`/`Servers` já usam, escondendo botões de escrita de quem só tem leitura.
3. **Gráfico do Dashboard**: usar `recharts` (já dependência do Insights) em vez de manter as
   barras CSS puras — pequeno ganho visual, biblioteca já validada no projeto.
4. **Tudo o mais** (paginação offset/limit sem UI de páginas, sem WS reconnect automático, sem
   editor de código/drag-and-drop) — portar 1:1, sem adicionar escopo não pedido.

## Patterns to Mirror
| Categoria | Origem | Padrão |
|---|---|---|
| Estrutura de projeto Vite | `insights-platform/frontend/{package.json,vite.config.ts,tsconfig*.json}` | Copiar quase 1:1, trocar `base: '/insights/'` → `'/agents/'`, nome do pacote |
| Sidebar dedicado | `insights-platform/frontend/src/components/Sidebar.tsx` | 8 itens (`agents.*`), ícones lucide-react, RBAC via `session.hasRead`, colapso |
| Login com 2FA | `insights-platform/frontend/src/components/Login.tsx` | Copiar tal qual (já é genérico — login no backend Telecom) |
| Hook de sessão | `insights-platform/frontend/src/hooks/useAuthSession.ts` | Copiar, resources `agents.*` |
| CSS/design system | `insights-platform/frontend/src/{App.css,index.css}` | Copiar — mesma paleta/sidebar/cards do Telecom, já provado portável |
| Cliente HTTP com token de streaming | `agents-platform/frontend/index.html:1319-1333` (fluxo atual) | Novo hook `useAgentsAlerts.ts` reproduzindo exatamente: `POST {VITE_API_URL}/auth/streaming-token` → abrir WS com o token retornado |
| Paginação offset/limit | `routers/agents.py`/`servers.py`/etc (`{items,total,limit,offset}`) | Tipo `PaginatedResponse<T>` próprio em `api/types.ts` (diferente do `PageResponse` de Insights) |
| Tratamento de erro `detail` string\|array | `main.py`/`auth.py` (todos os erros) | Helper `getErrorDetail(err)` em `api/client.ts`, usado nos `catch` de cada página |

## Arquivos a criar/alterar
| Arquivo | Ação | Motivo |
|---|---|---|
| `agents-platform/frontend/package.json` | CREATE | deps: `axios`, `lucide-react`, `react`/`react-dom` `^19`, `recharts`; devDeps espelhando Insights |
| `agents-platform/frontend/vite.config.ts` | CREATE | `base: '/agents/'` |
| `agents-platform/frontend/tsconfig*.json` | CREATE | Cópia dos 3 arquivos de Insights |
| `agents-platform/frontend/index.html` | REWRITE | Vira entry HTML do Vite (perde o conteúdo React inline) |
| `agents-platform/frontend/src/main.tsx` | CREATE | `ReactDOM.createRoot` |
| `agents-platform/frontend/src/App.tsx` | CREATE | Shell: Login vs conteúdo, Sidebar, WS de alertas, roteamento de página |
| `agents-platform/frontend/src/components/Sidebar.tsx` | CREATE | 8 itens de nav, RBAC `agents.*` |
| `agents-platform/frontend/src/components/Login.tsx` | CREATE | Copiado de Insights (com 2FA) |
| `agents-platform/frontend/src/hooks/useAuthSession.ts` | CREATE | Copiado de Insights |
| `agents-platform/frontend/src/hooks/useAgentsAlerts.ts` | CREATE | Streaming-token + WS `/agents/ws/alerts` + `alertCount` |
| `agents-platform/frontend/src/api/client.ts` | CREATE | axios `baseURL:'/agents'`, `canRead`/`canWrite`/`getRoleFromToken`/`getPermissionsFromToken`, `getErrorDetail()` |
| `agents-platform/frontend/src/api/types.ts` | CREATE | `Agent`, `Server`, `Execution`, `Alert`, `KnowledgeDoc`, `LlmStatus`/`LlmProviders`/`LlmConfig`, `PaginatedResponse<T>` |
| `agents-platform/frontend/src/components/StatusBadge.tsx` | CREATE | Mirror de `SBadge` |
| `agents-platform/frontend/src/components/ConfirmModal.tsx` | CREATE | Mirror de `Confirm` |
| `agents-platform/frontend/src/components/LogConsole.tsx` | CREATE | Console de log compartilhado (dedup de `LogModal`+`LogsTab`, hoje duplicado) |
| `agents-platform/frontend/src/components/DashboardTab.tsx` | CREATE | Stats + `recharts` (disponibilidade por agente) + tabelas |
| `agents-platform/frontend/src/components/AgentsTab.tsx` | CREATE | CRUD de agentes + ações (run/pause/resume/delete) |
| `agents-platform/frontend/src/components/AgentForm.tsx` | CREATE | Formulário multi-seção (maior componente) |
| `agents-platform/frontend/src/components/ServersTab.tsx` | CREATE | CRUD de servidores + testar conexão |
| `agents-platform/frontend/src/components/KnowledgeTab.tsx` | CREATE | Upload PDF + lista + busca, com gating de escrita |
| `agents-platform/frontend/src/components/LogsTab.tsx` | CREATE | Select agente→execução + `LogConsole` |
| `agents-platform/frontend/src/components/AlertsTab.tsx` | CREATE | Tabela de alertas (era `Reports`) |
| `agents-platform/frontend/src/components/SecretsTab.tsx` | CREATE | Segredos por agente, com gating de escrita |
| `agents-platform/frontend/src/components/LlmSettingsTab.tsx` | CREATE | Config de IA, com gating de escrita |
| `agents-platform/frontend/js/` | DELETE | Bundles UMD vendorizados, substituídos pelo build Vite |
| `frontend/Dockerfile` | UPDATE | Novo estágio `agents-builder` (mirror `insights-builder`); troca as 2 `COPY` diretas por 1 `COPY --from=agents-builder` |
| `.gitignore` | UPDATE | `agents-platform/frontend/node_modules/` + `.../dist/` |
| `Caddyfile` | — | **Sem alteração** (confirmado — roteamento por path já correto) |
| `frontend/nginx.conf` | — | **Sem alteração** (confirmado — `location /agents/` já é SPA-ready) |
| `frontend/src/data/releases.ts` | UPDATE | Nova versão (obrigatório) |
| `CLAUDE.md` | UPDATE | Atualizar seção de estrutura do repo + comentário do Caddyfile sobre CSP `unsafe-inline` (hoje justificado por Agentes ser UMD sem build — deixa de valer) |

## Tarefas (fases)

### Fase 0 — Scaffold do projeto Vite
- Criar `package.json`, `vite.config.ts` (`base:'/agents/'`), `tsconfig*.json`, `index.html`,
  `src/main.tsx` — cópia adaptada da Fase 1 do plano `insights-spa-independente.plan.md`.
- Copiar `App.css`/`index.css` do Insights (design system idêntico).
- **Validar:** `npm install` sem erro; `npx vite build` roda (mesmo com `App.tsx` vazio/placeholder).

### Fase 1 — Camada de API e sessão
- `api/types.ts`: todos os tipos de payload (baseado no inventário de endpoints do backend).
- `api/client.ts`: instância axios, interceptor de 401 (mesmo padrão de Insights/Telecom —
  dispara `asteriskia:logout`), `getErrorDetail(err)` tratando `detail` string ou array Pydantic,
  `canRead`/`canWrite`/`getRoleFromToken`/`getPermissionsFromToken` (mesma lógica, resources
  `agents.*`).
- `hooks/useAuthSession.ts`: cópia direta de Insights.
- **Validar:** `npx tsc --noEmit`.

### Fase 2 — Shell: Login, Sidebar, App, WebSocket de alertas
- `components/Login.tsx`: cópia do Insights (com 2FA).
- `components/Sidebar.tsx`: 8 itens (Dashboard/Agentes/Servidores/Base de Conhecimento/Logs/
  Alertas/Secrets/Config. IA), ícones lucide-react, resources `agents.*`.
- `hooks/useAgentsAlerts.ts`: reproduz o fluxo atual — `POST {VITE_API_URL}/auth/streaming-token`
  (backend Telecom) → abre `wss://.../agents/ws/alerts?token=...` → incrementa `alertCount` em
  mensagens `error`/`warning`. Sem reconexão automática (paridade com hoje).
- `App.tsx`: shell completo (Login vs conteúdo, Sidebar, roteamento de página por estado local,
  contador de alertas no item "Alertas").
- **Validar:** `npx tsc --noEmit && npm run build`; login manual (usuário testa depois do deploy).

### Fase 3 — Componentes compartilhados
- `StatusBadge.tsx` (mirror `SBadge`), `ConfirmModal.tsx` (mirror `Confirm`), `LogConsole.tsx`
  (novo componente compartilhado, dedup de `LogModal`+`LogsPage` — recebe `execution`/`logs` como
  props, cuida do auto-scroll).
- **Validar:** `npx tsc --noEmit`.

### Fase 4 — Páginas de baixo risco (somente leitura ou CRUD simples)
- `AlertsTab.tsx` (mais simples, só leitura).
- `DashboardTab.tsx` (stats + `recharts` para disponibilidade por agente, substituindo as barras
  CSS).
- `KnowledgeTab.tsx` (upload PDF + lista + busca — com gating `canWrite('agents.knowledge')` nos
  botões de upload/excluir, correção confirmada).
- `LogsTab.tsx` (selects em cascata + `LogConsole`).
- **Validar:** `npx tsc --noEmit && npm run build` a cada página adicionada.

### Fase 5 — Secrets e Config. IA (formulários, com gating de escrita)
- `SecretsTab.tsx` — com `canWrite('agents.secrets')` nos botões de adicionar/remover segredo.
- `LlmSettingsTab.tsx` — com `canWrite('agents.llm')` no formulário/botões de salvar/testar.
- **Validar:** `npx tsc --noEmit && npm run build`.

### Fase 6 — Servidores e Agentes (CRUDs completos + formulário complexo)
- `ServersTab.tsx` — CRUD + "Testar conexão" (trata resposta `{ok:false,error}` sem lançar
  exceção, mesmo padrão do backend).
- `AgentForm.tsx` — formulário multi-seção (tipo de agente, checks SSH dinâmicos, tags de URL,
  agendamento, notificações) — componente mais complexo, migrar por último com mais atenção.
- `AgentsTab.tsx` — CRUD + ações (executar/pausar/retomar/excluir) + `LogModal` reaproveitando
  `LogConsole`.
- **Validar:** `npx tsc --noEmit && npm run build`.

### Fase 7 — Infra: Dockerfile, .gitignore, remoção do código antigo
- `frontend/Dockerfile`: novo estágio `agents-builder` (mirror exato de `insights-builder`,
  linhas 48-62), troca das linhas 77-78 por `COPY --from=agents-builder /app/dist
  /usr/share/nginx/html/agents`.
- `.gitignore`: adicionar `agents-platform/frontend/node_modules/` e `.../dist/`.
- Remover `agents-platform/frontend/js/` (bundles vendorizados, não usados mais).
- **Validar:** `docker compose build frontend` completo (2 builders + nginx final).

### Fase 8 — Docs, release notes, deploy
- Atualizar `CLAUDE.md` (estrutura do repo + nota sobre CSP `unsafe-inline` do Caddyfile, que
  deixa de ser estritamente necessária por causa do Agentes — mas migrar a CSP de
  `Report-Only` para enforcement real é fora de escopo deste plano, fica como próxima
  oportunidade).
- `frontend/src/data/releases.ts`: nova versão.
- Deploy (só com confirmação separada): `docker compose build frontend && docker compose up -d
  frontend && docker compose ps && curl -I https://app.voiphash.com.br/agents/`.
- Teste funcional manual (login, WS de alertas, cada uma das 8 páginas) — sem acesso a browser
  nesta sessão, pedir para o usuário validar.

## Riscos
| Risco | Prob. | Mitigação |
|---|---|---|
| `AgentForm` é o componente mais complexo (243 linhas, múltiplas seções condicionais) — risco de perder algum campo/validação na migração | Média | Migrar por último (Fase 6), depois de já ter o padrão maduro nas páginas mais simples; conferir campo a campo contra o inventário desta pesquisa |
| WS de alertas depende de um endpoint do backend Telecom (`/auth/streaming-token`) — se `VITE_API_URL` apontar errado, alertas simplesmente não funcionam (sem erro visível) | Média | Mesma env var já usada e validada em Insights; testar manualmente após deploy |
| Erros do backend de Agentes vêm em formato diferente do Telecom/Insights (`detail` string\|array) — um `catch` que assume o formato errado quebra silenciosamente | Média | `getErrorDetail()` centralizado, usado em todas as páginas — testar com pelo menos um erro de validação real (422) |
| Corrigir o gating de escrita em Knowledge/Secrets/LLM muda comportamento visível pra usuários com só leitura nesses recursos (bot��es que hoje aparecem vão sumir) | Baixa | Já confirmado com o usuário como correção desejada, não regressão |
| Perda de alguma funcionalidade não documentada no inventário (o levantamento foi extenso mas é um arquivo de 1688 linhas) | Baixa | Migrar página por página com validação incremental; manter o `index.html` antigo no histórico do git (não é preciso branch separada, só não apagar até o deploy validado) |
| CSP `Report-Only` do Caddyfile hoje é `'unsafe-inline'` por causa do Agentes UMD — não faz parte deste plano migrar para enforcement, só observar que a justificativa textual do comentário deixa de valer | Baixa | Nota no CLAUDE.md; migração de CSP fica como item separado, fora de escopo |

## Aceite
- [ ] `agents-platform/frontend/` é um projeto Vite completo, sem `js/` vendorizado
- [ ] As 8 páginas funcionam com paridade comportamental (exceto as 3 correções confirmadas: 2FA,
      gating de escrita em Knowledge/Secrets/LLM, gráfico com recharts no Dashboard)
- [ ] WebSocket de alertas continua funcionando com o mesmo fluxo de streaming-token
- [ ] RBAC granular `agents.*` preservado (mesmos resources, mesmo comportamento de ADMIN)
- [ ] Backend FastAPI **inalterado** — nenhum arquivo em `agents-platform/backend/` tocado
- [ ] `Caddyfile`/`frontend/nginx.conf` **inalterados** (confirmado que já suportam o padrão SPA)
- [ ] `frontend/Dockerfile` com novo estágio `agents-builder`, buildando sem erro
- [ ] `tsc --noEmit`/`npm run build` limpos; release notes + CLAUDE.md atualizados

## Retomada em outra sessão
Para continuar este trabalho a partir de qualquer sessão nova, peça para ler este arquivo
(`.claude/plans/agentes-migracao-vite-spa.plan.md`) e seguir a partir da última fase marcada como
concluída em "Tarefas (fases)" — nenhuma fase foi iniciada ainda. Dado o tamanho (8 páginas),
recomenda-se implementar em lotes (ex: uma fase por sessão) em vez de tentar tudo de uma vez.
