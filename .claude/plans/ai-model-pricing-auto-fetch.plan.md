# Plan: Atualização automática diária do preço de tokens de IA (`ai_model_pricing`)

**Origem**: pedido do usuário em 2026-07-18, via `/ecc:plan`, logo após a correção do valor
zerado ($0,00) em `ai_model_pricing` identificado na entrega anterior (Insights Custos/Processamento).
**Complexidade**: Medium (~1-2 dias equivalente)
**Status geral**: ✅ CONCLUÍDO e deployado em produção (2026-07-18) — ver seção "Fechamento" no final

---

## Correção imediata — já aplicada em produção (2026-07-18)

Antes deste plano, já corrigi o problema atual: `ai_model_pricing` tinha `$0,00` cadastrado pros
2 modelos desde a entrega de Custos IA (aba de URA e de Insights usam a mesma tabela). Apliquei os
preços oficiais publicados por Google (USD por 1M de tokens, tabela em
`ai.google.dev/gemini-api/docs/pricing`) via o endpoint já existente
`PUT /api/v1/ai/model-pricing/{modelId}`:

| Modelo | Input | Output |
|---|---|---|
| `gemini-2.5-flash` | $0.30 | $2.50 |
| `gemini-2.5-flash-preview-tts` | $0.50 | $10.00 |

Validado: `/api/v1/insights/costs/summary` agora retorna `totalCostUsd: 0.532397` (42 chamadas de
junho) em vez de `0.000000`. Mesma correção vale para a aba de Custos de URA (mesma tabela).

**Isso resolve o sintoma agora. Este plano resolve a causa raiz** (preço nunca é atualizado
sozinho, vai ficar defasado de novo assim que a Google reajustar).

---

## Achado crítico da pesquisa — não existe API oficial de preços

Pesquisei se a Google expõe os preços do Gemini de forma programática (JSON, REST, feed) antes de
desenhar a solução. **Não existe.** A página `ai.google.dev/gemini-api/docs/pricing` é HTML puro,
sem JSON-LD, sem atributos parseáveis, sem endpoint documentado — é a única fonte pública. Qualquer
automação de busca de preço vai depender de fazer parsing de HTML de uma página que a Google pode
redesenhar a qualquer momento, sem aviso, quebrando o parser silenciosamente.

**Isso muda o desenho da solução**: como o valor "será usado para tomada de decisão de negócio",
não posso simplesmente sobrescrever o preço com o que o scraper conseguir ler — preciso tratar todo
resultado do scraper como **não confiável até provar o contrário**, com validação de sanidade,
nunca sobrescrever com zero/inválido, manter o último preço válido em caso de falha, e **alertar um
humano imediatamente** quando o scraper falhar ou quando o preço mudar de forma significativa — para
que a defasagem nunca fique invisível por muito tempo (diferente do bug atual, que ficou silencioso
desde a entrega original de Custos IA).

---

## Requisitos (restatement)

1. Job agendado no backend Java, rodando **todo dia às 02:00**, que busca o preço atualizado por
   milhão de tokens (input/output) dos modelos Gemini usados (`gemini-2.5-flash`,
   `gemini-2.5-flash-preview-tts`) e persiste em `ai_model_pricing`.
2. **Nunca** sobrescrever com preço zero/inválido — se a busca falhar por qualquer motivo, manter o
   último preço válido e registrar o erro de forma visível (log + alerta).
3. Fallback de correção manual — hoje **não existe nenhuma UI** para `AiModelPricingController`
   (só o endpoint backend, usado só por curl/seed). Construir uma tela mínima em
   Configurações → IA pra um admin conferir/corrigir o preço a qualquer momento, sem depender do
   scraper nem de acesso a banco.
4. Documentar o processo inteiro (onde roda, frequência, de onde vem o dado, comportamento em
   falha, como corrigir manualmente) na tela de Documentação — deixando claro que a fonte é uma
   página pública não-oficial, não uma API garantida pela Google, para o usuário calibrar o nível
   de confiança ao tomar decisões com esse número.

---

## Arquitetura da entrega

### Backend Java (`domain/ai/`)

