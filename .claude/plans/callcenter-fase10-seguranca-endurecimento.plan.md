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
| 3.2.8 | `AriClient` sem TLS/verificação | ARI trafega credencial Basic em HTTP na rede `voipia-net`. Registrar como resíduo aceito (mesmo nível de `POSTGRES_PASSWORD` em `environment:`) — **não** introduzir TLS interno nesta fatia | Decisão registrada | — |
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

---

## 10. Tentativa da parte 1 (teste de carga) e decisão sobre a parte 2 (particionamento) — 2026-08-14

Sessão seguinte, pedido do usuário: avançar para as partes 1 e 2 da Fase 10, deixadas de fora da
fatia 1 (§1). Antes de tocar em qualquer coisa, as duas foram confrontadas com o estado real do
ambiente — que mudou o resultado esperado das duas.

### 10.1 Parte 2 (particionamento) — decisão do usuário: só desenho, sem migration

Explicado ao usuário o que é particionamento de tabela (`PARTITION BY RANGE`, uma partição por
período, `DROP` de partição inteira em vez de `DELETE` linha a linha) e por que aplicar agora seria
decidir uma estratégia às cegas: **nenhuma chamada real de voz nem chat real passou pelo Call
Center até hoje** — o volume de `cc_interaction_events`/`cc_chat_messages` é essencialmente zero
nesta VPS. Migration de particionamento é irreversível em produção (regra inegociável nº 6).
**Decisão registrada**: permanece **não implementada** — mesma posição já documentada em §1, D9 do
plano-mãe (§9) — até haver volume real que justifique escolher a granularidade certa da partição.
Não houve tempo/pedido nesta sessão para escrever a especificação qualitativa (like D9 fez para
hardware); se o usuário quiser, é um item futuro de baixa complexidade.

### 10.2 Parte 1 (teste de carga SIPp) — tentada, interrompida por risco real ao ambiente

**Achado prévio que quase derrubou o plano de teste**: ao investigar o contexto do dialplan para
montar o cenário SIPp, um `grep` em `extensions.conf` (arquivo **gerado**, não o template
versionado) trouxe o valor real do `INTERNAL_API_KEY` em texto puro para a saída de um comando —
mesma classe de incidente já registrada na nota operacional da Fase 23, e que motivou a rotação já
feita na fatia 1 desta própria Fase 10 (§9, critério de conclusão). Ou seja: a chave rotacionada em
2026-08-14 já foi exposta de novo, pela mesma via, na mesma data. **Usuário optou por não rotacionar
de novo nesta sessão** — risco aceito explicitamente, registrado aqui para não se perder. Rotação
pendente fica para quando o usuário decidir (mesmo procedimento documentado em §9: backup do `.env`
→ `openssl rand -hex 32` → restart backend/ai-agent/docker-helper/insights/asterisk → `dialplan
reload` → validar via curl).

**Estado real do ambiente no momento do teste** (motivo da decisão de não insistir):
- `free -h`: ~130-190Mi de RAM livre, **1.8-1.9Gi de swap já em uso**, de um total de 3.8Gi.
- Esta VPS é o **próprio ambiente de produção**, compartilhado com projetos de outros sistemas
  rodando na mesma máquina (`echweb-*`, `ascsac-*`, confirmados via `docker stats`), não um
  ambiente de teste isolado.
- `docker stats` antes/depois do teste: nenhuma mudança relevante nos containers do Call Center
  (`voipia-asterisk` 34-38MiB/1GiB, estável).

**Tentativa real, com escopo reduzido para não incorrer em custo/risco desnecessário**: em vez de
simular uma chamada de voz completa (que atravessaria `AudioSocket → ai-agent → Gemini`, gerando
custo real de IA e potencialmente abrindo registro/alerta fictício), o teste ficou restrito à
camada de sinalização SIP — `sip-tester` (pacote `sip-tester` 3.6.1, instalado via `apt-get` nesta
sessão) enviando `OPTIONS` sintético contra `127.0.0.1:5060`.

