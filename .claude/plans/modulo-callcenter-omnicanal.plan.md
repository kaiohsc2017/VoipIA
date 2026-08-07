# Plano: Módulo Call Center Omnicanal (Voz + Chat)

**Origem**: pedido livre do usuário (2026-08-06)
**Complexidade**: **Extra-Large** — é um produto, não uma feature. Maior entrega já feita no AsteriskIA.
**Status**: **Fases 0-4 e 6 concluídas, commitadas e deployadas**. **Fase 5 (flow builder) —
sub-fase 5a (dados + editor visual + versionamento) implementada e deployada nesta sessão
(2026-08-07, release notes v1.52)** — detalhamento completo por sub-fase (5a-5f) feito com o
agente planner, decisões P1-P10 aceitas (app Stasis `callcenter`, faixa de ramal `6000-6999`,
grafo JSON nativo do React Flow, catálogo de nós servido pelo backend, simulador como dry-run no
próprio motor, fila via `continueInDialplan`, estado do motor em memória aceito como resíduo).
ARI **confirmado funcionando em runtime** nesta VPS (módulo carregado, `curl` autenticado ao ARI
retornou 200) — a sub-fase 5b (motor de execução real) não parte de zero. Nesta sub-fase **nenhum
tipo de nó do catálogo é executável ainda** (`implementado=false` em todos) — publicar um fluxo
que use qualquer nó é bloqueado pelo backend; isso só muda a partir da 5b. Revisão de segurança
(`ecc:security-reviewer`) e de React (`ecc:react-reviewer`) encontraram e já corrigiram, antes do
commit: 1 **HIGH** real (bypass de escopo por BU em `findVersion`), 1 **MEDIUM** (corrida em
`publish`/`rollback` sem lock — corrigido com `PESSIMISTIC_WRITE` + índice único parcial
`uq_cc_flow_versions_one_published_per_flow`), 1 **LOW** (canal sem allowlist), e no frontend 2
**HIGH** (parse de JSON do rascunho sem try/catch; paleta de nós só por arrastar-e-soltar, sem
alternativa por teclado/clique) + achados MEDIUM de acessibilidade (labels sem `htmlFor`/`id`) e
de consistência (badge "não executável" lido de dado persistido em vez do catálogo vivo — trocado
por `FlowCatalogContext`). Corrigido também, de passagem: o submenu Call Center do **Telecom**
nunca tinha ganhado as abas "Desktop do Agente" (Fase 4) e "Supervisão" (Fase 6) — só existiam na
SPA própria do módulo; ambas adicionadas junto com "Fluxos". Suíte backend: **354/354 verde**.
`tsc -b`/`npm run build` limpos nas duas SPAs (Call Center e Telecom). Sub-fases 5b-5f (motor de
execução ARI, simulador, catálogo avançado, horário/feriados/transbordo, traço de execução) ainda
não iniciadas. Fase 8 (Insights do Call Center) também ainda não iniciada. Decisões D1-D5 e
perguntas abertas respondidas (§13, 2026-08-06).

**Fase 1 (AD/LDAP) — backend implementado e commitado (2026-08-06, commit `aae82f5`)**: bind de
autenticação contra o AD (spring-ldap), espelho local (`ad_users`, migration V45), sincronização
periódica configurável, mapeamento opcional grupo AD → grupo de acesso, tela de configuração em
Settings.tsx (padrão Jira/Zabbix). 272/272 testes verdes. Revisão de segurança pré-commit
(`ecc:security-reviewer`) encontrou e já corrigiu 1 **CRITICAL** (conta local pré-existente podia
ser sequestrada por bind AD com o mesmo username — resolvido com a coluna `app_users.ad_linked`,
só contas provisionadas via AD aceitam esse fallback). 2 limitações **MEDIUM** aceitas e apenas
documentadas em código (não bloqueiam a fase): `ad_group_name` precisa ser o DN completo do grupo,
não o CN simples (sem UI de cadastro ainda — chega na Fase 2); `LdapClient.fetchAll()` não pagina
(limite ~1000 usuários por busca no AD, aceitável no volume-alvo desta fase).
**Deploy validado nesta VPS (2026-08-06)**: `docker compose up -d --build backend frontend`,
migration V45 aplicada (`ad_users`/`ad_sync_runs`/`ad_group_mappings`/`app_users.ad_linked`
confirmados via `\d`), backend e frontend `healthy`. **1 bug real encontrado e corrigido no
deploy**: `spring-boot-starter-data-ldap` autoconfigura um `LdapHealthIndicator` do Actuator que
tenta conectar em `localhost:389` por padrão (sem relação com o AD real, configurado em runtime) —
derrubava o healthcheck do container mesmo com AD desabilitado; corrigido com
`management.health.ldap.enabled=false` (commit `08df7ca`). Login local sem regressão (testado via
curl), rota `/api/v1/ad/**` corretamente protegida (403 sem token).
**Pendente antes de dar a Fase 1 por concluída**: (1) dados reais de conexão do Domain Controller
(host/porta/base DN/conta de serviço) — ainda não levantados, a tela de configuração está pronta
para recebê-los, sem eles não há como validar o bind AD de verdade; (2) validação visual em
navegador da tela de configuração AD em Settings; (3) recomendação de hardware do servidor
dedicado de produção, antes de iniciar a Fase 2.

**Fase 3 (Gravação, retenção e conformidade) — implementada (2026-08-07), pendente commit/deploy**:
migration **V49** (`cc_recordings`, `cc_recording_retention_config`, `cc_recording_disk_alert_config`,
colunas `recording_enabled`/`consent_message_path` em `cc_queues`). Dialplan de `_5XXX` (nos dois
contextos, tronco e WebRTC) grava com `MixMonitor(b)` em `/opt/telecom/gravacao/YYYY/MM/DD/`,
consultando a config de gravação/aviso da fila via CURL (fail-open: falha de rede grava por
padrão); toca o áudio de consentimento antes de enfileirar quando configurado. Ingestão via
`CallCenterRecordingIngestController` (padrão `ura-routing`, só `X-Internal-Key`). Nova aba
"Gravações" na SPA (listagem paginada por fila/período + player `AuthedAudio`), com auditoria de
reprodução (`AuditService`) e streaming defendido contra path traversal (mesmo padrão de
`CallRecordController`). Retenção configurável (padrão 60 meses) com expurgo diário
(`CallCenterRecordingRetentionScheduler`, 03:30) + disparo manual — só remove o registro do banco
se o arquivo físico foi de fato apagado (achado de code review: evita órfão permanente). Alerta de
disco diário via Telegram (`CallCenterDiskAlertScheduler`, 07:00), dedup por dia (não por mês,
diferente do Financeiro — disco pode encher em poucos dias). RBAC: recurso novo
`callcenter.gravacoes` (4 pontos de sincronia). `CallCenterRecordingControlService`
(pause/resume via AMI `MixMonitorMute`) já pronto no backend, sem endpoint/UI ainda — consumidor é
o Desktop do Agente (Fase 4) ou um nó do Flow Builder (Fase 5), escopo confirmado com o usuário.
**Validado nesta sessão**: `mvn test` 306/306 verde (rodado via container `maven:3.9-eclipse-
temurin-21`, sem Maven local no ambiente), `tsc --noEmit` limpo nas duas SPAs (Telecom e
`callcenter-platform`). Release notes **v1.48** registrada. Commitada, deployada e validada em
sessão seguinte (2026-08-07): `docker compose up -d --build backend frontend`, migration V49
confirmada aplicada, 1 bug real corrigido antes do deploy (mensagem de erro genérica ao salvar
retenção/alerta de disco — commits `caad601`/`dd2aba9`, release notes **v1.49**).

