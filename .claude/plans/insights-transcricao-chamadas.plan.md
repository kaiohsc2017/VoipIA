# Plan: Serviço de Transcrição e Insights de Chamadas (tela "Insights")

**Origem**: pedido free-form do usuário (kaio.correa@autoglass.com.br) em 2026-07-17
**Complexidade**: Large (~7-10 dias)
**Status geral**: 🟢 DEPLOYADO E VALIDADO EM PRODUÇÃO (2026-07-17) — build real feito direto na VPS
(este ambiente É a VPS, confirmado por hostname/IP; a falta de internet do início da sessão foi algo
transitório). `docker compose up -d insights backend frontend` rodado com sucesso, containers
saudáveis, e o pipeline completo processou dezenas de chamadas reais de `/opt/audio` (não só as 2 de
amostra — havia 42 pares aguardando). 1 bug real encontrado e corrigido em produção (ver Fase 6).

> Este documento é a fonte de verdade da tarefa. Se a sessão cair ou os créditos acabarem,
> retome por aqui + pela memória `asteriskia_insights_feature`. Atualize o campo **Status**
> de cada fase conforme for concluindo.

---

## Summary

Novo serviço que lê pares `.wav` + `.xml` (mesmo nome) de `/opt/audio`, transcreve o áudio via
Gemini, analisa prosódia (entonação) e gera insights estruturados (melhorias, falhas de processo,
oportunidades de treinamento, tendências) por chamada. Expõe tudo numa nova tela **Insights** na
Sidebar (entre URA e Conectividade) com busca por data/texto/frase, filtro de entonação por
locutor (cliente vs. atendente), player de áudio, transcrição sincronizada e dashboard de tendências.

---

## Requisitos (restatement)

- Serviço lê `/opt/audio/*.wav` + `/opt/audio/*.xml` (correlacionados pelo prefixo numérico do
  nome, não pelo nome idêntico — ver achados da Fase 0) → transcreve → gera insights.
- Insights: aprender o que é falado, sugerir melhorias, antecipar tendências, identificar falhas de
  processo, apontar oportunidades de treinamento (+ extras propostos abaixo).
- Nova tela **Insights** na Sidebar, posicionada **entre URA (modulo1) e Conectividade (modulo2)**.
- Busca por: data (range), texto livre, frase exata.
- Filtro por entonação da voz, **separado para cliente e atendente**.
- Ler o XML de metadados da chamada.
- Front: player para ouvir a gravação + ver transcrição + ver insights gerados.

---

## Descobertas da investigação (grounding — 2026-07-17)

| Item | Fato observado |
|---|---|
| Última migration Flyway | `V34__call_records_ai_costs.sql` → **próxima é V35** (CLAUDE.md diz V22/V25, está desatualizado) |
| RBAC catálogo | `backend/.../domain/accessgroup/ResourceCatalog.java` — lista fixa `TELECOM`; inserir `telecom.insights` entre `telecom.modulo1` e `telecom.modulo2` |
| Sidebar | `frontend/src/components/Sidebar.tsx` — array `NAV` ordenado; item `modulo1`=URA (linha 32), `modulo2`=Conectividade (linha 33). Inserir Insights entre eles |
| STT reaproveitável | `ai-agent/src/providers/gemini.py::GeminiProvider.transcribe()` já faz STT via Gemini — reusar para lote |
| Providers Gemini | `ai-agent/src/providers/gemini.py`, `gemini_shared.py`; services em `ai-agent/src/services/` (`ai_service.py`, `token_usage.py`, `backend_client.py`, `subject_classifier.py`, `audio_cache.py`) |
| Custos de IA | Infra de tracking de tokens já entregue (fases 1-3, `token_usage.py`) — estender pro fluxo de insights |
| Persistência do ai-agent | ai-agent **NÃO acessa Postgres direto** — fala com backend Java via HTTP (`backend_client.py` + `INTERNAL_API_KEY`). Novo serviço deve seguir o mesmo padrão |
| Áudio autenticado (front) | `AuthedAudio.tsx` já resolve "tocar áudio protegido por JWT" (usado em ModuloURA/ModuloAlertas) — reusar no player |
| Dashboard (front) | `CostsDashboardTab.tsx` é o modelo a espelhar para o dashboard de tendências |
| requirements ai-agent | google-genai, httpx, numpy, webrtcvad-wheels, python-dotenv (librosa precisará ser adicionado no novo serviço) |
| Rede Docker | `voipia-net` 172.16.7.0/24; containers .11–.16 usados, .17=docker-helper. **Próximo IP livre: 172.16.7.18** |
| `ffmpeg` no Dockerfile | `ai-agent/Dockerfile` já instala `ffmpeg` + `libsndfile1` via apt — mesmo padrão a espelhar no novo serviço |

