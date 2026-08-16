# Plano: Módulo Call Center Omnicanal (Voz + Chat)

**Origem**: pedido livre do usuário (2026-08-06), **ampliado em 2026-08-08** (`pla.txt` — 10 pedidos novos)
**Complexidade**: **Extra-Large** — é um produto, não uma feature. Maior entrega já feita no VoipIA.
**Última reorganização**: 2026-08-08 — Parte II (Fases 11-15) incorporada, §8 reescrita como
tabela de status, histórico verboso movido para §16.

---

## 0. Status geral

### Parte I — plano original (Fases 0-10)

| Fase | Escopo | Status | Pendências abertas |
|---|---|---|---|
| **0** | Fundação e PoCs (ARA, ARI, AMI, faixas, disco, carga) | ✅ **concluída** | — |
| **1** | Identidade: Active Directory | 🟡 **backend pronto, não validado** | Dados reais do DC (host/porta/base DN/conta de serviço); validação visual da tela; **bloqueia a Fase 14** |
| **2** | Cadastros de voz + ARA + SPA própria | ✅ **concluída** | Ver Fase 12 (provisionamento e filas ficaram incompletos) |
| **3** | Gravação, retenção e conformidade | ✅ **concluída** | Caminho muda na Fase 11; pause/resume sem UI (Fase 5c) |
| **4** | Estados do agente, interações, tabulação | 🟡 **entregue, não validada com tráfego real** | Softphone (Fase 13), screen pop (Fase 14), transferência; **nomes de campo AMI nunca confirmados** |
| **5** | Flow builder visual (voz) | 🟡 **5a + 5b entregues** | 5c-5f abertas — ver §9 (5c foi repriorizada) |
| **6** | Supervisão em tempo real | 🟡 **entregue, não validada com tráfego real** | Ver Fase 15 (fila individual, redirect) |
| **7** | Canal de chat | 🟡 **7a + 7b entregues** | WhatsApp/Telegram, flow builder de chat, blending, anexos |
| **8** | Insights do Call Center (IA) | ✅ **concluída** | `agent_evolution_snapshots` sem coluna `source` (gap aceito) |
| **9** | Relatórios analíticos | 🟡 **9a + 9b entregues** | 9c: agregado de fluxo/chat, timeline omnicanal, exportação, agendamento |
| **10** | Endurecimento, carga e operação | ❌ **não iniciada** | Tudo |

### Parte II — pedidos de 2026-08-08 (`pla.txt`)

| Fase | Escopo | Pedido | Status |
|---|---|---|---|
| **11** | Padronização dos caminhos de gravação | P10 | ✅ **implementada, testada e deployada** (2026-08-08) |
| **12** | Provisionamento de atendente e gestão de filas | P3, P4 | ✅ **implementada, testada e deployada** (2026-08-08) |
| **13** | Softphone do agente (fixo + global + credencial própria) | P2 | ❌ não iniciada |
| **5c** | Menu com ramificação 1-9 + biblioteca de áudios | P5, P6 | ❌ não iniciada (repriorizada acima do simulador) |
| **14** | Identidade do contato e screen pop | P1 | ❌ não iniciada — **bloqueada pela Fase 1** |
| **15** | Supervisão avançada (fila individual, sussurro, redirect) | P7, P8, P9 | ❌ não iniciada |
| **16** | Histórico do contato + copiloto de IA (perfil e ações sugeridas) | P11, P12 | ❌ não iniciada — depende da 14 |
| **17** | Co-browsing gravado do chat | P10 (parte visual) | ❌ não iniciada — depois da Fase 10 |
| **18** | IA local (memória/RAG, STT local, ML clássico) — reduzir custo de token | pergunta do usuário | ❌ estudo escrito; depende de custo medido e de decisão de GPU |

**Regra transversal nova (2026-08-08):** toda tela/funcionalidade com IA passa a ser obrigatoriamente
representada no módulo **Financeiro**, com custo por interação — ver **§5.1**.

### Bloqueio operacional que atravessa tudo

**Não existe nenhum agente cadastrado nesta VPS** (`SELECT count(*) FROM cc_agents` = 0) e
**nenhuma chamada real jamais atravessou uma fila**. Consequências diretas:

- O mapeamento dos eventos AMI (`CallCenterAmiEventListener` — 4 eventos tratados) nunca foi
  confirmado contra o Asterisk real. Todas as Fases 4, 6 e 15 dependem desses nomes de campo.
- O mapeamento dos eventos ARI (`AriEventListener`) idem — a Fase 5b nunca executou um fluxo real.
- **A Fase 12 é o desbloqueador**: sem provisionar um atendente completo (usuário → agente →
  ramal → fila), não há como validar nada de voz de ponta a ponta. Por isso ela vem antes das
  demais na ordem recomendada.

---

## 1. Sumário

Construir sobre a infraestrutura Asterisk existente um módulo de Call Center omnicanal com duas
frentes de atendimento (voz e chat) compartilhando o mesmo motor de roteamento, o mesmo construtor
visual de fluxos ("arrasta e solta"), a mesma identidade de agente (via Active Directory) e a mesma
camada analítica. Chamadas gravadas em **`/opt/gravacoes/audio`** (novo padrão — Fase 11) são
processadas por um pipeline de IA espelhando o módulo Insights. O entregável final inclui relatórios
analíticos por URA/agente/fila com granularidade dia/semana/mês/ano, relatórios equivalentes para
chat, e um relatório de omnicanalidade com a timeline unificada do contato.

**Premissa arquitetural central:** o *flow engine* é **um só**, agnóstico de canal. Um nó
("caixinha") declara em quais canais funciona (`voice`, `chat`, `both`). O mesmo fluxo desenhado
uma vez roda como URA e como chatbot, com nós exclusivos de cada canal onde faz sentido.

**Premissa de público (nova, inferida de `pla.txt` e a confirmar — ver D7):** o atendimento é
majoritariamente **interno** — o contato é identificado por **login de rede do AD**, não por CPF
ou telefone de cliente externo. Isso muda o desenho da identidade do contato (Fase 14) e explica
por que o screen pop se apoia em `ad_users` e não numa tabela `cc_contacts` de clientes.

---

## 2. Pedido do usuário

### 2.1 Pedido original (2026-08-06), reordenado por dependência técnica

| # | Bloco | Pedido original | Depende de |
|---|-------|-----------------|-----------|
| A | **Fundação Asterisk** | "aproveitando a infraestrutura do asterisk" | — |
| B | **Identidade (AD)** | login AD, base de consulta, dados em tela do atendente | — |
| C | **Cadastros de voz** | agentes, extensões, filas, URAs | A, B |
| D | **Flow builder visual** | fluxo de URA arrasta-e-solta | C |
| E | **Gravação** | todas as chamadas gravadas | A |
| F | **Cadastros de chat** | agentes, filas de chat | B, C |
| G | **Flow builder de chat** | chatbot no mesmo modelo, reusa fluxo de URA | D, F |
| H | **Desktop do agente** | tela do atendente com dados do AD | B, C, F |
| I | **Insights do Call Center** | mesma estrutura do Insights, exclusivo desses áudios | E |
| J | **Relatórios de voz** | analítico por URA/agente/fila + sumarizado, D/S/M/A | C, E |
| K | **Relatórios de chat** | métricas de mercado do canal chat | F, G |
| L | **Omnicanalidade** | timeline do contato, transcrição de chat e de áudio | I, J, K |

### 2.2 Pedidos novos (2026-08-08, `pla.txt`)

| # | Pedido (texto do usuário, resumido) | Fase | Situação no código hoje |
|---|---|---|---|
| **P1** | Screen pop com dados do AD. Cascata: chat → login de rede; voz → URA pergunta o login; não entendido → número/ramal; nada → sem screen pop | **14** | Não existe. `DesktopAgenteTab.tsx:123-126` exibe aviso explícito de "AD não disponível". Espelho `ad_users` (V45) já tem os atributos |
| **P2** | Softphone WebRTC fixo na tela do agente **e** disponível em todo o sistema, como já é hoje | **13** | Existe só no shell Telecom (`Softphone.tsx`, flutuante). Senha SIP é **única e global** (`VITE_SIP_PASSWORD`); ramal cai para `'9001'` hardcoded sem claim. SPA do Call Center não tem softphone |
| **P3** | Ao cadastrar usuário com perfil de atendente: alocar agente + ramal e definir filas e prioridades | **12** | `UserController.createUser` não toca em `CcAgent`. Ramal do agente é digitado à mão. `penalty` existe na entidade mas a API sempre grava `0` |
| **P4** | Tela de configuração de agentes (adicionar filas ao agente) + tela de filas (inserir/excluir agentes) + criar fila copiando membros de outra | **12** | Existe só fila→agentes, sem prioridade e sem cópia. Não existe agente→filas |
| **P5** | Nó de menu do flow não permite criar opções 1, 2, 3… 9 — ajustar a ramificação | **5c** | O editor tem **um único handle de saída** sem `id`; o operador precisa digitar IDs de aresta gerados pelo React Flow num campo texto (`"1=xy-edge__abc;2=..."`) |
| **P6** | Nó de anúncio deve permitir upload de áudio a partir do próprio menu | **5c** | `audioPath` é string livre resolvida pelo Asterisk. **Não existe nenhum endpoint de upload de áudio** para URA/fluxos (só o de Insights) |
| **P7** | Painel de supervisão: ver qual cliente está em cada fila, sua posição e seu tempo de espera | **15** | Só agregados (`waitingCount`, `longestWaitSeconds`). Nenhuma ação AMI `QueueStatus` no repositório |
| **P8** | Supervisor entra na call e fala com o analista sem o cliente ouvir | **15** | **Já implementado** — `whisper` (`ChanSpy` com `bw`). Falta validar com tráfego real, corrigir a origem do ramal do supervisor e deixar o rótulo claro na UI |
| **P9** | Perfil específico pode tirar chamada da fila e direcionar para outra fila ou agente | **15** | Não existe. Nenhuma ação AMI `Redirect` no repositório |
| **P10** | Gravações de chamada em `/opt/gravacoes/audio`; gravações do chat em `/opt/gravacoes/chat` | **11** + **17** | Hoje `/opt/telecom/gravacao` (10 pontos de configuração). Chat é **só banco**, sem nada em disco. Decidido em D6: chat grava **co-browsing**, não só transcript |

### 2.3 Pedidos adicionais (2026-08-08, segunda rodada)

| # | Pedido | Fase | Situação no código hoje |
|---|---|---|---|
| **P11** | Sempre que o cliente entrar em contato (voz **e** chat), trazer o **histórico de contatos** dele | **16** | Não existe. `cc_interactions` e `cc_chat_sessions` existem, mas nada os une por contato — falta a identidade resolvida (Fase 14) |
| **P12** | **IA mapeando o perfil do cliente** e indicando ações para o agente conduzir o atendimento | **16** | Não existe. Pipeline de IA existe (Fase 8) mas roda **depois** da chamada, para análise; aqui precisa rodar **antes/durante**, para o agente |
| **P13** | **Toda nova tela de interação com IA vai ao Financeiro**, com custo por interação — **isso deve ser um padrão** | **§5.1** (regra transversal) | 4 frentes já seguem o padrão (`ura`, `insights`, `envios`, `callcenter`). Faltava a regra estar escrita como obrigação |

### 2.4 Premissa corrigida — aplicação é interna

**A aplicação não será publicada na internet; roda dentro da rede corporativa.** Isso reclassifica
o risco central do canal de chat (ver **D8**) e permite que o chat confirme ao cliente se o login
informado é válido — o que era proibido enquanto se assumia exposição pública.

---

## 3. Lacunas do pedido — o que faltou e por que importa

### 3.1 Bloqueadores técnicos originais (todos já resolvidos nas Fases 0-2)

| Achado | Situação atual |
|---|---|
| `app_queue.so` estava `noload` | ✅ habilitado na Fase 0 |
| Nenhuma integração LDAP/AD | ✅ construída na Fase 1 (falta o DC real) |
| AMI era request/response, não event-driven | ✅ `CallCenterAmiEventListener` criado na Fase 4 |
| Configuração do Asterisk 100% estática | ✅ ARA sobre PostgreSQL (`ara/` — `PsEndpoint`, `PsAuth`, `PsAor`, `AraQueue`, `AraQueueMember`) |
| Faixas de ramal ocupadas | ✅ `4000-4999` agentes, `5000-5999` filas, `6000-6999` fluxos |
| Nó único, sem redundância | ⏳ Fase 10 |

### 3.2 Lacunas descobertas no mapeamento de 2026-08-08 (não pedidas, mas necessárias)

