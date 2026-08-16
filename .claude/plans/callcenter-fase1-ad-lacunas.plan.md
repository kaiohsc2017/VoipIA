# Plano: Fase 1 do Call Center — fechar as 3 lacunas do AD (employee_id, paginação, UI de sync)

**Complexidade**: Média

## Contexto — achado antes de planejar

A Fase 1/AD do plano-mãe do Call Center **já foi implementada e está em produção** (commit
`aae82f5`, 2026-08-06, 272/272 testes verdes) — o `CLAUDE.md` está desatualizado sobre isso
(mesmo padrão já visto antes na Fase 5). Já existem, validados:

- Login via bind LDAP (`AuthController`/`LdapClient.authenticate`) com fallback local
  (`AD_LOCAL_FALLBACK_ENABLED`) e proteção contra sequestro de conta (`app_users.ad_linked`).
- Configuração 100% via Web em Configurações do Sistema (`Settings.tsx:119-145`, seção `ad`) —
  host **por DNS** (sem IP fixo), porta, LDAPS, Base DN, Bind DN/senha mascarada
  (`AD_LDAP_BIND_PASSWORD`, sufixo padrão de segredo).
- Teste de conexão (`POST /settings/test/ad`, `SettingsTestController`).
- Espelho local `ad_users` (migration `V45__ad_identity.sql`) + sincronização periódica
  (`AdSyncScheduler`) + auditoria (`ad_sync_runs`).
- Mapeamento grupo AD → grupo de acesso (`ad_group_mappings`, `AdSyncController`).
- RBAC: reusa `telecom.settings` (decisão já tomada e documentada em
  `SecurityConfig.java:100-104,302-303` e `AdSyncController.java:15-17`) — **sem resource novo
  nesta fase**.

Esta demanda, portanto, **não recria a Fase 1** — fecha as 3 lacunas reais confirmadas com o
usuário, que hoje bloqueiam o restante do plano-mãe (Fase 14/screen pop depende do item 1):

1. `employee_id`/`employeeID` do AD não é espelhado em `ad_users`.
2. `LdapClient.fetchAll()` não pagina — trunca silenciosamente acima de ~1000 usuários
   (`LdapClient.java:81-119`, limitação já documentada no próprio código).
3. `AdSyncController` (status de sync, sync manual, CRUD de mapeamento de grupo) existe no
   backend mas não tem tela — `Settings.tsx:123` já anuncia textualmente: *"Sincronização e
   mapeamento de grupos AD ficam numa tela dedicada em breve."*

## Patterns to Mirror

| Categoria | Fonte | Padrão |
|---|---|---|
| Migration Flyway | `V83__callcenter_agent_schedules.sql` (última aplicada) | Próxima é `V84__ad_employee_id.sql` — `ALTER TABLE` aditivo, sem backfill destrutivo |
| Mapeamento LDAP→objeto | `LdapClient.java:129-140` (`mapAttributes`) | Helper `attrValue(attrs, name)` já genérico — só adicionar uma linha |
| Upsert de espelho | `AdUserService.upsertMirror` (`AdUserService.java:31-50`) | Seta campo a campo a partir do record `LdapUserAttributes` |
| CRUD com tabela+modal | `AccessGroups.tsx` (padrão já usado no projeto) | Estrutura a espelhar para a tela de sync/mapeamento |
| Erro do teste de conexão sem vazar segredo | `SettingsTestController` (padrão já usado em CFG-email/Jira/Zabbix) | Nunca `e.getMessage()` cru quando pode conter host/credencial |
| Testes | `LdapClientTest.java` (88 linhas, sem cenário de paginação) | Espelhar estilo de mock de `LdapTemplate`/`DirContextProcessor` |

## Files to Change