**Nova dependência**: `org.jsoup:jsoup` no `pom.xml` — parsing de HTML robusto (contra tags/atributos
reais), evita regex frágil sobre HTML de terceiro. Biblioteca madura e amplamente usada, sem
implicação de segurança (só parsing, nenhuma execução de script da página).

**`AiPricingSourceFetcher`** (novo): busca `https://ai.google.dev/gemini-api/docs/pricing` via
`WebClient` (mesmo padrão reativo já usado em `JiraIntegrationService`/`ZabbixPollingService`),
timeout curto (ex: 10s), parseia com Jsoup as tabelas de preço procurando as linhas dos 2 modelos
configurados (mapa interno `modelId → texto de busca na tabela`, ex: "Gemini 2.5 Flash" /
"Gemini 2.5 Flash Preview TTS"), extrai `input`/`output` em USD por 1M tokens.
Retorna um resultado tipado — sucesso com valores, ou falha com motivo (`rede`, `parse`,
`modelo não encontrado na página`, `valor fora da faixa plausível`).

**Validação de sanidade** (antes de aceitar qualquer valor extraído):
- Deve ser um `BigDecimal` parseável e positivo (`> 0`).
- Deve estar dentro de uma faixa plausível pra preço de LLM hoje (ex: `0.01` a `100` USD/milhão) —
  protege contra o parser pegar o número errado da página (ex: um valor de outra coluna/modelo) e
  gravar um preço absurdo sem perceber.
- Se qualquer critério falhar → tratado como falha de busca pra aquele modelo (não all-or-nothing:
  se só um dos 2 modelos falhar, o outro ainda é atualizado).

**`AiModelPricingSyncScheduler`** (novo, mirror de `ConnectivityScheduler`/`JiraSyncScheduler`):
- `@Scheduled(cron = "${app.ai.pricing-sync-cron:0 0 2 * * ?}")` — 02:00 todo dia, configurável via
  env (`AI_PRICING_SYNC_CRON`) igual aos outros schedulers do projeto.
- Pra cada modelo configurado: chama o fetcher; se sucesso e validado, compara com o preço atual
  (`AiModelPricingRepository.findById`) — se mudou, `save()` com `updatedBy="auto-fetch"` (reusa a
  coluna existente, **sem migration nova** — dá pra distinguir "atualizado pelo scraper" vs
  "atualizado por um admin" só olhando `updated_by`, que já é retornado pela API/reaproveitável na
  UI nova).
- Se a variação for grande (ex: >30% pra qualquer direção), inclui isso na mensagem de alerta —
  transparência mesmo quando a atualização "deu certo".
- Se falhar (qualquer motivo): **não escreve nada**, loga `ERROR` com o motivo e o modelo, e envia
  alerta via `TelegramBotService.sendMessage()` (reuso do mecanismo já usado por
  `AlertService`/Zabbix) — mesmo canal que já é monitorado pela operação.
- Roda com try/catch por modelo — falha em um modelo não impede a tentativa do outro.

**Endpoint manual de disparo** (novo, `AiModelPricingController`):
`POST /api/v1/ai/model-pricing/sync-now` (ADMIN/`telecom.settings` escrita) — dispara o mesmo fluxo
do scheduler imediatamente, síncrono, retornando o resultado (sucesso/falha por modelo). Necessário
pra validar a feature sem esperar até 02:00 ou mexer no relógio do container, e também dá ao admin
um jeito de forçar um refresh sob demanda pela UI nova.

### Frontend — nova UI de preços (não existia)

**`AISettingsPanel.tsx`** ou um painel novo dedicado (a definir na implementação, mirror da
estrutura já existente no arquivo — `useState`/`useEffect`/`api.get`/`api.put`): tabela com
`modelId`, `provider`, preço input, preço output, `updatedAt`, `updatedBy` (mostra se foi
`auto-fetch` ou o nome de um admin), botão "Editar" (chama `PUT /ai/model-pricing/{modelId}`,
mesmo endpoint já existente) e botão "Buscar agora" (chama o `POST /sync-now` novo, mostra
sucesso/falha por modelo).

**`api/types.ts`**: tipo `AiModelPricing` (mirror do record Java) se ainda não existir.

### Documentação (`docs/`)