| Achado | Onde | Impacto | Fase |
|---|---|---|---|
| **`CcAgent.userId` é `Integer` solto** — sem FK validada em Java, sem índice único | `CcAgent.java:41-42` | Dois agentes podem apontar para o mesmo usuário; `currentAgent()` (`findByUserId`) quebraria | 12 |
| **`penalty` nunca é exposto** — sempre `0` | `CallCenterQueueService.java:193,201` | Roteamento por prioridade é impossível hoje, apesar de a coluna existir | 12 |
| **Sem tela de cadastro de motivos de pausa e tabulações** | só seed em `V47` | O Desktop exige tabulação para sair do ACW, mas ninguém consegue criar uma nova | 12 |
| **`CcAgentSkill`/`CcQueueSkill` não têm classe Java** | só tabelas em `V48` | Roteamento por skill é inexistente, não só incompleto | 5f |
| **Softphone cai para ramal `'9001'` hardcoded** | `Softphone.tsx:24-31` | Usuário sem claim `extension` registra no ramal de outra pessoa | 13 |
| **Senha SIP única global no bundle** | `Softphone.tsx:39-46` (`VITE_SIP_PASSWORD`) | Incompatível com ramal por agente; qualquer usuário pode registrar como qualquer ramal | 13 |
| **`audioPath` concatenado na URL do ARI sem validação** | `AriClient.java:41-45` | Quem tem `PERM_WRITE_callcenter.fluxos` controla o caminho de som lido pelo Asterisk | 5c |
| **`FlowGraphValidator` não valida nenhuma propriedade de nó** | `FlowGraphValidator.java:102` | Publica fluxo com `opcoes` apontando para arestas inexistentes → chamada morre como `ABANDONED` | 5c |
| **`menu_opcoes` encerra a chamada no timeout** | `MenuNodeHandler.java:37-44` | Cliente que demora a digitar é desligado, sem retry nem ramo de timeout | 5c |
| **DTMF inválido é descartado em silêncio** | `AriVoiceChannelDriver.java:61-79` | Sem "opção inválida, tente de novo" | 5c |
| **`CALLCENTER_RECORDING_PATH` ausente do `.env.example`** | `.env.example` | Deploy novo não sabe que a variável existe | 11 |
| **`CLAUDE.md:702` desatualizado** | diz que o Insights varre `/opt/telecom/gravacao` | O pipeline é push-based (`GET /internal/insights/callcenter/pending`), não varredura | 11 |
| **`performChanSpy` usa o ramal do `AppUser`** | `CallCenterSupervisionActionService.java:79` | Supervisor que seja agente (ramal 4xxx) ou não tenha ramal falha na escuta | 15 |

### 3.3 Conformidade, dados e risco

| Item | Fase | Status |
|---|---|---|
| Aviso de gravação e consentimento (LGPD) | 3 | ✅ |
| Pause/resume de gravação para dado sensível | 3 (backend) / 5c (UI e nó) | 🟡 serviço pronto, sem consumidor |
| Retenção e expurgo | 3 | ✅ 60 meses configurável |
| Auditoria de acesso a gravação | 3 | ✅ |
| Mascaramento na transcrição | 8 | ✅ `insights/src/masking.py` |
| Escopo por BU no módulo inteiro | 2+ | 🟡 aplicado em cadastros/gravações; **ausente em Insights do Call Center** (gap aceito) |
| Particionamento de eventos/CDR | 10 | ❌ |
| Teste de carga (SIPp) | 10 | ❌ |
| **Vazamento de diretório corporativo pelo chat público** | **14** | ❌ **novo risco** — ver D8 |
| **Credencial SIP entregue ao browser** | **13** | ❌ **novo risco** — ver D9 |

---

## 4. Decisões arquiteturais

### 4.1 Decisões confirmadas (D1-D5, 2026-08-06)

| # | Decisão | Escolha | Validação |
|---|---|---|---|
| **D1** | Configuração dinâmica do Asterisk | **Realtime (ARA) sobre PostgreSQL** | ✅ PoC Fase 0; em produção desde a Fase 2 |
| **D2** | Motor de execução dos fluxos | **ARI + Stasis** | ✅ PoC Fase 0; motor real na Fase 5b |
| **D3** | Insights do Call Center | **Mesmo pipeline, discriminador de fonte** | ✅ Fase 8 (`call_audio_files.cc_recording_id`, `agent_performance_reports.source`) |
| **D4** | Autenticação AD | **Bind LDAP + espelho local (`ad_users`)** | 🟡 mecanismo validado, DC real pendente |
| **D5** | Biblioteca do flow builder | **React Flow (`@xyflow/react`)** | ✅ v12.11.2 em uso |

### 4.2 Decisões novas — **precisam da sua confirmação antes da implementação**

#### D6 — O que vai em `/opt/gravacoes/chat`? ✅ **decidido (2026-08-08): co-browsing gravado**

O usuário escolheu **co-browsing de verdade** — gravar a navegação do cliente na tela (DOM,
cliques, scroll) com player de replay, não apenas o transcript textual.

**Consequência de escopo:** isso não cabe na Fase 11. Vira a **Fase 17**, um subsistema próprio
(captura no widget, storage de sessão, player de replay, consentimento explícito, retenção). A
Fase 11 continua responsável por criar o diretório, a configuração e a política de retenção;
o **transcript textual da sessão é entregue junto na Fase 11** (custo baixo, e é o insumo que o
pipeline de IA das Fases 8/16 consome), e a Fase 17 acrescenta a gravação visual por cima.

#### D7 — Como o login de rede é coletado? ✅ **decidido (2026-08-08)**

- **Voz:** o cliente **fala o login e a IA transcreve** (opção escolhida). Reusa a cadeia
  STT já existente (`ai-agent` + Gemini) — mas dentro do fluxo ARI isso exige o nó `agente_ia`
  (hoje `implementado=false`), que passa a ser pré-requisito da Fase 14.
  **Mitigação obrigatória da taxa de erro** (transcrever `kaio.correa` como `caio correia` é real):
  a transcrição não vira identidade direta — ela alimenta uma **busca aproximada** em `ad_users`
  (`pg_trgm`, já usado no projeto em `tools/agente-google.py`), e o resultado é **confirmado com o
  cliente por voz** ("Confirma João da Silva? Diga sim ou não") antes de virar screen pop.
  Sem essa confirmação, um erro de transcrição entrega ao agente os dados da pessoa errada — pior
  que não identificar ninguém.
- **Chat:** pergunta textual direta — *"Qual seu login?"* — e o sistema **responde ao cliente se o
  login é válido ou não** (decidido em D8, viável porque a aplicação é interna).

#### D8 — Superfície de exposição ✅ **resolvido (2026-08-08): aplicação é interna**

**A aplicação não será publicada na internet — roda dentro da rede corporativa.** Isso elimina o
risco de enumeração do diretório corporativo que motivava a pergunta original.

**Consequências diretas:**

1. O chat **pode confirmar ao cliente** se o login é válido ou não (era o ponto proibido antes).
2. O "widget público" da Fase 7b deixa de ser público: passa a ser **widget interno**. O token
   anônimo (`scope=chat_customer`) e o rate limiting continuam válidos e úteis (defesa em
   profundidade, e o cliente ainda não é um usuário logado do VoipIA), mas deixam de ser a
   última linha de defesa contra a internet aberta.
3. A revisão de segurança dedicada ao "WebSocket de chat exposto à internet" (Fase 10, §3.3)
   muda de severidade: continua na lista, rebaixada de **Alto** para **Médio**.
4. `CALLCENTER_CHAT_PUBLIC_QUEUE_ID` e a rota `/callcenter/chat/public/**` devem ser **renomeados
   ou ao menos documentados** como internos, para ninguém reintroduzir a premissa errada depois.
   O `allowedOriginPatterns("*")` do CORS dessa rota (Fase 7b) deve ser restringido às origens
   corporativas reais — hoje é aberto porque se assumia widget embutido em site externo.

#### D9 — Como o softphone do agente obtém a credencial SIP? ⚠️ **risco de segurança**

Hoje a senha é única, global e embutida no bundle JS (`VITE_SIP_PASSWORD`) — qualquer usuário
autenticado pode registrar-se como qualquer ramal. Com ramal por agente (`cc_extensions.secret`,
gerado e nunca exposto em `GET`), isso não se sustenta.

- **Opção A (recomendada) — endpoint autenticado sob demanda.**
  `GET /api/v1/callcenter/agentes/me/sip-credentials` devolve `{extension, secret, wsUrl}` **do
  agente do usuário logado**, resolvido por `currentAgent()`, auditado a cada chamada, nunca em
  listagem. O secret continua chegando ao browser — é inerente ao SIP over WebRTC com autenticação
  digest — mas deixa de ser compartilhado e passa a ser rotacionável por agente.
- **Opção B — proxy SIP com token efêmero.** Elimina o secret do browser, mas exige um componente
  de autenticação customizado no Asterisk que não existe e não é suportado nativamente pelo PJSIP.
  Desproporcional ao ganho.

**Recomendo A**, registrando explicitamente o resíduo aceito: *o secret SIP do próprio agente é
visível para o próprio agente*. É estritamente melhor que hoje.

#### D10 — Um softphone ou dois? ⚠️ **decisão que evita um bug grave**

O agente trabalha no Desktop do Agente, que roda **dentro de um iframe** (`/callcenter/`) sob o
shell Telecom, onde o softphone flutuante já existe. Se a SPA do Call Center instanciar o próprio
JsSIP, **dois user agents registram o mesmo ramal simultaneamente** — o Asterisk faz fork da
chamada para os dois, e o comportamento de atendimento vira loteria.

- **Opção A (recomendada) — um único UA, no shell; a SPA controla por `postMessage`.** O softphone
  segue morando no shell Telecom (atende o pedido "disponível em todo o sistema"). O Desktop do
  Agente ganha um **painel de chamada fixo** que comanda o softphone pelo `useShellBridge`, com
  tipos de mensagem novos (`callState` shell→iframe, `callAction` iframe→shell). Quando a SPA é
  aberta **direta** (`/callcenter/`, sem shell — `window.self === window.top`), aí sim ela
  instancia o próprio softphone, e nunca há dois ao mesmo tempo.
- **Opção B — softphone dentro da SPA e escondido no shell quando na página do Call Center.**
  Quebra "disponível em todo o sistema" durante a navegação e ainda deixa janela de corrida na
  troca de página.

**Recomendo A.**

#### D11 — Fonte de verdade da fila em tempo real

- **Opção A (recomendada) — `QueueStatus` sob demanda pela conexão do listener AMI**, correlacionando
  a resposta multi-evento (`QueueParams`/`QueueEntry`/`QueueStatusComplete`) por `ActionID`.
  Sempre correto, não depende de manter estado consistente entre eventos, e o `Position`/`Wait` vêm
  prontos do Asterisk.
- **Opção B — reconstruir a fila a partir dos eventos** (`QueueCallerJoin` traz `Position`, mas
  seria preciso tratar `QueueCallerLeave` — hoje **não tratado** — para recalcular a posição dos
  demais a cada saída). Estado derivado, propenso a divergir do Asterisk.

**Recomendo A para o painel** (snapshot) **e B para o histórico** (persistir `position` em
`cc_interactions` no `QueueCallerJoin`, que é dado de relatório, não de tela).

#### D12 — Onde ficam os áudios da biblioteca do flow builder?

O upload chega ao **backend**, mas quem toca o som é o **Asterisk**, que resolve `sound:<path>`
dentro do próprio container. Não há volume compartilhado de sons entre os dois hoje.

✅ **Decidido (2026-08-08): `/opt/gravacoes/flow`.**

- **Opção A (escolhida)** — bind mount `/opt/gravacoes/flow` → `backend:rw` e
  `asterisk:/var/lib/asterisk/sounds/asteriskia:ro`. O backend grava, o Asterisk lê como
  `sound:asteriskia/<arquivo>`. Transcodificação para PCM 8 kHz/16-bit mono via `ffmpeg`, que
  **já está instalado no backend** (`backend/Dockerfile:34`).
- **Opção B** — servir o áudio por HTTP e usar `sound:http://...` no ARI. Adiciona latência no
  hot-path de voz e uma dependência de rede onde hoje há leitura de disco local.

**Recomendo A.** Observação: o mesmo diretório passa a servir o `consentMessagePath` das filas
(hoje caminho digitado à mão em `FilasTab.tsx:150`, validado contra `<base>/avisos`).

---

## 5. Padrões do repositório a espelhar

| Categoria | Fonte | Padrão a seguir |
|---|---|---|
| Módulo com SPA própria | `callcenter-platform/frontend/` | Vite+React+TS servido em `/callcenter`; backend Java reusado |
| Submenu na Sidebar do Telecom | `frontend/src/components/Sidebar.tsx:94-102` | `NavParent` com filhos; iframe não remonta ao trocar aba |
| Ponte shell↔iframe | `useShellBridge.ts` + `CallCenterPage.tsx:20-48` | Tripla validação: `origin`, `event.source`, `data.source`. **Estender com `callState`/`callAction` na Fase 13** |
| RBAC granular | `ResourceCatalog.java:87-109` + `SecurityConfig` + `Sidebar.tsx` + `AccessGroups.tsx` | **4 pontos de sincronia manual** — 18 resources `callcenter.*` hoje |
| Escopo por BU | `CallCenterSpecifications`, `CallCenterFlowService:260-275` | Aplicar em toda query nova |
| Upload de arquivo | `InsightsUploadService.java:34-92` | Limite de tamanho/quantidade, allowlist de extensão, `sanitizeFileName`, `target.startsWith(dir)` anti-traversal |
| Streaming de áudio autenticado | `CallCenterRecordingController.java:62-85` + `AuthedAudio.tsx` | Escopo de BU → 404 (nunca 403, não vaza existência); auditoria de reprodução |
| Resolução segura de caminho | `CallCenterRecordingService.resolveAudioFile:199-213` | Usa só o **nome-base** do path persistido e reconstrói o diretório de dado confiável do banco |
| Endpoint interno do dialplan | `CallCenterRecordingIngestController.java:22` | `/api/v1/internal/**`, só `X-Internal-Key` via `InternalKeyFilter` |
| Ação AMI | `AmiOriginateService.sendAction:157-189` | `sanitizeAmiField` remove CR/LF (injeção de ação) |
| Scheduler + alerta Telegram | `CallCenterDiskAlertScheduler`, `CostAlertScheduler` | Cron configurável em `application.properties`, dedup por período |
| Erro de regra de negócio | `CcChatService`, `CallCenterQueueAggregationService` | `ResponseStatusException(404/400)` — **nunca** `IllegalStateException` crua (causou 500 genérico na Fase 4) |
| **Custo de IA** | `CostAlertService.java:40`, `ai_model_pricing`, `insights/src/token_usage.py` | **Ver regra obrigatória abaixo** |
| Migrations | `db/migration/` — última é **V59** | Próxima: **V60**. Irreversíveis |
| Release notes | `frontend/src/data/releases.ts` — última **v1.58** | Entrada obrigatória por entrega |

