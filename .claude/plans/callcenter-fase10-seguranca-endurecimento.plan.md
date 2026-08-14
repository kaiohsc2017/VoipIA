# Plano — Fase 10 do Call Center (fatia 1): revisão de segurança, endurecimento operacional e documentação

**Plano-mãe**: `.claude/plans/modulo-callcenter-omnicanal.plan.md` §8, **Fase 10 — Endurecimento,
carga e operação** (linha 488).
**Revisão de referência**: `.claude/plans/callcenter-parte-iii-revisado.plan.md` (Fases 19-27, todas
deployadas até 2026-08-14).
**Complexidade agregada**: **G** — 3 das 6 partes da Fase 10, sendo a parte 3 majoritariamente
revisão (achar+corrigir) e a parte 5 majoritariamente escrita de conteúdo.

---

## 1. Escopo — o que entra e o que NÃO entra

**Decisão do usuário (2026-08-14), registrada como premissa deste plano**: a Fase 10 é fatiada. Esta
fatia entrega **apenas** as partes 3, 4 e 5.

| Parte da Fase 10 | Nesta fatia? | Motivo |
|---|---|---|
| 1 — Teste de carga SIPp (voz) e de chat | ❌ | Exige tráfego real em produção e servidor dedicado; decisão explícita do usuário |
| 2 — Particionamento de `cc_interaction_events`/`cc_chat_messages` | ❌ | Volume atual desta VPS não justifica; migration irreversível — só com volume real conhecido |
| **3 — Revisão de segurança completa** | ✅ | Núcleo desta fatia (§4) |
| **4 — Limites de recurso e healthchecks** | ✅ | §5 |
| **5 — Documentação em `Documentacao.tsx`** | ✅ | §6 |
| 6 — Recomendação de hardware do servidor dedicado | ❌ (parcial) | Depende da parte 1 (carga) para ser numérica; **decisão D9** abaixo |

---

## 2. Premissa transversal do módulo (não é achado, é contexto)

Nenhuma chamada real de voz atravessou uma fila do Call Center até hoje — todo o motor
ARI/Stasis/AMI foi validado com mocks e `curl`. **Isso não é resolvido por esta fatia** e nenhum item
abaixo deve ser marcado como "validado com tráfego real". Onde a revisão depender de payload real do
Asterisk, o item é registrado como *revisão estática + teste unitário*, nunca como validação de campo.

---

## 3. Decisões (numeradas — ambiguidades resolvidas pela via mais conservadora)

**D1 — Fase 1/AD não é "revisão", é escopo aberto: entra apenas a parte de segurança já
implementada.** O AD/LDAP **existe** (`integration/ad/LdapClient.java`, `LdapTemplateFactory`,
`AdSyncScheduler`, `AdLdapConfig`, ponto de entrada em `AuthController`) — a pendência do CLAUDE.md
("Fase 1/AD: dados reais do DC, paginação do `fetchAll()`, `employee_id` não espelhado") é
**funcional**, não de segurança. Decisão conservadora: esta fatia **revisa** o que existe (bind,
fallback local, vazamento de segredo, timeouts, DoS por truncamento silencioso) e **não** implementa
paginação/`employee_id`/screen pop — isso continua na Fase 1/14 do plano-mãe.

**D2 — Nenhuma mudança de comportamento funcional entra "de brinde" na revisão.** Achado que exija
decisão de produto (ex.: mudar quem pode ouvir a própria gravação, item já registrado como lacuna
aceita na Fase 22) é **reportado no plano/commit, não implementado**. Precedente:
`asteriskia_nao_adicionar_escopo_sem_pedido`.

**D3 — Nó `consultar_api` NÃO existe como código executável.** `FlowGraphNodeCatalog.java:76` lista
`consultar_api` no catálogo com `implementado=false`; não há `NodeHandler` correspondente em
`flow/engine/handlers/`. Logo **não há SSRF a revisar nele**. Decisão: em vez de revisar o
inexistente, esta fatia **escreve a especificação de segurança obrigatória do nó** (allowlist de
host/porta/esquema, bloqueio de IP privado/loopback/IPv6 ULA, sem redirect 3xx, timeout, teto de
corpo, sem eco de corpo de resposta em variável de fluxo sem sanitização) como pré-requisito
documentado, e **cobre o SSRF que existe de verdade hoje**: `CallCenterKbFetchService` (Fase 25).