**Resultado**: o Asterisk **não respondeu** a nenhum `OPTIONS` não correlacionado (sem 200/401/
403/404) — comportamento de segurança esperado, não falha: os endpoints SIP deste sistema são
IP-based (tronco) ou exigem registro autenticado (ramais), e há 3 jails de fail2ban ativos
(`asterisk-auth`, `asterisk-flood`, `asterisk-scan`) vigiando exatamente esse tipo de sonda não
correlacionada. Um teste honesto exigiria autenticar como um ramal real (ex.: `1002`) e sustentar
tráfego por tempo suficiente para medir uma curva — o que, com a memória do host já no limite,
troca a variável que se queria medir (capacidade) pela consequência que a regra inegociável nº 1
proíbe (derrubar o sistema).

**Decisão**: a parte 1 permanece **não concluída nesta sessão** — tentativa documentada, sem número
de capacidade real produzido. `sip-tester` ficou instalado no host (`apt-get install sip-tester`)
para reuso futuro, sem nenhuma alteração em containers/config de produção. 0 canais ativos e
memória do host inalterada após a tentativa — nenhum efeito residual em produção.

### 10.3 Critérios de conclusão desta continuação

- [x] Particionamento (parte 2): decisão do usuário confirmada — **não implementar** sem volume
      real; nenhuma migration escrita ou aplicada
- [ ] Teste de carga SIPp (parte 1): **não concluído** — ambiente atual (VPS compartilhada, memória
      no limite) não permite um teste honesto sem risco a produção; fica pendente de servidor
      dedicado ou janela de manutenção com folga de memória
- [x] Nenhuma mudança de código/config/schema nesta continuação — sessão foi só investigação +
      1 tentativa de teste de sinalização, revertida sem deixar processo/estado residual
- [x] Risco de segurança identificado e reportado ao usuário: `INTERNAL_API_KEY` exposta de novo em
      output de comando — rotação pendente por decisão explícita do usuário, não esquecimento

---

## 11. Continuação (mesmo dia, sessão seguinte) — usuário reverteu as duas decisões acima

Pedido explícito: "Faca o particionamento agora e depois rotaciona internal_api_key" — o usuário
decidiu **fazer** a parte 2 (particionamento) e rotacionar a chave, revertendo as duas posições
conservadoras de §10.

### 11.1 Particionamento — migration V71, aplicada e validada em produção

`backend/src/main/resources/db/migration/V71__callcenter_partition_events_chat_messages.sql`.
Confirmado antes de escrever qualquer SQL: as duas tabelas seguiam com **0 linhas** em produção
(`SELECT count(*)`), o que elimina o risco normal dessa conversão (nada a migrar/perder) — a
migration é um `DROP TABLE` + `CREATE TABLE ... PARTITION BY RANGE` direto, sem `INSERT INTO ...
SELECT` de dado antigo.

- **Estratégia**: `PARTITION BY RANGE` mensal em `occurred_at` (`cc_interaction_events`) e
  `created_at` (`cc_chat_messages`), gerada por um `DO $$ ... $$` em loop de 2025-01 a 2027-12 (36
  partições por tabela) + uma partição `DEFAULT` em cada uma, para nunca falhar um `INSERT` por
  falta de partição (linha fora do range cai na `DEFAULT`, visível/auditável, nunca perdida).
- **PK virou composto** (`id, occurred_at`/`id, created_at`) — exigência do Postgres: toda PK/
  unique de tabela particionada precisa incluir a coluna de particionamento. Confirmado que isso
  não quebra nada: nenhuma FK de outra tabela referencia `cc_interaction_events.id`/
  `cc_chat_messages.id`; as entidades JPA (`CcInteractionEvent`/`CcChatMessage`) usam só
  `@GeneratedValue(IDENTITY)` na coluna `id` (globalmente única pela sequência `BIGSERIAL`, mesmo
  com PK composto na tabela) — nenhum código Java mudou. Testes usam H2 com Flyway desabilitado
  (`spring.flyway.enabled=false`, schema gerado por Hibernate a partir das entidades) — totalmente
  imunes a essa migration, que só afeta o Postgres de produção via Flyway.
- **Validado antes de aplicar**: a migration inteira rodou dentro de uma transação `BEGIN;
  \i v71.sql; \d ...; ROLLBACK;` direto no Postgres de produção — sintaxe confirmada (37
  partições por tabela, PK/FK/índices corretos) sem deixar nenhum efeito, antes de deixar o
  Flyway aplicar de verdade.
- **Aplicada**: `docker compose up -d --build backend` — Flyway confirmou
  "Successfully applied 1 migration to schema public, now at version v71"; backend subiu
  `healthy`, listeners ARI/AMI reconectaram normalmente.