### Achados da Fase 0 (amostra real inspecionada em `/opt/audio`, 2026-07-17)

**`/opt/audio` é um diretório COMPARTILHADO**, não exclusivo de gravações — contém dezenas de
arquivos não relacionados (backups `.tar` de outros serviços como n8n/CrowdSec/evolution-api,
imagens, `.xlsx`, `.ogg`). **O watcher precisa filtrar rigorosamente por regex de nome** (padrão
abaixo), ignorando tudo o mais — nunca varrer o diretório assumindo que só há pares de chamada.

**Os XMLs NÃO são gerados pelo Asterisk** — são de um sistema corporativo de gravação **Verint**
(`xmlns:x="http://www.verint.com/xmlns/recording20080320"`, elemento raiz `x:recording`).
**Confirmado pelo usuário (2026-07-17): os arquivos de áudio/XML não têm nenhuma referência ao
VoipIA — é um módulo novo e apartado do PBX/telecom deste sistema**, que deve seguir os mesmos
padrões de desenvolvimento (RBAC, estrutura de código, deploy, code review) já estabelecidos no
projeto, mas sem cruzar dados com `call_records`/URA/ramais do VoipIA. Ou seja: a tela Insights
é uma feature de análise de call center corporativo *hospedada* dentro do Telecom (mesma Sidebar,
mesmo backend, mesmo padrão de auth), mas semanticamente independente do domínio de telefonia
Asterisk já existente.

**Correlação `.wav` ↔ `.xml` é pelo PREFIXO NUMÉRICO, não pelo nome completo**:
- Nome do arquivo: `{ref}---{uuid-aleatorio}.{wav|xml}` — ex: `256001003459910---0a2bc43f-....wav`
  e `256001003459910---74af155b-....xml` **compartilham o prefixo `256001003459910`** mas têm UUIDs
  de sufixo **diferentes** (o UUID parece ser gerado no momento do export/dump, não pelo Verint).
- O prefixo é exatamente o atributo `x:ref` da raiz `<x:recording x:ref="256001003459910">` e também
  aparece como `x:inum` dentro de `x:session/x:inums/x:inum`.
- **Regex de descoberta de pares**: extrair `^(\d+)---` do nome de cada arquivo `.wav`/`.xml` e
  casar por esse grupo — nunca por nome de arquivo idêntico.

**Áudio é MONO, codec G.729A, sem separação de canal agente/cliente**:
- `ffprobe`/`ffmpeg` confirmam: `Audio: g729 ([131][0][0][0] / 0x0083), 8000 Hz, 1 channels`.
- O WAV tem um chunk proprietário extra (`WAVESIGN`) antes do `fmt ` — provável assinatura digital
  de tamper-evidence do Verint. `ffmpeg` (mesmo binário já usado no `ai-agent/Dockerfile`) ignora o
  chunk desconhecido e **decodifica nativamente** para PCM16 mono 8kHz sem lib extra — testado e
  funcionando (`ffmpeg -i entrada.wav saida.wav` produz PCM válido).
- **Consequência direta**: como é mono, **não há split de canal para separar cliente de atendente**
  (isso descarta a hipótese de "estéreo com canal por locutor"). A diarização (quem fala o quê)
  precisa vir da **transcrição** (pedir ao Gemini turnos rotulados por locutor), não de canal —
  ver arquitetura revisada abaixo. Uma vez com timestamps de turno, ainda dá para fatiar o PCM por
  trecho e rodar prosódia (`librosa`) por segmento, então a análise de tom por locutor continua
  viável — só a técnica de separação mudou.

**Schema XML real (Verint) — campos mapeáveis**:
- Raiz `x:recording[@x:ref]`, `x:segment` (`starttime`, `duration` em segundos, `contenttype`,
  `x:streams/x:stream[@rtptypename]` = codec real).