### 5.1 Regra obrigatória — toda interação com IA aparece no Financeiro

**Definida pelo usuário em 2026-08-08. Vale para todo o VoipIA, não só para o Call Center.**

> Nenhuma tela ou funcionalidade nova que chame um modelo de IA entra em produção sem estar
> visível no módulo Financeiro, com custo por interação.

Checklist a cumprir em **toda** entrega que adicione uma chamada a LLM/STT/TTS:

1. **Contabilizar tokens** na origem — `insights/src/token_usage.py` (Python) ou o equivalente no
   Java; nunca estimar.
2. **Persistir o custo junto com o artefato gerado** (`input_tokens`, `output_tokens`, `cost_usd`,
   `model`), precificado por `ai_model_pricing` — que já é sincronizado diariamente e nunca
   sobrescreve com valor inválido.
3. **Frente própria em `CostAlertService.SCOPES`** (`CostAlertService.java:40`) quando a natureza
   do gasto for distinta das existentes — hoje `ura`, `insights`, `envios`, `callcenter`.
4. **Submenu no módulo Financeiro** com as três telas do padrão já estabelecido: custos
   detalhados, dashboard de evolução, e **alerta de gasto em USD** com limite mensal configurável
   e notificação por Telegram.
5. **Custo por interação visível na própria tela onde a IA é usada** — não só agregado no
   Financeiro. É o que permite ao operador perceber um gasto anômalo no momento em que acontece.
6. **RBAC**: recurso `financeiro.<frente>` novo, com os 4 pontos de sincronia manual.
7. **Controle de reuso/cache** documentado — toda frente de IA precisa de uma resposta explícita
   para "quando eu *não* chamo o modelo?".

Frentes existentes que já seguem o padrão: `financeiro.ura`, `financeiro.insights`,
`financeiro.envios`, `financeiro.callcenter` (V41, V42, V54).
Frente nova prevista neste plano: **`callcenter_copiloto`** (Fase 16).

---

## 6. Modelo de dados

### 6.1 Existente (V45-V59)

**Identidade** — `ad_users` (espelho AD: `sam_account_name`, `display_name`, `department`,
`office`, `title`, `member_of`, `manager_sam`, `email`, `telephone_number`) · `ad_sync_runs` ·
`ad_group_mappings` · `app_users.ad_linked`

**Agente e roteamento** — `cc_agents` · `cc_extensions` · `cc_queues` · `cc_queue_members`
(**tem `penalty`, nunca usado**) · `cc_skills` · `cc_agent_skills` / `cc_queue_skills`
(**tabelas sem classe Java**) · `cc_pause_reasons`

**Operação** — `cc_agent_states` · `cc_interactions` · `cc_interaction_events` · `cc_dispositions`

**Fluxos** — `cc_flows` · `cc_flow_versions` (grafo `jsonb` nativo do React Flow) ·
`cc_flow_executions` · `cc_flow_execution_steps`

**Gravação** — `cc_recordings` · `cc_recording_retention_config` · `cc_recording_disk_alert_config`

**Supervisão** — `cc_supervision_actions` · `cc_queue_alert_config`

**Chat** — `cc_chat_channels` · `cc_chat_sessions` · `cc_chat_messages` · `cc_canned_responses`

**Relatórios** — `cc_agg_queue_daily` · `cc_agg_agent_daily`

**IA** — `call_audio_files.cc_recording_id` · `agent_performance_reports.source`

### 6.2 Novo (Fases 11-15)

| Migration | Objeto | Fase |
|---|---|---|
| **V60** | `UPDATE cc_recordings SET file_path = replace(...)` (novo prefixo) + `cc_chat_sessions.transcript_path` | 11 |
| **V61** | Índice único em `cc_agents.user_id`; `cc_queue_members.penalty` exposto (sem DDL, só API) | 12 |
| **V62** | `cc_audio_files` (biblioteca de áudios: `id`, `name`, `file_name`, `format`, `duration_seconds`, `business_unit_id`, `uploaded_by`, `created_at`) | 5c |
| **V63** | `ad_users.employee_id` (matrícula, D7-A) + `cc_interactions.resolved_ad_sam`/`identity_source` + `cc_chat_sessions.resolved_ad_sam`/`identity_source` | 14 |
| **V64** | `cc_interactions.position_on_join`, `cc_interactions.channel_name`; `cc_supervision_actions.agent_id` nullable + `target_queue_id`/`target_agent_id`; RBAC `callcenter.supervisao.redirect` | 15 |

---

## 7. Indicadores a implementar

**Voz** — Volume recebido/atendido/abandonado · Taxa de abandono (com e sem *short abandon*) ·
Nível de Serviço · ASA · TMA/AHT · TMO · Tempo de conversação · Tempo em espera · ACW · Ocupação ·
Utilização · Aderência à escala · Transferências · Rechamada em 24h/7d · Retenção na URA ·
Abandono dentro da URA por nó · Distribuição por hora/dia · Top motivos (tabulação) · CSAT/NPS.

**Chat** — Sessões iniciadas/atendidas/abandonadas · FRT · ART · Tempo de resolução · Duração ·
Concorrência média e pico · Taxa de contenção do bot · Taxa de escalonamento · Mensagens por
sessão · Abandono em fila · Sessões por agente/hora · CSAT · Sentimento.

**Entregues até aqui:** volume/atendidas/abandonadas, ASA, aproximação de TMA (só conversação),
nível de serviço (Fase 9a); ocupação/disponibilidade por agente (Fase 9b).

---

## 8. Fases 0-10 — escopo e pendências

> O relato detalhado de cada entrega (bugs encontrados, achados de revisão, testes) está em
> **§16 — Histórico** e, com mais detalhe ainda, em `CLAUDE.md`.

### FASE 0 — Fundação e provas de conceito ✅
`app_queue.so` habilitado, PoCs de ARA/ARI/AMI validados, faixas reservadas (`4000-4999` agentes,
`5000-5999` filas, `6000-6999` fluxos), consumo de disco medido, teto de capacidade da VPS medido
(**insuficiente para 200/200** — produção real vai para servidor dedicado).

### FASE 1 — Identidade: Active Directory 🟡
**Entregue**: `integration/ad/` (14 classes), `LdapClient` (bind + `fetchAll` + `testConnection`),
`AdUserService` (espelho local, nunca consulta o AD ao vivo), `AdSyncScheduler`, mapeamento
grupo AD → grupo de acesso, tela em `Settings.tsx`, `app_users.ad_linked` (fix de CRITICAL:
sequestro de conta local homônima).
**Pendências**: (1) dados reais do DC; (2) validação visual da tela; (3) `member_of` exige DN
completo, não CN; (4) `fetchAll()` não pagina (~1000 usuários); (5) **`employee_id` não é
espelhado** — necessário para D7-A.

### FASE 2 — Cadastros de voz e configuração dinâmica ✅
CRUD de agentes/ramais/filas/skills com ARA (`PsEndpoint`/`PsAuth`/`PsAor`/`AraQueue`/
`AraQueueMember`), SPA `callcenter-platform/frontend` (14 abas), RBAC `callcenter.*`.
**Buracos que a Fase 12 fecha**: ramal digitado à mão, `penalty` inutilizado, sem vínculo com o
cadastro de usuário, sem UI de pausa/tabulação, skills sem vínculo.

### FASE 3 — Gravação, retenção e conformidade ✅
`MixMonitor` no dialplan `_5XXX` (fail-open), consentimento por fila, ingestão via endpoint
interno, aba "Gravações" com player autenticado e auditoria, retenção de 60 meses com expurgo
diário, alerta de disco via Telegram.
**Pendências**: caminho muda na Fase 11; `CallCenterRecordingControlService` (pause/resume via
`MixMonitorMute`) está pronto **sem nenhum consumidor** — vira o nó `pausar_gravacao` na Fase 5c.

### FASE 4 — Estados do agente, interações e tabulação 🟡
`CallCenterAmiEventListener` (4 eventos: `QueueCallerJoin`, `AgentConnect`, `AgentComplete`,
`QueueCallerAbandon`), estados com histórico, tabulação obrigatória no ACW, aba "Desktop do Agente".
**Pendências**: softphone (Fase 13), screen pop (Fase 14), transferência/hold/conferência,
**mapeamento AMI nunca validado com tráfego real**.

### FASE 5 — Flow builder visual (voz) 🟡
**5a** ✅ editor React Flow, versionamento draft/published/archived com lock pessimista, catálogo
de 14 nós servido pelo backend, validador de grafo.
**5b** ✅ motor ARI/Stasis (`FlowExecutionEngine` + `ChannelDriver` + `AriVoiceChannelDriver`),
7 nós executáveis, guarda de 200 passos, fallback para fila em exceção.
**5c** ⏳ **repriorizada** — ver §9 (menu 1-9 + biblioteca de áudios; era simulador).
**5d** ⏳ simulador (dry-run passo a passo, reusa `ChannelDriver` com driver falso).
**5e** ⏳ horário de funcionamento, feriados, transbordo.
**5f** ⏳ roteamento por skill (exige criar `CcAgentSkill`/`CcQueueSkill` do zero), traço de
execução na UI (endpoint existe, tela não).
**7 nós ainda bloqueados**: `coletar_entrada`, `consultar_api`, `transferir_ramal`,
`horario_funcionamento`, `agente_ia`, `pausar_gravacao`, `pesquisa_satisfacao`.

### FASE 6 — Supervisão em tempo real 🟡
Painel de filas/agentes (agregados em memória), escuta/sussurro/barge via `ChanSpy`, forçar
pausa/despausa, Modo TV, alerta de SLA via Telegram, auditoria de toda ação.
**Pendências**: ver Fase 15.

### FASE 7 — Canal de chat 🟡
**7a** ✅ modelo de dados, roteamento por claim, aba "Chat", simulador ADMIN-only.
**7b** ✅ token de sessão anônimo (`scope=chat_customer`, 2h, sem `role`/`perm`/`bu`), fila fixa
por config, rate limiting em memória, widget JS embeddável, CORS unificado em
`CorsConfigurationSource`.
**Pendências**: `CALLCENTER_CHAT_PUBLIC_QUEUE_ID` sem fila real; widget não validado em página
real; WhatsApp/Telegram (sem credenciais); flow builder de chat; blending; anexos; retomada de
sessão; tempo real para o cliente (hoje polling).

### FASE 8 — Insights do Call Center ✅
Ingestão push-based correlacionada no `ingest` (mais confiável que o XML da Verint), mascaramento
de dado sensível antes do LLM, 5 telas, isolamento por `source` nos relatórios de agente.
**Gap aceito**: `agent_evolution_snapshots` sem `source`; sem escopo de BU.

### FASE 9 — Relatórios analíticos 🟡
**9a** ✅ `cc_agg_queue_daily`, médias ponderadas por volume, scheduler noturno + reprocessamento.
**9b** ✅ `cc_agg_agent_daily`, ocupação com recorte de meia-noite e período aberto.
**9c** ⏳ agregado de fluxo/URA e de chat, timeline omnicanal, exportação Excel/PDF, agendamento
por e-mail/Telegram, drill-down até a interação, rechamada 24h/7d, top tabulações, aderência à
escala (exige conceito de escala/turno, inexistente).

### FASE 10 — Endurecimento, carga e operação ❌
Teste de carga SIPp e de chat; particionamento de `cc_interaction_events`/`cc_chat_messages`;
revisão de segurança completa (AD/LDAP, ARI, WebSocket de chat, SSRF do nó `consultar_api`,
upload de áudio da 5c, credencial SIP da 13); limites de recurso e healthchecks; documentação em
`Documentacao.tsx`; **recomendação de hardware do servidor dedicado**.

---

## 9. Parte II — Fases novas (pedidos de 2026-08-08)

### FASE 11 — Padronização dos caminhos de gravação _(P10)_ — ✅ **implementada, testada, revisada e deployada (2026-08-08)**

**Objetivo:** `/opt/telecom/gravacao` → **`/opt/gravacoes/audio`**; chat passa a ter artefato em
disco em **`/opt/gravacoes/chat`**.
**Complexidade:** **P** (voz) + **M** (chat, se D6-A).
**Por que primeiro:** é barato agora e caro depois — cada dia de operação real move mais arquivos.

