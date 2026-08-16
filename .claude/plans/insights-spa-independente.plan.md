# Plan: Insights como SPA independente (padrão AGENTES)

**Status:** aprovado, pronto para implementação — nenhuma fase iniciada ainda.
**Origem:** pedido livre — "alterar o menu INSIGHTS para ser independente igual o menu AGENTES, com tela de login e url de acesso dedicado".
**Complexidade:** Large (frontend novo + roteamento + RBAC + migration)

## Decisões confirmadas com o usuário
1. **URL:** path `/insights` no mesmo domínio (`app.voiphash.com.br/insights`) — igual `/agents`. Sem subdomínio.
2. **Frontend:** Vite próprio (nova pasta `insights-platform/frontend/`), **reaproveitando** os 6 componentes `Insights*.tsx` existentes. Não é React UMD puro.
3. **Backend:** **mantido no Spring Boot** — os endpoints `/api/v1/insights/**` continuam como estão. Sem FastAPI novo, sem container novo.
4. **Menu Telecom:** segue o padrão do AGENTES — o item da Sidebar vira um `<iframe src="/insights/">` (espelho de `AgentesPage.tsx`), com resource dedicado `telecom.insights_link`.
5. **Login:** reaproveita o auth do Telecom — tela de login própria na SPA que faz `POST /api/v1/auth/login` e guarda `asteriskia_token` (exatamente como a SPA de AGENTES).
6. **RBAC:** **granular por aba** (`insights.calls`, `insights.dashboard`, `insights.processing`, `insights.costs`) — segue fielmente o padrão do namespace `agents.*` do AGENTES, em vez de um resource único. Decisão default do plano (não houve objeção do usuário); revisar se preferir simplificar para um resource único `insights` antes de iniciar a Fase 4.

## Como o padrão AGENTES realmente funciona (base da réplica)
- `AgentesPage.tsx` no Telecom = só um `<iframe src="/agents/">` em tela cheia; mesma origem compartilha `localStorage`/sessão (`asteriskia_token`), sem ponte.
- SPA servida pelo **mesmo nginx do frontend** em `/usr/share/nginx/html/agents/` (Dockerfile copia); Caddy tem `@agents-ui { path /agents* } → frontend:80` sem strip.
- A SPA reusa o JWT do Telecom; RBAC granular por namespace (`agents.*`) na sidebar da SPA + `telecom.agents_link` para o item de menu no Telecom.
- **Diferença chave do Insights:** AGENTES tem backend FastAPI próprio (`/agents/api`, `/agents/ws`). O Insights **não** — reusa o backend Java via `/api/*` já roteado. Logo, só precisamos servir estáticos em `/insights/`.

## Patterns to Mirror
| Categoria | Origem | Padrão |
|---|---|---|
| Página-iframe no Telecom | `frontend/src/components/AgentesPage.tsx` | iframe tela cheia, mesma origem |
| Item de menu link | `Sidebar.tsx:36`, `App.tsx:113,217` | resource `telecom.agents_link` |
| SPA servida por prefixo | `frontend/nginx.conf:66-70` (`location /agents/`) | `alias` + `try_files` SPA fallback |
| Roteamento Caddy | `Caddyfile:69-74` (`@agents-ui`) | `path /agents*` sem strip → frontend:80 |
| Build da SPA no Dockerfile | `frontend/Dockerfile:60-61` | `COPY` do build para `/usr/share/nginx/html/agents/` |
| Camada de API | `frontend/src/api/client.ts` | axios baseURL `/api/v1`, Bearer `asteriskia_token`, refresh, `canRead/canWrite` |
| RBAC namespace | `ResourceCatalog.java:36-45` (`agents.*`) | namespace próprio p/ abas + link no Telecom |

