# CONTEXT.md — VoipIA

Glossário da linguagem de domínio do projeto. Objetivo: eliminar ambiguidade em
termos que já causaram confusão real (RBAC granular, escopo por BU, multi-URA).
Não repete conceitos genéricos de programação — só o que é específico deste domínio.

## Telecom — Módulo 1 (URA)

**URA**:
Instância configurável de atendimento por voz (Módulo 1), com ramal próprio na
faixa `2000`-`2999` e toggle próprio de integração com o Jira.
_Avoid_: robô de atendimento, bot de voz

**URA legada/fallback**:
A URA `id=1`, ramal fixo `1000` ("Service Desk") — usada sempre que a resolução
de URA via dialplan falha, para nunca derrubar a chamada.
_Avoid_: URA padrão, URA default

**Ramal**:
Extensão SIP/PJSIP registrada no Asterisk. Ramais fixos (`9001`, `9002`, `1000`,
`1001`, `1002`) coexistem com a faixa dinâmica `2000`-`2999` das URAs cadastradas.
_Avoid_: extensão (usar só quando o contexto é claramente dialplan)

**Fluxo**:
Lógica de atendimento executada pelo ai-agent para uma ligação (`JiraCallFlow`,
`ZabbixAlertFlow`), escolhida a partir do `FLOW_TYPE` resolvido pelo backend.
_Avoid_: flow (em inglês), script de chamada

**Correlação callUuid → uraId**:
Vínculo temporário (TTL 5 min, só em memória, sem persistência) entre uma
chamada em andamento e a URA que a originou, resolvido pelo dialplan via `CURL`.
_Avoid_: sessão de chamada, contexto de chamada

## Telecom — Módulo 2 e 3

**Teste de conectividade**:
Discagem automática agendada (`ConnectivityScheduler`) para validar que um
número externo está alcançável.
_Avoid_: healthcheck de número, ping de ramal

**Alerta Zabbix**:
Ligação automática disparada ao responsável de plantão quando o Zabbix reporta
um incidente crítico (Módulo 3, ramal `1001`).
_Avoid_: notificação de monitoramento

## RBAC granular (grupos de acesso)

**Grupo de acesso**:
Conjunto nomeado de permissões de leitura/escrita por `resource_key`
(`access_groups` + `access_group_permissions`), que substitui o binário
`role` ADMIN|USER como mecanismo principal de autorização.
_Avoid_: perfil, papel, role (role continua existindo, mas é o mecanismo legado)

**Resource key**:
Identificador fixo de um menu/recurso no catálogo de código (`ResourceCatalog.java`),
ex: `telecom.settings`, `agents.secrets`. Os menus são fixos — só a matriz de
permissões por grupo é dinâmica.
_Avoid_: permissão (permissão é o valor r/w/rw associado a uma resource key)

**Claim `perm`**:
Claim do JWT com a matriz `{resource_key: "r"|"w"|"rw"}` resolvida do grupo do
usuário no login/refresh/2FA. Coexiste em dual-emit com a claim `role` legada
para não invalidar tokens antigos.
_Avoid_: escopo do token, permissões do token

**Streaming token**:
Token JWT de curta duração (60s, claim `scope=stream`) emitido só para
autenticar WebSocket/SSE — existe porque um JWT normal na query string vaza em
logs. Não funciona como Bearer normal e não pode gerar outro streaming token.
_Avoid_: token de sessão, ws token

## Controle de acesso por BU

**BU (Business Unit / Unidade de Negócio)**:
Escopo obrigatório de um usuário (`user_business_units`), carregado no JWT como
authority `BU_<id>`. Define quais Chamadas, Cadastros e Testes de Conectividade
o usuário enxerga. ADMIN sempre vê tudo, independente de BU.
_Avoid_: setor, unidade, filial

**Operação**:
Cadastro vinculado a Cliente e a uma BU. Em Cadastros, a BU é opcional — um
registro sem BU fica visível a todos; em Chamadas e Conectividade, a BU já é
obrigatória e filtra de fato.
_Avoid_: negócio, projeto

**Acesso indeterminado**:
Flag (`access_indeterminate=true`) marcada nos usuários migrados antes da
introdução do controle por BU (V26) e vinculados a todas as BUs ativas, para
não perder acesso retroativamente.
_Avoid_: acesso legado, sem BU

## Infra

**Docker-helper**:
Único container do stack com acesso ao `docker.sock` — expõe uma API interna
estreita (`/compose/up`, `/logs`, `/exec` restrito ao Asterisk) atrás de
`X-Internal-Key`, sem porta publicada no host.
_Avoid_: docker proxy, docker api

**AudioSocket**:
Protocolo do Asterisk para streaming de áudio bidirecional (frames PCM) entre
o dialplan e o ai-agent, na porta interna `9092`.
_Avoid_: socket de áudio, stream de voz