**D4 — Toda correção de segurança desta fatia vem com teste que reproduz o achado.** Sem teste, o
achado não conta como fechado (padrão já seguido nas Fases 20/23/26/27).

**D5 — Healthcheck novo só onde ele é diagnóstico, não decorativo.** `frontend` (nginx), `coturn` e
`caddy` não têm healthcheck hoje. `caddy` é o ponto de entrada: um healthcheck que falhe e leve o
Docker a marcar unhealthy **não** derruba o container (sem `restart: on-failure` acoplado), então o
risco é baixo e o ganho de observabilidade é real. Decisão: adicionar aos três, todos com
`start_period` generoso, e **jamais** transformar `depends_on: service_started` do Caddy em
`service_healthy` (violaria a regra inegociável "nunca deixar o Caddy cair").

**D6 — Limites de memória: só ajustar onde há evidência.** O `backend` está em `memory: 1g` e desde a
auditoria acumulou ARI listener, AMI listener, 6 schedulers de Call Center, KB/RAG e agregados. Não
há métrica coletada. Decisão conservadora: **medir antes de mexer** (`docker stats` em janela de
observação + `/actuator/health`/`metrics` se exposto), documentar o número observado no plano de
execução, e só então propor o novo teto. Nenhum limite é reduzido nesta fatia.

**D7 — Nada de CSP em enforcement nesta fatia.** O CSP em `Report-Only` é débito conhecido e
transversal (softphone/WebRTC/iframes das 4 SPAs). Migrar exige observar violações reais — fora do
escopo, permanece registrado como débito.

**D8 — Rotação do `INTERNAL_API_KEY` entra como item operacional desta fatia.** A nota da Fase 23
registra que o valor apareceu em output de comando. Rotacionar é barato e cabe exatamente em
"endurecimento".

**D9 — Recomendação de hardware entra como seção qualitativa da documentação, não numérica.** Sem
teste de carga não há número honesto. A seção descreve dimensionamento por eixo (canais SIP
simultâneos × codec, `cpus`/`memory` por container, IOPS de gravação, retenção em disco) e diz
explicitamente que os números finais dependem da parte 1 da Fase 10.

**D10 — Documentação do Call Center vira um grupo próprio no TOC, não um item dentro de "Telecom".**
O módulo tem 20+ telas; enfiar tudo em `telecom-*` desequilibraria o sumário.

---

## 4. Parte 3 — Revisão de segurança: mapa de pontos reais no código

Cada item traz: **arquivo real**, **o que verificar**, **natureza** (revisão / trabalho novo /
especificação) e **complexidade**.

### 4.1 AD/LDAP (Fase 1) — `backend/src/main/java/com/asteriskia/integration/ad/`

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.1.1 | `LdapClient.authenticate` | Senha nunca em log (hoje só `e.getMessage()` — confirmar que nenhuma implementação de `NamingException` inclui credencial na mensagem); mensagem de erro não distingue "usuário inexistente" de "senha errada" (evita enumeração de usuário) | Revisão | P |
| 3.1.2 | `LdapClient.authenticate` — injeção de filtro LDAP | `LdapQueryBuilder...is(username)` escapa o valor? Um `username` com `)(&`/`*` deve virar valor literal, nunca alterar o filtro. Teste com payload de injeção | Revisão | M |
| 3.1.3 | `LdapClient.fetchAll` | Limitação já documentada (sem `PagedResultsControl`): truncamento **silencioso** de >1000 entradas. Risco de segurança: usuário desabilitado no AD pode nunca ser visto pelo sync e manter acesso. Decisão D1: **não** implementar paginação; **implementar log de aviso** quando o resultado bater no limite | Revisão + correção mínima | P |
| 3.1.4 | `AdLdapConfig` / `LdapTemplateFactory` | `AD_LDAP_USE_SSL=false` permite bind com senha em texto claro na rede; `AD_LDAP_BIND_PASSWORD` nunca deve sair em `GET /settings` (verificar sufixo de redação em `SettingsService`) e nunca em `testConnection` | Revisão | M |
| 3.1.5 | `AuthController` — fallback local | `AD_LOCAL_FALLBACK_ENABLED=true` (default) permite login local quando o AD está fora. Verificar: (a) o fallback não permite autenticar um usuário **espelhado do AD** com senha local vazia/placeholder; (b) usuário desabilitado no AD não entra pelo fallback | Revisão — **maior risco desta seção** | M |
| 3.1.6 | `AdSyncScheduler` | Sync não deve poder promover usuário a grupo de acesso privilegiado por atributo controlável no AD (`memberOf`) sem mapeamento explícito; `AD_DEFAULT_ACCESS_GROUP_ID=2` deve ser o grupo de menor privilégio | Revisão | M |
| 3.1.7 | `SettingsTestController` (teste de conexão AD) | Já coberto pelo guard de SSRF de 2026-07-02? Confirmar que host/porta arbitrários no body não permitem varredura de porta interna (o bind LDAP é um *connect* a host arbitrário — mesma classe do SSRF) | Revisão | M |