**Entregue**: 11.1/11.2/11.3 abaixo implementadas, migration **V60** aplicada e confirmada em
`flyway_schema_history`, 436/436 testes verdes na primeira rodada (depois **429/429** após a
reversão do mascaramento, ver nota abaixo) — nenhuma regressão. Revisão paralela
`ecc:security-reviewer` + `ecc:code-reviewer` encontrou e corrigiu antes do commit: 1 achado
**HIGH** real (mount de `/opt/gravacoes/chat` ausente no `docker-compose.yml` — o transcript seria
escrito na camada de escrita efêmera do container e perdido a cada rebuild) e 2 **MEDIUM** (4
defaults `@Value` de `app.callcenter.recording-path` ainda apontando para o caminho antigo, mesmo
com `application.properties` já corrigido; e — revertido depois a pedido do usuário — o corpo do
chat sendo gravado sem mascaramento de CPF/cartão/telefone).

**Achado real de deploy, fora do escopo original desta fase mas bloqueante para ela**: ao
rebuildar o `backend`, uma mudança de Dockerfile já pendente antes desta sessão (de-rootização do
container, `USER backend` uid 1501/gid 1500 `voipia-app`) entrou em vigor e o backend passou a
rodar como não-root. Os dois diretórios novos (`/opt/gravacoes/audio`, `/opt/gravacoes/chat`),
criados pelo bind mount do Docker como `root:root 755`, ficaram ilegíveis/inescreveíveis pelo
backend — o Asterisk (root) grava normalmente, mas o backend não conseguia ler/expurgar o próprio
arquivo gravado nem exportar o transcript. Corrigido com o **mesmo padrão já usado em
`/opt/VoipIA/env`**: `chown root:voipia-app` + `chmod 2770` (setgid — todo arquivo criado
dentro, mesmo por um processo root, herda o grupo `voipia-app`, dando ao backend acesso via
grupo). `scripts/migrar-gravacoes.sh` foi estendido para aplicar essa permissão nos dois
diretórios de forma idempotente, incondicional (mesmo sem nada para migrar) — reprodutível no
servidor dedicado de produção.

**Decisão do usuário (2026-08-08): sem mascaramento de PII no transcript de chat.** A
implementação inicial replicava `insights/src/masking.py` (CPF/cartão/telefone) para o corpo das
mensagens antes de gravar — achado real levantado pela revisão de segurança (MEDIUM). O usuário
pediu explicitamente a remoção ("retira o mascaramento de cpf pq eu nao pedi isso"): o transcript
de chat grava o corpo das mensagens **em texto puro**, sem mascaramento. Classe
`SensitiveDataMasker` e seu teste foram removidos; `ChatTranscriptExportService` voltou a gravar
`customerName`/`body` sem transformação.

**Validado em produção via simulador de chat ADMIN-only** (fila de teste criada e removida depois,
mesmo padrão de "sem dado real cadastrado nesta VPS" já registrado em outras fases): sessão
iniciada → mensagem → encerrada como ADMIN → transcript `.json`/`.txt` confirmado em
`/opt/gravacoes/chat/YYYY/MM/DD/` → `cc_chat_sessions.transcript_path` persistido corretamente.
Dados de teste removidos ao final (fila, sessão, mensagem, arquivos).

#### 11.1 Voz — troca do caminho (10 pontos, todos mapeados)

| Arquivo | Linha | Mudança |
|---|---|---|
| `docker-compose.yml` | 127 | mount do `asterisk` → `/opt/gravacoes/audio` |
| `docker-compose.yml` | 235 | mount do `insights` (`:ro`) |
| `docker-compose.yml` | 344 | env `CALLCENTER_RECORDING_PATH` |
| `docker-compose.yml` | 374 | mount do `backend` (`:rw`) |
| `application.properties` | 112 | default de `app.callcenter.recording-path` |
| `extensions.conf.template` | 115, 203 | `REC_DIR` nos dois contextos |
| `.env.example` | — | **adicionar** `CALLCENTER_RECORDING_PATH` (hoje ausente) |
| `FilasTab.tsx` | 150 | placeholder do caminho de consentimento |
| `CLAUDE.md` | 702 | corrigir o caminho **e** a afirmação errada de que o Insights varre o diretório |
| testes | `CallCenterRecordingServiceTest`, `CallCenterQueueServiceTest`, `InsightsIngestionServiceTest` | atualizar fixtures |

As 4 classes Java (`CallCenterRecordingService:42`, `CallCenterDiskAlertService:30`,
`CallCenterQueueService:34`, `CallCenterInsightsController:66`) leem o valor por `@Value` com
default literal — **basta atualizar o default**, nenhuma lógica muda.

#### 11.2 Migração dos dados existentes

**Achado favorável:** `resolveAudioFile` (`CallCenterRecordingService:199-213`) usa apenas o
**nome-base** do `file_path` persistido e reconstrói `yyyy/MM/dd` a partir de `startedAt` + base
path da configuração. Ou seja, **mover os arquivos físicos é suficiente** — o banco não quebra.

- Script de migração `scripts/migrar-gravacoes.sh`: `mkdir -p /opt/gravacoes/audio` →
  `rsync -a --remove-source-files` preservando a árvore `YYYY/MM/DD` → verificação de contagem
  antes/depois → só então remover a origem.
- **Migration V60** (cosmética, por consistência do dado): `UPDATE cc_recordings SET file_path =
  replace(file_path, '/opt/telecom/gravacao', '/opt/gravacoes/audio')`. Não é funcionalmente
  necessária; é para o dado não mentir sobre onde o arquivo está.
- **Nesta VPS a migração é trivial** — `/opt/telecom/gravacao` está vazio (4 KB). O script existe
  para o servidor dedicado de produção.

#### 11.3 Chat — artefato em disco (assumindo **D6-A**)

- Nova propriedade `app.callcenter.chat-transcript-path` (default `/opt/gravacoes/chat`) + env
  `CALLCENTER_CHAT_TRANSCRIPT_PATH` + mount `rw` no `backend`.
- `CcChatService.close(...)` passa a exportar a sessão: `/opt/gravacoes/chat/YYYY/MM/DD/
  <sessionId>.json` (mensagens com remetente/timestamp) e `.txt` (legível). Export **assíncrono e
  tolerante a falha** — nunca impedir o encerramento da sessão por erro de I/O (mesmo princípio do
  fail-open do dialplan). Falha loga e marca a sessão para reexportação.
- `cc_chat_sessions.transcript_path` (V60) guarda o caminho — mesma disciplina do `file_path` de
  voz: só o nome-base é reaproveitado na leitura.
- Retenção e alerta de disco: estender `CallCenterRecordingRetentionService` e
  `CallCenterDiskAlertService` para cobrir o segundo diretório, ou aceitar que
  `Files.getFileStore` já mede o filesystem inteiro (**hoje mede o FS, não o diretório** — se
  `/opt/gravacoes` for o mesmo FS, o alerta já cobre os dois).

**Validação:** `docker compose up -d --build backend asterisk insights frontend` — feito.
Chamada de teste real de voz ainda depende da Fase 12 (sem agente/fila provisionados de ponta a
ponta nesta VPS); a leitura do transcript e o mecanismo de retenção/streaming não dependem disso e
já foram validados via o simulador de chat.

---

### FASE 12 — Provisionamento de atendente e gestão de filas _(P3, P4)_

**Objetivo:** cadastrar um atendente completo em uma tela e distribuí-lo por filas com prioridade.
**Complexidade:** **G**.
**Por que segundo:** é o desbloqueador de toda validação real do módulo (hoje há **zero** agentes).

#### 12.1 Perfil "atendente" no cadastro de usuário

- `CreateUserRequest` ganha `callCenterAgent: boolean` e
  `queueMemberships: List<QueueMembershipRequest{queueId, priority}>`.
- Novo `CallCenterAgentProvisioningService.provisionForUser(AppUser, request)`:
  1. aloca o próximo ramal livre em **4000-4999** (novo `CcExtensionRepository.findNextExtension`,
     espelhando `UserRepository.findNextExtension` usado em `UserController:92`);
  2. gera o secret (`ExtensionSecretGenerator`) e provisiona ARA (`PsEndpoint`/`PsAuth`/`PsAor`) —
     reusa o caminho já existente em `CallCenterAgentService`, **não duplicar**;
  3. cria `CcAgent` (`userId`, `name` = `displayName`, `businessUnit` = primeira BU selecionada);
  4. insere os `CcQueueMember` com `penalty`, espelhando em `AraQueueMember`.
- `UserController.createUser` chama o serviço **dentro da mesma transação** — se o provisionamento
  falhar, o usuário não é criado pela metade. (Verificar/adicionar `@Transactional`.)
- **Desativação** (`DELETE /users/{id}`): remover o agente das filas ARA (senão o Asterisk continua
  tocando um ramal morto e a chamada fica sem resposta) e desativar `CcAgent`, **preservando** a
  linha para histórico de relatórios.
- **Reversibilidade**: um usuário existente pode virar atendente depois (`PUT /users/{id}` com o
  mesmo flag), e o provisionamento é idempotente — se já houver agente, erro claro (409/400), não
  duplicação.

#### 12.2 Correção estrutural: `CcAgent.userId`

- **Migration V61**: `CREATE UNIQUE INDEX ... ON cc_agents(user_id) WHERE user_id IS NOT NULL` +
  FK explícita para `app_users(id)`.
- Sem isso, dois agentes podem apontar para o mesmo usuário e `currentAgent()`
  (`CallCenterAgentStateService:53`, `findByUserId`) quebra em runtime.

#### 12.3 Prioridade de fila (`penalty`) exposta

- `CallCenterQueueService.addMember(queueId, agentId, **penalty**)` — hoje fixo `0` em `:193` e
  `:201`. Novo `updateMemberPenalty(queueId, agentId, penalty)`.
- Espelhar sempre em `AraQueueMember.penalty` (é o campo que o Asterisk realmente lê).
- **UI**: expor como **"Prioridade"** de 1 (mais alta) a 9, mapeando 1:1 para `penalty`, com nota
  explícita "menor valor é atendido antes" — a semântica do Asterisk é contraintuitiva e a UI não
  deve repassar a confusão.

#### 12.4 Tela de Agentes → filas

- Endpoints novos em `CallCenterAgentController` (RBAC `callcenter.agentes`):
  `GET /agentes/{id}/filas`, `POST /agentes/{id}/filas/{queueId}`, `DELETE`, `PUT .../prioridade`.
  Escrevem nas mesmas tabelas dos endpoints de fila — **a lógica vive só em
  `CallCenterQueueService`**, os controllers são fachadas.
- `AgentesTab.tsx`: modal "Filas do agente" (listar, adicionar, remover, editar prioridade),
  espelhando o modal de membros que já existe em `FilasTab.tsx:164-199`.
- Formulário de agente ganha o campo **usuário vinculado** (hoje `userId` existe no DTO mas
  **não tem input** — `AgentesTab.tsx:7`), com busca por usuário do sistema.

#### 12.5 Tela de Filas → agentes (completar) + clonar membros

- Modal de membros ganha coluna **Prioridade** editável.
- `QueueRequest` ganha `copyMembersFromQueueId` (opcional). `CallCenterQueueService.create` copia
  `CcQueueMember` + `AraQueueMember` da origem, **validando o escopo de BU da fila de origem** —
  copiar de uma fila de outra BU seria vazamento de composição de equipe.
- UI: checkbox "Copiar membros de outra fila" + select, no formulário de criação.

#### 12.6 Cadastro de motivos de pausa e tabulações (lacuna descoberta)

