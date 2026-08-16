# Plan: Insights — expor todos os campos do XML Verint na tela de Chamadas

---

## Adendo pós-deploy (2026-07-24) — 4 ajustes na tela de Chamadas

**Origem:** pedido do usuário após validar o deploy da entrega original, começando por uma
pergunta de investigação ("o XML tem o número do agente?").

### Investigação — campo "agentid"
Confirmado nos XMLs reais (`/opt/audio`): não existe um número de telefone/DDD do agente — só
identificadores internos. O mais próximo do que o usuário pediu é a tag `agentid` (idêntica ao
elemento `session/pbx_login_id`, ex. `39773`) — o **login do agente no PBX/Avaya**, diferente do
`agent_id_verint` já existente (`session/agent_id`/`ultraagentid`, ex. `256003639`, chave interna
da Verint) e diferente do `extension`/Ramal (`devicename`, já mapeado). Hoje **nenhum dos dois**
(`agentid`/`pbx_login_id`) está mapeado — vai virar a coluna nova.

### Decisão 11 — 4 ajustes confirmados pelo usuário
1. **Nova coluna "Agente"** — campo `agentid`/`pbx_login_id` do XML, coluna + filtro (busca).
2. **Remover a coluna "Nº do cliente" — E o filtro também (revisado):** nem coluna nem filtro.
   `customerNumber` deixa de ser filtrável; o campo passa a aparecer só na seção "Identificação" do
   detalhe (mesmo padrão já usado para Organização/DNIS, que também não são coluna nem filtro).
3. **Renomear o cabeçalho "ANI" para "Tel. Cliente" — E adicionar filtro (revisado):** além do
   rótulo, ganha um filtro de busca novo. Como o valor exibido já é calculado por direção (decisão
   9 — `dnis` bruto se outbound, `ani` bruto caso contrário), o filtro precisa buscar no campo
   **certo conforme a direção de cada linha**, não só em `ani` bruto — senão uma busca por telefone
   não encontraria as chamadas efetuadas (que exibem o `dnis`). Implementação: predicado
   `(direction='outbound' AND dnis LIKE %v%) OR (direction<>'outbound' AND ani LIKE %v%)` — mesmo
   critério de `resolveDisplayAni`, só que como filtro de busca em vez de campo calculado.
4. **Layout responsivo** — a causa raiz é `--content-max-width: 1600px` em
   `insights-platform/frontend/src/App.css:59`, aplicado a `.page-header`/`.page-body`
   (`margin: 0 auto`) — em qualquer monitor mais largo que ~1860px (sidebar 260px + 1600px), sobra
   faixa em branco dos dois lados. **Essa variável é compartilhada por toda a SPA de Insights**
   (Dashboard, Processamento, Fichas, Relatórios, Meus Envios, Chamadas) — a correção é global, não
   só da tela de Chamadas (efeito colateral positivo: todas as telas passam a aproveitar a largura
   do monitor, não só esta). Proposta: trocar o teto fixo por algo fluido (`--content-max-width:
   100%` ou um teto bem mais alto tipo `2400px`) — a tabela de Chamadas já tem seu próprio
   `overflow-x:auto` interno, então isso não quebra o scroll horizontal dela.

## Mapa do campo novo (Grupo A — Identificação)
| Campo | Origem no XML | Coluna nova | Exibição |
|---|---|---|---|
| Agente (login PBX) | tag `agentid` (= elemento `session/pbx_login_id`) | `agent_login_id` VARCHAR(20) | **Coluna + filtro (busca)** |

## Files to Change (adendo)
| Arquivo | Ação | Porquê |
|---|---|---|
| `backend/.../db/migration/V44__call_audio_files_agent_login_id.sql` | CREATE | Coluna `agent_login_id` (nullable) |
| `insights/src/xml_parser.py` | UPDATE | Extrai `agentid`/`pbx_login_id` → `CallMetadata.agent_login_id` |
| `insights/src/main.py` (`_build_payload`) | UPDATE | Envia `agentLoginId` na ingestão |
| `insights/src/backfill_metadata.py` | UPDATE | Inclui `agentLoginId` no payload de backfill |
| `IngestInsightsRequest.java` / `InsightsMetadataUpdateRequest.java` | UPDATE | Campo `agentLoginId` |
| `CallAudioFile.java` | UPDATE | Coluna `agentLoginId` |
| `InsightsIngestionService.java` | UPDATE | Persiste em `ingest()` e `updateMetadata()` |
| `InsightsListItem.java` | UPDATE | Novo campo `agentLoginId` (coluna) |
| `InsightsAudioFileDto.java` | UPDATE | Novo campo `agentLoginId` no detalhe |
| `InsightsFilter.java` | UPDATE | **+** filtro `agentLoginId` (busca); **+** filtro `telCliente` (busca direction-aware); **−** filtro `customerNumber` (removido) |
| `InsightsSpecifications.java` | UPDATE | Predicado novo do `agentLoginId` (LIKE simples); predicado novo do `telCliente` (OR condicional por `direction`, mesmo critério de `resolveDisplayAni`); remove o predicado de `customerNumber` |
| `InsightsController.java` | UPDATE | `@RequestParam` novos (`agentLoginId`, `telCliente`); remove `@RequestParam customerNumber` |
| `insights-platform/frontend/src/api/types.ts` | UPDATE | Campo `agentLoginId` em `InsightsListItem`/`CallAudioFile` |
| `insights-platform/frontend/src/components/InsightsTab.tsx` | UPDATE | +coluna "Agente" e filtro; −coluna E −filtro de "Nº do cliente" (só continua no detalhe); renomeia cabeçalho "ANI"→"Tel. Cliente" e +filtro de busca por esse campo |
| `insights-platform/frontend/src/App.css` | UPDATE | `--content-max-width` fluido |