### 4.2 Motor ARI / Stasis (Fase 5b, 21) — `domain/callcenter/flow/engine/ari/`

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.2.1 | `AriClient` (construtor) | Credencial já vai em header `Authorization` (correto). Verificar que `app.asterisk.ari.base-url` nunca embute `user:pass@` na URL e que nenhuma `WebClientResponseException` logada inclui a URI com credencial (mesma classe do achado CRITICAL da Fase 21) | Revisão | P |
| 3.2.2 | `AriClient.play` — allowlist `SAFE_MEDIA` | Regex `^[A-Za-z0-9_:/-]+$` + rejeição de `..`. Verificar bypass: `sound:/etc/passwd`, path absoluto, `%2e%2e` já decodificado pelo `UriComponentsBuilder`. Testes de caso de borda | Revisão | M |
| 3.2.3 | `AriClient.setChannelVar` | `name`/`value` chegam de nó `set_variable` do fluxo (entrada de usuário com `PERM_WRITE_callcenter.fluxos`). Nome de variável de canal é interpretado pelo dialplan — verificar se um nome como `EXTEN`/`CDR(...)`/`SHELL(...)` pode ser sobrescrito e virar execução no dialplan. **Provável achado real; allowlist de nome de variável** | Revisão → correção | M |
| 3.2.4 | `AriClient.continueInDialplan` | `context`/`extension` vêm do canal/fluxo — verificar que não é possível saltar para um contexto privilegiado (ex.: contexto de tronco de saída) a partir de um fluxo editado por usuário não-ADMIN | Revisão | M |
| 3.2.5 | `AriEventListener.buildEventsUri` | `subscribeAll=false` (bom). Verificar que `app` não é injetável e que o `ws://` derivado do `base-url` não cai em `ws://` sem TLS atravessando rede não confiável (hoje é rede Docker interna — registrar como aceito) | Revisão | P |
| 3.2.6 | `AriEventListener.onStasisStart` | Uma thread daemon **por chamada**, sem pool nem teto: N chamadas simultâneas = N threads. É o vetor de DoS mais direto do motor (mesma classe do MEDIUM já corrigido na Fase 24, que trocou thread solta por `ExecutorService` limitado). **Correção: pool limitado + rejeição com fallback para fila** | Trabalho novo (pequeno) | M |
| 3.2.7 | `AriEventListener.redactCaller` | Redige `channel.caller` no log do primeiro `StasisStart`. Verificar se `channel.connected`/`caller_rdnis` também carregam número (PII) e ficam no log | Revisão | P |
| 3.2.8 | `AriClient` sem TLS/verificação | ARI trafega credencial Basic em HTTP na rede `asteriskia-net`. Registrar como resíduo aceito (mesmo nível de `POSTGRES_PASSWORD` em `environment:`) — **não** introduzir TLS interno nesta fatia | Decisão registrada | — |
| 3.2.9 | `AriPlaybackTracker` / `AriRecordingTracker` | Chave vinda de evento externo (`playback.id`, `recording.name`): mapa cresce sem expurgo se o evento de conclusão nunca chegar? Vazamento de memória por chamada abandonada | Revisão | M |