**Fase 4 (Estados do agente, interações e tabulação) — implementada e deployada (2026-08-07)**:
migration **V50** (`cc_agent_states`, `cc_dispositions`, `cc_interactions`,
`cc_interaction_events`, `cc_recordings.interaction_id`) + entidade `CcPauseReason` (tabela já
existia desde a V47, sem código até agora). `CallCenterAmiEventListener` — primeira conexão AMI
**event-driven** do projeto (diferente do padrão request/response de `AmiOriginateService`):
thread dedicada, `Events: on`, reconexão automática a cada 5s em caso de queda, interpreta
`QueueCallerJoin`/`AgentConnect`/`AgentComplete`/`QueueCallerAbandon` para criar/atualizar
`cc_interactions` e mover o estado do agente (`CallCenterAgentStateService`, broadcast via
WebSocket STOMP em `/topic/callcenter/agent-states`). Transições manuais (Disponível/Pausa+
motivo/Offline) e automáticas (Em Atendimento/ACW) fecham sempre a linha de estado anterior —
nunca fazem UPDATE no estado em si. Tabulação (`cc_dispositions`) obrigatória para saved do ACW
de volta a Disponível. Nova aba "Desktop do Agente" na SPA (seletor de estado, painel da
interação em curso, formulário de tabulação) — **sem softphone WebRTC embutido e sem screen pop
de dados do AD nesta entrega** (softphone fica para quando o agente precisar discar/atender pela
própria tela; screen pop depende da Fase 1/AD, ainda sem dados reais de conexão do DC). RBAC:
recurso novo `callcenter.desktop` (4 pontos de sincronia). **Não validado contra tráfego real de
fila** — o listener conectou com sucesso ao Asterisk real (`asterisk:5038`, log confirmado), mas
sem uma chamada de teste atravessando uma fila com agente logado não há como confirmar os nomes
exatos de campo que os eventos AMI trazem nesta versão do Asterisk; parsing e lógica de cada
handler têm teste unitário (`AmiEventParserTest`, `CallCenterAgentStateServiceTest`, 11 testes
novos, suíte completa 317/317 verde). Validado via Chrome headless + CDP contra produção: tela
carrega, motivos de pausa/tabulações carregam do backend, nenhuma exceção de console — usuário de
teste sem agente vinculado recebe erro genérico 500 (comportamento intencional e já existente do
`GlobalExceptionHandler` para `IllegalArgumentException` de regra de negócio, não uma regressão
desta entrega). Release notes **v1.50** registrada.

**Fase 6 (Supervisão em tempo real) — implementada e deployada (2026-08-07, release notes
v1.51)**: migration **V51** (`cc_supervision_actions`, `cc_queue_alert_config`).
`CallCenterSupervisionPanelService` computa estatísticas do dia (chamadas em espera, maior
espera, atendidas/abandonadas, nível de serviço por fila; estado/tempo no estado/atendidas hoje
por agente) em memória a partir de `cc_interactions`/`cc_agent_states` — decisão deliberada de
não escrever SQL agregado dado o volume atual (zero tráfego real de fila ainda validado).
`AmiOriginateService.originateChanSpy` — escuta/sussurro/interceptação usam `ChanSpy` com
correspondência por **prefixo** da extensão do agente (`PJSIP/4001`, opções `b`/`bw`/`bB`), o que
evita depender do nome exato do canal ativo (que exigiria capturar e persistir o campo certo dos
eventos AMI, ainda não validado — mesma ressalva da Fase 4). Pausa/despausa forçada reusa
`CallCenterAgentStateService` da Fase 4 sem duplicar lógica. Toda ação de supervisão é auditada
(`cc_supervision_actions`) — LGPD art. 37, escuta de conversa exige rastro.
`CallCenterSlaAlertScheduler` (a cada 10min, 8h-20h) dispara alerta via Telegram por fila,
dedup diário (mesmo padrão do alerta de disco). Nova aba "Supervisão" com painel de
filas/agentes (polling 4s — sem cliente STOMP na SPA ainda, mesmo padrão do Desktop do Agente) +
botões de ação por agente + "Modo TV" (overlay fullscreen local, sem rota nova). RBAC: recurso
`callcenter.supervisao`. 18 testes novos (`CallCenterSupervisionActionServiceTest`,
`CallCenterSupervisionPanelServiceTest`) — suíte completa **328/328 verde**. Validado via Chrome
headless + CDP: tela e Modo TV renderizam sem erro de console (sem fila/agente cadastrado nesta
VPS para validar os botões de ação e os números reais do painel).
**Deliberadamente fora do escopo**: transferência de chamada em curso (exigiria capturar o nome
exato do canal ativo do agente para o AMI `Redirect`, mesma incerteza de mapeamento de campo já
registrada na Fase 4) e um endpoint dedicado de "remover agente da fila" (já coberto pela aba
Filas existente, `CallCenterQueueService.removeMember`).

---

## 1. Sumário

Construir sobre a infraestrutura Asterisk existente um módulo de Call Center omnicanal com duas
frentes de atendimento (voz e chat) compartilhando o mesmo motor de roteamento, o mesmo construtor
visual de fluxos ("arrasta e solta"), a mesma identidade de agente (via Active Directory) e a mesma
camada analítica. Chamadas gravadas em `/opt/telecom/gravacao` são processadas por um pipeline de
IA espelhando o módulo Insights. O entregável final inclui relatórios analíticos por URA/agente/fila
com granularidade dia/semana/mês/ano, relatórios equivalentes para chat, e um relatório de
omnicanalidade com a timeline unificada do contato.

**Premissa arquitetural central:** o *flow engine* é **um só**, agnóstico de canal. Um nó
("caixinha") declara em quais canais funciona (`voice`, `chat`, `both`). O mesmo fluxo desenhado
uma vez roda como URA e como chatbot, com nós exclusivos de cada canal onde faz sentido. Essa
decisão é o que torna o pedido "o fluxo de URA pode ser usado no fluxo de bot" real e não uma
duplicação disfarçada.