| Arquivo | Ação | Por quê |
|---|---|---|
| `backend/src/main/resources/db/migration/V84__ad_employee_id.sql` | CREATE | Nova coluna `employee_id` em `ad_users` |
| `backend/src/main/java/com/asteriskia/integration/ad/LdapUserAttributes.java` | UPDATE | Novo campo `employeeId` no record |
| `backend/src/main/java/com/asteriskia/integration/ad/LdapClient.java` | UPDATE | `mapAttributes` lê `employeeID`; `fetchAll()` ganha paginação real via `PagedResultsRequestControl` |
| `backend/src/main/java/com/asteriskia/integration/ad/LdapTemplateFactory.java` | UPDATE (se necessário) | Suporte a `DirContextProcessor` no `LdapTemplate` usado por `fetchAll` |
| `backend/src/main/java/com/asteriskia/integration/ad/AdUser.java` | UPDATE | Coluna/campo `employeeId` |
| `backend/src/main/java/com/asteriskia/integration/ad/AdUserService.java` | UPDATE | `upsertMirror` propaga `employeeId` |
| `backend/src/test/java/com/asteriskia/integration/ad/LdapClientTest.java` | UPDATE | Casos de paginação (múltiplas páginas, cookie, truncamento não ocorre mais) + `employeeID` mapeado |
| `backend/src/test/java/com/asteriskia/integration/ad/AdUserServiceTest.java` (se existir; senão criar) | UPDATE/CREATE | `employeeId` persistido no upsert |
| `frontend/src/components/AdSyncTab.tsx` | CREATE | Painel de status/sync manual/tabela de mapeamento de grupo, consumindo `AdSyncController` já existente |
| `frontend/src/components/Settings.tsx` | UPDATE | Renderiza `<AdSyncTab/>` condicionalmente quando `section.id === 'ad'`; remove o texto "em breve" (`:123`) |
| `frontend/src/api/client.ts` (ou onde já vivem as chamadas de settings) | UPDATE | Funções para os 5 endpoints de `AdSyncController` (podem já existir parcialmente — checar antes de duplicar) |
| `docs/sections/*.tsx` (Documentação) | UPDATE (opcional) | Se a doc de Configurações já cobre AD, atualizar a menção de "em breve" |

## Tasks

### Task 1 — Migration V84 + espelhar `employeeID`

- **Ação**: `ALTER TABLE ad_users ADD COLUMN employee_id VARCHAR(64);` (nullable — nem todo AD
  popula esse atributo, e o backfill do sync noturno resolve organicamente, sem precisar de
  script de migração de dado). Índice opcional só se a Fase 14 vier a consultar por
  `employee_id` diretamente (a decisão de design já registrada é: a correlação
  `cc_agents → app_users (user_id) → ad_users (sam_account_name == username)` não exige índice
  novo em `employee_id` — mas adicionar `idx_ad_users_employee_id` é barato e evita
  reabrir migration na Fase 14).
- **Mirror**: `V45__ad_identity.sql` (mesmo estilo de tabela, sem `NOT NULL` para atributo opcional do AD).
- **Ação (Java)**: adicionar `employeeId` ao record `LdapUserAttributes`; em
  `LdapClient.mapAttributes` (`:129-140`), acrescentar `attrValue(attrs, "employeeID")` na
  construção do record; em `AdUser.java`, novo campo/coluna `employeeId`; em
  `AdUserService.upsertMirror`, propagar o valor no upsert.
- **Decisão a confirmar durante a implementação**: usar o atributo LDAP `employeeID` (mais comum
  em AD Windows) — se o DC real do usuário popular `employeeNumber` em vez disso, é troca de uma
  linha (`attrValue(attrs, "employeeID")` → `"employeeNumber"`), sem impacto de schema.
- **Validate**: `LdapClientTest` cobrindo `employeeID` presente/ausente no mock de atributos;
  `AdUserServiceTest` (ou equivalente) cobrindo upsert persistindo o valor; `mvn test` verde.

### Task 2 — Paginação real de `fetchAll()`

- **Ação**: reescrever `LdapClient.fetchAll()` para usar
  `LdapTemplate.search(base, filter, searchControls, mapper, dirContextProcessor)` com um
  `PagedResultsDirContextProcessor` (pacote `org.springframework.ldap.control`, já transitivo do
  `spring-boot-starter-data-ldap`, sem dependência nova) — loop com cookie até
  `processor.hasMore() == false`, acumulando os resultados de cada página numa lista. Remover o
  `SUSPECTED_TRUNCATION_THRESHOLD`/warning (deixa de ser necessário — a paginação real elimina o
  truncamento, não só o sinaliza).
- **Mirror**: nenhum precedente direto no projeto (é a primeira paginação LDAP) — seguir a API
  pública padrão do Spring LDAP (`PagedResultsDirContextProcessor`, construtor com page size).
  Verificar se `LdapTemplateFactory` expõe o `ContextSource`/`SearchControls` necessários ou se
  precisa de um método novo lá.
- **Tamanho de página**: usar um valor conservador e configurável (ex: 500 — abaixo do limite
  típico de `MaxPageSize` de 1000 do AD) — constante nomeada, não mágica.