- `x:contacts/x:contact/x:sessions/x:session`: `agent_id`, `employeename` (nome do atendente —
  usar direto, sem cruzar com nossa tabela `users`), `extension`, `ani` (nº de origem), `dnis`
  (nº discado), `direction` (`Inbound`|`Outbound` — Inbound = cliente ligou; Outbound = atendente
  ligou), `duration`, `switch_call_id`, `organization_id`.
- `x:session/x:tags/x:tag/x:attribute[@x:key]`: eventos com timestamp (`Alerting`, `Connected`,
  `Disconnected`) e atributos soltos (`skill` = fila/departamento, `disconnectingparty`,
  `agentname`, `calledparty`/`callingparty`).
- Um `x:contact` pode ter mais de um `x:session` (transferências/conferências — campos
  `number_of_transfers`/`number_of_conferences` existem) — MVP trata a sessão primária; múltiplas
  sessões viram trabalho futuro se aparecer caso real.
- Tudo que não for mapeado explicitamente cai em `xml_raw` (jsonb) — parser nunca falha por campo
  desconhecido.

---

## Arquitetura proposta (revisada pós-Fase 0)

Novo container dedicado **`asteriskia-insights`** (Python asyncio, IP `172.16.7.18`), separado do `ai-agent`:
- `ai-agent` é tempo-real (chamada ao vivo) → responsabilidade única, não deve carregar lote pesado.
- Monta `/opt/audio:/opt/audio:ro` (read-only; estado "processado" fica no banco via backend, não em mutação do diretório compartilhado).
- Sem porta pública; fala com backend Java via `INTERNAL_API_KEY` (mesmo padrão do ai-agent).
- Reusa `google-genai` (STT + insights) e `ffmpeg` (decodificação G.729→PCM, já no padrão do ai-agent). Adiciona `librosa`/`numpy` para prosódia.

```
/opt/audio (mistura de arquivos — filtrar por regex ^\d+---[0-9a-f-]{36}\.(wav|xml)$)
        │ poll/watcher — agrupa .wav/.xml pelo prefixo numérico (x:ref), não por nome idêntico
        ▼
asteriskia-insights (Python)
  ├── xml_parser.py      → schema Verint: agent/ani/dnis/direction/skill + xml_raw jsonb fallback
  ├── audio_decode.py    → ffmpeg: G.729 mono WAV (com chunk WAVESIGN) → PCM16 8kHz mono
  ├── stt_diarize.py     → Gemini STT com prompt de diarização → turnos rotulados
  │                          (Locutor A/B) + timestamps; heurística de mapeamento pro papel
  │                          real (agente/cliente) usa `direction` + `employeename` do XML
  │                          como âncora (ex: em Inbound o 1º turno tende a ser o cliente)
  ├── prosody.py         → fatia o PCM pelos timestamps de turno (não por canal) → librosa:
  │                          F0/pitch, energia RMS, taxa de fala, pausas → tom acústico heurístico
  ├── insights_llm.py    → 1 chamada Gemini c/ transcript diarizado → JSON estruturado (function calling)
  └── backend_client.py  → POST /api/v1/internal/insights (idempotente por `x:ref`/file_hash)
        │
        ▼
Backend Java (persiste, valida, expõe busca/dashboard)
        │
        ▼
Frontend React — tela "Insights" (busca + player + transcrição + cards de insight + dashboard)
```

---

## Fases de implementação

### Fase 0 — Discovery — Status: ✅ CONCLUÍDA (2026-07-17)
- Amostra real inspecionada em `/opt/audio` (2 pares `.wav`+`.xml` da mesma janela de tempo).
- Resultado completo na seção "Achados da Fase 0" acima. Resumo executivo:
  - Diretório compartilhado → watcher precisa filtrar por regex de nome.
  - XML é schema **Verint** (não Asterisk), correlação por **prefixo numérico** (`x:ref`), não nome idêntico.
  - Áudio é **mono G.729A**, decodificável via `ffmpeg` puro — **sem canal separado** por locutor.
  - Diarização precisa ser feita **via transcrição** (Gemini), não por canal → prosódia é fatiada por
    timestamp de turno, não por canal de áudio.