---

## 2. Pedido do usuário, reordenado e estruturado

Reorganizei o pedido original em blocos de capacidade, na ordem em que precisam ser construídos
(dependência técnica, não ordem de importância):

| # | Bloco | Pedido original | Depende de |
|---|-------|-----------------|-----------|
| A | **Fundação Asterisk** | "aproveitando a infraestrutura do asterisk" | — |
| B | **Identidade (AD)** | login AD, base de consulta, dados em tela do atendente | — |
| C | **Cadastros de voz** | agentes, extensões, filas, URAs | A, B |
| D | **Flow builder visual** | fluxo de URA arrasta-e-solta | C |
| E | **Gravação** | todas as chamadas em `/opt/telecom/gravacao` | A |
| F | **Cadastros de chat** | agentes, filas de chat | B, C |
| G | **Flow builder de chat** | chatbot no mesmo modelo, reusa fluxo de URA | D, F |
| H | **Desktop do agente** | tela do atendente com dados do AD | B, C, F |
| I | **Insights do Call Center** | mesma estrutura do Insights, exclusivo desses áudios | E |
| J | **Relatórios de voz** | analítico por URA/agente/fila + sumarizado, D/S/M/A | C, E |
| K | **Relatórios de chat** | métricas de mercado do canal chat | F, G |
| L | **Omnicanalidade** | timeline do contato, transcrição de chat e de áudio | I, J, K |

---

## 3. Lacunas do pedido — o que faltou e por que importa

Estes itens **não** foram citados mas são obrigatórios para o produto funcionar em operação real.
Cada um está classificado por criticidade e alocado a uma fase mais adiante.

### 3.1 Bloqueadores técnicos (descobertos no código)

| Achado | Onde | Impacto |
|---|---|---|
| **`app_queue.so` está `noload`** | `asterisk/config/modules.conf:21` | Não existe fila nenhuma hoje. Sem isso, não há call center. Precisa ser habilitado e o módulo carregado. |
| **Nenhuma integração LDAP/AD** | grep em todo o repo: zero ocorrências | Autenticação por AD é construção do zero (Spring LDAP / UnboundID). |
| **AMI é request/response, não event-driven** | `integration/ami/AmiSession.java` — `readBlock()`/`readUntil()` | Estado de fila/agente em tempo real exige um listener persistente de eventos AMI, que não existe. É componente novo. |
| **Configuração do Asterisk é 100% estática em arquivo** | `pjsip.conf.template`, `extensions.conf` | Criar ramal/fila pela web exigiria reescrever arquivo + reload — frágil e sem transação. **Recomendo migrar para Asterisk Realtime (ARA) sobre PostgreSQL** para as entidades novas do call center, mantendo o estático para o que já existe. |
| **Faixas de ramal ocupadas** | `1000`, `1001-1002`, `2000-2999`, `9001-9002` | Agentes precisam de faixa nova. Proponho **`4000-4999`** para ramais de agente e **`5000-5999`** para filas. |
| **Nó único, sem redundância** | `docker-compose.yml` — 1 Asterisk | Call center é sistema crítico de negócio. Um restart do container derruba todas as chamadas ativas. Precisa ao menos de plano documentado de janela e, idealmente, de um segundo nó. |

### 3.2 Funcionalidades de operação que faltaram

| Item | Por que importa | Fase |
|---|---|---|
| **Estados e pausas do agente** (disponível, em atendimento, ACW, pausa produtiva/improdutiva codificada: almoço, banheiro, feedback, treinamento) | Sem isso não existe cálculo de ocupação, aderência nem produtividade — que são exatamente os "principais indicadores de call center" pedidos | 4 |
| **Tabulação / motivo de encerramento** (disposition codes) | É o dado que transforma volume em inteligência de negócio; todo relatório analítico sério parte dele | 4 |
| **Roteamento por skill e prioridade de fila** | Fila FIFO simples não sustenta operação com mais de um tipo de demanda | 5 |
| **Transbordo (overflow) e fila secundária** | Sem overflow, pico = abandono | 5 |
| **Horário de funcionamento, feriados e mensagem fora de expediente** | Já existe `BusinessDayCalculator.java` para reusar | 5 |
| **Callback / retorno de fila** | Reduz abandono; é padrão de mercado | 9 (opcional) |
| **Transferência cega e assistida + conferência** | Operação básica de voz | 4 |
| **Monitoria em tempo real: escuta, sussurro (whisper), barge-in** | Ferramenta primária do supervisor | 6 |
| **Wallboard / painel em tempo real** | Gestão de fila só existe se for em tempo real | 6 |
| **Pesquisa de satisfação pós-atendimento (CSAT/NPS)** em voz e chat | Sem isso não há métrica de qualidade percebida — e o relatório de chat de mercado sempre tem CSAT | 8 |
| **Discagem ativa mínima (click-to-call / preview)** | Operações mistas receptivo+ativo são a norma | 9 (opcional) |
| **Blending de canais** — limite de chats simultâneos por agente e regra de voz-interrompe-chat | É o coração real da omnicanalidade; sem isso são dois produtos lado a lado | 7 |
| **Respostas rápidas, transferência de chat, anexos** | Básico do canal chat | 7 |
| **Conectores de canal de chat** (webchat embeddável, WhatsApp Cloud API, Telegram) | "Chat" precisa de uma porta de entrada real | 7 |

### 3.3 Conformidade, dados e risco

| Item | Por que importa | Fase |
|---|---|---|
| **Aviso de gravação e consentimento (LGPD)** | Gravar sem aviso é exposição legal direta | 3 |
| **Pause/resume de gravação em coleta de dado sensível** (cartão, CPF, senha) | Requisito PCI-DSS e boa prática LGPD | 3 |
| **Política de retenção e expurgo de gravações** | `/opt/telecom/gravacao` cresce sem limite. ~1 MB/min em WAV. 50 agentes × 6h/dia ≈ 18 GB/dia | 3 |
| **Auditoria de acesso a gravação** (quem ouviu o quê, quando) | LGPD art. 37 — registro de operações | 3 |
| **Anonimização/mascaramento na transcrição** | A IA vai transcrever CPF, cartão e endereço para dentro do banco | 8 |
| **Particionamento e retenção de eventos/CDR** | Um call center gera dezenas de eventos por chamada; tabela única vira gargalo em meses | 8 |
| **Escopo por BU no módulo inteiro** | Já existe `user_business_units` + claim `bu` — precisa ser respeitado desde o dia 1, não retrofitado | 2 em diante |
| **Teste de carga (SIPp) e dimensionamento** | Não sabemos hoje quantas chamadas simultâneas a VPS aguenta | 10 |

---

## 4. Decisões arquiteturais que preciso da sua confirmação

Estas cinco decisões mudam materialmente o esforço e o resultado. Marquei minha recomendação.