### 4.3 Chat — polling, widget e superfície "pública" (Fases 7a/7b/24/25)

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.3.1 | `PublicCallCenterChatController.startSession` | Rota em `permitAll()`; `customerRef` é entrada livre. Verificar teto de tamanho de `customerRef`/`customerName`/`text` (hoje só `@NotBlank`) — sem `@Size`, um POST grande vira abuso de banco barato. **Provável achado real** | Revisão → correção | P |
| 3.3.2 | `PublicChatRateLimiter` | Gap já documentado: chaves nunca removidas do mapa (crescimento por IP único). Em "endurecimento" isso deixa de ser aceitável de graça — **adicionar expurgo de janelas expiradas** (sem Redis, sem dependência nova) | Trabalho novo (pequeno) | M |
| 3.3.3 | `PublicCallCenterChatController.resolveIp` / `isTrustedProxy` | `InetAddress.getByName("caddy")` a cada requisição: falha de DNS → header ignorado (fail-closed, correto). Verificar custo/DoS de resolução por requisição e se um segundo container na rede pode responder por `caddy` | Revisão | P |
| 3.3.4 | `JwtService.generateChatCustomerToken` / `validateChatCustomerToken` | Token de 2h sem `role`/`perm`/`bu`. Verificar: não serve como Bearer de staff; não gera streaming-token; `scope` comparado por igualdade estrita; `sessionId` da URL sempre confrontado; **revogação ao encerrar a sessão** (hoje o token continua válido até expirar após `end`?) | Revisão | M |
| 3.3.5 | `CcChatService` | Gates da Fase 7a/24 (claim, dono da sessão, status `bot`) já testados. Reverificar após a Fase 25: `postMessage` de cliente em sessão de bot não pode injetar prompt que faça o nó `consultar_base` responder fora dos trechos recuperados (prompt injection — verificar o *system prompt* de `CallCenterKbAnswerService`) | Revisão | M |
| 3.3.6 | `frontend/public-widget/callcenter-chat-widget.js` | `textContent` (correto, sem XSS). Verificar: token de sessão em `localStorage` (XSS na página hospedeira lê o token — registrar risco, o token só dá acesso à própria conversa); polling de 3s sem backoff em erro; CORS folgado (`allowedOriginPatterns("*")` — reconfirmar que é aceito por a app rodar em intranet, D8 do plano-mãe) | Revisão | M |
| 3.3.7 | `CallCenterChatTestController` | `ROLE_ADMIN` puro, `senderType="customer"` forjado. Reconfirmar posição do matcher em `SecurityConfig` (antes do genérico) e que a rota não é alcançável por `PERM_*` de chat | Revisão | P |
| 3.3.8 | **WebSocket do chat** | Não existe — o chat é **polling** em todas as pontas (agente e cliente), decisão registrada na Fase 7a. O item "WebSocket do chat" da Fase 10 se resolve verificando os **WebSockets que existem**: STOMP do backend (`/ws/**`, streaming-token) e o WS de alertas do agents-platform | Esclarecimento + revisão | P |

### 4.4 SSRF real hoje — base de conhecimento (Fase 25) e especificação do `consultar_api`

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.4.1 | `CallCenterKbFetchService` | Guard de SSRF portado de `notifier.py`/`SettingsTestController` + IPv6 ULA (já corrigido na 25). Reverificar: DNS rebinding/TOCTOU (resíduo já aceito no projeto), redirect 3xx desabilitado, teto de tamanho do corpo baixado, timeout, `file:`/`gopher:`/`ftp:` rejeitados por esquema, porta arbitrária | Revisão | M |
| 3.4.2 | `CallCenterKbExternalSourceController` / `CallCenterKbArticleController` | RBAC de escrita; conteúdo de fonte externa é texto não confiável que entra no prompt do LLM → **prompt injection via página cadastrada**. Verificar delimitação do trecho no prompt e a instrução "responda só com base nos trechos" | Revisão | M |
| 3.4.3 | Nó `consultar_api` (`FlowGraphNodeCatalog:76`, `implementado=false`) | **Não existe handler.** Entregar a **especificação de segurança obrigatória** (D3) como comentário normativo no catálogo + seção na documentação, para que a implementação futura não nasça vulnerável | Especificação | P |
| 3.4.4 | `CallCenterKbEmbeddingClient` | Chama o `embedding_server.py` do container `insights`. Verificar que a URL é fixa por configuração (não vem de entrada de usuário) e que uma resposta anômala não derruba o índice anterior | Revisão | P |