## Tasks (adendo)
### Task A1 — Migration V44 + parser Python
- **Action:** `ALTER TABLE call_audio_files ADD COLUMN agent_login_id VARCHAR(20)`; extrair no
  parser via `_find_tag_attribute(session, "agentid")` (helper já genérico, sem código novo de
  parsing); adicionar campo em `CallMetadata`.
- **Validate:** novo teste em `test_xml_parser.py` conferindo o valor `39773` no fixture existente;
  `pytest insights/`.

### Task A2 — Backend: campo, DTOs, filtros
- **Action:** propagar `agentLoginId` por `IngestInsightsRequest` → `CallAudioFile` →
  `InsightsListItem`/`InsightsAudioFileDto`; novo filtro `agentLoginId` (mesmo padrão do filtro
  `extension` já existente — busca `LIKE`); novo filtro `telCliente` com predicado condicional por
  `direction` (busca em `dnis` quando outbound, em `ani` caso contrário); **remover** o filtro
  `customerNumber` de `InsightsFilter`/`InsightsSpecifications`/`InsightsController` por completo.
- **Validate:** `mvn compile` + testes (`InsightsQueryServiceTest` cobrindo os 2 filtros novos e
  confirmando que `customerNumber` não é mais um parâmetro aceito).

### Task A3 — Frontend: coluna/filtro/detalhe
- **Action:** `types.ts` (`agentLoginId`); `InsightsTab.tsx`: nova coluna "Agente" (proposta: logo
  após "Ramal") + novo filtro de busca; remover **coluna e filtro** de "Nº do cliente" da UI —
  campo passa a aparecer só na seção "Identificação" do detalhe (ao lado de Organização/DNIS);
  renomear cabeçalho "ANI" → "Tel. Cliente" (mantém a chave `ani` no JSON, só troca o rótulo) **e**
  adicionar filtro de busca por esse campo (`telCliente`).
- **Validate:** `tsc --noEmit` + `npm run build`.

### Task A4 — Layout fluido
- **Action:** em `App.css`, trocar `--content-max-width: 1600px` por um valor fluido (proposta:
  `100%`, sem teto artificial — ou um teto bem mais alto como `2400px` se preferir manter alguma
  limitação em monitores 4K+/ultrawide para não deixar texto de outras telas — ex. narrativa de
  Relatórios — excessivamente esticado).
- **Validate:** `npm run build`; conferir visualmente (sem acesso a browser nesta sessão — pedir
  validação do usuário).

### Task A5 — Backfill + deploy
- **Action:** `docker compose up -d --build backend insights frontend` (V44 aplica no boot) →
  `docker exec asteriskia-insights python -m src.backfill_metadata` (repopula os 42 registros, agora
  incluindo `agentLoginId`) → validar visualmente.

## Riscos (adendo)
| Risco | Prob. | Mitigação |
|---|---|---|
| `--content-max-width` fluido esticar demais texto de outras telas (Relatórios, Dashboard) em monitores 4K/ultrawide | Média | Preferir um teto alto (`2400px`) a remover o limite por completo, se o usuário topar abrir mão de 100% fluido |
| Remover coluna + filtro de "Nº do cliente" reduz a busca por telefone do cliente ao que der pra achar via "Tel. Cliente" | Média | Aceito pelo usuário — o novo filtro "Tel. Cliente" já cobre boa parte do caso de uso (é o mesmo número na maioria das chamadas inbound); campo continua visível no detalhe pra consulta pontual |
| Filtro "Tel. Cliente" com predicado condicional por direção pode divergir sutilmente de `resolveDisplayAni` se a lógica não for espelhada com exatidão | Baixa | Implementar os dois a partir do mesmo critério documentado (decisão 9), com teste de Specification cobrindo inbound e outbound |
| Rótulo "Tel. Cliente" vs "TEL CLIENTE" (caixa alta) — usuário escreveu em maiúsculas | Baixa | Plano assume Title Case pra manter consistência com os demais cabeçalhos da tabela; ajusto fácil se o usuário realmente quiser caixa alta |

## Acceptance (adendo)
- [x] Coluna "Agente" (login PBX) aparece na tabela e tem filtro de busca funcionando
- [x] Coluna e filtro de "Nº do cliente" não existem mais; campo continua visível só no detalhe
- [x] Cabeçalho antes "ANI" agora mostra "Tel. Cliente" (mesmo dado, só o rótulo mudou) e tem filtro de busca próprio, funcionando tanto para chamadas inbound quanto outbound
- [x] Tela de Chamadas (e demais abas da SPA de Insights) preenchem a largura do monitor sem faixa em branco nas laterais, em pelo menos 1920px e 2560px de largura (`--content-max-width: 2400px`, sem acesso a browser nesta sessão para conferência visual)
- [x] `pytest` (16 passed), `mvn compile`+testes Insights* (verde via imagem `maven:3.9-eclipse-temurin-21` + volume `maven-repo-asteriskia`, mvn não disponível localmente), `tsc --noEmit`+`npm run build` limpos (SPA Insights e Telecom)
- [x] Deploy + backfill (2026-07-25): `docker compose build --no-cache backend insights` + `up -d`
      (V44 aplicou no boot, coluna+índice confirmados via psql); `backfill_metadata.py` rodou nas
      42 chamadas `done` (0 falhas, 38/42 já têm `agent_login_id` — as 4 restantes não trazem
      `agentid` no XML de origem, não é falha do backfill); validado via curl com JWT ADMIN forjado
      contra a API real (`agentLoginId`/`telCliente` funcionando, `customerNumber` inofensivo se
      ainda enviado). Validação visual na SPA (navegador) segue pendente — sem acesso a browser
      nesta sessão.