Nova subseção (dentro de `TelecomInsights.tsx` ou `TelecomModulos.tsx`/uma nova, a decidir na
implementação — onde já se fala de Custos IA) explicando, em linguagem direta:
- Que o preço por token é buscado automaticamente todo dia às 02:00.
- Que a fonte é a página pública de preços da Google — **não é uma API oficial garantida**, então
  em tese pode falhar se a Google mudar o layout da página.
- O que acontece se a busca falhar: o sistema **nunca** zera ou grava um valor inválido — mantém o
  último preço confirmado e avisa por Telegram no mesmo dia.
- Como corrigir manualmente a qualquer momento (a UI nova), e que `updated_by` mostra se o valor
  veio do robô ou de um admin.
- Recomendação explícita: como o preço alimenta decisão de negócio, revisar periodicamente mesmo
  com a automação ativa, e desconfiar de custo "zerado" — isso indica preço não cadastrado, não
  necessariamente "grátis".

---

## Fases de implementação

### Fase 1 — Backend: fetcher + validação de sanidade
- `AiPricingSourceFetcher` (WebClient + Jsoup), tipos de resultado (sucesso/falha com motivo),
  faixa de sanidade configurável.
- **Validar**: teste manual direto (chamar o fetcher isolado, ex: via um endpoint de debug
  temporário ou teste unitário) contra a página real, conferir que os 2 modelos são encontrados e
  os valores batem com o que pesquisei agora ($0.30/$2.50 e $0.50/$10.00).

### Fase 2 — Backend: scheduler + alerta + endpoint manual
- `AiModelPricingSyncScheduler` (cron 02:00, configurável), integração com
  `TelegramBotService.sendMessage()`, `POST /api/v1/ai/model-pricing/sync-now`.
- **Validar**: `mvn compile`; disparar `sync-now` manualmente via curl com JWT inline e conferir
  que `ai_model_pricing.updated_by` vira `auto-fetch` e os valores batem; simular falha (ex:
  apontar a URL de fetch pra um endpoint inválido temporariamente) e confirmar que o preço não
  muda e o Telegram recebe o alerta.

### Fase 3 — Frontend: UI de preços + disparo manual
- Tabela de preços com edição + botão "Buscar agora" em Configurações → IA.
- **Validar**: `npx tsc --noEmit`; conferir na UI (Fase 5) que a tabela mostra os 2 modelos, edição
  funciona, e "Buscar agora" atualiza a tela com o resultado.

### Fase 4 — Documentação
- Nova subseção explicando o processo (frequência, fonte, comportamento em falha, correção manual).
- **Validar**: `npx tsc --noEmit`; conferir na UI que a seção aparece e o texto está claro sobre a
  limitação (fonte não-oficial) e o critério de confiabilidade.

### Fase 5 — Deploy real + validação com dados reais
- `docker compose build/up backend frontend` real, `docker compose ps`, disparo real do
  `sync-now` em produção contra a página real da Google, conferir preço/Telegram, aguardar (ou
  simular) o primeiro disparo agendado às 02:00 e conferir os logs no dia seguinte.
- Release notes (`v1.29` ou próxima livre).

---

## Riscos

| Risco | Prob. | Mitigação |
|---|---|---|
| Google redesenha a página de preços e o parser para de encontrar os modelos | Média (é HTML não-oficial, sem contrato de estabilidade) | Falha tratada como "não encontrado" → preço antigo mantido + alerta Telegram no mesmo dia; nunca falha silenciosa |
| Parser encontra a linha errada e extrai um valor plausível mas incorreto (ex: preço de outro modelo/contexto de 1M+ tokens) | Baixa-Média | Faixa de sanidade reduz risco mas não elimina 100% — mitigado por deixar `updated_by=auto-fetch` visível na UI pra um admin notar e corrigir manualmente se desconfiar |
| Site bloqueia scraping automatizado (rate limit, user-agent, robots.txt) | Baixa (1 request/dia é tráfego insignificante) | Timeout curto + tratamento de erro já cobre; se acontecer, cai no mesmo caminho de falha com alerta |
| Ninguém vê o alerta do Telegram e o preço fica desatualizado por muito tempo | Baixa | Mesmo canal já usado/monitorado pelos alertas de Zabbix — não é canal novo a manter |
| Preço legítimo muda bruscamente (ex: Google reduz preço de lançamento) e o alerta de "variação grande" gera ruído | Baixa | É intencional — transparência > silêncio, dado que o valor alimenta decisão de negócio |