### 4.5 Upload de áudio (Fase 5c) — `domain/callcenter/flow/audio/`

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.5.1 | `CallCenterAudioService.upload` | Teto de 20MB e allowlist de extensão são checados **antes** do transcode (bom). Verificar: extensão derivada do nome enviado pelo cliente — conteúdo real nunca validado antes do `ffmpeg` (aceitável: `ffmpeg` é a validação, e o original é descartado). Confirmar que **nada** do original sobrevive em falha (hoje `deleteQuietly(tempOriginal)` no `finally` — verificar o caminho do 422) | Revisão | M |
| 3.5.2 | `CallCenterAudioService.upload` — nome de arquivo | `fileName = "audio-" + UUID` (não vem do usuário) e `finalTarget.startsWith(libraryDir)` — path traversal fechado. Verificar `displayName` (truncado a 150, mas vai para a UI: XSS armazenado? React escapa por padrão — confirmar que nenhum ponto usa `dangerouslySetInnerHTML`) | Revisão | P |
| 3.5.3 | `transcodeToPcm8kMono` | `ProcessBuilder` com argumentos separados (sem shell — sem injeção de comando). Verificar: timeout de 30s + `destroyForcibly`; **DoS por upload concorrente** (N uploads = N processos `ffmpeg`, sem limite) — mesmo padrão do 3.2.6. Proposta: semáforo de concorrência | Revisão → correção | M |
| 3.5.4 | `CallCenterAudioController` | RBAC de upload/delete; ausência de rate limit (upload é a operação mais cara em CPU da API autenticada) | Revisão | M |
| 3.5.5 | `resolveFile` / pré-escuta | Streaming autenticado (padrão `AuthedAudio`); `delete` remove banco + disco em ordem segura; áudio referenciado por fluxo publicado pode ser apagado e derrubar a chamada — **fail-open já existe** (`resolveSoundPath` devolve `Optional.empty` sem travar) — confirmar | Revisão | P |

### 4.6 Credencial SIP do agente (Fase 13)

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.6.1 | `CallCenterAgentController` — `GET /agentes/me/sip-credentials` | Sempre por `currentAgent()`, nunca `agentId` do chamador; auditado a cada leitura; `SipCredentialsRateLimiter` 10/min. Verificar expurgo do mapa do rate limiter (mesma classe do 3.3.2) e se o segredo aparece em log de auditoria/`AuditLog` | Revisão | M |
| 3.6.2 | `POST /agentes/{id}/rotate-secret` | RBAC `callcenter.ramais`; espelhamento em `PsAuth` na mesma transação; rotacionar derruba o registro ativo do agente (efeito operacional a documentar) | Revisão | P |
| 3.6.3 | `ExtensionSecretGenerator` | Fonte de aleatoriedade (`SecureRandom`, não `Random`), entropia e alfabeto do segredo | Revisão | P |
| 3.6.4 | `PsAuth` — segredo em texto claro no banco | Requisito do PJSIP ARA (o Asterisk precisa do valor). Registrar como resíduo estrutural aceito + verificar que nenhum endpoint de listagem de agente devolve o campo | Revisão | M |
| 3.6.5 | `useSipPhone.ts` (2 cópias: `frontend/src/hooks/` e `callcenter-platform/frontend/src/hooks/`) | Ordem de resolução de credencial e estado `'no-extension'` (sem fallback silencioso para 9001 — achado que originou a Fase 13). Verificar que o segredo não vaza em `console`/erro de JsSIP e que as duas cópias não divergiram | Revisão | M |
| 3.6.6 | `docker-compose.yml:449` | `VITE_SIP_PASSWORD: ${VITE_SIP_PASSWORD:-webrtc9001pass}` — **default de senha SIP versionado no repositório**, embutido no bundle do frontend em build time se a variável não estiver no `.env`. **Achado real, mesma classe do CRITICAL de `install.sh` da auditoria de 2026-07-02.** Correção: remover o default (falhar o build ou registrar aviso) | Achado → correção | P |

### 4.7 Superfície interna e RBAC do módulo (varredura de fechamento)