### D1 — Configuração dinâmica do Asterisk: **Realtime (ARA) sobre PostgreSQL** ✅ recomendado

- **Opção A (recomendada):** habilitar `res_config_pgsql`/`res_pjsip_config_wizard` e mapear
  `ps_endpoints`, `ps_auths`, `ps_aors`, `queues`, `queue_members` como tabelas no banco
  `asteriskia`. Criar um agente/fila pela web vira um `INSERT` — sem reescrever arquivo, sem
  `reload`, transacional, e o Asterisk lê on-demand.
- **Opção B:** gerar `.conf` a partir do banco e dar `reload` (padrão atual do `pjsip.conf.template`).
  Mais familiar ao projeto, porém sem transação, com janela de inconsistência e risco de corromper
  o arquivo que também serve os ramais legados.
- **Trade-off:** A exige aprender ARA e adicionar tabelas cujo schema é ditado pelo Asterisk (não
  podemos versioná-las livremente no Flyway sem cuidado). Mesmo assim, A é o padrão de mercado
  para exatamente este caso.

### D2 — Motor de execução dos fluxos: **ARI + Stasis** ✅ recomendado

- **Opção A (recomendada):** o dialplan vira uma casca mínima que joga a chamada em
  `Stasis(callcenter)`. Um serviço conectado ao **ARI** (WebSocket) interpreta o grafo JSON do
  fluxo e comanda a chamada (`play`, `record`, `bridge`, `dial`, `queue`). Mesma engine serve o
  chat, trocando só o *driver* de canal.
- **Opção B:** manter o padrão atual da URA (dialplan genérico + `CURL` para o backend a cada
  passo). Funciona, mas cada nó novo do flow builder vira código de dialplan — o "arrasta e solta"
  fica limitado ao que o dialplan expressa.
- **Trade-off:** A concentra a lógica em um serviço só, testável, e é o único caminho realista
  para nós avançados (condicional, chamada de API externa, laço, integração com o ai-agent
  existente). Custa um serviço novo no compose.

### D3 — Insights do Call Center: **mesmo código, discriminador de fonte** ✅ recomendado

Você pediu "a mesma estrutura do módulo Insights para esse módulo de callcenter para tratar
exclusivamente esses áudios". Duas leituras:

- **Opção A (recomendada):** **um container só**, o `asteriskia-insights` atual, ganha uma coluna
  `source` (`verint` | `callcenter`) e um segundo diretório de descoberta (`/opt/telecom/gravacao`).
  A UI mostra telas separadas (menu do Call Center), os dados ficam segregados por `source`, o RBAC
  é namespace próprio. **Zero duplicação de pipeline STT/LLM.**
- **Opção B:** container novo `asteriskia-cc-insights` + tabelas próprias, cópia do código Python.
  Isolamento total, mas duas cópias de um pipeline caro que vão divergir em 3 meses (é exatamente
  o que aconteceria com `client.ts`/`AuthedAudio.tsx` se não fossem conscientemente aceitos).
- **Trade-off:** A entrega o mesmo resultado visual e de permissão que você pediu, com metade do
  custo de manutenção. Se a exigência for isolamento de infraestrutura (limite de CPU separado,
  falha independente), B se justifica — mas aí recomendo B com *código compartilhado por imagem
  base*, não copy-paste.

### D4 — Autenticação AD: **bind LDAP + espelho local sincronizado** ✅ recomendado

- **Opção A (recomendada):** autenticação por *bind* LDAP no momento do login (senha nunca
  armazenada) + job de sincronização periódica que espelha os atributos consultáveis
  (`displayName`, `department`/BU, `physicalDeliveryOfficeName`/localidade, `title`/cargo,
  `memberOf`/grupos, `manager`, `mail`, `telephoneNumber`) numa tabela `ad_users`. A tela do
  atendente lê do espelho (rápido, resiliente a AD fora do ar); o login valida no AD real.
- **Opção B:** consultar o AD ao vivo a cada carregamento de tela. Sempre atualizado, porém
  acopla a operação à disponibilidade e latência do AD.
- **Pendências que preciso de você:** host/porta do DC, uso de LDAPS (recomendo obrigatório),
  base DN, conta de serviço de leitura, e se haverá *fallback* para login local (recomendo sim,
  para quebra de vidro).

### D5 — Biblioteca do flow builder: **React Flow (`@xyflow/react`)** ✅ recomendado

Padrão de fato para editores de grafo em React, MIT, ativo, sem dependência de backend. Alternativas
(`rete.js`, `litegraph`) têm ecossistema menor. Persistência: JSON versionado com
`draft`/`published` e histórico de versões — **fluxo publicado nunca é editado in-place**, senão
uma chamada em andamento muda de comportamento no meio.

---

## 5. Padrões do repositório a espelhar

| Categoria | Fonte | Padrão a seguir |
|---|---|---|
| Módulo com SPA própria | `insights-platform/frontend/` + `frontend/nginx.conf` + `Caddyfile` | Vite+React+TS servido em `/<modulo>` pelo mesmo nginx; backend Java reusado; `Login.tsx`/`Sidebar.tsx`/`useAuthSession.ts` copiados |
| Submenu na Sidebar do Telecom | `frontend/src/components/Sidebar.tsx:69-102` (`NavParent`/`children[]`) | Call Center vira `NavParent` com filhos; iframe não remonta ao trocar aba |
| Ponte shell↔iframe | `useShellBridge.ts` (handshake `ready`, `navigate`, `tabChanged`) | Reusar tal e qual |
| RBAC granular | `domain/accessgroup/ResourceCatalog.java` + `SecurityConfig` + `Sidebar.tsx` + `AccessGroups.tsx` | 4 pontos de sincronia manual — namespace novo `callcenter.*` |
| Escopo por BU | `BusinessUnitContext`, authorities `BU_<id>` | Aplicar em filas, agentes, relatórios desde o início |
| Serviço Python de IA | `insights/src/` (`discovery.py`, `stt_diarize.py`, `insights_llm.py`, `token_usage.py`) | Estrutura e nomes; `backend_client.py` fala com o Java via `X-Internal-Key` |
| Ingestão + custo de IA | `InsightsIngestionService.java`, `InsightsCostService.java`, `ai_model_pricing` | Custo por chamada rastreado desde o dia 1, integra ao módulo Financeiro |
| Migrations | `backend/src/main/resources/db/migration/` — última é **V44** | Próximas: **V45+**, irreversíveis, revisar SQL antes |
| AMI | `integration/ami/AmiSession.java`, `AmiOriginateService.java` | Reusar conexão/login; **estender** com listener de eventos (novo) |
| Release notes | `frontend/src/data/releases.ts` | Entrada obrigatória por entrega |

---

## 6. Modelo de dados (visão geral)

Novos agrupamentos. Nomes definitivos saem no detalhamento de cada fase.