Não existe UI para nenhum dos dois — só o seed da V47 (ALMOCO/BANHEIRO/FEEDBACK/TREINAMENTO) e
consumo read-only no Desktop. Como a tabulação é **obrigatória** para o agente sair do ACW, a
operação real fica presa ao seed. Duas abas pequenas de CRUD (ou uma aba "Configurações do Call
Center" com as duas seções), sob `callcenter.agentes` ou resource novo `callcenter.config`.

**Testes:** `CallCenterAgentProvisioningServiceTest` (7 testes: aloca ramal livre; idempotência —
usuário já com agente falha com 409; faixa esgotada falha com 409; falha de fila convertida em 400,
não 500; desativação remove das filas ARA e preserva o agente; sem-op sem agente vinculado),
`CallCenterQueueServiceTest` (+5: penalty persistido e espelhado em ARA; prioridade negativa
rejeitada; `updateMemberPenalty` espelha; cópia de membros respeita escopo de BU da origem; cópia
de fila fora do escopo falha limpo). Suíte completa **443/443 verde** (+14 desta fase).

**Achado real corrigido antes do commit (revisão de segurança)**: `POST /users` era protegido só
por `PERM_WRITE_telecom.users` — um grupo customizado com essa permissão e **sem**
`PERM_WRITE_callcenter.filas` conseguia, via `queueMemberships`, vincular um agente a qualquer
fila, exercendo uma permissão que não lhe foi concedida (escalação de privilégio real, atenuada
pelo contexto de rede interna). Corrigido com checagem explícita em `UserController.createUser` —
`ROLE_ADMIN` ou `PERM_WRITE_callcenter.filas` exigida sempre que `queueMemberships` não vier vazio,
com teste dedicado (403 sem a permissão, 200 com ela).

**Achado real, também corrigido**: `IllegalArgumentException` lançada por `CallCenterAgentService`/
`CallCenterQueueService` caía no catch-all do `GlobalExceptionHandler` (500 genérico), não no
padrão de erro claro do resto do módulo — `CallCenterAgentProvisioningService` reconverte para
`ResponseStatusException` antes de propagar.

**Gap conhecido, não introduzido por esta fase (pré-existente no módulo, não corrigido)**:
`CallCenterAgentService.findById`/`CallCenterQueueService.findById` lançam `IllegalArgumentException`
para "não encontrado", que ainda cai no catch-all 500 nos endpoints de leitura antigos
(`GET /agentes/{id}`, `GET /filas/{id}/membros`) e nos novos que os reusam
(`GET /agentes/{id}/filas`) — confirmado com um agente/fila inexistente retornando 500 em vez de
404. Fora do escopo desta fase corrigir todo o padrão do módulo (mesmo tipo de achado já corrigido
pontualmente na validação visual das Fases 7a/7b/8/9a, mas nunca generalizado).

**Validado em produção via curl com JWT forjado inline**: fila e usuário atendente criados de
ponta a ponta → confirmado no banco `cc_agents`/`cc_extensions`/`cc_queue_members` (`penalty=3`
persistido e espelhado em `queue_members`/ARA com o ramal `4000`, primeiro da faixa) →
`DELETE /users/{id}` → agente desativado (`active=false`), linha preservada, removido de
`cc_queue_members` e `queue_members`. Dados de teste limpos ao final. **Softphone real e chamada
atravessando a fila ainda não validados nesta VPS** — depende da Fase 13 (softphone do agente) e
de tráfego real, que segue como incerteza aberta do módulo (mapeamento de eventos AMI/ARI nunca
confirmado contra o Asterisk real).

---

### FASE 13 — Softphone do agente _(P2)_

**Objetivo:** softphone fixo na tela do agente e disponível em todo o sistema, com a credencial do
**ramal do agente** (4xxx), não a senha global.
**Complexidade:** **G**. **Depende da Fase 12** (o ramal do agente precisa existir).
**Decisões que governam esta fase:** **D9** (credencial) e **D10** (um único UA).

#### 13.1 Credencial por agente (D9-A)

- `GET /api/v1/callcenter/agentes/me/sip-credentials` → `{extension, secret, wsUrl, realm}`, do
  agente do usuário logado, resolvido por `currentAgent()` (que já responde 404 sem vínculo).
  Auditado a cada chamada (`AuditService`), nunca em listagem, RBAC `callcenter.desktop`.
- **Rate limit** no endpoint (reusar o padrão de `PublicChatRateLimiter`) — é uma credencial.
- Endpoint de **rotação** do secret (`POST /agentes/{id}/rotate-secret`, `callcenter.ramais`),
  já que agora a credencial circula.

#### 13.2 `Softphone.tsx` — correções e credencial dinâmica

- **Corrigir o fallback `'9001'` hardcoded** (`:24-31`): sem claim `extension` e sem agente, o
  softphone **não registra** e mostra estado "sem ramal" — hoje ele se registra silenciosamente no
  ramal de outra pessoa.
- Ordem de resolução: (1) agente de call center via o endpoint novo → ramal 4xxx + secret próprio;
  (2) senão, claim `extension` do JWT + `VITE_SIP_PASSWORD` (comportamento legado dos ramais 9xxx);
  (3) senão, não registra.
- Extrair a lógica de UA/registro para `hooks/useSipPhone.ts` — hoje as 576 linhas são um
  componente monolítico, e a Fase 13 precisa reusar a lógica em dois lugares (D10).

#### 13.3 Painel de chamada fixo no Desktop do Agente (D10-A)

- `useShellBridge` ganha dois tipos de mensagem novos (hoje só existem `ready`/`navigate`/
  `tabChanged` — `CallCenterPage.tsx:20-48`):

| Direção | `type` | Payload |
|---|---|---|
| shell → iframe | `callState` | `{status: 'idle'\|'registering'\|'ringing'\|'active'\|'held', remote: string, durationSeconds: number, muted: boolean}` |
| iframe → shell | `callAction` | `{action: 'answer'\|'hangup'\|'reject'\|'mute'\|'unmute'\|'dtmf'\|'dial', payload?: string}` |

- Manter a **tripla validação** já usada (`origin`, `event.source`, `data.source`) nos dois lados.
- `DesktopAgenteTab.tsx` ganha um painel fixo no topo: estado do registro, chamada em curso,
  botões atender/encerrar/mudo/teclado DTMF, campo de discagem manual (o "ativo manual" já
  decidido em §14.5).
- **Quando a SPA roda direta** (`window.self === window.top`, sem shell): instancia o próprio
  `useSipPhone`. Assim **nunca há dois UAs no mesmo ramal** — que causaria fork da chamada.

#### 13.4 Integração com o estado do agente

- Ao registrar → estado `DISPONIVEL` (opt-in, não automático — o agente pode estar logado sem
  querer receber).
- Chamada ativa/encerrada continuam vindo dos eventos AMI (`AgentConnect`/`AgentComplete`), que são
  a fonte de verdade — o softphone **não** escreve estado, só reflete. Evita duas fontes divergindo.

**Testes:** `useSipPhone` com JsSIP mockado (resolução de credencial nas 3 ordens; não registra sem
ramal); teste do bridge (mensagem de origem/source errada é ignorada; `callAction` desconhecido não
quebra o shell). Backend: `sip-credentials` só devolve o próprio agente; 404 sem vínculo; auditoria
gravada.

**Validação:** dois usuários atendentes distintos registram ramais 4xxx distintos simultaneamente;
chamada entre eles; painel do Desktop comanda a chamada dentro do iframe; login direto em
`/callcenter/` também tem softphone; **verificar em `pjsip show endpoints` que não há dois
registros do mesmo ramal**.

---

### FASE 5c — Menu com ramificação 1-9 e biblioteca de áudios _(P5, P6)_

**Objetivo:** desenhar uma URA com menu de verdade e subir o áudio pelo próprio editor.
**Complexidade:** **G**. **Repriorizada acima do simulador** (era 5d).

#### 5c.1 Ramificação do menu por handle nomeado

**Problema atual:** o editor tem **um único handle de saída sem `id`** (`GenericNode.tsx:81`), então
todas as arestas de um menu são indistinguíveis. O operador precisa digitar IDs de aresta gerados
pela biblioteca (`"1=xy-edge__abc123;2=xy-edge__def456"`) num campo texto — IDs que a UI nem exibe.
E `FlowGraphValidator` **não valida nada disso**, então um fluxo quebrado publica sem reclamar e a
chamada morre como `ABANDONED`.

**Mudanças:**

| Camada | Arquivo | Mudança |
|---|---|---|
| Modelo | `engine/FlowGraph.java:57` | record `Edge` ganha `sourceHandle` (**não existe hoje**) |
| Editor | `FlowEditor.tsx:30` | `NODE_TYPES` deixa de ser `{generic}` e passa a despachar por tipo |
| Editor | `flow/nodes/MenuNode.tsx` (novo) | handles `opt-0`…`opt-9`, `opt-timeout`, `opt-invalido`, cada um rotulado no nó |
| Editor | `NodePropertiesPanel.tsx:38-61` | editor de opções em linhas (dígito + rótulo), não campo texto |
| Motor | `MenuNodeHandler.java:31-62` | casar por `edge.sourceHandle() == "opt-<digito>"`; **manter o parser antigo como fallback** para grafos já publicados |
| Motor | `MenuNodeHandler.java:23,37-44` | `timeoutSegundos` e `tentativas` configuráveis (hoje 10 s fixo e timeout **desliga a chamada**); ramos `opt-timeout` e `opt-invalido` |
| Driver | `AriVoiceChannelDriver.java:61-79` | dígito inválido deixa de ser descartado em silêncio → devolve `INVALID` para o handler tocar "opção inválida" e repetir |
| Validador | `FlowGraphValidator.java:102` | validar propriedades: todo dígito declarado tem aresta; nenhuma aresta com handle inexistente; menu sem nenhuma opção é erro |

- O nó de menu ganha `audioPath`/`texto` **próprios** (hoje exige um `tocar_audio` antes — o
  handler não toca nada), `timeoutSegundos`, `tentativas`.
- **Compatibilidade**: `schemaVersion` do grafo sobe para `2`; grafos `1` continuam sendo lidos
  pelo parser antigo. Zero fluxos publicados hoje, mas o custo de manter é baixo.

#### 5c.2 Tipos de propriedade no catálogo

`FlowGraphNodeType.NodeProperty.type` hoje é só `string|number|boolean|select`, e **`select` cai
como campo texto** porque o backend nunca manda a lista de opções (`NodePropertiesPanel.tsx:12-15`).
Resolver junto:

- `NodeProperty` ganha `options: List<Option{value,label}>` e `required: boolean`.
- Tipos novos: `audio` (select da biblioteca + upload), `keypad` (editor de opções do menu).
- `filaId` do nó `enviar_fila` vira **select real** de filas (hoje o operador digita um id numérico).

#### 5c.3 Biblioteca de áudios com upload (D12-A)

- **Migration V62**: `cc_audio_files` (`id`, `name`, `file_name`, `format`, `duration_seconds`,
  `business_unit_id`, `uploaded_by`, `created_at`).
- Volume novo `/opt/telecom/audios` → `backend:rw`, `asterisk:/var/lib/asterisk/sounds/asteriskia:ro`.
- `CallCenterAudioController` (multipart), **espelhando `InsightsUploadService.java:34-92`**:
  allowlist de extensão (`wav`, `mp3`, `ogg`), limite de tamanho, `sanitizeFileName`, verificação
  `target.startsWith(dir)`.
- **Transcodificação obrigatória** para PCM 8 kHz/16-bit mono via `ffmpeg` (já instalado —
  `backend/Dockerfile:34`); o arquivo original **não** é servido ao Asterisk. Sem isso, um `.wav`
  44.1 kHz toca em velocidade errada ou não toca.
- O nó `tocar_audio` passa a referenciar `cc_audio_files.id`; o driver resolve para
  `sound:asteriskia/<file_name>`.
- **Corrigir o achado de segurança**: `AriClient.play` (`:41-45`) concatena `audioPath` cru na URL
  do ARI. Com a biblioteca, o valor deixa de ser string livre; adicionar validação defensiva de
  qualquer forma (allowlist de caractere, sem `..`, sem `/` no nome).
- `consentMessagePath` das filas passa a usar a mesma biblioteca (hoje é caminho digitado à mão,
  `FilasTab.tsx:150`), mantendo `normalizeConsentPath` como defesa.
- **Upload a partir do próprio painel do nó** — é o pedido literal: botão "Enviar novo áudio" no
  `NodePropertiesPanel`, que sobe e já seleciona. Player de pré-escuta reusando `AuthedAudio.tsx`.

#### 5c.4 Nó `pausar_gravacao` (destrava um serviço órfão)

`CallCenterRecordingControlService` (pause/resume via AMI `MixMonitorMute`) está pronto desde a
Fase 3 **sem nenhum consumidor**. Implementar o nó (`implementado=true`) fecha o requisito
PCI/LGPD de não gravar coleta de dado sensível — barato, já que o backend existe.

**Testes:** `MenuNodeHandlerTest` estendido (dígito válido segue o handle certo; timeout segue
`opt-timeout` e **não** desliga; inválido repete até `tentativas` e então segue `opt-invalido`;
grafo v1 antigo ainda funciona); `FlowGraphValidatorTest` (menu com opção sem aresta é erro);
`CallCenterAudioServiceTest` (extensão negada; traversal negado; transcodificação chamada).

**Validação:** desenhar uma URA com menu de 3 opções + timeout + inválido, subir o áudio pelo
editor, publicar, ligar para a extensão 6xxx e percorrer todos os ramos, conferindo o traço em
`cc_flow_execution_steps`.

---

### FASE 14 — Identidade do contato e screen pop _(P1)_

**Objetivo:** o agente vê quem está falando com ele.
**Complexidade:** **G**. **Bloqueada pela Fase 1** (exige o DC real) e pelas decisões **D7** e **D8**.

#### 14.1 Cascata de identificação (exatamente como pedida)

```
CHAT   → login de rede           ┐
VOZ    → URA pergunta (D7)       ├→ ad_users → screen pop completo
       ↓ não identificado        │
       → ANI / ramal do chamador ┘→ screen pop parcial (o que casar)
       ↓ ainda não identificado
       → SEM screen pop (estado explícito na tela, não erro)
```

- `CallCenterIdentityResolver.resolve(channel, ResolutionInput{networkLogin, uraInput, ani})` →
  `Optional<ResolvedIdentity{adUser, source}>`, com `source ∈ {NETWORK_LOGIN, URA_INPUT, ANI,
  UNRESOLVED}`.
- Fallback por ANI casa contra `ad_users.telephone_number`, `cc_extensions.extension` e
  `app_users.extension` — nessa ordem.
- **`UNRESOLVED` é estado normal, não erro.** A tela mostra "Contato não identificado" com o ANI
  cru, nunca uma exceção (lição da Fase 4, onde `IllegalStateException` virou 500 genérico).

#### 14.2 Coleta por voz (assumindo **D7-A**)

- **Migration V63**: `ad_users.employee_id` + espelhamento no `LdapClient`/`LdapUserAttributes`
  (atributo `employeeID` do AD).
- **Implementar o nó `coletar_entrada`** (hoje `implementado=false`) — é pré-requisito e serve a
  muito mais que esta fase. `ChannelDriver` ganha `collectDigits(maxDigits, timeout, terminator)`.
  A propriedade `sensivel` do nó (já no catálogo) deve suprimir o valor do traço de execução.
- Novo nó `identificar_contato` (ou propriedade do `coletar_entrada`) que grava
  `cc_interactions.resolved_ad_sam`/`identity_source` (V63).

#### 14.3 Screen pop no Desktop do Agente

- `GET /callcenter/interactions/current` passa a devolver o bloco de identidade: `displayName`,
  `department`, `office`, `title`, `managerSam`, `email`, `telephoneNumber` — **todos já existem em
  `ad_users`** (V45), nenhum atributo novo além de `employee_id`.
- Histórico de contatos anteriores: últimas N `cc_interactions` + `cc_chat_sessions` do mesmo
  `resolved_ad_sam`, com link para a gravação/transcrição (é o embrião da timeline omnicanal da
  Fase 9c — **construir já pensando nisso**).
- `DesktopAgenteTab.tsx:123-126`: remover o aviso de "AD não disponível".

#### 14.4 Chat (assumindo **D8-A + D8-B**)

- **Chat interno autenticado**: o login vem do JWT — zero digitação, zero risco.
- **Widget público**: campo opcional "login de rede" na abertura da sessão. O backend resolve e
  guarda em `cc_chat_sessions.resolved_ad_sam`, mas **a resposta ao widget é idêntica para login
  válido e inválido** — sem confirmação de existência, sem eco de nome. Os dados do AD só aparecem
  no painel do agente.
- Rate limit já existente (`PublicChatRateLimiter`) cobre a tentativa de enumeração por força bruta;
  adicionar contador dedicado de tentativas de identificação por IP.

**Testes:** `CallCenterIdentityResolverTest` (cada degrau da cascata; `UNRESOLVED` não lança;
ANI casa nas 3 fontes na ordem certa); teste de que o endpoint público **não** difere a resposta
entre login existente e inexistente; teste de que `sensivel=true` não grava o valor no traço.

**Validação:** ligar para a URA, digitar a matrícula, ver o screen pop; digitar matrícula inválida
e ver o fallback por ANI; ligar de número desconhecido e ver "não identificado"; abrir chat pelo
widget informando login e confirmar que o widget não recebe nenhum dado do AD.

---

### FASE 15 — Supervisão avançada _(P7, P8, P9)_ — ✅ **implementada, testada, revisada e deployada (2026-08-13)**

**Entregue**: `AmiQueueStatusClient` novo (conexão AMI dedicada e curta, `Events: on` +
`ActionID`, correlacionando `QueueParams`→`QueueMember`→`QueueEntry`→`QueueStatusComplete` —
decisão deliberada de não multiplexar no socket persistente do listener, por risco de
concorrência); `QueueSupervisionView.waitingCallers` populado ao vivo, fail-open se o AMI cair.
`QueueCallerLeave` passou a fechar `endedAt` (antes só `QueueCallerAbandon` era tratado — uma
chamada que saía por transbordo ficava presa "esperando" pra sempre). Whisper corrigido:
`resolveSupervisorExtension` prioriza o ramal do `CcAgent` do supervisor (4xxx) antes do
`AppUser.extension` legado (9xxx), com erro claro se nenhum existir; rótulos da UI trocados
pelos definidos no plano. `AmiOriginateService.redirectChannel` novo, resolvendo o nome do
canal AO VIVO no instante da ação (nunca o valor persistido, evita corrida); RBAC dedicado
`callcenter.supervisao.redirect`, separado de `callcenter.supervisao`. Migration **V67** (não
V64, já ocupada pela Fase 23). `mvn test` 525/525 verde (18 novos, 0 regressão). `tsc --noEmit`
e `npm run build` limpos nas duas SPAs.

**Pendência aceita, documentada**: nenhuma validação com tráfego SIP real do whisper/redirect —
mesma ressalva já registrada para todo o motor de voz desde a Fase 5b (sem chamada real
disponível nesta VPS de dev).

**Objetivo:** o supervisor vê cada cliente na fila e age sobre a chamada específica.
**Complexidade:** **G**. **Depende da Fase 12** (sem agente/fila real não há o que supervisionar).

#### 15.1 Fila em tempo real com posição e tempo de espera por chamador _(P7)_

**Hoje:** `QueueSupervisionView` só tem `waitingCount` e `longestWaitSeconds`; o serviço até calcula
a lista de quem está esperando (`CallCenterSupervisionPanelService:47`) mas **descarta os itens**.
Não há nenhuma ação AMI `QueueStatus` no repositório.

**Implementação (D11-A para a tela, D11-B para o histórico):**

- `AmiQueueStatusClient` novo: envia `Action: QueueStatus` com `ActionID` pela **conexão do listener
  event-driven** (que já tem `Events: on`) e agrega os eventos de resposta
  (`QueueParams` → `QueueMember` → `QueueEntry` → `QueueStatusComplete`) correlacionados por
  `ActionID`, com timeout. **Não** usar a conexão request/response do `AmiOriginateService` — ela
  lê até um bloco único e não sabe agregar resposta multi-evento.
- `QueueEntry` traz o que o pedido pede pronto: `Position`, `Wait` (segundos), `CallerIDNum`,
  `Channel`, `Uniqueid`.
- `QueueSupervisionView` ganha `List<WaitingCallerView{position, ani, waitSeconds, channelUniqueId,
  channelName}>`.
- `SupervisaoTab.tsx`: linha de fila expansível com a tabela de quem está esperando (polling de 4 s
  já existe). Ordenada por posição.
- **Histórico (D11-B)**: persistir `position_on_join` e `channel_name` em `cc_interactions` no
  `QueueCallerJoin` (V64) — o `Channel` é necessário para o redirect da 15.3, e a posição inicial é
  dado de relatório.
- **Tratar `QueueCallerLeave`** (hoje ignorado — só `QueueCallerAbandon` é tratado): sem ele, uma
  chamada que sai da fila por transbordo fica marcada como esperando para sempre.

#### 15.2 Supervisor fala com o analista sem o cliente ouvir _(P8)_ — **já existe, falta fechar**

`whisper` está implementado (`CallCenterSupervisionActionService:46`, `ChanSpy` com opções `bw`).
O que falta:

- **Corrigir a origem do ramal do supervisor**: `performChanSpy:79` usa `supervisor.getExtension()`
  do `AppUser` (faixa 9xxx). Se o supervisor também for agente (ramal 4xxx) ou não tiver ramal
  registrado, a escuta falha silenciosamente ou toca no ramal errado. Passar a preferir o ramal do
  `CcAgent`/`CcExtension` quando houver, e falhar com mensagem clara quando não houver nenhum.
- **Rótulo na UI**: hoje "sussurro" não diz o que faz. Trocar para *"Falar com o agente (o cliente
  não ouve)"* — e "Escuta" para *"Ouvir a chamada (ninguém ouve o supervisor)"*, "Barge" para
  *"Entrar na conversa (os dois ouvem)"*.