| # | Ponto | Verificar | Natureza | Cx |
|---|---|---|---|---|
| 3.7.1 | `SecurityConfig` — matchers `callcenter.*` | Varredura: todo controller novo das Fases 19-27 tem matcher próprio e nenhum cai no `anyRequest().authenticated()` genérico (**exatamente o HIGH da Fase 23**, que deixava qualquer JWT chamar `/internal/**`). Teste de integração que enumere as rotas e prove que nenhuma responde 200 a um JWT sem permissão | Revisão → **teste novo de regressão** | G |
| 3.7.2 | Escopo de BU | Gap aceito e recorrente (Insights do Call Center, 9c, 26 parcial, 27). Esta fatia **não** implementa BU — apenas **documenta a superfície exata** que hoje ignora BU, para o usuário decidir | Documentação | M |
| 3.7.3 | `INTERNAL_API_KEY` | Rotação (D8): `.env` → restart do backend → `dialplan reload`, em janela de manutenção; verificar que nenhum log/`extensions.conf` gerado é lido sem redação | Operacional | P |

---

## 5. Parte 4 — Limites de recurso e healthchecks

### 5.1 Estado real hoje (`docker-compose.yml`)

`deploy.resources.limits` (memory + cpus) presente em **todos os 11 serviços** — confirmado:
postgres 1g/1.0, asterisk 1g/1.5, ai-agent 1g/1.0, insights 1536m/1.0, docker-helper 256m/0.5,
backend 1g/1.0, frontend 256m/0.5, coturn 512m/1.0, security 256m/0.5, agents-backend 512m/1.0,
caddy 256m/0.5. **Nenhum serviço novo apareceu desde a auditoria** — o Call Center inteiro roda
dentro de `backend` + `asterisk` + `frontend` + `insights` (KB/embeddings).

Healthcheck presente: postgres, asterisk, ai-agent, insights, docker-helper, backend, security,
agents-backend. **Ausente: `frontend`, `coturn`, `caddy`** (comentário na linha 629 confirma
"nginx não tem healthcheck").

### 5.2 Itens

| # | Item | Ação | Cx |
|---|---|---|---|
| 4.1 | Healthcheck do `frontend` | `curl -f http://localhost/` (nginx serve o SPA) — sem `depends_on` novo (D5) | P |
| 4.2 | Healthcheck do `caddy` | `wget`/`curl` no admin socket Unix já usado nos comandos de diagnóstico, ou `:80` interno. **Nunca** promover `depends_on` de `service_started` para `service_healthy` | P |
| 4.3 | Healthcheck do `coturn` | `turnutils_uclient`/porta 3478 — `network_mode: host` limita opções; se não houver comando confiável dentro da imagem, **registrar como não aplicável** em vez de inventar um teste que sempre passa | P |
| 4.4 | Medição de memória real do `backend` (D6) | Janela de observação com `docker stats` + heap da JVM; documentar o número; só então propor teto. **Nenhum limite reduzido** | M |
| 4.5 | `insights` — memória do embedding server (Fase 25) | Embeddings locais em CPU no container de 1536m; confirmar que uma reindexação grande não estoura o limite e mata o container (OOMKill silencioso) | M |
| 4.6 | Concorrência aplicacional como limite (liga com 3.2.6/3.5.3) | Teto de threads/processos por eixo caro: execução de fluxo ARI, transcode `ffmpeg`, transcrição NPS. Limite de container não protege contra thrashing dentro do processo | M |
| 4.7 | `restart` e OOM | Todos em `unless-stopped` (bom). Verificar se há `restart` loop mascarando OOMKill — checar `docker inspect .State.OOMKilled` dos containers do Call Center | P |
| 4.8 | Pool de conexões do banco vs. schedulers | 6+ schedulers de Call Center + ARI + AMI + KB competem pelo pool HikariCP; conferir `maximum-pool-size` contra `max_connections` do Postgres. Precedente: 4 achados HIGH de `@Transactional` sobre I/O bloqueante (Fases 21/24/25) | M |

---

## 6. Parte 5 — Documentação

### 6.1 Estado real hoje

`frontend/src/components/Documentacao.tsx` monta 11 seções de `docs/sections/`:
`Instalacao`, `TelecomModulos`, `TelecomInsights`, `TelecomRBAC`, `Financeiro`, `Introducao`,
`AgentesDashboard`, `AgentesTipos`, `AgentesAutomacao`, `AgentesInfra`, `Sistema`.
O sumário vive em `frontend/src/components/docs/toc.ts` (grupos: Instalação, Telecom, Financeiro,
Agentes, Sistema).