**Identidade e agente**
`ad_users` (espelho do AD) · `cc_agents` (agente ↔ usuário ↔ ramal ↔ skills) · `cc_agent_states`
(histórico de estado/pausa, base de toda métrica de tempo) · `cc_pause_reasons` (códigos de pausa)

**Roteamento**
`cc_queues` · `cc_queue_members` · `cc_skills` · `cc_agent_skills` · `cc_queue_skills` ·
`cc_business_hours` · `cc_holidays`

**Fluxos**
`cc_flows` (metadados, canal, status) · `cc_flow_versions` (grafo JSON versionado, draft/published) ·
`cc_flow_executions` (traço de execução por interação — essencial para depurar fluxo)

**Interações (o coração da omnicanalidade)**
`cc_contacts` (identidade unificada do cliente: telefone, e-mail, documento, id externo) ·
`cc_interactions` (uma linha por atendimento, com `channel` = voice|chat — chave da timeline) ·
`cc_interaction_events` (fila, oferta, atendimento, transferência, hold, encerramento — particionada por mês) ·
`cc_dispositions` (tabulação) · `cc_surveys` (CSAT/NPS)

**Chat**
`cc_chat_sessions` · `cc_chat_messages` · `cc_chat_channels` (webchat/WhatsApp/Telegram) ·
`cc_canned_responses`

**Gravação e IA** — reusa `call_audio_files`/`call_transcript_segments`/`call_insights` com a
coluna `source` (decisão D3-A), mais `cc_recordings` ligando gravação ↔ interação.

**Agregados de relatório**
`cc_agg_queue_daily` · `cc_agg_agent_daily` · `cc_agg_flow_daily` — tabelas materializadas por job
noturno. **Relatórios de ano inteiro não podem varrer eventos brutos.**

---

## 7. Indicadores a implementar

**Voz** — Volume recebido/atendido/abandonado · Taxa de abandono (com e sem *short abandon*) ·
Nível de Serviço (% atendidas em ≤ X s, X configurável por fila) · ASA (tempo médio de espera) ·
TMA/AHT (fala + hold + ACW) · TMO · Tempo de conversação · Tempo em espera · ACW · Ocupação ·
Utilização · Aderência à escala · Transferências (taxa e destino) · Rechamada em 24h/7d (proxy de
FCR) · Retenção na URA (quantos resolveram sem agente) · Abandono dentro da URA por nó ·
Distribuição por hora/dia da semana · Top motivos (tabulação) · CSAT/NPS.

**Chat** — Sessões iniciadas/atendidas/abandonadas · **FRT** (First Response Time) · **ART**
(Average Response Time) · Tempo de resolução · Duração da sessão · Concorrência média e pico por
agente · Taxa de contenção do bot (resolvidas sem humano) · Taxa de escalonamento bot→humano ·
Mensagens por sessão · Taxa de abandono em fila · Sessões por agente/hora · CSAT · Sentimento
(via IA, reusando o pipeline de Insights).

**Granularidades** — dia, semana, mês, ano; visão analítica (por URA/fluxo, por agente, por fila) e
sumarizada. Todos com escopo de BU aplicado.

---

## 8. Fases de execução

Marcos com validação explícita ao fim de cada um. **Cada fase é deployável e demonstrável** — não
existe fase que só faz sentido junto com a seguinte.

---

### FASE 0 — Fundação e provas de conceito _(bloqueante para tudo)_
**Objetivo:** eliminar as três incertezas técnicas antes de comprometer arquitetura.

1. Habilitar `app_queue.so` (`modules.conf`), `res_ari*`, `res_config_pgsql`; validar
   `asterisk -rx "queue show"` e `module show like ari`.
2. PoC ARA: criar um endpoint PJSIP inteiro via `INSERT` no banco e registrar um softphone nele.
3. PoC ARI: dialplan mínimo `Stasis(cc)` + script conectado no WebSocket ARI que atende, toca áudio
   e enfileira. Mede latência de resposta.
4. PoC AMI event-driven: listener persistente consumindo `QueueMemberStatus`, `AgentCalled`,
   `QueueCallerJoin`; confirmar reconexão automática.
5. Reservar faixas de numeração e documentar (`4000-4999` agentes, `5000-5999` filas).
6. Criar `/opt/telecom/gravacao` com permissão e mount, e medir consumo real de disco por minuto
   de gravação nos codecs em uso (G.711a / G.729A).
7. Dimensionamento: teste SIPp de chamadas simultâneas na VPS atual → define o teto de agentes.

**Validação:** os 4 PoCs rodando; relatório de capacidade escrito.
**Saída:** D1/D2 confirmadas ou revistas com dado real.

---

### FASE 1 — Identidade: integração com Active Directory
**Objetivo:** login por AD e dados do usuário disponíveis para todo o resto.

1. Migration **V45**: `ad_users` (espelho) + `ad_sync_runs` (auditoria de sincronização).
2. `integration/ad/`: `LdapClient` (bind LDAPS, timeouts, pool), `AdSyncScheduler` (job periódico),
   `AdUserService` (consulta ao espelho), mapeamento de atributos configurável via `Settings`.
3. `AuthController`: autenticação por AD com fallback local; usuário AD é provisionado na primeira
   entrada e recebe grupo de acesso padrão configurável.
4. Mapeamento **grupo do AD → grupo de acesso do AsteriskIA** (tabela de correspondência, opcional
   e desligável) — evita gestão manual de permissão para dezenas de agentes.
5. Tela de administração: status da sincronização, teste de conexão, mapeamento de atributos e de
   grupos, consulta de usuário do AD.
6. Segurança: credencial da conta de serviço só via `.env`; LDAPS obrigatório; sem senha em log;
   rate limit no login; bloqueio de *user enumeration*.

**Validação:** login com usuário real do AD; espelho sincronizado; `mvn test` verde; revisão
`ecc:security-reviewer` sem CRITICAL/HIGH.
**Risco:** dependência de acesso de rede da VPS ao DC — **precisa ser confirmado antes da fase**.

---

### FASE 2 — Cadastros de voz e configuração dinâmica do Asterisk
**Objetivo:** criar agente, ramal e fila pela web e ver o Asterisk obedecer.

1. Migrations **V46-V48**: `cc_agents`, `cc_extensions`, `cc_queues`, `cc_queue_members`,
   `cc_skills`, `cc_agent_skills`, `cc_queue_skills`, `cc_pause_reasons`.
2. Camada ARA (conforme D1): mapeamento das tabelas do Asterisk + serviço de provisionamento que
   escreve endpoint/aor/auth ao criar um ramal, com senha gerada e nunca exposta em `GET`.
3. `domain/callcenter/`: controllers e serviços de CRUD, todos com escopo de BU e RBAC
   `callcenter.*`.