### Fase 1 — Banco (migration V35) — Status: ✅ CONCLUÍDA (2026-07-17)
- **Arquivo criado**: `backend/src/main/resources/db/migration/V35__call_insights.sql`
- Sintaxe validada rodando dentro de `--single-transaction` no Postgres real (`voipia-postgres`)
  e conferido que nenhuma tabela ficou persistida (0 linhas em `pg_tables` pro padrão `call_%`) —
  Flyway ainda vai aplicar de fato só no próximo `docker compose up -d --build backend`.
- Ajustes de nomenclatura em relação ao desenho original: `call_ref` (não `call_uuid`/`file_hash`)
  como chave de dedupe — é o `x:ref` do Verint, prefixo numérico do nome do arquivo. Sem FK para
  `call_records`/`uras` (módulo apartado, confirmado pelo usuário). Full-text via coluna gerada
  `tsvector` (`GENERATED ALWAYS AS ... STORED`, idioma `portuguese`) + índice GIN.
- Tabelas:
  - `call_audio_files` — id, `call_ref` (o `x:ref`/prefixo numérico — UNIQUE, chave de dedupe real,
    não um file_hash), wav_path, xml_path, duration_seconds, `agent_name`, `agent_id_verint`,
    `extension`, `ani`, `dnis`, `direction` (`inbound`|`outbound`), `skill`, xml_raw (jsonb),
    status (`pending`|`processing`|`done`|`error`), error_msg, call_starttime, ingested_at, processed_at
  - `call_transcript_segments` — id, audio_file_id FK, speaker (`agente`|`cliente`|`indefinido` —
    resolvido por heurística de diarização, nunca por canal), start_ms, end_ms, text,
    tone_acoustic, tone_semantic, sentiment_score
  - `call_insights` — id, audio_file_id FK, resumo, categoria_assunto, sentimento_geral,
    aderencia_script, criticidade (`baixa`|`media`|`alta`|`urgente`), insights_json (jsonb)
  - `call_insight_findings` — id, audio_file_id FK, tipo (`melhoria`|`falha`|`treinamento`|`tendencia`),
    descricao, trecho_referencia, prioridade — normalizado para agregação no dashboard
- Full-text: coluna `tsvector` sobre transcript concatenado + índice **GIN** → busca texto/frase nativa
  no Postgres (KISS — sem stack extra). Índices em call_ref, call_starttime, status.
- **Espelhar**: migrations V30-V34 existentes (estilo SQL, comentários em PT).
- **Validar**: revisar SQL manualmente (migrations Flyway são irreversíveis em prod).

### Fase 2 — Serviço Python (`asteriskia-insights`) — Status: 🟡 CODADO, falta validação de execução real (2026-07-17)
- **Diretório criado**: `insights/` (Dockerfile, requirements.txt, `src/config.py`, `src/discovery.py`,
  `src/xml_parser.py`, `src/audio_decode.py`, `src/gemini_client.py`, `src/token_usage.py`,
  `src/stt_diarize.py`, `src/prosody.py`, `src/insights_llm.py`, `src/backend_client.py`, `src/main.py`)
- Diarização implementada como pedida diretamente ao Gemini (rótulos `agente`/`cliente`/`indefinido`,
  não "Locutor A/B" + mapeamento heurístico manual — o próprio modelo já recebe `agent_name` e
  `direction` como contexto e resolve o papel real, mais simples e mais robusto do que heurística
  de código) — tudo em **1 chamada** que já retorna também `tone_semantic` por segmento
  (`response_schema` estruturado, sem parsing de texto livre).
- Prosódia (`prosody.py`) via `librosa.pyin` (F0) + RMS, fatiada pelos timestamps de cada segmento
  diarizado → tom acústico heurístico, documentado como aproximado nos comentários do módulo.
- Insights (`insights_llm.py`): 1 chamada Gemini com transcript diarizado completo + metadados
  (skill/duração) → JSON estruturado (`response_schema`) com resumo/categoria/sentimento/aderência
  script/criticidade + 4 arrays de achados (melhorias/falhas/treinamentos/tendências), cada um já
  ancorado em `trecho_referencia`.
- `backend_client.py` já expõe `get_known_call_refs()` e `submit_insights()` — os dois endpoints
  internos que a Fase 3 (Backend Java) precisa implementar (`GET /api/v1/internal/insights/known-refs`,
  `POST /api/v1/internal/insights`); serviço nunca acessa Postgres direto.