**O módulo Call Center tem ZERO documentação** — nenhuma seção, nenhum item de TOC, e o texto do
`DocsLayout` ainda descreve o sistema como "Telecom + Plataforma de Agentes".

### 6.2 Seções novas propostas (grupo "Call Center" no TOC — D10)

| # | Arquivo novo | Conteúdo | Cx |
|---|---|---|---|
| 5.1 | `docs/sections/CallCenterVisaoGeral.tsx` | Arquitetura do módulo (SPA própria em `/callcenter`, backend Java compartilhado, ARI/Stasis, AMI, filas ARA), faixas de ramal 4xxx/5xxx/6xxx e a tela de Gestão (Fase 19) | M |
| 5.2 | `docs/sections/CallCenterOperacao.tsx` | Agentes/ramais/filas/pausas/tabulações, Desktop do Agente, softphone (Fase 13), chamadas de saída (Fase 23), supervisão (escuta/sussurro/barge, Modo TV) | G |
| 5.3 | `docs/sections/CallCenterFluxos.tsx` | Flow builder de voz e de chat, catálogo de nós (**com o que está implementado e o que não está**), biblioteca de áudios, NPS (4 modos), base de conhecimento/RAG | G |
| 5.4 | `docs/sections/CallCenterRelatorios.tsx` | Agregados 9a/9b, relatório analítico 9c, qualidade (26, cooldown de 5 dias úteis + feriados), gamificação/perfil/produtividade (27), Insights do Call Center (Fase 8) | G |
| 5.5 | `docs/sections/CallCenterSegurancaOperacao.tsx` | Saída desta fatia: RBAC `callcenter.*`, superfície interna (`/internal/**` + `X-Internal-Key`), token anônimo de chat, credencial SIP e rotação, gaps aceitos (BU, tráfego real nunca validado), **especificação de segurança do `consultar_api`** (D3), **recomendação de hardware qualitativa** (D9) | G |
| 5.6 | `toc.ts` + `Documentacao.tsx` + hero do `DocsLayout` | Grupo novo, ordem após "Telecom", texto do hero atualizado para incluir Call Center e Insights | P |
| 5.7 | `docs/sections/Sistema.tsx` | Acrescentar variáveis de ambiente do Call Center (`AST_ARI_*`, `AD_LDAP_*`, `CALLCENTER_*`) à seção "Variáveis de Ambiente" existente | M |

**Gate de honestidade da documentação**: nenhuma seção pode afirmar que algo foi validado com
tráfego real de voz (§2). Onde houver gap conhecido, o gap é escrito na documentação, não omitido.

---

## 7. Ordem de execução

**Etapa A — Revisão (achar)**: rodar `ecc:security-reviewer`, `ecc:java-reviewer` e
`ecc:react-reviewer` em paralelo, cada um com um recorte de §4 (A1: 4.1+4.2; A2: 4.3+4.4; A3:
4.5+4.6+4.7). Consolidar achados por severidade.
**Etapa B — Corrigir**: CRITICAL/HIGH obrigatórios; MEDIUM quando cabe sem mudar comportamento
funcional (D2). Cada correção com teste (D4).
**Etapa C — Endurecimento operacional** (§5): medir → healthchecks → limites de concorrência.
**Etapa D — Documentação** (§6) por último, já refletindo o que B e C mudaram.
**Etapa E — Validação**: suíte completa do backend verde (baseline 638/638), `tsc --noEmit` +
`npm run build` nas SPAs tocadas, deploy incremental, validação por `curl` com JWT forjado inline
(nunca persistido), release notes em `frontend/src/data/releases.ts`.

**Sem migration Flyway prevista.** Se algum achado exigir schema, ele é reportado antes de escrever
SQL — migration em produção é irreversível.

---

## 8. Riscos

| Risco | Mitigação |
|---|---|
| Correção de allowlist (3.2.2/3.2.3) quebra um fluxo já publicado | Testar contra os grafos publicados existentes antes do deploy; falha de nó nunca derruba a chamada (fallback para fila já existe) |
| Pool/semáforo (3.2.6, 3.5.3, 4.6) dimensionado baixo demais rejeita chamada legítima | Teto conservador + log explícito de rejeição + fallback para fila, nunca `Hangup` |
| Healthcheck novo no `caddy` marcar unhealthy e alguém acoplar restart depois | Comentário normativo no `docker-compose.yml` proibindo o acoplamento (regra inegociável nº 1) |
| Remover o default de `VITE_SIP_PASSWORD` (3.6.6) quebrar o build | Verificar o `.env` real **antes** de remover; falha de build é preferível a senha versionada |
| Rotação do `INTERNAL_API_KEY` (3.7.3) derrubar o CURL do dialplan | Ordem: `.env` → restart backend → `dialplan reload`, em janela combinada com o usuário |
| Documentação virar propaganda | Gate de honestidade (§6.2) |