4. Namespace RBAC novo (**migration V49**) — 4 pontos de sincronia: `ResourceCatalog.java`,
   `SecurityConfig.java`, `Sidebar.tsx`, `AccessGroups.tsx`.
5. SPA `callcenter-platform/frontend/` (Vite+React+TS, padrão do Insights) + `location /callcenter/`
   no nginx + regra no `Caddyfile` + estágio de build no `frontend/Dockerfile`.
6. Item `NavParent` "Call Center" na Sidebar do Telecom + ponte `useShellBridge`.

**Validação:** criar agente+ramal+fila pela UI, registrar softphone no ramal criado, ligar para a
fila e ser atendido. `tsc --noEmit` e build limpos.

---

### FASE 3 — Gravação, retenção e conformidade
**Objetivo:** toda chamada gravada, rastreável e legalmente defensável.

1. `MixMonitor` no dialplan/ARI gravando em `/opt/telecom/gravacao` com estrutura
   `YYYY/MM/DD/<interaction_id>.wav` e metadados em `cc_recordings` (**V50**).
2. Aviso de gravação configurável por fila/fluxo; nó de fluxo `pausar gravação` para coleta de dado
   sensível (PCI/LGPD).
3. Política de retenção configurável + job de expurgo com relatório do que foi apagado.
4. Auditoria: toda reprodução/download de gravação registrada em `audit_log` (quem, quando, qual).
5. Streaming autenticado da gravação reusando o padrão de `InsightsController.getAudio` +
   `AuthedAudio.tsx` (transcodificação `ffmpeg` quando necessário).
6. Alerta de disco: notificação quando `/opt/telecom/gravacao` cruzar limiar (reusar
   `TelegramBotService`, padrão do `CostAlertScheduler`).

**Validação:** chamada gravada e tocável na UI; expurgo em ambiente de teste; log de auditoria
conferido; alerta de disco disparado artificialmente.

---

### FASE 4 — Operação do agente: estados, desktop e controle de chamada
**Objetivo:** o agente consegue trabalhar de verdade.

1. Migration **V51**: `cc_agent_states` (histórico), `cc_dispositions`, `cc_interactions`,
   `cc_interaction_events` (particionada por mês).
2. Serviço **AMI event listener** (novo, persistente, com reconexão) alimentando estado em tempo
   real + WebSocket STOMP para o frontend (padrão já usado no backend).
3. Tela **Desktop do Agente**: login/logout de fila, seletor de estado e pausa codificada,
   softphone WebRTC embutido (reusa `Softphone.tsx` / JsSIP, ramal do agente), controles de
   atender/encerrar/hold/mudo/transferir (cega e assistida)/conferência.
4. **Screen pop do AD** — ao receber a chamada, painel com nome completo, BU, localidade, cargo,
   perfil, grupos, gestor, e-mail (dados do espelho da Fase 1) + histórico de contatos anteriores.
5. Tabulação obrigatória/opcional por fila ao encerrar, com tempo de ACW cronometrado.
6. Toda transição de estado gravada com timestamp — é a matéria-prima das métricas de tempo.

**Validação:** ciclo completo — agente loga, entra em fila, recebe chamada, vê dados do AD, atende,
transfere, tabula, entra em pausa. Estados batendo com `queue show` no Asterisk.

---

### FASE 5 — Flow builder visual (canal voz)
**Objetivo:** desenhar uma URA arrastando caixinhas e publicá-la sem tocar em arquivo.

1. Migrations **V52-V53**: `cc_flows`, `cc_flow_versions`, `cc_flow_executions`.
2. Editor React Flow: canvas, paleta de nós, painel de propriedades, validação do grafo (nó órfão,
   ciclo infinito, saída não conectada), minimapa, zoom, desfazer.
3. **Catálogo de nós v1** — cada um com atributo de canal (`voice`/`chat`/`both`):
   `início` · `tocar áudio/TTS` (both: vira mensagem no chat) · `menu de opções` (both: DTMF ↔
   botões) · `coletar entrada` (both: DTMF ↔ texto) · `condição` (both) · `definir variável` (both) ·
   `consultar API externa` (both) · `enviar para fila` (both) · `transferir para ramal` (voice) ·
   `horário de funcionamento` (both) · `agente de IA` (both — integra o `ai-agent` existente) ·
   `pausar/retomar gravação` (voice) · `pesquisa de satisfação` (both) · `encerrar` (both).
4. Versionamento: rascunho ↔ publicado; publicar cria versão imutável; chamada em curso continua na
   versão que iniciou. Rollback para versão anterior.
5. Motor de execução ARI interpretando o grafo, com traço completo por interação
   (`cc_flow_executions`) — permite ver exatamente onde o cliente abandonou.
6. **Simulador**: executar o fluxo na tela sem ligar, passo a passo, para testar antes de publicar.
7. Horário de funcionamento e feriados (reusa `BusinessDayCalculator`), transbordo e prioridade de
   fila, roteamento por skill.

**Validação:** desenhar uma URA com menu, condição, horário e fila; publicar; ligar e percorrer os
dois ramos; ver o traço de execução; fazer rollback.

---

### FASE 6 — Supervisão em tempo real
**Objetivo:** o supervisor enxerga e age sobre a operação agora.

1. Painel tempo real: filas (chamadas em espera, maior espera, nível de serviço no dia), agentes
   (estado, tempo no estado, chamadas atendidas), alertas de SLA — via WebSocket STOMP.
2. Wallboard modo TV (tela cheia, sem navegação, auto-refresh).
3. Ações do supervisor: **escuta** (`ChanSpy`), **sussurro** (whisper), **barge-in**, forçar
   pausa/despausa, remover agente da fila, transferir chamada em curso.
4. Toda ação de monitoria registrada em auditoria (é escuta de conversa — exige rastro).
5. Alertas configuráveis (fila acima de N em espera, SLA abaixo de X%) via Telegram.

**Validação:** dois softphones em chamada, supervisor escuta, sussurra e faz barge-in; painel
refletindo mudanças em < 2 s.

---

### FASE 7 — Canal de chat
**Objetivo:** segunda frente da omnicanalidade, reusando tudo que já existe.

1. Migrations **V54-V55**: `cc_chat_channels`, `cc_chat_sessions`, `cc_chat_messages`,
   `cc_canned_responses`.
2. Serviço de chat: WebSocket para atendente e cliente, filas de chat reusando `cc_queues`
   (discriminadas por canal), roteamento pelo mesmo motor.
3. **Conectores** (arquitetura plugável, um adaptador por canal): **webchat** (widget JS
   embeddável, primeiro a entregar) → **WhatsApp Cloud API** → **Telegram**. Cada conector traduz
   para um formato interno de mensagem único.
4. **Flow builder de chat**: mesmo editor, filtrando nós por canal; fluxo marcado como `both` roda
   nos dois. Nós exclusivos: `enviar mídia`, `botões de resposta rápida`, `carrossel`.