- **Validar com tráfego real** — nunca foi feito. É o teste que confirma se a correspondência por
  prefixo de canal (`PJSIP/4001`) funciona na prática.

#### 15.3 Retirar chamada da fila e redirecionar _(P9)_

- **Ação AMI `Redirect`** — não existe nenhuma no repositório. Novo
  `AmiOriginateService.redirectChannel(channel, context, exten, priority)`, reusando
  `sendAction` + `sanitizeAmiField` (proteção contra injeção de ação por CR/LF).
- **Fonte do nome do canal**: `Redirect` exige o **nome** do canal (`PJSIP/tronco-0000001a`), não o
  `Uniqueid` que `cc_interactions` guarda hoje. Obter do snapshot `QueueStatus` da 15.1 **no
  instante da ação** — o canal pode ter mudado desde o join, então usar o valor persistido seria
  uma corrida.
- **Destinos**: outra fila → `Redirect` para `ramais-internos`, exten = ramal da fila (`_5XXX`);
  agente específico → exten = ramal do agente (`_4XXX`). Validar escopo de BU do destino.
- **RBAC "perfil específico"** (pedido literal): resource novo **`callcenter.supervisao.redirect`**
  (V64) — separado de `callcenter.supervisao`, que já governa escuta/sussurro/barge. 4 pontos de
  sincronia (`ResourceCatalog`, `SecurityConfig`, `Sidebar.tsx`/RBAC da SPA, `AccessGroups.tsx`).
- **Auditoria**: `SupervisionActionType` ganha `REDIRECT_QUEUE` e `REDIRECT_AGENT`.
  `cc_supervision_actions.agent_id` precisa virar **nullable** (o alvo pode ser uma chamada em fila
  sem agente) e ganhar `target_queue_id`/`target_agent_id` (V64).
- **UI**: botões "Mover para outra fila" e "Direcionar para agente" na linha de cada chamador em
  espera, visíveis só com a permissão nova.

**Testes:** `AmiQueueStatusClientTest` (agregação multi-evento por `ActionID`; timeout devolve
parcial ou erro claro, nunca trava); `CallCenterSupervisionActionServiceTest` estendido (redirect
sem permissão é 403; destino de outra BU é rejeitado; auditoria gravada com o alvo certo; supervisor
sem ramal falha com mensagem clara).

**Validação:** duas chamadas em espera na mesma fila → painel mostra posições 1 e 2 com tempos
crescendo → mover a posição 2 para outra fila → confirmar no Asterisk (`queue show`) e no painel →
supervisor sussurra numa chamada ativa e o cliente confirma que não ouviu.

---

### FASE 16 — Histórico do contato e copiloto de IA para o agente _(P11, P12)_

**Objetivo:** sempre que o cliente entra em contato (voz **ou** chat), o agente recebe o histórico
de contatos anteriores e um **perfil traçado por IA com ações sugeridas** para conduzir o
atendimento.
**Complexidade:** **G**. **Depende da Fase 14** (sem identidade resolvida não há histórico a
buscar) e da **Fase 12** (sem agente não há a quem entregar).

#### 16.1 Histórico unificado de contatos

- `CallCenterContactHistoryService.historyFor(resolvedAdSam, limit)` → lista unificada de
  `cc_interactions` (voz) + `cc_chat_sessions` (chat), ordenada por data decrescente, cada item com
  canal, fila, agente, duração, tabulação, e link para a gravação/transcrição.
- **Escopo por BU aplicado** — o agente não vê contato de BU que não é dele (o módulo Insights do
  Call Center hoje **não** filtra por BU; esta fase não repete esse gap).
- É deliberadamente o **mesmo serviço** que a timeline omnicanal da Fase 9c vai consumir — construir
  já com essa forma evita reescrever depois. Diferença: aqui é "últimos N do contato atual",
  lá é "busca por qualquer contato, com filtros".
- Cache curto (30-60 s) por `resolvedAdSam`: numa fila movimentada o mesmo contato pode ser puxado
  várias vezes, e é consulta de hot-path de atendimento.

#### 16.2 Perfil do cliente e ações sugeridas por IA

- Novo `ContactProfileService` no backend + prompt dedicado no serviço Python
  (`insights/src/contact_profile_llm.py`, espelhando `insights_llm.py`).
- **Entrada do modelo:** histórico resumido (16.1) + transcrições/insights já existentes das
  interações anteriores (Fase 8) + dados do AD (cargo, área, gestor) + motivo da chamada atual se
  já coletado no fluxo. **Nunca** o áudio bruto — só texto já transcrito e **já mascarado**
  (`insights/src/masking.py`, obrigatório, mesma disciplina da Fase 8).
- **Saída estruturada** (não texto livre): `{resumoPerfil, sentimentoHistorico, temasRecorrentes[],
  riscoEscalonamento, acoesSugeridas[{acao, justificativa}]}`. Schema validado no Java; valor fora
  de faixa é *clampado*, não persistido cru (lição do `numeric field overflow` da Fase 8).
- **Migration V65**: `cc_contact_profiles` (`resolved_ad_sam`, `profile_json`, `generated_at`,
  `model`, `input_tokens`, `output_tokens`, `cost_usd`, `interaction_id`).
- **Geração assíncrona e não bloqueante**: o screen pop aparece **imediatamente** com identidade +
  histórico; o perfil de IA chega depois, em um segundo momento, via polling. **Uma chamada não
  espera o LLM** — se o modelo demora ou falha, o agente atende sem o copiloto, nunca com a tela
  travada.
- **Reaproveitamento**: perfil gerado há menos de X horas (configurável, default 24 h) para o mesmo
  contato é reusado em vez de regerado — é o principal controle de custo desta fase.

#### 16.3 UI

- Painel no Desktop do Agente, abaixo do screen pop: abas "Histórico" e "Perfil & Ações".
- Cada ação sugerida com um botão de *feedback* (útil / não útil) — matéria-prima para ajustar o
  prompt depois, e sinal barato de que a feature está entregando valor.
- **Rótulo explícito de que é sugestão de IA**, nunca apresentado como fato sobre a pessoa.
- Mesmo painel no atendimento de chat (`ChatTab.tsx`).