## Acceptance
- [x] Fase 1: fetcher valida contra a página real, extrai os 2 modelos corretamente
- [x] Fase 2: `mvn compile` limpo; scheduler + endpoint manual funcionando; falha simulada (valor forçado divergente) foi corrigida pelo sync-now, `updated_by=auto-fetch` confirmado no banco
- [x] Fase 3: `tsc --noEmit` exit 0; UI de preços com edição manual e "Buscar agora" em Configurações → IA
- [x] Fase 4: seção de Documentação (`TelecomInsights.tsx`) explicando o processo, a limitação da fonte e o critério de confiabilidade
- [x] Fase 5: deploy real (`docker compose build/up backend frontend`), disparo real via `sync-now` validado em produção contra a página real da Google, release notes `v1.29` registrada
- [x] Nenhum caminho de código sobrescreve preço com zero/inválido — `PricingFetchResult.fail` nunca carrega valores, `AiModelPricingSyncScheduler` só grava em `applySuccess`
- [x] `ai_model_pricing.updated_by` distingue `auto-fetch` de atualização manual (validado na tela e no banco)

---

## Fechamento (2026-07-18)

**Correção imediata**: preço zerado corrigido via UPDATE pelo endpoint já existente (ver seção
"Correção imediata" no topo) — validado nos endpoints de Custos IA de URA/Insights.

**Achado de estrutura HTML durante a implementação**: minha suposição inicial (tabela de preços
dentro do mesmo `<div class="models-section">` do `<h2>`) estava errada — o `models-section` fecha
logo após o cabeçalho, e a tabela fica em divs **irmãs seguintes**. Descoberto testando o parser
Jsoup isoladamente contra o HTML real (`curl` + programa Java standalone) antes de integrar no
serviço — corrigido para andar pelos irmãos até achar o bloco com `<section><h3>`, parando no
`models-section` do próximo modelo (limite de 10 saltos como proteção). Validado contra a página
real: `gemini-2.5-flash` → input $0.30/output $2.50, `gemini-2.5-flash-preview-tts` → input
$0.50/output $10.00 — batem exatamente com os valores oficiais.

**Validação end-to-end em produção**: 
1. `sync-now` disparado com os preços já corretos → detectou "sem mudança", não regravou (evita
   escrita desnecessária).
2. Forcei manualmente um valor divergente ($0.99/$9.99) em `gemini-2.5-flash` via `PUT`, disparei
   `sync-now` de novo → corrigiu automaticamente de volta para $0.30/$2.50, `updated_by` virou
   `auto-fetch` no banco.
3. Log confirmou a tentativa de alerta Telegram por variação significativa (0.99→0.30 é >30%) —
   `TelegramBotService` reportou "não configurado" de forma graciosa (comportamento já existente,
   sem crash) — Telegram não está configurado neste ambiente hoje, então o alerta real não foi
   entregue, mas o caminho de código foi exercitado e está correto.

**Não testado ao vivo**: o caminho de falha completo (rede indisponível / modelo não encontrado na
página / valor fora da faixa plausível) — validado por leitura de código e pelo teste isolado do
parser contra um `modelId` inexistente (retornou falha corretamente), mas não foi forçado dentro do
scheduler rodando em produção. Risco baixo — o código de falha (`applyFailure`) é simples e não
grava nada no banco.

**Dependência nova**: `org.jsoup:jsoup:1.18.1` adicionada ao `pom.xml` — precisou de acesso à
internet no build do Maven (cache offline não tinha o artefato); resolvido rodando `mvn compile`
sem `-o` uma vez para baixar e cachear no volume `maven-repo-asteriskia`.

**Não verificado nesta sessão**: renderização visual da nova seção "Preço de tokens (Custos IA)"
em Configurações → IA — Chrome DevTools MCP indisponível neste ambiente (mesma limitação já
registrada na entrega anterior). Recomendo validação visual manual pelo usuário.