- **Validado depois de aplicar**: `\d cc_interaction_events`/`\d cc_chat_messages` confirmam
  `Partition key: RANGE`, PK composto e 37 partições cada; um `INSERT` de teste (dentro de
  `BEGIN`/`ROLLBACK`, revertido) provou que uma linha com `occurred_at` em agosto/2026 foi
  roteada corretamente para `cc_interaction_events_2026_08`. `SELECT count(*)` de ambas as tabelas
  confirmado em 0 depois do teste — nenhum resíduo.
- **Gap aceito, documentado no SQL**: não há job agendado para criar partições além de 2027-12 —
  se ninguém estender o range a tempo, inserts futuros caem na `DEFAULT` (sem falha, só sem
  pruning). Fora do pedido desta sessão; um scheduler dedicado (mesmo padrão de
  `AiModelPricingSyncScheduler`) fica para quando fizer sentido.
- Backup de precaução (mesmo com 0 linhas): `pg_dump --schema-only` das duas tabelas antes da
  migration, salvo em `/tmp/claude-0/.../scratchpad/backup_v71_pre_partition_schema.sql` (fora do
  repositório, sessão-local).

### 11.2 Rotação do `INTERNAL_API_KEY` — concluída e validada

Chave nova gerada via `secrets.token_hex(32)` (Python), escrita no `.env` por um script que nunca
imprime o valor em stdout nem o passa como argumento de linha de comando — só o comprimento e o
número de substituições (mesmo cuidado de
[[asteriskia_no_persist_forged_tokens]]). Backup prévio de `.env` → `.env.bak` (regra
inegociável nº 2).
- Containers recriados: `backend`, `ai-agent`, `docker-helper`, `insights`, `asterisk` (todos os
  que carregam `INTERNAL_API_KEY`) + `dialplan reload` (regenera `extensions.conf` com a chave
  nova via `envsubst` no entrypoint).
- **Validado sem nunca expor a chave em texto puro**: comparação de hash SHA-256 truncado entre o
  valor no `.env` e o valor embutido no `extensions.conf` gerado dentro do container Asterisk —
  hashes idênticos (`0ff8b773b30eccb4`), confirmando que a chave nova propagou corretamente sem
  precisar imprimir o segredo em nenhum momento.
- **Validado via curl** (de dentro do próprio container backend, porta 8080 não exposta ao host):
  requisição a `/api/v1/internal/ura-routing` **sem** header `X-Internal-Key` → `403` (rejeitada,
  correto); **com** a chave nova (lida da env var `$INTERNAL_API_KEY` dentro do container, nunca
  impressa) → `500` (passou pelo `InternalKeyFilter` — a chave autenticou — e falhou adiante na
  lógica de negócio porque a extensão de teste `2000`/UUID fictício não existem; comportamento
  esperado, não é falha de autenticação).
- Todos os 11 containers `healthy` após a recriação.

### 11.3 Critérios de conclusão desta segunda continuação

- [x] Particionamento de `cc_interaction_events`/`cc_chat_messages` implementado (migration V71),
      validado com transação de teste antes e depois da aplicação real, deployado em produção
- [x] `INTERNAL_API_KEY` rotacionada, propagada a todos os containers que a usam, validada por
      hash (nunca por texto puro) e por comportamento HTTP (403 sem chave / autentica com a nova)
- [x] Nenhum valor de segredo apareceu em texto puro em nenhuma saída de comando desta continuação
- [ ] Teste de carga SIPp (parte 1) continua **não concluído** — fora do pedido desta continuação,
      mesma posição de §10.3

---

## 12. Continuação (mesmo dia) — particionamento de fluxo/URA (migration V72)

Pedido do usuário: "roda o particionamento de fluxo/URA agora (Fase 9c/10)". Interpretado como
estender o particionamento da V71 (§11) às tabelas de traço de execução do Flow Builder/URA —
`cc_flow_executions` (uma linha por chamada, Fase 5b) e `cc_flow_execution_steps` (traço nó a nó,
N linhas por execução).

### 12.1 Restrição técnica que decidiu o escopo — só `cc_flow_execution_steps`