## Arquivos a criar/alterar
| Arquivo | Ação | Motivo |
|---|---|---|
| `insights-platform/frontend/package.json` | CREATE | deps Vite/React/axios/recharts |
| `insights-platform/frontend/vite.config.ts` | CREATE | `base: '/insights/'` |
| `insights-platform/frontend/tsconfig*.json` | CREATE | build TS |
| `insights-platform/frontend/index.html` | CREATE | entry HTML |
| `insights-platform/frontend/src/main.tsx` | CREATE | `ReactDOM.createRoot` |
| `insights-platform/frontend/src/App.tsx` | CREATE | shell: Login vs conteúdo + sidebar própria (5 abas filtradas por `canRead`) + logout |
| `insights-platform/frontend/src/Login.tsx` | CREATE | adaptado de `frontend/src/components/Login.tsx` (inclui 2FA) |
| `insights-platform/frontend/src/api/client.ts` | CREATE | copiado de `frontend/src/api/client.ts` |
| `insights-platform/frontend/src/api/types.ts` | CREATE | subset Insights (`types.ts:289-395`) + `PageResponse` |
| `insights-platform/frontend/src/components/*` | CREATE | cópia dos 6 `Insights*.tsx` + `AuthedAudio.tsx` |
| `insights-platform/frontend/src/index.css` | CREATE | CSS base/variáveis do Telecom (subset necessário) |
| `frontend/nginx.conf` | UPDATE | `location /insights/` (alias + SPA fallback) |
| `Caddyfile` | UPDATE | `@insights-ui { path /insights* } → frontend:80` |
| `frontend/Dockerfile` | UPDATE | build + `COPY` da SPA Insights p/ `/usr/share/nginx/html/insights/` |
| `frontend/src/components/InsightsPage.tsx` | CREATE | iframe `/insights/` (espelho de `AgentesPage.tsx`) |
| `frontend/src/App.tsx` | UPDATE | `page==='insights'` → `<InsightsPage/>`; `PAGE_RESOURCE.insights = 'telecom.insights_link'`; remove lazy de `ModuloInsights` |
| `frontend/src/components/Sidebar.tsx` | UPDATE | item insights → resource `telecom.insights_link` |
| `frontend/src/components/ModuloInsights.tsx` + 5 tabs | DELETE | migrados p/ a SPA (AuthedAudio **fica** — é compartilhado com URA/Alertas) |
| `backend/.../config/ResourceCatalog.java` | UPDATE | +`telecom.insights_link`, +namespace `insights.*` (calls/dashboard/processing/costs); migrar `telecom.insights` |
| `backend/.../config/SecurityConfig.java` | UPDATE | matchers GET granulares `/api/v1/insights/{calls,dashboard,processing,costs}/**` → `PERM_READ_insights.*` |
| `backend/src/main/resources/db/migration/V37__insights_rbac_namespace.sql` | CREATE | migra permissões `telecom.insights` → novos resources (preserva acesso), confirmar nº com `ls | sort -V | tail -1` |
| `frontend/src/components/docs/sections/TelecomInsights.tsx` | UPDATE | documentar acesso dedicado |
| `frontend/src/data/releases.ts` | UPDATE | nova versão (obrigatório) |
| `CLAUDE.md` | UPDATE | nova arquitetura Insights SPA |

## Tarefas (fases)
### Fase 1 — SPA Insights (Vite próprio)
- Scaffold `insights-platform/frontend/` (package.json, vite.config `base:'/insights/'`, tsconfig, index.html, main.tsx).
- Copiar `client.ts`, subset de `types.ts`, os 6 `Insights*.tsx` e `AuthedAudio.tsx`, e o CSS base.
- Criar `Login.tsx` (adaptado do Telecom) e `App.tsx` (shell com sidebar própria + logout, filtrando abas por `canRead` no namespace `insights.*`).
- **Validar:** `cd insights-platform/frontend && npm ci && npm run build` sem erros; `tsc --noEmit`.

### Fase 2 — Servir a SPA em `/insights`
- `frontend/nginx.conf`: `location /insights/` espelhando `/agents/`.
- `Caddyfile`: bloco `@insights-ui` espelhando `@agents-ui`.
- `frontend/Dockerfile`: build da SPA + `COPY dist /usr/share/nginx/html/insights/`.
- **Validar:** `docker compose build frontend` ok; após deploy, `curl -I https://app.voiphash.com.br/insights/` → 200.