- `main.py`: loop de poll com concorrência limitada (`asyncio.Semaphore`), erro em 1 par nunca derruba
  o loop nem os demais pares do ciclo; se o backend estiver inacessível, o ciclo inteiro é pulado
  (nunca trata "não consigo checar o que já foi feito" como "nada foi feito", pra não reprocessar e
  cobrar API do Gemini à toa).
- Custo de IA rastreado com a mesma nomenclatura de `call_records` (`sttTokensIn/Out/Model`,
  `llmTokensIn/Out/Model`) — consistente com o dashboard de Custos de IA já existente.
- **Validado nesta sessão**: `python3 -m py_compile` em todos os módulos (sintaxe OK) + revisão manual
  de todas as referências cruzadas entre módulos (imports/assinaturas conferem).
- **NÃO validado nesta sessão** (sem acesso à internet neste ambiente de dev — nem Docker Hub nem
  PyPI alcançáveis): build real da imagem Docker, instalação das dependências (`google-genai`,
  `librosa`, `xmltodict`), e execução ponta a ponta contra os 2 pares reais já em `/opt/audio`.
  **Precisa ser validado na VPS** (`docker compose up -d --build insights` + acompanhar logs) antes
  de considerar a Fase 2 definitivamente fechada — ver Fase 5.

### Fase 3 — Backend Java (`domain/insights/`) — Status: ✅ CONCLUÍDA e COMPILADA (2026-07-17)
- Pacote `backend/src/main/java/com/asteriskia/domain/insights/` criado com: 4 entidades JPA
  (`CallAudioFile`, `CallTranscriptSegment`, `CallInsight`, `CallInsightFinding` — mapeamento manual
  `@Column(name=...)`, sem naming strategy, seguindo o padrão do resto do projeto), 4 repositories
  (`JpaSpecificationExecutor` em `CallAudioFileRepository`; queries nativas com `text_search @@
  plainto_tsquery`/`phraseto_tsquery` para busca full-text em `CallTranscriptSegmentRepository`),
  DTOs (`InsightsFilter`, `InsightsListItem`, `InsightsDetailResponse`, `InsightsDashboardSummary`,
  `KnownCallRefsResponse`, `IngestInsightsRequest` com records aninhados
  `SegmentPayload`/`InsightsPayload`/`FindingPayload` espelhando exatamente o payload de
  `insights/src/main.py::_build_payload`), `InsightsSpecifications` (Criteria API — data range direto
  na Specification; texto/frase/tom/categoria resolvidos ANTES para um Set de IDs em
  `InsightsQueryService`, já que vivem em outras tabelas), `InsightsIngestionService` (upsert por
  `call_ref` — substitui segmentos/insight/achados por completo a cada reprocessamento),
  `InsightsQueryService` (busca/detalhe/dashboard), e 2 controllers:
  - `InsightsInternalController` — `GET /api/v1/internal/insights/known-refs` +
    `POST /api/v1/internal/insights` (protegidos pelo `InternalKeyFilter` já existente — **nenhuma
    mudança no `SecurityConfig` foi necessária** pra esses 2 endpoints, caem no
    `anyRequest().authenticated()` genérico, mesmo padrão de `UraRoutingController`).
  - `InsightsController` — `GET /api/v1/insights/calls` (busca paginada), `GET
    /api/v1/insights/calls/{id}` (detalhe), `GET /api/v1/insights/dashboard`, `GET
    /api/v1/insights/calls/{id}/audio` (streaming).
- **JSONB**: primeira coluna desse tipo no projeto (sem precedente) — usa `@JdbcTypeCode(SqlTypes.JSON)`
  nativo do Hibernate 6.x (sem dependência extra tipo hypersistence-utils), documentado no código.
- **Achado corrigido durante a implementação, antes de qualquer commit**: o endpoint de áudio ia
  servir o `.wav` cru (codec G.729A proprietário do Verint) — **nenhum navegador reproduz esse
  codec**, o player nasceria mudo. Corrigido: `InsightsController.getAudio` agora transcodifica via
  `ffmpeg` (subprocess + `StreamingResponseBody`) para PCM WAV 8kHz mono antes de responder — mesma
  ferramenta já usada no serviço Python (Fase 2). `ffmpeg` adicionado ao `backend/Dockerfile`.