5. Desktop do agente ganha aba de chat: múltiplas conversas simultâneas, respostas rápidas,
   transferência de chat, anexos, indicador de digitação, histórico do contato.
6. **Blending**: limite de chats simultâneos por agente, regra de precedência voz×chat, estado
   unificado do agente entre canais.

**Validação:** conversa completa pelo widget web — bot atende, escalona para fila, agente responde,
transfere, encerra e tabula. Agente atendendo 3 chats e recusando voz conforme a regra.

---

### FASE 8 — Insights do Call Center (pipeline de IA sobre as gravações)
**Objetivo:** transcrição, análise e qualidade sobre os áudios de `/opt/telecom/gravacao`.

1. Migration **V56**: coluna `source` em `call_audio_files` e correlatas (conforme D3-A), com
   backfill marcando o existente como `verint`. Índices revistos.
2. `insights/src/discovery.py` ganha segunda origem (`/opt/telecom/gravacao`), com correlação
   direta pelo `interaction_id` no nome do arquivo — **muito mais confiável que o XML da Verint**,
   porque a gravação já nasce ligada à interação, à fila e ao agente.
3. Reuso integral de `stt_diarize.py`, `insights_llm.py`, `prosody.py`, `token_usage.py`. Custo de
   IA por chamada integrado ao módulo **Financeiro** (frente nova, alerta de gasto próprio).
4. **Mascaramento de dado sensível** na transcrição (CPF, cartão, telefone) antes de persistir.
5. Telas no menu do Call Center espelhando o Insights: Chamadas, Dashboard de Tendências,
   Processamento, Fichas de Qualidade, Relatórios — com RBAC `callcenter.insights.*`.
6. Transcrição do chat entra no mesmo modelo de análise (já é texto — pula o STT, vai direto ao LLM).
7. Pesquisa de satisfação (CSAT/NPS) em voz e chat, com resultado ligado à interação.

**Validação:** gravação de uma chamada real percorrendo o pipeline até a análise na tela; custo
registrado no Financeiro; dado sensível mascarado.

---

### FASE 9 — Relatórios analíticos
**Objetivo:** entregar os relatórios pedidos, com desempenho aceitável em recorte anual.

1. Migration **V57**: agregados `cc_agg_queue_daily`, `cc_agg_agent_daily`, `cc_agg_flow_daily`,
   `cc_agg_chat_daily` + job noturno de consolidação (padrão do `AiModelPricingSyncScheduler`) e
   reprocessamento sob demanda de um período.
2. **Relatórios de voz**: analítico por URA/fluxo, por agente, por fila; sumarizado; granularidade
   dia/semana/mês/ano; comparativo entre períodos; todos os indicadores da seção 7.
3. **Relatórios de chat**: mesma estrutura com as métricas do canal.
4. **Relatório de omnicanalidade**: busca por contato (telefone, e-mail, documento, nome) →
   **timeline unificada** de todas as interações de voz e chat, com transcrição do chat, e da
   gravação em texto **e** áudio lado a lado, análise de IA, tabulação, agente, fila, duração e
   eventos detalhados.
5. Exportação Excel/CSV reusando `ExcelExportService` (com o escape de fórmula CSV já
   implementado no `ReportController`) e PDF reusando `AgentReportPdfService`.
6. Drill-down: do sumarizado para o analítico e do analítico para a interação individual.
7. Agendamento de relatório por e-mail/Telegram (opcional).

**Validação:** relatório anual respondendo em tempo aceitável sobre volume sintético de 12 meses;
timeline de um contato com interações de voz e chat; números conferidos contra consulta bruta.

---

### FASE 10 — Endurecimento, carga e operação
**Objetivo:** entregar pronto para produção real, não para demonstração.

1. Teste de carga SIPp no cenário-alvo de agentes/chamadas simultâneas; teste de carga do chat.
2. Particionamento e retenção de `cc_interaction_events` e `cc_chat_messages`; índices revistos com
   `EXPLAIN ANALYZE` sob volume.
3. Revisão de segurança completa (`ecc:security-reviewer` + `ecc:code-reviewer` em paralelo,
   padrão da auditoria pós-RBAC): AD/LDAP, ARI, WebSocket de chat (entrada de usuário anônimo da
   internet — maior superfície nova do projeto), upload de anexo, SSRF no nó "consultar API
   externa" do flow builder.
4. Limites de recurso no compose para os serviços novos; healthchecks; plano de janela de
   manutenção (Asterisk single-node derruba chamadas ativas).
5. Documentação: seções novas em `Documentacao.tsx` (manual do agente, do supervisor e do
   administrador do flow builder).
6. Release notes em `releases.ts`; atualização do `CLAUDE.md`.

**Validação:** teste de carga no alvo; zero CRITICAL/HIGH nas revisões; documentação publicada.

---

## 9. Sequenciamento e dependências

```
FASE 0 (fundação/PoC) ──┬─→ FASE 1 (AD) ──┬─→ FASE 2 (cadastros voz) ──→ FASE 3 (gravação)
                        │                 │                                    │
                        │                 └─────────────────┐                  │
                        └────────────────────────────→ FASE 4 (agente) ←───────┘
                                                            │
                                        ┌───────────────────┼───────────────────┐
                                        ↓                   ↓                   ↓
                                   FASE 5 (flow voz)   FASE 6 (supervisão)  FASE 8 (insights)
                                        │                                       │
                                        └──→ FASE 7 (chat) ────────────────────┤
                                                                                ↓
                                                                          FASE 9 (relatórios)
                                                                                ↓
                                                                          FASE 10 (hardening)
```

**Paralelizáveis:** 5 e 6 depois da 4. 8 pode começar assim que a 3 fechar. 6 é a que mais gera
valor percebido cedo — se precisar demonstrar resultado, priorize-a.

---

## 10. Riscos