- **Validate**: `LdapClientTest` com mock simulando 2+ páginas (cookie não vazio na primeira
  resposta, vazio na segunda) confirmando que o resultado final concatena as duas; teste
  confirmando que uma busca de >1000 entradas simulada não trunca mais.

### Task 3 — Tela de sincronização e mapeamento de grupos

- **Ação**: criar `AdSyncTab.tsx` (componente novo, RBAC herdado de `telecom.settings` — sem
  gate próprio, a própria seção `ad` de `Settings.tsx` já está atrás dessa permissão) contendo:
  - Painel de status (`GET /ad/sync-status`) — última execução, quantidade sincronizada, erro se houver.
  - Botão "Sincronizar agora" (`POST /ad/sync`) — desabilitado durante a chamada, mesmo padrão de
    UX já usado em botões de ação assíncrona no projeto (loading state + try/catch com alerta).
  - Busca de usuário AD por `samAccountName` (`GET /ad/users?query=`).
  - Tabela de mapeamento grupo AD → grupo de acesso (`GET/POST/DELETE /ad/group-mappings`) — CRUD
    simples com modal de criação, mirror de `AccessGroups.tsx`.
- **Decisão de UX a confirmar com o usuário durante a implementação** (registrado como aberto na
  pesquisa): renderizar como painel extra dentro da seção `ad` já existente em `Settings.tsx`
  (mais simples, sem nova aba de nível superior `Tab`) — é a opção recomendada, dado que
  `type Tab = 'config' | 'history'` já é o único nível de navegação da tela hoje.
- **Ação**: remover o texto "em breve" de `Settings.tsx:123`.
- **Validate**: `tsc --noEmit` e `npm run build` do frontend Telecom limpos; validação visual
  (Chrome headless, workaround já documentado em memória — MCP falha nesta VPS) confirmando que
  o painel carrega status real, sincroniza manualmente e lista/cria/remove mapeamento sem exceção
  JS.

## Validation

```bash
# Backend
docker exec voipia-backend true 2>/dev/null || true  # apenas para referência de ambiente
mvn -o test   # dentro do container Maven com cache offline, mesmo padrão já usado nas fases recentes

# Frontend
cd frontend && npx tsc --noEmit && npm run build

# Deploy
docker compose up -d --build backend frontend
# confirmar migration V84 em flyway_schema_history
docker exec voipia-postgres psql -U asteriskia -d asteriskia -c \
  "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"

# Validação funcional via curl com JWT ADMIN forjado inline (nunca persistido em arquivo)
curl -s https://app.voiphash.com.br/api/v1/ad/sync-status -H "Authorization: Bearer $TOKEN"
curl -s -X POST https://app.voiphash.com.br/api/v1/ad/sync -H "Authorization: Bearer $TOKEN"
```

## Risks

| Risco | Probabilidade | Mitigação |
|---|---|---|
| DC real do usuário não popula `employeeID` (usa `employeeNumber` ou nenhum) | Média | Campo nullable; troca de atributo é 1 linha; sync noturno preenche quando existir |
| `PagedResultsDirContextProcessor` exigir mudança mais profunda em `LdapTemplateFactory`/`ContextSource` do que o previsto | Baixa-Média | Investigar `LdapTemplateFactory.java` no início da Task 2 antes de comprometer a abordagem; Spring LDAP suporta isso nativamente na maioria das configs de `ContextSource` padrão |
| UI de sync expor uma sincronização "achatada" (correr 2x concorrentemente se o botão for clicado 2x) | Baixa | Desabilitar botão durante chamada + o próprio `AdSyncScheduler`/`AdSyncController.sync()` já é síncrono (bloqueia a segunda chamada na mesma requisição HTTP se for a mesma call) — confirmar se precisa de lock adicional no backend |
| Regressão em login AD/sync existente ao alterar `LdapClient` | Baixa | Suíte `LdapClientTest` já cobre autenticação; rodar suíte completa antes de deploy, não só os testes novos |

## Acceptance

- [ ] Migration `V84` aplicada, `employee_id` persistido no espelho local pelo sync
- [ ] `fetchAll()` não trunca mais acima de 1000 usuários (paginação real, testada com mock multi-página)
- [ ] Tela de sincronização/mapeamento de grupos funcional em Configurações → AD, sem exceção JS
- [ ] Suíte completa do backend verde, 0 regressão
- [ ] `tsc --noEmit`/`npm run build` do frontend limpos
- [ ] Deploy validado em produção (migration confirmada, endpoints respondendo, RBAC 403 sem token/permissão)
- [ ] CLAUDE.md atualizado registrando o fechamento das 3 lacunas