- RBAC: `telecom.insights` adicionado a `ResourceCatalog.TELECOM` (entre `modulo1` e `modulo2`);
  matchers GET/escrita em `SecurityConfig.java` seguindo exatamente o padrão de `telecom.settings`.
- Nova property `app.insights.audio-path=${INSIGHTS_AUDIO_PATH:/opt/audio}` em `application.properties`.
- **Validado nesta sessão**: `docker run maven:3.9-eclipse-temurin-21 mvn -q -o compile` usando o
  volume `maven-repo-asteriskia` já cacheado — **compilação real bem-sucedida**, `.class` de todas
  as 24 classes novas confirmados em `target/classes/`. Diferente da Fase 2, esta fase FOI validada
  de ponta a ponta (build real disponível offline via imagem/volume Docker já em cache).

### Fase 4 — Frontend — Status: ✅ CONCLUÍDA e VALIDADA (2026-07-17)
- `Sidebar.tsx`: item `insights` (ícone `Lightbulb`) inserido entre `modulo1` e `modulo2`,
  resource `telecom.insights`. `App.tsx`: `Page` union, `PAGE_RESOURCE`, lazy import e bloco de
  renderização atualizados no mesmo padrão dos demais módulos.
- `api/types.ts`: interfaces novas (`InsightsListItem`, `CallAudioFile`, `CallTranscriptSegment`,
  `CallInsight`, `CallInsightFinding`, `InsightsDetailResponse`, `InsightsDashboardSummary`)
  espelhando exatamente os DTOs Java da Fase 3.
- `InsightsTab.tsx` (espelha `ModuloURA.tsx`): busca por texto livre na toolbar + painel de filtros
  colapsável (data de/até, frase exata, tom do cliente, tom do atendente, categoria/assunto — os 5
  tons `calmo/neutro/tenso/irritado/empolgado` do backend). Tabela paginada + modal de detalhe com
  player (`AuthedAudio` reaproveitado sem alteração — já é genérico o suficiente), resumo/insights,
  achados agrupados por tipo (falha/melhoria/treinamento/tendência) com trecho de referência, e
  transcrição com badge de locutor + badge de tom (semântico e acústico) por turno.
  **Sem separação de canal no player** — é o mesmo áudio mono transcodificado pelo backend
  (`GET /insights/calls/{id}/audio`), a diarização aparece só na transcrição textual, não no áudio.
- `InsightsDashboardTab.tsx` (espelha `CostsDashboardTab.tsx`, recharts `BarChart`): cards de total
  de chamadas/criticidade urgente/alta/falhas + gráfico de achados por tipo + top categorias/assuntos.
- `ModuloInsights.tsx` (espelha `ModuloURA.tsx`): wrapper com abas Chamadas/Dashboard.
- **Release notes**: propositalmente NÃO adicionada ainda — só quando a feature for de fato
  implantada (Fase 5), pra não deixar uma entrada de changelog referenciando algo ainda não em
  produção.
- **Validado nesta sessão**: `npx tsc --noEmit` na raiz do frontend, **exit code 0** — zero erros de
  tipo em todo o projeto, incluindo os 5 arquivos novos/modificados desta fase.

### Fase 5 — Deploy & release — Status: 🟡 CODADA/VALIDADA ONDE POSSÍVEL, falta rodar na VPS (2026-07-17)
- `docker-compose.yml`: novo serviço `insights` (container `asteriskia-insights`, IP `172.16.7.18`,
  resource limits 1g/1.0 cpu, mount `/opt/audio:ro` + `.env` read-only, sem porta publicada,
  healthcheck via `pgrep` já que é um loop de polling, não um servidor — sem porta própria pra
  checar). `depends_on: backend (service_healthy)`.
- `backend` (serviço existente) ganhou mount `/opt/audio:/opt/audio:ro` + env `INSIGHTS_AUDIO_PATH`
  — necessário pro `InsightsController.getAudio` conseguir achar o arquivo original e transcodificar
  via `ffmpeg` (fix da Fase 3).
- `.env.example`: `INSIGHTS_AUDIO_DIR`, `INSIGHTS_POLL_INTERVAL_SECONDS`, `INSIGHTS_MAX_CONCURRENCY`
  (todas com default seguro no `docker-compose.yml`, então **não precisou editar o `.env` real** —
  respeitando a regra de nunca editar sem necessidade/backup).