#### 16.4 Custo no Financeiro (padrão obrigatório — ver §5)

- Frente nova **`callcenter_copiloto`** em `CostAlertService.SCOPES` (hoje
  `["ura", "insights", "envios", "callcenter"]`, `CostAlertService.java:40`), com submenu próprio no
  módulo Financeiro (aba de custos + dashboard + alerta de gasto em USD), espelhando exatamente o
  que a V42/V54 fizeram para as frentes existentes.
- Tokens contabilizados por `insights/src/token_usage.py` e precificados por `ai_model_pricing`
  (busca automática diária já existente) — **nenhum caminho de custo novo, só uma frente nova**.
- **Custo por interação visível na própria tela**: cada perfil gerado mostra o custo daquela
  geração, e o dashboard agrega por dia/mês. É o pedido literal ("saber quanto está custando cada
  interação").
- **Alerta de gasto obrigatório desde o dia 1** — esta é a frente de IA com o pior perfil de custo
  do projeto: dispara por *contato*, não por gravação processada, e o volume-alvo é 200 chamadas
  simultâneas.

**Testes:** `ContactProfileServiceTest` (reuso dentro da janela de cache; falha do LLM não impede
o atendimento; saída fora de schema é rejeitada; mascaramento aplicado antes da chamada);
`CallCenterContactHistoryServiceTest` (escopo de BU respeitado; voz e chat intercalados na ordem
certa); `CostAlertServiceTest` estendido para a frente nova.

**Validação:** cliente identificado liga duas vezes → segunda chamada mostra a primeira no
histórico → perfil de IA gerado com ações plausíveis → custo daquela geração aparece no Financeiro,
frente `callcenter_copiloto` → segunda chamada dentro de 24 h reusa o perfil sem gerar custo novo.

---

### FASE 17 — Co-browsing gravado do chat _(P10, parte visual — D6)_

**Objetivo:** gravar a navegação do cliente durante o atendimento por chat, com replay.
**Complexidade:** **XG** — é um subsistema, não uma feature.
**Posição:** depois da Fase 10. Não bloqueia nenhuma outra fase.

- **Captura**: biblioteca de *session replay* embarcada no widget (avaliar `rrweb`, MIT, padrão de
  mercado) gravando mutações de DOM, cliques, scroll e navegação. **Não grava** campos marcados
  como sensíveis (senha, cartão) — mascaramento no cliente, antes de sair do browser.
- **Consentimento explícito e revogável**: capturar a tela de alguém exige aviso claro e aceite
  ativo, distinto do aviso de gravação de voz. Sem aceite, o chat funciona normalmente, só sem
  captura.
- **Transporte e storage**: eventos em lote (não por evento) para
  `/opt/gravacoes/chat/YYYY/MM/DD/<sessionId>.events.jsonl.gz`. Volume é a preocupação principal —
  co-browsing gera **ordens de magnitude mais dado que transcript**; dimensionamento entra na
  recomendação de hardware da Fase 10.
- **Player de replay** na aba Gravações, autenticado e auditado (mesma disciplina do áudio:
  escopo de BU → 404, auditoria de reprodução).
- **Retenção**: política própria, provavelmente mais curta que os 60 meses da voz — replay de tela
  é dado pessoal de sensibilidade alta e valor decrescente rápido.
- **Riscos a tratar na fase**: tamanho do payload no browser do cliente, LGPD (é captura de tela de
  pessoa identificada), e o fato de o widget passar a executar código de captura — superfície nova
  mesmo em rede interna.

### FASE 18 — IA local: reduzir custo de token e criar memória que melhora com o uso

**Pergunta do usuário (2026-08-08):** *"é possível criar um agente para trabalhar local e ele
aprender com cada interação sem a necessidade de gastar tokens? o que precisamos fazer?"*

**Resposta curta:** sim, mas é preciso separar duas coisas que costumam ser confundidas —
**rodar local** (deixa de haver custo por token, passa a haver custo de hardware) e **aprender**
(LLM não aprende em runtime; existem três mecanismos distintos, com custos e prazos diferentes).

#### 18.0 O que "aprender" significa, na prática

| Mecanismo | O que é | Aprende em runtime? | Custo | Serve para |
|---|---|---|---|---|
| **Memória / RAG** | Guardar interações passadas e recuperar as relevantes no prompt | **Sim** — cada interação nova já fica disponível na seguinte | Baixo (embeddings + banco) | 90% do que se quer dizer com "aprender com cada interação" |
| **Fine-tuning (LoRA/QLoRA)** | Ajustar os pesos do modelo com um dataset curado | Não — é em lote, periódico | Médio-alto (GPU + curadoria) | Ensinar **estilo, formato e jargão da operação** |
| **ML supervisionado clássico** | Modelo pequeno treinado para uma tarefa fechada | Retreino barato e frequente | **Muito baixo** (roda em CPU) | Classificar tabulação, prever transbordo, priorizar fila |

O erro comum é achar que só o segundo é "aprender de verdade". Na prática, **o primeiro entrega a
maior parte do valor percebido**, e o terceiro é o de melhor relação custo/benefício para as
tarefas repetitivas de um call center.

#### 18.1 Bloqueio de hardware — medido nesta VPS (2026-08-08)

```
2 vCPU · 3 GB RAM (0 GB disponível, swap em uso) · sem GPU · 23 GB livres em /opt
```

**Esta VPS não roda nenhum LLM local de forma útil.** Também não é o alvo: o plano já prevê
servidor dedicado para produção (§13, Fase 10). A Fase 18 **entra na recomendação de hardware da
Fase 10** — é ela que decide se o servidor leva GPU.

#### 18.2 Onde o token realmente é gasto hoje — atacar na ordem certa

O maior gasto do projeto **não é o LLM de análise, é o STT**: 100% das chamadas são transcritas
(decisão de 2026-08-06), e áudio consome muito mais token que texto.

| Onda | O que sai da API | Substituto local | Hardware | Ganho |
|---|---|---|---|---|
| **1ª — Embeddings** | Vetores para memória/RAG | `BGE-m3` ou `multilingual-e5` via `sentence-transformers` | **CPU serve** | Habilita memória/RAG **sem token nenhum**. Pré-requisito das outras ondas |
| **2ª — STT** | Transcrição de todas as gravações | `faster-whisper` (`large-v3`) | GPU (~8-12 GB VRAM) | **O maior corte de custo do projeto.** Qualidade em PT-BR comparável ou superior ao atual |
| **3ª — LLM** | Análise de insights, perfil do contato (Fase 16) | Llama 3.1 8B / Qwen 2.5 14B via Ollama ou vLLM | GPU (~16-24 GB VRAM) | Corte grande, **mas com perda de qualidade real** — ver 18.5 |

#### 18.3 Onda 1 — memória que melhora com o uso (viável já, sem GPU)

É aqui que mora o "aprender com cada interação".

- **Banco vetorial**: extensão `pgvector` no PostgreSQL 16 que já existe — **sem serviço novo**.
  O projeto já usa `pg_trgm` para memória em `tools/agente-google.py`, então o precedente existe.
- **Embeddings locais** em CPU (`BGE-m3`), rodando dentro do container `insights` que já é Python.
- **O que memorizar**: cada interação encerrada vira um registro `{resumo, tabulação, perfil do
  contato, ações que funcionaram, feedback útil/não-útil do agente da Fase 16}`.
- **Como isso "aprende"**: na interação seguinte, o copiloto (Fase 16) recupera os casos mais
  parecidos e os injeta no prompt. Um caso resolvido hoje influencia o atendimento de amanhã
  **sem retreinar nada e sem gastar token de embedding** (é local).
- O **feedback útil/não-útil** já previsto na Fase 16.3 deixa de ser só métrica: vira o sinal de
  qualidade que filtra o que entra na memória. Sugestão marcada como inútil não é recuperada de
  novo.

**Isto é implementável no servidor dedicado sem GPU alguma** e já corta o custo do copiloto,
porque encurta o prompt (recupera 3 casos relevantes em vez de mandar histórico inteiro).

#### 18.4 Onda 3 — ML clássico, o mais subestimado

Várias tarefas do call center **não precisam de LLM**:
- prever a tabulação provável a partir da transcrição (classificação multiclasse);
- prever risco de abandono na fila (o dado de `cc_agg_queue_daily` já existe);
- prever transbordo e dimensionar escala.

`scikit-learn` em CPU, retreino noturno com o dado acumulado, custo de token **zero** e resultado
determinístico e auditável — o oposto do LLM. Cada uma dessas substitui uma chamada de modelo que
hoje seria paga.

#### 18.5 O que NÃO recomendo prometer

- **Modelo local pequeno não iguala Gemini 2.5 Flash em raciocínio.** Para a análise de insights e
  o perfil do contato (Fase 16), a diferença de qualidade é perceptível. A estratégia correta é
  **híbrida**: local para volume (STT, embeddings, classificação), API para raciocínio complexo.
- **"Sem gastar" é meia verdade**: o custo migra de OPEX (token) para CAPEX (GPU) + energia +
  manutenção. Só compensa acima de um volume — que a Fase 10 vai calcular com dado real.
- **Fine-tuning contínuo em produção é armadilha**: sem dataset curado e sem *eval harness*, o
  modelo piora silenciosamente. Fine-tuning só depois da Onda 1 rodando e com métrica de qualidade
  estabelecida.

#### 18.6 O que precisamos fazer, em ordem

1. **Medir o custo real por frente** (o Financeiro, §5.1, já dá isso) durante 30 dias de operação —
   sem esse número, qualquer decisão de GPU é chute.
2. **Onda 1 no servidor dedicado**: `pgvector` + embeddings locais em CPU + memória alimentada
   pelo feedback da Fase 16. Sem hardware novo.
3. **Onda 3 em paralelo**: um classificador de tabulação como piloto — é a prova barata de que
   ML local resolve tarefa fechada melhor e mais barato que LLM.
4. **Decidir GPU com o dado do passo 1**: se o custo de STT justificar, especificar a placa junto
   com a recomendação de hardware da Fase 10 e migrar o STT (Onda 2). É o item de maior retorno.
5. **LLM local (Onda 2→3) só depois**, e mesmo assim mantendo o roteamento híbrido.
6. **Eval harness antes de qualquer troca de modelo**: um conjunto fixo de chamadas com resultado
   conhecido, para comparar local vs. API objetivamente. Sem isso, a troca é fé.

**Complexidade:** **XG**, atravessa infraestrutura, Python e produto.
**Posição:** depois da Fase 10 (depende da recomendação de hardware e de custo real medido).
**Dependência invertida importante:** a Fase 16 deve ser construída com a **interface de provedor
já abstraída** (`provider_registry.py` do `ai-agent` é o precedente), para que trocar Gemini por
modelo local depois não exija reescrever o copiloto.

---

## 10. Sequenciamento

```
                          [ CONCLUÍDO: 0, 2, 3, 8 ]
                                     │
   ┌─────────────────────────────────┼─────────────────────────────────┐
   ↓                                 ↓                                 ↓
FASE 11 (caminhos)          FASE 12 (provisionamento)         FASE 5c (menu+áudio)
   │  P, independente          │  DESBLOQUEADOR                  │  independente
   │                           ├──────────────┬──────────────┐   │
   ↓                           ↓              ↓              ↓   ↓
   └──────────────────→  FASE 13 (softphone)  FASE 15 (supervisão)
                               │              │
                               └──────┬───────┘
                                      ↓
                        [ validação real com tráfego ]
                                      ↓
                        ┌─────────────┴─────────────┐
                        ↓                           ↓
                 FASE 1 (DC real)  ──────→  FASE 14 (screen pop)
                                                    ↓
                                       FASE 16 (histórico + copiloto IA)
                                                    ↓
                                    FASE 5d-5f · 7-resto · 9c
                                                    ↓
                                              FASE 10 (hardening)
                                                    ↓
                                          FASE 17 (co-browsing)
```

**Ordem recomendada:** **11 → 12 → 13 → 5c → 15 → 14 → 16** (17 depois da 10).

**14 e 16 são uma dupla inseparável na percepção do usuário** — screen pop sem histórico nem
sugestão é meio produto. Mas são fases separadas de propósito: a 14 entrega valor sozinha (o agente
já sabe com quem fala) e a 16 tem um perfil de custo e de risco completamente diferente (chama LLM
por contato). Fatiar permite ligar a 16 quando o custo estiver medido, não antes.

- **11 primeiro** porque é barata agora e cara depois (cada dia de operação move mais arquivos).
- **12 segundo** porque é o desbloqueador de toda validação real — sem um atendente provisionado,
  nada de voz pode ser confirmado, e três fases inteiras (4, 6, 5b) estão esperando isso.
- **14 por último** entre as novas, porque depende do DC real, que não está nas nossas mãos.
- **5c e 15 são paralelizáveis** entre si depois da 12 (tocam áreas disjuntas: flow builder vs.
  supervisão/AMI).

---

## 11. Riscos

| Risco | Prob. | Impacto | Mitigação |
|---|---|---|---|
| **Mapeamento de eventos AMI/ARI errado** — nunca validado com tráfego real | **Alta** | **Alto** | A Fase 12 é desenhada para produzir exatamente essa validação. Os payloads do primeiro evento real já são logados (sem ANI) para ajuste sem adivinhação |
| VPS não suporta 200 agentes/200 chamadas | Confirmado | Alto | Produção vai para servidor dedicado; esta VPS é homologação |
| Sem acesso da VPS ao Domain Controller | Média | **Alto** | Bloqueia a Fase 14 inteira. Levantar host/porta/base DN/conta de serviço **antes** de começá-la |
| **Dois UAs SIP no mesmo ramal** (softphone duplicado) | **Alta se D10-B** | **Alto** | D10-A: um único UA; a SPA só instancia o próprio quando não embutida |
| **Credencial SIP do agente exposta no browser** | **Certa** | Médio | Resíduo aceito e documentado (D9-A): é inerente ao WebRTC. Mitigado por ser individual, sob demanda, auditada e rotacionável — hoje é pior (senha única global no bundle) |
| ~~Enumeração do diretório corporativo pelo widget público~~ | — | — | **Eliminado (2026-08-08)**: aplicação é interna, não vai à internet (D8). Rate limit e token anônimo mantidos como defesa em profundidade |
| **Transcrição errada do login por voz entrega os dados da pessoa errada** | **Alta** | **Alto** | D7: a transcrição alimenta busca aproximada (`pg_trgm`) e o resultado é **confirmado com o cliente por voz** antes de virar screen pop. Sem confirmação, não há screen pop |
| **Custo do copiloto de IA (Fase 16) — dispara por contato, não por gravação** | **Alta** | **Alto** | Frente `callcenter_copiloto` no Financeiro com alerta de gasto desde o dia 1 (§5.1); cache de perfil por 24 h; geração assíncrona que nunca bloqueia o atendimento |
| **IA sugere ação errada e o agente segue** | Média | Médio | Rótulo explícito de sugestão, nunca de fato; feedback útil/não-útil por ação; saída estruturada validada por schema |
| **Volume de dados do co-browsing (Fase 17)** | Alta | **Alto** | Ordens de magnitude acima do transcript; dimensionamento entra na recomendação de hardware da Fase 10; retenção própria, mais curta que os 60 meses da voz |
| **Perda de gravação na migração de caminho** | Média | **Alto** | `rsync --remove-source-files` só após verificação de contagem; `resolveAudioFile` não depende do prefixo persistido, então o rollback é só remontar o volume antigo |
| Transcodificação de áudio errada faz a URA tocar ruído | Média | Médio | `ffmpeg` com parâmetros fixos (PCM 8k/16/mono) + pré-escuta obrigatória no editor antes de publicar |
| Asterisk é nó único — restart derruba chamadas ativas | Alta | **Alto** | Janela documentada; segundo nó avaliado na Fase 10 |
| Crescimento de disco em `/opt/gravacoes` | Alta | Médio | Retenção de 60 meses + alerta diário já existentes; **conferir se `/opt/gravacoes/chat` e `/audio` estão no mesmo filesystem** (o alerta mede o FS, não o diretório) |
| Custo de IA em 100% das chamadas | Alta | **Alto** | Alerta de gasto no Financeiro (frente "callcenter", V54); estimativa entra na recomendação de hardware |
| Relatório anual varrendo eventos brutos = timeout | Alta | Médio | Agregados materializados (9a/9b feitos; 9c pendente) |
| **Escopo cresce durante a execução** | **Alta — já aconteceu** | Médio | Fases fechadas e deployáveis; pedido novo entra em fase nova (foi exatamente o que se fez com `pla.txt`) |

---

## 12. Dimensão do esforço

| Fase | Peso | Observação |
|---|---|---|
| 11 — Caminhos de gravação | **P** (voz) / **M** (chat) | 10 pontos de config mapeados; script de migração; D6 decide o peso do chat |
| 12 — Provisionamento e filas | **G** | Toca `UserController`, provisionamento ARA, 2 telas, 2 CRUDs novos, correção estrutural de FK |
| 13 — Softphone do agente | **G** | Refatoração do `Softphone.tsx` (576 linhas), protocolo novo no bridge, endpoint de credencial |
| 5c — Menu e áudios | **G** | Handles nomeados tocam modelo+editor+motor+validador; upload é subsistema novo com volume e transcodificação |
| 14 — Screen pop | **G** | Depende do DC; implementa `coletar_entrada`; superfície de segurança nova no chat público |
| 15 — Supervisão avançada | **G** | `QueueStatus` multi-evento é padrão AMI novo no projeto; `Redirect` idem; RBAC novo |
| 5d-5f, 7-resto, 9c, 10 | **XG** somados | Escopo remanescente do plano original |

**Release sugerida para a Parte II:** `release 4 = 11 + 12 + 13` (call center de voz realmente
operável, com atendente provisionável e softphone próprio) → `release 5 = 5c + 15` (URA desenhável
de verdade e supervisão completa) → `release 6 = 14 + 5d-5f + 9c + 10`.

---

## 13. Aceite

- [x] Decisões D1-D5 confirmadas (2026-08-06)
- [ ] **Decisões D6-D12 confirmadas** — bloqueiam o início das Fases 11, 13, 14 e 5c
- [ ] Conectividade com o Domain Controller confirmada (bloqueia a Fase 14)
- [x] Teto de capacidade da VPS medido — insuficiente para 200/200
- [ ] Recomendação de hardware do servidor dedicado apresentada
- [ ] **Primeiro atendente provisionado de ponta a ponta e primeira chamada real atravessando uma
      fila** — encerra a maior incerteza técnica aberta do projeto (mapeamento AMI/ARI)
- [ ] Cada fase deployada e validada nesta VPS antes de iniciar a seguinte
- [ ] Sem CRITICAL/HIGH em `ecc:security-reviewer` por fase
- [ ] Cobertura de teste ≥ 80% no código novo
- [ ] Release notes e `CLAUDE.md` atualizados por fase
- [ ] Escopo por BU e RBAC granular em todo endpoint novo

---

## 14. Perguntas abertas

### 14.1 Respondidas (2026-08-06)

1. **Volume-alvo**: 200 agentes / 200 chamadas simultâneas.
2. **Ambiente**: desenvolvimento nesta VPS; produção em servidor dedicado a dimensionar.
3. **AD**: conexão inteira configurável por tela de administração, não `.env`.
4. **Canais de chat**: só webchat próprio nesta entrega.
5. **Receptivo/ativo**: receptivo + ativo manual pelo softphone. Sem discador automático.
6. **Retenção de gravação**: 60 meses.
7. **Análise de IA**: 100% das chamadas.
8. **Jira**: sim, reusa `JiraIntegrationService`.
9. **D1-D5**: todas aceitas conforme recomendado.

### 14.2 Respondidas (2026-08-08, segunda rodada)

10. **D6** — `/opt/gravacoes/chat` recebe **co-browsing gravado** (Fase 17); o transcript textual
    vem junto na Fase 11 por ser insumo do pipeline de IA.
11. **D7** — voz: **o cliente fala o login e a IA transcreve**, com busca aproximada + confirmação
    falada antes do screen pop. Chat: pergunta textual *"Qual seu login?"* com resposta de
    válido/inválido.
12. **D8** — **a aplicação é interna, não vai à internet.** Risco de enumeração eliminado; o
    "widget público" passa a ser widget interno.
13. **Histórico + copiloto de IA** — Fase 16, para voz e chat.
14. **Custo de IA no Financeiro** — regra transversal obrigatória, §5.1.

### 14.3 Respondidas (2026-08-08, terceira rodada)

15. **D9/D10** — *"faça o melhor para manter a operação segura e estável"*: confirmadas as opções
    recomendadas. **D9-A** (endpoint autenticado `GET /callcenter/agentes/me/sip-credentials`,
    por agente, sob demanda, auditado, rotacionável) e **D10-A** (um único UA SIP, no shell,
    comandado por `postMessage`; a SPA só instancia o próprio quando aberta fora do shell).
    Justificativa de estabilidade: D10-B permitiria dois registros no mesmo ramal, e o Asterisk
    forkaria a chamada para ambos — falha intermitente e difícil de diagnosticar em produção.
16. **D12** — biblioteca de áudios do flow builder fica em **`/opt/gravacoes/flow`**.
17. **Prioridade de fila** — exibida como **1-9, menor = atendido antes** (mapeia 1:1 para o
    `penalty` do Asterisk, sem inversão).
18. **Ordem de execução** — a critério do assistente: mantida **11 → 12 → 13 → 5c → 15 → 14 → 16**,
    com **17** e **18** depois da Fase 10.
19. **Janela de reuso do perfil de IA** — **24 h**.

### 14.4 Respondidas (2026-08-08, quarta rodada)

20. **Fase 14/16** — confirmado: cliente não identificado recebe atendimento normal, sem screen
    pop e sem copiloto (não é erro, é o estado `UNRESOLVED` já desenhado em 14.1).
21. **Fase 18 (IA local)** — **mantém-se com API (reuso de cache de 24h, sem modelo local por
    ora)**. Modelo local para reduzir custo fica para depois, quando o custo real de 30 dias
    estiver medido (§9, Fase 18.6, passo 1) — a Fase 18 permanece escrita como estudo/roadmap,
    não entra na implementação desta rodada.

### 14.5 Sem pendência de decisão — pode iniciar a implementação

Todas as decisões (D6-D12 + prioridade de fila + ordem + janela de cache + IA local) estão
fechadas. Início autorizado pela Fase 11.

---

## 15. Padrão de trabalho por fase

Cada fase segue o ciclo já estabelecido no repositório:

1. **Pesquisa** — GitHub code search / docs oficiais antes de escrever código novo.
2. **TDD** — teste primeiro; suíte completa verde antes do commit (container
   `maven:3.9-eclipse-temurin-21` com cache offline, já que não há Maven local nem internet
   confiável nesta VPS).
3. **Revisão paralela** — `ecc:security-reviewer` + `ecc:code-reviewer` (e `ecc:react-reviewer`
   quando houver TSX). CRITICAL/HIGH corrigidos **antes** do commit.
4. **Validação em produção** — `docker compose up -d --build <serviços>`, migration confirmada em
   `flyway_schema_history`, teste por `curl` com JWT forjado inline (ADMIN vs USER vs sem token).
5. **Validação visual** — Chrome headless + CDP puro via WebSocket do Node 22 (o Chrome DevTools
   MCP falha nesta VPS com "Target closed").
6. **Registro** — release notes em `releases.ts`, `CLAUDE.md` atualizado, memória gravada.
7. **Espelho** — `git push origin main` **e** `git push azure main:desenvolvimento`. **Nunca**
   `git push azure main` — `main` no Azure DevOps fica congelada (regra fixada em 2026-08-13, ver
   `.claude/plans/callcenter-parte-iii-revisado.plan.md` §8).

---

## 16. Histórico de entregas

> Resumo cronológico. O detalhamento completo (bugs encontrados, achados de revisão por
> severidade, contagem de testes) está em `CLAUDE.md`.

| Data | Entrega | Release | Migrations | Suíte |
|---|---|---|---|---|
| 2026-08-06 | Fase 1 — AD/LDAP (backend) | — | V45 | 272/272 |
| 2026-08-06 | Fase 0 — PoCs, faixas, capacidade | — | — | — |
| 2026-08-07 | Fase 2 — cadastros de voz + ARA + SPA | — | V46-V48 | — |
| 2026-08-07 | Fase 3 — gravação, retenção, conformidade | v1.48/v1.49 | V49 | 306/306 |
| 2026-08-07 | Fase 4 — estados, interações, tabulação | v1.50 | V50 | 317/317 |
| 2026-08-07 | Fase 6 — supervisão em tempo real | v1.51 | V51 | 328/328 |
| 2026-08-07 | Fase 5a — editor e versionamento de fluxos | v1.52 | V52 | 354/354 |
| 2026-08-07 | Fase 5b — motor ARI/Stasis | v1.53 | V53 | 377/377 |
| 2026-08-07 | Fase 8 — Insights do Call Center | v1.54 | V54, V55 | 391/391 |
| 2026-08-07 | Fase 7a — base interna do chat | — | V56 | 399/399 |
| 2026-08-08 | Fase 7b — auth anônima e widget público | — | V57 | 411/411 |
| 2026-08-08 | Fase 9a — agregado diário de fila | — | V58 | 418/418 |
| 2026-08-08 | Fase 9b — agregado diário de agente | — | V59 | 425/425 |
| 2026-08-08 | Validação visual 7a/7b/8/9a (16 telas) | — | — | 418/418 |

**Bugs reais encontrados e corrigidos ao longo do módulo** (amostra representativa, todos
documentados em `CLAUDE.md`): sequestro de conta local homônima via bind AD (CRITICAL, Fase 1);
`LdapHealthIndicator` derrubando o healthcheck do backend (Fase 1); bypass de escopo por BU em
`findVersion` (HIGH, 5a); corrida em `publish`/`rollback` sem lock (5a); IDOR no traço de execução
(HIGH, 5b); credencial ARI na query string (HIGH, 5b); validador lendo `node.type` em vez de
`node.data.nodeType` — rejeitaria todo fluxo real (5b); menu não encerrando a chamada no timeout
(5b); duas configurações de CORS combinadas gerando 403 (7b); `IllegalStateException` virando 500
genérico no Desktop e no Chat para qualquer usuário sem vínculo de agente (validação visual).