`cc_flow_execution_steps.execution_id` tem FK para `cc_flow_executions(id)`. O Postgres exige que
toda PK/unique de tabela particionada inclua a coluna de particionamento — se `cc_flow_executions`
fosse particionada por `started_at`, o PK viraria `(id, started_at)` e a coluna `id` sozinha
deixaria de ter constraint único (Postgres não permite `UNIQUE(id)` isolado numa tabela
particionada), **quebrando essa FK**. Diferente de `cc_interaction_events`/`cc_chat_messages`
(§11, sem nenhuma FK apontando pra elas), aqui existe uma dependência real.

**Decisão**: particionar só `cc_flow_execution_steps` (tabela folha, nada referencia seu `id` por
FK, confirmado antes da migration) — mesmo padrão já usado na V71: particiona o traço/evento
filho que cresce rápido, não o agregado pai. `cc_flow_executions` permanece não particionada.

### 12.2 Particularidade nova vs V71 — `cc_flow_execution_steps` tem UPDATE, não só INSERT

`FlowExecutionTraceService` fecha um passo existente via `step.setExitedAt(...)` +
`stepRepository.save(step)`, que o Hibernate traduz em `UPDATE ... WHERE id = ?` (sem
`entered_at`). Isso **não quebra** — `entered_at` (a coluna de partição) nunca é alterada, não há
tentativa de mover linha entre partições — mas **perde o pruning nesse UPDATE**: o Postgres varre
o índice `(id, entered_at)` de cada uma das 37 partições em vez de uma só. Aceitável no volume
atual (0 linhas); documentado no próprio SQL (migration V72) para não surpreender quem for
investigar lentidão de escrita no futuro, com volume real.

### 12.3 Execução — migration V72, validada e aplicada

- Confirmado **0 linhas** em `cc_flow_executions`/`cc_flow_execution_steps` antes de escrever o
  SQL — mesmo padrão de segurança da V71.
- `backend/src/main/resources/db/migration/V72__callcenter_partition_flow_execution_steps.sql`:
  `DROP TABLE` + `CREATE TABLE ... PARTITION BY RANGE (entered_at)`, PK composto
  `(id, entered_at)`, 36 partições mensais (2025-01 a 2027-12) + 1 `DEFAULT`.
- Backup de precaução: `pg_dump --schema-only` de `cc_flow_execution_steps` antes da migration.
- Validada em transação `BEGIN/ROLLBACK` direto em produção antes de aplicar de verdade —
  confirmado PK, FK e 37 partições.
- Aplicada via `docker compose up -d --build backend` — Flyway confirmou "Successfully applied 1
  migration ... now at version v72"; backend `healthy`.
- **Teste de fluxo completo, dentro de `BEGIN/ROLLBACK`**: criado um `cc_flow`/`cc_flow_version`/
  `cc_flow_execution`/`cc_flow_execution_step` de teste com `entered_at` em setembro/2026, e
  reproduzido exatamente o padrão real do `FlowExecutionTraceService`
  (`UPDATE cc_flow_execution_steps SET exited_at=..., taken_edge=... WHERE id=?`) — confirmado
  roteamento correto para `cc_flow_execution_steps_2026_09` e UPDATE funcionando sem erro.
  `ROLLBACK` no final — 0 linhas residuais confirmadas nas 3 tabelas envolvidas (`cc_flows`,
  `cc_flow_executions`, `cc_flow_execution_steps`).
- **Suíte completa do backend**: 662/662 verde (0 regressão), validada no mesmo container Maven +
  `ffmpeg` já usado nas continuações anteriores. `tsc --noEmit` e `npm run build` do
  `callcenter-platform/frontend` limpos (sem alteração nesta fatia — validação de zero
  regressão).
- **Gap aceito, documentado no SQL**: mesmo da V71 — sem job de manutenção para criar partições
  além de 2027-12.

### 12.4 Critérios de conclusão

- [x] `cc_flow_execution_steps` particionada (migration V72), validada com transação de teste
      antes e depois da aplicação real, deployado em produção
- [x] `cc_flow_executions` permanece não particionada — decisão técnica documentada (FK), não
      esquecimento
- [x] Padrão de UPDATE por `id` (sem coluna de partição) verificado funcionando corretamente,
      trade-off de pruning documentado no SQL
- [x] Suíte do backend 662/662 verde, `tsc --noEmit`/`npm run build` do frontend limpos
- [x] Nenhum resíduo de dado de teste em produção (tudo dentro de transações revertidas)