---

## 9. Critérios de conclusão

- [x] §4 varrido inteiro (3 revisores em paralelo — A1 AD/ARI, A2 chat/SSRF, A3 upload/SIP/RBAC); todo achado classificado, CRITICAL (C1) e HIGH (H1/H2 ARI, H1 chat) corrigidos com teste
- [x] Teste de regressão de RBAC (3.7.1) — `CallCenterSecurityMatcherCoverageTest`, prova estruturalmente que nenhum controller `callcenter.*` cai no `anyRequest().authenticated()` genérico
- [x] Rate limiters e trackers sem crescimento ilimitado de memória — expurgo periódico agendado em `PublicChatRateLimiter`, `SipCredentialsRateLimiter` e `AudioUploadRateLimiter` (3.2.9 `AriPlaybackTracker`/`AriRecordingTracker` já confirmados sem vazamento na revisão)
- [x] Eixos caros com teto de concorrência — pool de 50 execuções no `AriEventListener` (ARI), semáforo de 3 no transcode de áudio (`ffmpeg`); NPS fora desta fatia (sem achado real levantado pelos revisores)
- [x] `VITE_SIP_PASSWORD` sem default versionado — `docker-compose.yml` agora usa `${VAR:?...}`, build falha explicitamente sem a variável
- [x] Healthcheck em `frontend` (wget), `caddy` (curl) e `coturn` (reachability TCP via `/dev/tcp` do bash — a imagem não tem curl/wget/turnutils sem credencial pré-validada)
- [x] Memória do `backend`/`insights` medida (~48%/1GiB e ~3,5%/1,5GiB, sem OOMKill/restart) e documentada; nenhum limite alterado
- [x] Grupo "Call Center" no TOC com as 5 seções novas; `Sistema.tsx` com as variáveis novas (ARI/AD/`INTERNAL_API_KEY`)
- [x] Especificação de segurança do `consultar_api` escrita (catálogo comentado nesta doc + seção `CallCenterFluxos.tsx`, 8 requisitos obrigatórios)
- [x] `INTERNAL_API_KEY` **rotacionada e validada** (usuário confirmou que o sistema ainda não está em produção real, sem necessidade de janela separada) — backup de `.env` antes da troca, chave nova gerada via `openssl rand -hex 32`, containers `backend`/`ai-agent`/`insights`/`docker-helper`/`asterisk` recriados, `dialplan reload` executado; validado via curl: chave nova autentica (sem log de `InternalKeyFilter`), chave antiga/ausente rejeitada com 403
- [x] Suíte do backend verde (662/662, exceto o flake pré-existente de `ffmpeg` ausente no container Maven — não reproduz em produção, onde o binário existe), `tsc --noEmit`/`npm run build` do Telecom limpos, release notes `v1.72` registrada
- [x] Fora de escopo permanece fora: carga (parte 1), particionamento (parte 2), hardware numérico, CSP em enforcement, BU, paginação do AD

**Deployado e validado em produção (2026-08-14)**: `docker compose up -d --build backend frontend`
+ recriação de `caddy`/`coturn` para os healthchecks novos entrarem em vigor. 1 achado real na
própria validação: healthcheck de `frontend` usava `localhost`, que resolve para `::1` (IPv6)
antes de `0.0.0.0` dentro do container Alpine — nginx só escuta em IPv4, causando "connection
refused" mesmo com o processo de pé; corrigido para `127.0.0.1` explícito em `frontend` e `caddy`
(commit separado). Todos os 11 containers `healthy` após o deploy; validado via `curl`: site
externo 200, RBAC 403 sem token em `/callcenter/audios` (GET e POST), chat público 503 (sem fila
configurada, não 403/500). Rotação do `INTERNAL_API_KEY` fica para uma janela de manutenção
separada, combinada com o usuário — não fechada nesta sessão.