| Risco | Prob. | Impacto | Mitigação |
|---|---|---|---|
| VPS atual não suporta o volume-alvo (200 agentes/200 chamadas) | **Confirmado** | **Alto** | **Resolvido por decisão do usuário (§13.2)**: produção real vai para servidor dedicado, dimensionado por recomendação de hardware a apresentar durante o projeto — esta VPS é só build/homologação |
| Sem acesso de rede da VPS ao Domain Controller | Média | **Alto** | Confirmar conectividade e conta de serviço antes da Fase 1; sem isso a fase não começa |
| Migração para ARA quebra os ramais legados (`pjsip.conf` estático) | Média | **Alto** | ARA convive com estático; não migrar o que já funciona; PoC na Fase 0 valida a coexistência |
| Asterisk é nó único — restart derruba chamadas ativas | Alta | **Alto** | Janela de manutenção documentada; avaliar segundo nó na Fase 10 |
| Crescimento de disco em `/opt/telecom/gravacao` | Alta | Médio | Retenção + alerta na Fase 3, dimensionado com dado medido na Fase 0 |
| Custo de IA nas gravações do call center (100% das chamadas, volume >> Verint) | Alta | **Alto** | Sem amostragem por decisão do usuário (2026-08-06) — alerta de gasto no Financeiro obrigatório desde o dia 1; estimativa de custo mensal projetado (200 canais × taxa de uso real) entra na recomendação de hardware/infra da Fase 10 |
| Relatório anual varrendo eventos brutos = timeout | Alta | Médio | Agregados materializados desde o desenho (Fase 9), nunca consulta direta em ano |
| WebSocket de chat exposto à internet | Alta | **Alto** | Rate limit, validação de origem, sanitização de mensagem, sem execução de HTML, revisão dedicada na Fase 10 |
| Escopo cresce durante a execução | Alta | Médio | Fases fechadas e deployáveis; item novo entra na fase seguinte, não na atual |
| Divergência de código se D3 for opção B | Média | Médio | Preferir D3-A (discriminador de fonte) |
| Dimensionamento de storage p/ 60 meses de retenção em 200 canais | Alta | **Alto** | Faz parte da recomendação de hardware da Fase 10 — não estimar com o bind mount improvisado da Fase 0 |
| Recomendação de hardware da Fase 10 chegar tarde/errada | Média | **Alto** | Antecipar um esboço de dimensionamento (CPU/RAM/storage/rede) logo após a Fase 4, quando o custo real por chamada (RTP+gravação+IA) já estiver medido em ambiente completo, não só sinalização |

---

## 11. Dimensão do esforço

Estimativa por fase, considerando o ritmo observado nas entregas anteriores deste repositório:

| Fase | Peso | Observação |
|---|---|---|
| 0 — Fundação/PoC | P | Curta, mas bloqueante e de alto valor informativo |
| 1 — AD | M | Depende de acesso externo |
| 2 — Cadastros + ARA | **G** | Inclui SPA nova + RBAC + provisionamento |
| 3 — Gravação | M | |
| 4 — Desktop do agente | **G** | Listener AMI + softphone + estados |
| 5 — Flow builder voz | **XG** | Maior item isolado do plano |
| 6 — Supervisão | M | |
| 7 — Chat | **XG** | Conectores + blending + editor de chat |
| 8 — Insights CC | M | Alto reuso do que existe |
| 9 — Relatórios | **G** | |
| 10 — Hardening | M | |

Total: entrega de **vários meses**. É viável fatiar em releases — sugiro **release 1 = Fases 0-4 +
6** (call center de voz operacional com supervisão, URA ainda pelo modelo atual), **release 2 =
Fase 5 + 8 + 9** (flow builder, IA e relatórios de voz), **release 3 = Fase 7 + 9-chat + 10**
(omnicanalidade completa).

---

## 12. Aceite

- [x] Decisões D1-D5 confirmadas pelo usuário (2026-08-06, todas conforme recomendado)
- [ ] Conectividade com o Domain Controller confirmada (host/porta/base DN ainda não levantados —
      serão configurados via tela de administração, não em `.env`)
- [x] Teto de capacidade da VPS medido (Fase 0) — confirmado insuficiente para 200/200; produção
      real vai para servidor dedicado a dimensionar
- [ ] Recomendação de hardware para o servidor dedicado de produção apresentada
- [ ] Cada fase deployada e validada **nesta VPS** (ambiente de build/homologação) antes de iniciar
      a seguinte — deploy no servidor dedicado de produção é evento separado, fora deste ciclo
- [ ] Sem CRITICAL/HIGH em `ecc:security-reviewer` por fase
- [ ] Cobertura de teste ≥ 80% no código novo
- [ ] Release notes e `CLAUDE.md` atualizados por fase
- [ ] Escopo por BU e RBAC granular aplicados em todo endpoint novo

---

## 13. Perguntas abertas — respondidas (2026-08-06)

1. **Volume-alvo**: pico de **200 agentes / 200 chamadas simultâneas**. Muito acima da capacidade
   desta VPS (2 vCPU, ~3,8 GB RAM, já com ~900 MB disponíveis e 2,2 GB de swap em uso em repouso —
   ver §10). Resolvido pela decisão de infraestrutura abaixo.
2. **Ambiente de desenvolvimento vs. produção** (decisão nova, fora da lista original): **todo o
   desenvolvimento e entrega acontece nesta VPS** (`app.voiphash.com.br`), como o resto do
   AsteriskIA — mas o módulo Call Center **entra em produção real num servidor dedicado à parte**,
   dimensionado por recomendação de hardware que preciso apresentar durante o projeto (ver novo
   item na Fase 10). Esta VPS nunca precisa sustentar 200/200 — é ambiente de build/homologação
   para este módulo.
3. **AD**: sem host/porta/base DN fixos em `.env` — conexão inteira (host, porta, LDAPS, base DN,
   conta de serviço, fallback de login local) configurável por uma **tela de administração**
   (mesmo padrão de `SettingsService`/`ConfigService` já usado para Jira/Zabbix). Isso já era o
   item 5 da Fase 1 ("teste de conexão"); a resposta só confirma que não há atalho por `.env` —
   os valores reais de conexão com o DC ainda precisam ser levantados antes de iniciar a Fase 1.
4. **Canais de chat**: só **webchat próprio** nesta entrega. WhatsApp/Telegram ficam para release
   futura (Cloud API da Meta exige conta comercial verificada e tem custo por conversa).
5. **Receptivo/ativo**: **receptivo + ativo manual** — agente disca manualmente pelo softphone da
   própria tela (já previsto na Fase 4, sem trabalho extra). **Sem discador automático** — motor de
   campanha/discagem preditiva fica fora deste plano, será um **módulo separado em outro momento**.
6. **Retenção de gravação**: **60 meses (5 anos)**. Com 200 canais simultâneos no pico, isso deixa
   de ser "alguns GB" (ver §10, risco de disco atualizado) — dimensionamento de storage real vira
   parte da recomendação de hardware da Fase 10, não um bind mount improvisado como o da Fase 0.
7. **Análise de IA**: **100% das chamadas** (decisão revista em 2026-08-06 — não amostragem). Com
   200 canais simultâneos no pico, custo de IA escala linear com o volume; monitoramento/alerta de
   gasto (mesmo padrão do módulo Financeiro) segue obrigatório, e a estimativa de custo mensal
   entra na recomendação de hardware/infra da Fase 10.
8. **Integração com o Jira**: **sim, desde a Fase 4/5** — reusa `JiraIntegrationService` existente.
9. **Confirmação das decisões D1-D5**: **todas aceitas conforme recomendado** (D1 ARA, D2
   ARI+Stasis, D3 Insights com discriminador de fonte, D4 bind LDAP + espelho local, D5 React
   Flow) — D1/D2/D4(mecanismo) já validados tecnicamente pelos PoCs da Fase 0.