- **Release notes**: `v1.27` adicionada em `frontend/src/data/releases.ts` (2026-07-17).
- `CLAUDE.md` atualizado: tabela de containers (+`172.16.7.18`), próxima migration Flyway (V25→V36),
  nova entrada em "Pendências conhecidas" documentando o que falta validar.
- **Validado nesta sessão**: `docker compose config --quiet` (exit 0 — sintaxe do compose inteiro
  válida) + confirmação manual de que `172.16.7.18` não colide com nenhum IP já em uso.
- **NÃO validado nesta sessão** (mesma limitação da Fase 2 — sem internet neste ambiente de dev):
  `docker compose up -d --build insights backend frontend` real, `docker compose ps` saudável,
  `docker compose logs -f insights` processando os 2 pares reais já em `/opt/audio`, `curl` nos
  endpoints novos. **Isso precisa ser feito na VPS antes de anunciar a feature como pronta.**

---

## Extras "empolgantes" (pós-MVP, opcionais — não bloqueiam)

1. **Tendência entre chamadas**: job agrega `call_insight_findings` por período → "falha X subiu 40%
   na semana" (alerta proativo, não relatório passivo).
2. **Alerta de criticidade urgente em quase-tempo-real**: reusa `notifier.py` (Telegram/webhook) do
   agents-platform quando chamada bate `criticidade=urgente` (risco churn/Procon).
3. **Busca semântica** ("chamadas parecidas com esta") via `pgvector` — Postgres já é o banco único.
4. **Aderência a script por URA**: cruza `aderencia_script` com URA de origem (multi-URA já existe).
5. **Relatório semanal automático** (e-mail/PDF) — reusa padrão de `ReportController`/Excel.
6. **Feedback loop de treinamento**: cada "oportunidade de treinamento" ancorada no trecho exato →
   trilha de coaching por atendente.

---

## Riscos

| Risco | Prob. | Mitigação |
|---|---|---|
| Diarização por LLM em áudio mono G.729 8kHz pode errar quem fala o quê | Alta | Cruzar com `direction`/`employeename` do XML como âncora; rotular "indicativo"; permitir correção manual futura se acurácia for baixa na prática |
| `/opt/audio` compartilhado — arquivo de outro serviço confundido com par de chamada | Média | Regex estrita `^\d+---[0-9a-f-]{36}\.(wav\|xml)$` no watcher; nunca varrer sem filtro |
| Custo Gemini em lote (STT + diarização + insights) | Média | Fila concorrência limitada + retry + tracking de custo existente |
| Prosódia é aproximada | Média | Rotular "indicativo"; exibir junto do tom semântico (LLM); nunca critério único |
| Backlog inicial grande (diretório já tem histórico) | Média | Status por arquivo + processamento incremental; nunca reprocessar tudo |
| Path traversal no stream de áudio | Média | Validar path contra whitelist `/opt/audio`; nunca concatenar input do usuário |
| ~~Volume real pode ser de um call center corporativo maior (Verint), não só VoipIA~~ | — | **Confirmado pelo usuário**: é módulo novo e apartado do VoipIA/Asterisk, sem cruzamento de dados — não bloqueia arquitetura, só reforça que `call_audio_files` não deve ter FK para `call_records`/`uras` |

### Fase 6 — Deploy real na VPS + bug encontrado e corrigido (2026-07-17)
- **Build real**: `docker compose build insights backend frontend` — tudo buildou limpo (frontend
  rodou `tsc -b && vite build` de verdade, gerou o chunk `ModuloInsights`; insights instalou
  `librosa`/`google-genai` reais; backend compilou o WAR).
- **Deploy real**: `docker compose up -d insights backend frontend` — todos saudáveis
  (`insights` com healthcheck `pgrep` passando), Flyway migrou de verdade pra V35 no boot do backend.
- **Descoberta em produção**: `/opt/audio` tinha **42 pares reais** (não só os 2 de amostra da Fase 0)
  — o serviço já começou a processá-los de verdade, com custo real de API Gemini.