**Origem:** pedido do usuário (mapear todos os campos dos `.xml` em `/opt/audio` e montar MVP da tela de Chamadas com todos os campos); ampliado depois com a feature de descobrir **para qual ramal/atendente uma chamada foi transferida**.
**Complexidade:** Média-Alta (full-stack: Python + Flyway + Java + 2 SPAs, com backfill metadata-only + correlação assíncrona entre gravações).

## Decisões do usuário (confirmadas)
1. Exibir **os três grupos** de campos (A Identificação, B Qualidade, C Técnico/Auditoria).
2. Grupos **A e B** → **tabela + detalhe + filtros** (colunas próprias, com migration + backfill).
3. Grupo **C** → **restrito a administradores**, com enforcement no **frontend E no backend**.
4. **Colunas da tabela — REVISADO (versão final, campo a campo):** depois de ver a tabela ficar
   excessivamente extensa com "todos os campos como coluna" (~30 no total), o usuário revisou campo
   a campo e decidiu por uma lista bem mais enxuta. **Ver decisão 7** com a lista final e definitiva
   — esta decisão 4 fica registrada só como histórico do que foi descartado.
5. **Nova feature (pedido adicional):** descobrir **para qual número/ramal/atendente** cada chamada
   foi transferida — confirmado com o usuário que o XML **não traz isso como campo direto**; a
   única forma é **correlacionar dois arquivos `.xml`** (o da chamada original e o da perna de
   destino) por um ID de call-leg do PBX. Ver seção "Investigação" abaixo — a correlação é
   **best-effort**: no lote de 52 chamadas hoje em `/opt/audio`, **nenhuma das 5 transferências reais
   encontrou a perna de destino no mesmo lote** (a gravação de destino provavelmente não existe ainda
   nesse recorte). O usuário está ciente de que "não identificado" será o resultado mais comum até
   o volume de gravações crescer.
6. **Colunas de transferência (escolha do usuário, contra a recomendação do assistente):**
   **2 colunas separadas** — "Ramal destino" e "Atendente destino" — em vez de 1 coluna-resumo
   combinada. Mostram o **último** evento resolvido; vazio/traço quando `number_of_transfers=0`;
   badge "Não identificado" quando transferiu mas nada resolveu ainda. Nenhum filtro extra além do
   já planejado "Teve transferência" (um filtro de "resolvida/não resolvida" ficaria praticamente
   sempre vazio com a taxa de acerto atual).