### Fase 3 — Menu Telecom vira iframe (padrão AGENTES)
- Criar `InsightsPage.tsx` (iframe).
- `App.tsx`/`Sidebar.tsx`: trocar render e resource; remover lazy de `ModuloInsights`.
- Deletar os 6 componentes Insights do Telecom (manter `AuthedAudio`).
- **Validar:** `tsc --noEmit` no frontend Telecom; nada mais importa os arquivos removidos (`grep -rn ModuloInsights frontend/src`).

### Fase 4 — RBAC namespace `insights.*`
- `ResourceCatalog.java`: novos resources.
- `SecurityConfig.java`: matchers GET granulares.
- `V37`: migration que copia permissões de `telecom.insights` para os novos resources e remove o antigo.
- **Validar:** `mvn -q -pl backend compile`; forjar JWT de teste e `curl` nos endpoints checando 200/403.

### Fase 5 — Docs, release notes, memória, deploy
- Atualizar `TelecomInsights.tsx`, `releases.ts`, `CLAUDE.md`, memória.
- Deploy: `docker compose up -d --build backend frontend caddy` (migration V37 roda no boot do backend).
- **Validar:** `docker compose ps` healthy; login em `/insights/`; abas carregam; áudio toca; item no menu Telecom abre o iframe.

## Riscos
| Risco | Prob. | Mitigação |
|---|---|---|
| CSS/variáveis (`--text-muted`, `.spinner`) faltando na SPA nova → visual quebrado | Alta | Copiar o CSS base do Telecom para `insights-platform/frontend/src/index.css` |
| `AuthedAudio` removido por engano (é compartilhado com URA/Alertas) | Média | **Copiar**, não mover; manter no Telecom |
| Migration de permissões irreversível pode remover acesso | Média | Revisar SQL; copiar antes de remover `telecom.insights`; testar com grupo real |
| Login: 2FA quebrar na SPA | Média | Reaproveitar `Login.tsx` completo do Telecom (já trata 2FA) |
| `VITE_API_URL` da SPA nova apontar errado | Média | Usar `/api/v1` relativo (mesma origem), igual Telecom em prod |
| Tempo de build do frontend dobra (2 builds Vite) | Baixa | Aceitável; multi-stage reaproveita cache |
| Tokens JWT antigos sem claim `perm` dos novos resources | Baixa | ADMIN sempre passa; usuários migram no próximo login/refresh |

## Validação final
```bash
cd /opt/VoipIA
ls backend/src/main/resources/db/migration/ | sort -V | tail -1     # confirmar nº da migration
cd insights-platform/frontend && npm ci && npm run build && npx tsc --noEmit
cd /opt/VoipIA/frontend && npx tsc --noEmit
docker compose build frontend backend
docker compose up -d --build backend frontend caddy
docker compose ps
curl -I https://app.voiphash.com.br/insights/
```

## Aceite
- [ ] `/insights` abre a SPA independente com tela de login própria (reusa auth do Telecom)
- [ ] As 5 abas do Insights funcionam (Chamadas, Tendências, Processamento, Custos IA, Dashboard de Custos), áudio toca
- [ ] Item "Insights" no menu Telecom abre a SPA via iframe (padrão AGENTES)
- [ ] RBAC granular por aba (`insights.*`) + `telecom.insights_link` no menu; permissões existentes preservadas
- [ ] Backend Java inalterado nos endpoints; migration V37 aplicada
- [ ] Release notes + CLAUDE.md + memória atualizados; `docker compose ps` healthy

## Retomada em outra sessão
Para continuar este trabalho a partir de qualquer sessão nova, basta pedir para
ler este arquivo (`.claude/plans/insights-spa-independente.plan.md`) e seguir
a partir da última fase marcada como concluída em "Tarefas (fases)" acima —
nenhuma fase foi iniciada ainda. Ver também memória `asteriskia_insights_spa_independente_plan`.