- **Validação ponta a ponta com dados reais**: primeiras chamadas processaram com sucesso
  (STT+diarização em ~15-45s por chamada, prosódia, insights, POST ao backend com 200 OK).
  Testados via `curl` (dentro do container backend, já que a porta 8080 não é publicada): busca
  (`GET /insights/calls`), detalhe (`GET /insights/calls/{id}`), dashboard
  (`GET /insights/dashboard`) e streaming de áudio (`GET /insights/calls/{id}/audio`) — o áudio
  voltou como `pcm_s16le` confirmado via `ffprobe` (antes era G.729A), ou seja, a transcodificação
  funciona de verdade.
- **Bug real encontrado em produção**: `call_ref=256001003459954` falhou com HTTP 500 —
  `numeric field overflow` do Postgres na coluna `call_insights.aderencia_script`
  (`NUMERIC(4,3)`, máx 9.999). O Gemini retornou um valor fora do intervalo `0 a 1` pedido no prompt
  (o `response_schema` não tem como forçar faixa numérica, só o tipo). **Corrigido com clamp
  defensivo nos dois lados** (nunca confiar em faixa de saída de LLM sem validar no boundary):
  `insights/src/insights_llm.py` (`max(0.0, min(1.0, valor))`) e
  `backend/.../InsightsIngestionService.java` (`clampAderenciaScript`, já que o endpoint interno
  também é um boundary real entre dois serviços). Recompilado (`mvn compile` limpo), rebuildado e
  redeployado — a chamada que falhou foi reprocessada automaticamente no ciclo seguinte (nunca tinha
  sido persistida, então não ficou presa em `known-refs`).
- **Custo real de IA já sendo rastreado**: `sttTokensIn/Out`/`llmTokensIn/Out` populados
  corretamente nas chamadas persistidas, mesma nomenclatura do dashboard de custos existente.
- **Fila inicial de 42 chamadas totalmente processada** (monitorado via dashboard até
  `totalChamadas=42`): 27 baixa / 7 alta / 4 urgente / 4 média criticidade, 40+ categorias distintas,
  284 achados no total (falha=59, melhoria=70, tendência=68, treinamento=87).
- **2º bug real encontrado e corrigido**: `call_ref=256001003459942` recebeu HTTP 400 na 1ª tentativa
  — a transcrição veio com **0 segmentos** (áudio curto/silencioso) e `@NotEmpty` em
  `IngestInsightsRequest.segments` rejeitava a ingestão inteira. Como a chamada nunca era persistida,
  o watcher a reprocessaria **para sempre** a cada ciclo de poll (gastando API do Gemini
  indefinidamente) se o áudio fosse genuinamente sem fala — não é hipotético, é exatamente o tipo de
  chamada real que existe num call center (ligação caída, só ruído). Corrigido trocando `@NotEmpty`
  por `@NotNull` (permite lista vazia, chamada persiste com 0 segmentos em vez de tentar pra sempre).
  Recompilado, rebuildado, redeployado — no caso real observado, o retry automático já tinha
  funcionado (2ª tentativa gerou 2 segmentos e passou), mas o fix elimina o risco de loop infinito
  para o caso genuinamente silencioso que ainda não apareceu.

## Acceptance
- [x] Fase 0: amostra real obtida, correlação e formato de áudio confirmados
- [x] Fase 1: V35 criada e validada (aplicação real acontece no próximo build do backend)
- [ ] Fase 2: código completo e sintaxe validada — falta build real + execução ponta a ponta na VPS (sem internet neste ambiente de dev)
- [x] Fase 3: compilação real bem-sucedida (`mvn compile`), path traversal e transcodificação de áudio corrigidos
- [x] Fase 4: `tsc --noEmit` exit 0; Sidebar/App/Tab/Dashboard implementados; release notes adiada pra Fase 5
- [x] Fase 5: `docker compose config` válido, `.env.example`/`CLAUDE.md`/release notes atualizados
- [x] Fase 6: deploy real na VPS, pipeline validado com dados reais (42 chamadas), 1 bug real encontrado e corrigido (overflow numérico em aderencia_script)
- [ ] Fase 2: serviço processa a amostra ponta a ponta
- [ ] Fase 3: `mvn compile` verde; endpoints respondem
- [ ] Fase 4: `tsc --noEmit` verde; tela Insights entre URA e Conectividade
- [ ] Fase 5: `docker compose ps` saudável; release notes; teste e2e com par real
- [ ] Padrões espelhados (STT/backend_client/AuthedAudio/CostsDashboardTab), não reinventados