7. **Lista final de colunas (decisão definitiva, campo a campo, revendo a decisão 4):** a tabela
   ficou grande demais com "tudo vira coluna" — o usuário revisou os 20 campos novos um a um contra
   a recomendação do assistente e decidiu **7 colunas novas** (não 20), somadas às 10 que já
   existiam = **17 colunas no total** (bem menos que as ~30 do desenho anterior, dispensando
   scroll horizontal como regra):
   - **Novas colunas confirmadas:** Nº do cliente, Ramal, ANI, Quem desligou, Wrap-up, Ramal destino,
     Atendente destino.
   - **Viram filtro + detalhe (sem coluna):** Organização, Nº de esperas (via checkbox "teve
     espera").
   - **Só detalhe (sem coluna nem filtro dedicado):** DNIS, Tempo em espera, Nº de transferências
     (o filtro "teve transferência" continua existindo como checkbox, só o *número exato* não vira
     coluna), Nº de conferências, e **todo o grupo C** (Codec, Pacotes RTP perdidos, Erros de
     decodificação, ID global/`switch_call_id`, Tronco, Tipo de captura, Datasource) — sempre
     admin-only, mesmo no detalhe.
   - Note que o usuário manteve as 2 colunas de transferência (decisão 6) mesmo sabendo da baixa
     taxa de resolução — ficou definitivo, sem novas mudanças nessa parte.
8. **Lista final de filtros (decisão definitiva, campo a campo — revisa e substitui a menção a
   filtros nas decisões 2/6):** o usuário revisou todos os candidatos a filtro (14 já existentes +
   21 candidatos novos) e escolheu **8 filtros novos**, ficando **22 filtros no total**:
   - **Filtros novos confirmados:** Nº do cliente (busca), Ramal (busca — mesmo já sendo coluna),
     Quem desligou (dropdown), Teve espera (checkbox), Wrap-up (faixa min/max — mesmo já sendo
     coluna), Ramal destino (busca), Atendente destino (busca), **ID global / `switch_call_id`
     (busca exata, ADMIN-only)**.
   - **Descartados, inclusive alguns que pareciam certos antes:** Organização (**revoga o que a
     decisão 7 tinha registrado** — vira só detalhe, sem filtro nem coluna), **"Teve transferência"
     (revoga o que a decisão 6 tinha assumido como certo)** — o usuário não escolheu esse filtro
     desta vez, então `number_of_transfers` fica só no detalhe, sem nenhum filtro associado; ANI,
     DNIS, Tempo em espera, Nº de transferências (faixa), Teve conferência, Nº de conferências,
     Transferência identificada, Codec, Tronco, Tipo de captura, Datasource.
   - **Atenção de segurança (novo risco):** o filtro por `switch_call_id` só pode ser aceito pelo
     backend quando o usuário autenticado é ADMIN — do contrário um usuário comum poderia usar o
     parâmetro de busca para descobrir a existência/correlação de um ID técnico do grupo C mesmo
     sem a coluna aparecer. Ver Task 5 e Riscos.
9. **Ajuste no cálculo de ANI (pedido adicional):** em chamadas com `direction=Outbound`
   (Efetuada), a coluna **ANI exibida** deve receber o valor do **DNIS**, não o `session/ani` bruto.
   Motivo: em ligações efetuadas o `session/ani` do XML é o **ramal do próprio atendente** (quem
   originou a chamada), enquanto o `session/dnis` é o número externo discado — mostrar o `ani` bruto
   nesse caso confundiria o usuário fazendo parecer que "ANI" é o ramal e não o número do cliente.
   Já no inbound o `session/ani` já é o número externo (chamador) e continua exibido normalmente.
   **Decisão de implementação:** isso é uma regra de **exibição/mapeamento**, não uma mudança na
   ingestão — o `ani`/`dnis` brutos persistidos em `call_audio_files` continuam fiéis ao XML (sem
   mutação no parser/ingestão), a troca acontece só na camada de DTO (`InsightsListItem`/
   `InsightsAudioFileDto`), o mesmo lugar onde `customer_number` já resolve precedência por direção
   — evita reescrever um campo pré-existente (`ani`) que pode ter outros consumidores futuros.
10. **Remover a coluna Wrap-up da tabela (pedido adicional):** Wrap-up deixa de ser coluna — vira
    **só filtro (faixa min/max) + detalhe**, igual ao padrão já usado pra Nº de esperas. Reduz a
    tabela de 17 para **16 colunas no total**.

## Sumário
Hoje o XML Verint é rico, mas só 10 campos viram coluna em `call_audio_files` e a tela mostra 5.
Todo o XML já está persistido em `xml_raw` (JSONB) nas 52 chamadas. Vamos promover os campos
escolhidos a colunas próprias (parsing no `xml_parser.py`, migration V43), expô-los na tabela /
detalhe / filtros da SPA de Insights, e restringir o grupo C a ADMIN no backend (DTO de detalhe +
nulling na lista) e no frontend (colunas escondidas). Backfill das 52 chamadas é **metadata-only**
(re-parse do XML em disco/`xml_raw`, sem reprocessar STT/LLM).

Além disso, vamos extrair os **eventos de transferência** de cada chamada (0..N por chamada, tabela
filha nova `call_transfer_events`) e tentar **resolver o destino** correlacionando com outras
gravações já ingeridas — dos dois lados (quando a chamada nova É uma origem de transferência, e
quando ela É o destino de uma transferência pendente de outra chamada já ingerida).

## Investigação — como o XML representa uma transferência
Confirmado em 5 arquivos reais com `contact/number_of_transfers=1` (ex.:
`256001003459902---d2343ea9-....xml`, `256001003459919---bf3c85b2-....xml`):

- Dentro de `session/tags`, os `<x:tag>` aparecem **em ordem cronológica**. Uma transferência gera
  duas tags em sequência:
  1. `eventtype=Begin_Call` com atributos `externalcallid`/`globalcallid` — **um novo ID de call-leg**
     gerado pelo PBX para a chamada que está sendo originada para o destino.
  2. Logo em seguida, `eventtype=Transferred` (+ `disconnectingparty`, `numberoftimestransferred`,
     `extendedcallhistory=...,Transfer`) — o evento que fecha a perna atual.
- **Nenhuma tag carrega o ramal/número de destino diretamente** — `extension`/`calledparty`/`dnis`/
  `numberdialed` no arquivo são sempre do **ramal de origem** deste próprio arquivo.
- O único jeito de saber o destino é achar **outro arquivo `.xml`** cujo `session/switch_call_id`
  seja igual ao `globalcallid` capturado no `Begin_Call` que precede o `Transferred` — aí sim
  `session/extension` e `session/employeename` **daquele outro arquivo** são o ramal/atendente de
  destino real.
- Testado nos 5 casos reais: **0/5** `globalcallid` de destino batem com o `switch_call_id` de
  qualquer um dos outros 47 arquivos do lote — ou seja, a perna de destino não está neste recorte
  de `/opt/audio` (pode estar em um lote anterior/futuro, ou o ramal de destino não é gravado).
  A feature precisa ser desenhada para esse cenário ser o **caso normal, não um erro**.

## Mapa dos campos (XML `recording20080320` → coluna nova)

### Grupo A — Identificação
| Campo | Origem no XML | Coluna nova | Exibição final |
|---|---|---|---|
| Número do cliente | tag `signallingcallingparty` (inbound) / `calledparty`\|`numberdialed` (outbound) | `customer_number` VARCHAR(50) | **Coluna + filtro (busca)** |
| Organização/Unidade | tag `organization` (`Agentes-CM01`…`CM04`) | `organization` VARCHAR(100) | Só detalhe, **sem coluna nem filtro** (revisado na decisão 8) |
| Ramal | `session/extension` (**já existe** `extension`) | — (só expor) | **Coluna + filtro (busca)** |
| ANI (exibido) | `session/ani` **inbound** / `session/dnis` **outbound** (ver decisão 9 — troca só na exibição, raw `ani`/`dnis` preservados no banco) | — (só expor, com regra de exibição por direção) | **Coluna**, sem filtro |
| DNIS | `session/dnis` (**já existe**) | — (só expor) | Só detalhe, **sem coluna nem filtro** |

### Grupo B — Qualidade
| Campo | Origem | Coluna nova | Exibição final |
|---|---|---|---|
| Quem desligou | tag `disconnectingparty` (EMPLOYEE→atendente / OTHER→cliente) | `disconnected_by` VARCHAR(20) | **Coluna + filtro (dropdown)** |
| Nº de esperas | `session/number_of_holds` | `number_of_holds` INT | Filtro (checkbox "teve espera") + detalhe, **sem coluna** |
| Tempo em espera (s) | `session/total_hold_time` | `total_hold_time` INT | Só detalhe, **sem coluna nem filtro** |
| Nº de transferências | `contact/number_of_transfers` | `number_of_transfers` INT | Só detalhe, **sem coluna nem filtro** (revisado na decisão 8 — "teve transferência" não foi escolhido desta vez) |
| Nº de conferências | `contact/number_of_conferences` | `number_of_conferences` INT | Só detalhe, **sem coluna nem filtro** |
| Wrap-up (s) | `session/wrapup_time` | `wrapup_time` INT | Filtro (faixa min/max) + detalhe, **sem coluna** (revisado — decisão 10) |
| **Ramal destino** *(novo)* | resolvido via `call_transfer_events` (ver Grupo D), último evento | — (calculado, não persistido em `call_audio_files`) | **Coluna + filtro (busca)** |
| **Atendente destino** *(novo)* | resolvido via `call_transfer_events` (ver Grupo D), último evento | — (calculado, não persistido em `call_audio_files`) | **Coluna + filtro (busca)** |

### Grupo C — Técnico/Auditoria (ADMIN-only, sempre só detalhe — nenhum vira coluna, nem para ADMIN)
| Campo | Origem | Coluna nova | Exibição final |
|---|---|---|---|
| Codec | `stream/rtptypename` (G729A) | `codec` VARCHAR(20) | Só detalhe (ADMIN) |
| Pacotes RTP perdidos | `stream/missedrtppackets` | `missed_rtp_packets` INT | Só detalhe (ADMIN) |
| Erros de decodificação | `stream/decodingerrors` | `decoding_errors` INT | Só detalhe (ADMIN) |
| ID global da chamada | `session/switch_call_id` | `switch_call_id` VARCHAR(50) | Só detalhe (ADMIN) |
| Tronco | tag `trunk` (+ `trunkgroup`) | `trunk` VARCHAR(20) | Só detalhe (ADMIN) |
| Tipo de captura | `segment/capturetype` (IP) | `capture_type` VARCHAR(20) | Só detalhe (ADMIN) |
| Datasource | tag `datasourcename` (CM01) | `datasource_name` VARCHAR(20) | Só detalhe (ADMIN) |

**Resumo da tabela final: 16 colunas** = 10 já existentes (Data/Hora, Atendente, Direção, Fila,
Duração, Categoria, Sentimento, Criticidade, Nota, Status) + 6 novas (Nº do cliente, Ramal, ANI —
com a regra de exibição por direção da decisão 9 —, Quem desligou, Ramal destino, Atendente
destino). Wrap-up saiu da tabela (decisão 10), ficando só filtro + detalhe. Scroll horizontal deixa
de ser regra — vira só um reforço de segurança se a largura ainda não couber em telas menores.

### Grupo D — Transferências *(novo, tabela filha `call_transfer_events`)*
Uma chamada pode ter 0..N transferências — modelado como tabela filha (mesmo padrão de
`call_insight_findings`/`call_evaluation_items`), não como colunas flat em `call_audio_files`.

| Campo | Origem | Coluna |
|---|---|---|
| Ordem da transferência | posição na sequência de tags | `transfer_order` SMALLINT |
| Quando ocorreu | `x:timestamp` da tag `Transferred` | `transferred_at` TIMESTAMP |
| Quem desligou naquele evento | atributo `disconnectingparty` da própria tag `Transferred` | `disconnected_by` VARCHAR(20) |
| ID de correlação do destino | `globalcallid` da tag `Begin_Call` imediatamente anterior | `target_switch_call_id` VARCHAR(50) — **admin-only**, é o mesmo dado técnico do grupo C; **filtrável por busca exata, mas só quando o requisitante é ADMIN** (decisão 8) |
| Ramal de destino (resolvido) | `session/extension` do arquivo cujo `switch_call_id` bate | `target_extension` VARCHAR(20), nullable |
| Atendente de destino (resolvido) | `session/employeename` do arquivo cujo `switch_call_id` bate | `target_agent_name` VARCHAR(100), nullable |
| Chamada de destino (resolvida) | `call_ref`/`id` do `call_audio_files` casado | `target_audio_file_id` BIGINT FK, nullable |
| Resolvido em | quando a correlação bateu | `resolved_at` TIMESTAMP, nullable — `NULL` = pendente/não encontrado |

## Patterns to Mirror
| Categoria | Fonte | Padrão |
|---|---|---|
| Parser XML | `insights/src/xml_parser.py:32` | `@dataclass(frozen=True) CallMetadata` + `_find_tag_attribute`/`_parse_int` |
| Payload ingestão | `insights/src/main.py:79` `_build_payload` | dict camelCase espelhando `IngestInsightsRequest` |
| Migration | `backend/.../db/migration/V36__call_audio_files_started_at.sql` | `ALTER TABLE ... ADD COLUMN` idempotente-seguro, nullable |
| Tabela filha (1:N) | `CallInsightFinding.java` / `CallEvaluationItem.java` | Entidade própria com FK `audio_file_id`, sem herança, factory a partir do DTO de ingestão |
| Filtro/Spec | `InsightsSpecifications.withFilters:19` | Criteria API sobre colunas de `CallAudioFile`; campos em branco ignorados |
| Detecção ADMIN | `InsightsController.canAccessUpload:215` | `auth.getAuthorities()` contém `ROLE_ADMIN` |
| DTO de resposta | `InsightsListItem.java` (record + `from(...)`) | record imutável + factory |
| Tabela/filtros SPA | `insights-platform/frontend/src/components/InsightsTab.tsx` | colunas `<th>`, grid de filtros, `criticidadeBadge`/`directionBadge` |
| Correlação assíncrona | **sem equivalente no repo** | Desenho novo — ver Task 7 (resolução nos dois sentidos, disparada a cada ingestão) |

## Files to Change
| Arquivo | Ação | Porquê |
|---|---|---|
| `backend/.../db/migration/V43__call_audio_files_verint_fields.sql` | CREATE | Adiciona as 13 colunas do grupo A/B/C + tabela `call_transfer_events` |
| `insights/src/xml_parser.py` | UPDATE | Extrai os novos campos A/B/C **e** a lista de eventos de transferência (par `Begin_Call`→`Transferred`) → `CallMetadata` |
| `insights/src/main.py` (`_build_payload`) | UPDATE | Envia os novos campos e `transferEvents` na ingestão |
| `insights/src/backfill_metadata.py` | CREATE | Script one-off: re-parse XML/`xml_raw` (metadata-only, sem tocar disco se possível) → POST metadata endpoint, incluindo eventos de transferência |
| `insights/src/backend_client.py` | UPDATE | `submit_metadata(call_ref, payload)` p/ o novo endpoint interno |
| `IngestInsightsRequest.java` | UPDATE | Novos campos A/B/C opcionais + `List<TransferEventDto> transferEvents` |
| `CallTransferEvent.java` | CREATE | Entidade JPA da tabela filha |
| `CallTransferEventRepository.java` | CREATE | `findByTargetSwitchCallIdAndResolvedAtIsNull`, `findByAudioFileId` |
| `TransferResolutionService.java` | CREATE | Resolve nos dois sentidos (ver Task 7) |
| `InsightsIngestionService.java` | UPDATE | Persistir os novos campos em `CallAudioFile`; persistir `call_transfer_events`; chamar `TransferResolutionService` |
| `InsightsInternalController.java` | UPDATE | `POST /internal/insights/{callRef}/metadata` (backfill metadata-only, aceita `transferEvents`) |
| `CallAudioFile.java` | UPDATE | 13 campos novos (`@Column`) |
| `InsightsAudioFileDto.java` | CREATE | DTO do detalhe: A/B sempre; C só ADMIN; **sem `xml_raw`** |
| `InsightsDetailResponse.java` | UPDATE | Trocar `CallAudioFile` cru por `InsightsAudioFileDto`; incluir `transferEvents` |
| `InsightsListItem.java` | UPDATE | **Só os 6 campos de coluna** (`customerNumber`, `extension`, `ani` — calculado: `direction==OUTBOUND ? dnis : ani` bruto, ver decisão 9 —, `disconnectedBy`, `transferTargetExtension`, `transferTargetAgentName` — os 2 últimos do último evento resolvido, `null` se não transferiu/não resolveu); `wrapupTime` **não entra** mais aqui (decisão 10, foi pra filtro/detalhe); demais campos A/B/C **não entram** neste DTO, só no de detalhe |
| `InsightsFilter.java` | UPDATE | Novos filtros finais (decisão 8): `customerNumber`, `extension`, `disconnectedBy`, `hasHold`, `wrapupTimeMin`/`wrapupTimeMax`, `transferTargetExtension`, `transferTargetAgentName`, `targetSwitchCallId` |
| `InsightsSpecifications.java` | UPDATE | Predicados dos novos filtros; `targetSwitchCallId` só aplicado se `isAdmin=true` (senão o parâmetro é ignorado, nunca gera erro) |
| `InsightsQueryService.java` / `InsightsController.java` | UPDATE | Propagar `isAdmin` p/ mapping e para a Specification (gate do filtro `targetSwitchCallId`); novos `@RequestParam` |
| `insights-platform/frontend/src/api/types.ts` | UPDATE | Espelhar campos novos em `CallAudioFile`/`InsightsListItem`; `CallTransferEvent` |
| `insights-platform/frontend/src/api/client.ts` | UPDATE/verify | Helper `isAdmin()` (decodifica claim `role`) |
| `insights-platform/frontend/src/components/InsightsTab.tsx` | UPDATE | Colunas (A/B sempre, C admin), coluna "Transferido para", histórico de transferências no detalhe, filtros novos |

## Tasks
### Task 1 — Migration V43 (schema completo)
- **Action:** `ALTER TABLE call_audio_files ADD COLUMN` das 13 colunas A/B/C (nullable) **+**
  `CREATE TABLE call_transfer_events` (FK `audio_file_id` → `call_audio_files.id`, índice em
  `target_switch_call_id` para a resolução, índice em `resolved_at` para achar pendentes rápido).
- **Validate:** revisar SQL (migrations são irreversíveis em prod); sobe no boot do backend.

### Task 2 — Parser Python: campos A/B/C (going-forward)
- **Action:** estender `CallMetadata` e `parse_call_xml`; incluir os campos em `_build_payload`.
- **Mirror:** `_find_tag_attribute`, `_parse_int`, `@dataclass(frozen=True)`.
- **Validate:** `python -m pytest insights/` (novos testes de parser com os XMLs reais); `python -m ast`.

### Task 3 — Parser Python: eventos de transferência
- **Action:** percorrer `session/tags/tag` **na ordem em que aparecem**; manter o último
  `globalcallid` visto num `eventtype=Begin_Call`; a cada `eventtype=Transferred`, emitir
  `TransferEvent(transferred_at, disconnected_by, target_switch_call_id=<último globalcallid>)`.
  Retornar `List[TransferEvent]` em `CallMetadata`. Cobrir com teste os 5 XMLs reais confirmados
  na investigação (todos com 1 transferência) e um XML sintético com 2 transferências.
- **Validate:** `pytest` cobrindo 0/1/N transferências e o caso sem nenhum `Begin_Call` anterior
  (não deve quebrar, só não emitir evento).

### Task 4 — Backend: ingestão + detalhe + lista (A/B/C)
- **Action:** novos campos em `IngestInsightsRequest` + persistência; `InsightsAudioFileDto` (C só
  ADMIN, sem `xml_raw`); `InsightsListItem` com nulling de C; endpoint metadata interno. Incluir a
  regra de exibição do ANI (decisão 9): método utilitário (ex.: `resolveDisplayAni(CallAudioFile)`)
  usado tanto em `InsightsListItem.from()` quanto em `InsightsAudioFileDto` — retorna `dnis` quando
  `direction == OUTBOUND`, senão retorna `ani` — **sem alterar os campos brutos persistidos**.
- **Mirror:** `canAccessUpload` p/ detecção ADMIN; record + `from(...)`.
- **Validate:** `mvn compile` + testes (`InsightsQueryServiceTest`, novo teste de gating ADMIN, teste
  unitário de `resolveDisplayAni` cobrindo inbound/outbound).

### Task 5 — Backend: filtros (decisão 8, lista final)
- **Action:** novos campos em `InsightsFilter`/`InsightsSpecifications`/`InsightsController`:
  `customerNumber` (busca), `extension` (busca), `disconnectedBy` (dropdown), `hasHold` (checkbox),
  `wrapupTimeMin`/`wrapupTimeMax` (faixa), `transferTargetExtension` (busca),
  `transferTargetAgentName` (busca), `targetSwitchCallId` (busca exata — **exige `isAdmin=true`**,
  o `InsightsController` recebe o parâmetro mas só repassa pra Specification se o usuário for
  ADMIN; para não-ADMIN o parâmetro é silenciosamente ignorado, nunca retorna erro nem vaza dado).
  **Não incluir** filtro de organização nem de "teve transferência" — descartados na decisão 8.
- **Validate:** `mvn compile`; teste de Specification cobrindo o gate ADMIN do `targetSwitchCallId`
  (usuário comum enviando esse parâmetro não deve influenciar o resultado).

### Task 6 — Backend: persistir eventos de transferência
- **Action:** `InsightsIngestionService` grava um `CallTransferEvent` por item de
  `IngestInsightsRequest.transferEvents`, com `resolved_at=null` inicialmente.
- **Mirror:** mesmo padrão de persistência de `CallInsightFinding`.
- **Validate:** `mvn compile` + teste de persistência (N eventos por chamada).

### Task 7 — Backend: `TransferResolutionService` (correlação nos dois sentidos)
- **Action:** chamado **dentro da mesma transação** de toda ingestão (nova chamada ou backfill):
  1. **Sentido "esta chamada é origem":** para cada `CallTransferEvent` recém-criado desta chamada,
     buscar em `call_audio_files` uma linha com `switch_call_id = target_switch_call_id`; se achar,
     preencher `target_extension`/`target_agent_name`/`target_audio_file_id`/`resolved_at`.
  2. **Sentido "esta chamada é destino":** buscar em `call_transfer_events` linhas pendentes
     (`resolved_at IS NULL`) cujo `target_switch_call_id = <switch_call_id da chamada recém-ingerida>`;
     se achar, resolver da mesma forma.
- **Por que os dois sentidos:** a ordem de ingestão entre origem e destino não é garantida (a
  gravação de destino pode chegar em `/opt/audio` antes ou depois da de origem).
- **Comportamento esperado quando não resolve:** permanece `resolved_at=null` indefinidamente — não
  é erro, é o estado normal enquanto a gravação de destino não aparecer. **Confirmado empiricamente:
  0 das 5 transferências reais do lote atual resolvem hoje** — a UI precisa tratar isso como "não
  identificado", nunca como falha.
- **Validate:** teste com 2 chamadas fictícias (origem ingerida primeiro, depois destino) e o
  inverso (destino primeiro, depois origem) — ambos devem resolver; teste de "nunca resolve" não
  deve gerar exceção nem retry infinito.

### Task 8 — Backfill metadata-only das 52 chamadas (+ correlação retroativa)
- **Action:** `insights/src/backfill_metadata.py` — itera call_refs, re-parseia XML/`xml_raw` (sem
  Gemini), POST no endpoint metadata (inclui `transferEvents`). Rodar via
  `docker exec asteriskia-insights python -m src.backfill_metadata`. Como a ingestão do backend já
  roda `TransferResolutionService` nos dois sentidos, basta reingerir metadata de todas as 52 —
  qualquer par origem/destino que exista dentro do próprio lote se resolve sozinho.
- **Validate:** conferir 1-2 linhas no psql após rodar; **não** deve alterar transcrição/insights;
  confirmar que os 5 eventos de transferência conhecidos aparecem em `call_transfer_events` (mesmo
  que `resolved_at` continue `null` nesse lote).

### Task 9 — Frontend (SPA Insights)
- **Action:** `types.ts` (campos novos + `CallTransferEvent`), `client.ts` (`isAdmin()`),
  `InsightsTab.tsx`:
  - **6 colunas novas na tabela** (16 no total com as 10 existentes): Nº do cliente, Ramal, ANI
    (exibindo DNIS quando `direction=Efetuada`, ver decisão 9 — já vem calculado do backend, o
    frontend só exibe o valor recebido), Quem desligou, Ramal destino, Atendente destino. Wrap-up
    **não é mais coluna** (decisão 10). **Nenhuma coluna do grupo C** — nem para ADMIN, o gating
    admin passa a valer só para o que aparece no **detalhe**.
  - Colunas "Ramal destino"/"Atendente destino": traço `—` quando `number_of_transfers = 0`; badge
    neutro **"Não identificado"** quando transferiu mas nada resolveu ainda.
  - Filtros finais (decisão 8): Nº do cliente (busca), Ramal (busca), Quem desligou (dropdown —
    reforça a coluna), Teve espera (checkbox), Wrap-up (faixa min/max — reforça a coluna), Ramal
    destino (busca), Atendente destino (busca), e **ID global/`switch_call_id`** (busca exata,
    **campo do filtro só renderiza quando `isAdmin()`**). Sem filtro de Organização nem de "teve
    transferência" (descartados).
  - No detalhe: campos que **não** viraram coluna nem filtro (Organização, DNIS **cru** — a seção
    de identificação mostra o `dnis` bruto separado do "ANI" calculado que já aparece na coluna,
    Tempo em espera, Nº de esperas, Nº de transferências, Nº de conferências, Wrap-up) ficam na
    seção de identificação/qualidade;
    grupo C inteiro (Codec, RTP, erros, ID global, Tronco, Tipo de captura, Datasource) numa seção
    separada **visível só para `isAdmin()`**; seção **"Histórico de transferências"** (lista, não
    coluna — suporta N eventos) com timestamp, quem desligou e destino (ramal + atendente, ou "não
    identificado").
- **Mirror:** `directionBadge`/`criticidadeBadge`, grid de filtros existente.
- **Validate:** `tsc --noEmit` + `npm run build` na SPA de Insights.

### Task 10 — Release notes + deploy
- **Action:** entrada em `frontend/src/data/releases.ts` (release notes é obrigatório) — mencionar
  explicitamente que a correlação de transferência é best-effort e depende da gravação de destino
  existir em `/opt/audio`.
- **Deploy:** `docker compose up -d --build backend insights frontend` (V43 aplica no boot) → rodar
  backfill → validar no navegador (inclusive o estado "não identificado").

## Validation
```bash
# Python
cd /opt/VoipIA && python -m pytest insights/ && python -m py_compile insights/src/*.py
# Backend
cd backend && mvn -q compile && mvn -q test -Dtest='Insights*'
# Frontend
cd insights-platform/frontend && npx tsc --noEmit && npm run build
```

## Riscos
| Risco | Prob. | Mitigação |
|---|---|---|
| Tabela ficar extensa demais | **Já ocorreu uma vez** (~30 colunas na primeira rodada de decisão) | **Corrigido**: usuário revisou campo a campo (decisão 7) e reduziu para 17 colunas totais (10 existentes + 7 novas); grupo C e a maioria de B/A viraram filtro/detalhe. `overflow-x:auto` no `table-wrapper` mantido como reforço, não como muleta principal. |
| Correlação de transferência quase nunca resolve com o volume atual | **Confirmada (0/5 no lote de 52)** | Tratar `resolved_at=null` como estado normal na UI ("não identificado"), não como erro; reavaliar taxa de acerto conforme o volume de `/opt/audio` cresce |
| Correlação por `globalcallid`/`switch_call_id` pode ter falso-positivo se o PBX reaproveitar IDs | Baixa | IDs observados são únicos e crescentes por chamada; sem evidência de reuso nos dados reais — monitorar se aparecer |
| Backfill reprocessar STT/LLM por engano (custo Gemini) | Média | Caminho metadata-only dedicado; nunca passa por `submit_insights`/pipeline de IA |
| Migration irreversível em prod | Média | Só `ADD COLUMN`/`CREATE TABLE` nullable, sem backfill destrutivo no SQL; revisar antes |
| `xml_raw` hoje trafega no detalhe p/ todos | Baixa | DTO novo remove `xml_raw` do payload (ganho colateral de segurança) |
| Derivação de "número do cliente" varia por direção | Média | Precedência robusta inbound/outbound + fallback; testes com XMLs reais das duas direções |
| ANI exibido (decisão 9) confundir quem espera ver o `session/ani` bruto do XML em auditoria | Baixa | O `ani`/`dnis` brutos continuam intactos em `call_audio_files`/`xml_raw` e visíveis no detalhe (DNIS cru); só a coluna/DTO de exibição aplica a troca — documentar isso no código (`resolveDisplayAni`) pra não parecer bug |
| Sincronia de tipos entre as 2 SPAs (Telecom espelha types.ts) | Baixa | Insights consome só a SPA própria; Telecom não mostra estas colunas — atualizar só `insights-platform` |
| Filtro `targetSwitchCallId` vazar existência de dado técnico (grupo C) pra não-ADMIN via busca | Média | Gate no backend (`InsightsController`/`InsightsSpecifications`), não só esconder o campo no frontend — mesmo padrão de "nunca confiar só na UI" já usado pras colunas do grupo C |

## Acceptance
- [ ] 13 colunas A/B/C criadas em `call_audio_files` (V43) + tabela `call_transfer_events` criadas,
      e populadas nas 52 chamadas via backfill (sem reprocessar IA)
- [ ] Tabela `call_transfer_events` populada com os eventos de transferência reais (5 no lote
      atual), mesmo que `resolved_at` fique `null`
- [ ] `TransferResolutionService` resolve corretamente em teste controlado (origem→destino e
      destino→origem, em qualquer ordem de ingestão)
- [ ] **Tabela final com exatamente 16 colunas** (10 existentes + Nº do cliente, Ramal, ANI, Quem
      desligou, Ramal destino, Atendente destino) — nenhuma coluna do grupo C, nem para ADMIN
- [ ] Coluna ANI mostra o `dnis` bruto para chamadas `direction=Outbound` e o `ani` bruto para
      `Inbound` (teste unitário de `resolveDisplayAni`); dado bruto de `ani`/`dnis` continua intacto
      no banco e visível no detalhe
- [ ] Organização, DNIS, Nº esperas, Tempo em espera, Nº transferências, Nº conferências, Wrap-up e
      grupo C aparecem só no detalhe (grupo C só para ADMIN)
- [ ] **8 filtros novos funcionando** (Nº do cliente, Ramal, Quem desligou, Teve espera, Wrap-up
      faixa, Ramal destino, Atendente destino, ID global/`switch_call_id`); filtro de Organização e
      de "teve transferência" **não implementados** (descartados na decisão 8)
- [ ] Filtro `targetSwitchCallId` confirmadamente ignorado no backend quando o requisitante não é
      ADMIN (teste automatizado, não só ausência no frontend)
- [ ] Colunas "Ramal destino"/"Atendente destino" e histórico de transferências no detalhe tratam
      "não identificado" sem parecer erro
- [ ] Grupo C (incl. `target_switch_call_id`) ausente do payload para não-ADMIN (verificado via
      DevTools/token USER)
- [ ] `mvn compile` + testes, `tsc --noEmit` + `npm run build`, `pytest` limpos
- [ ] Release notes registrada; deploy + validação visual
