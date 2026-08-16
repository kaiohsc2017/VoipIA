-- V35: schema do módulo "Insights" — transcrição e análise de IA de gravações de call center.
--
-- Módulo novo e apartado do domínio de telefonia Asterisk já existente (call_records/uras):
-- as chamadas aqui vêm de um sistema de gravação corporativo Verint (arquivos .wav + .xml em
-- /opt/audio, processados pelo serviço asteriskia-insights), sem nenhuma referência ao PBX
-- VoipIA. Por isso não há FK para call_records/uras — call_ref é a chave de correlação
-- própria do Verint (prefixo numérico do nome do arquivo = atributo x:ref do XML).

-- ─── call_audio_files — 1 linha por gravação (.wav + .xml) descoberta em /opt/audio ───
CREATE TABLE call_audio_files (
    id               BIGSERIAL PRIMARY KEY,
    call_ref         VARCHAR(50)  NOT NULL UNIQUE,
    wav_path         VARCHAR(500) NOT NULL,
    xml_path         VARCHAR(500) NOT NULL,
    duration_seconds INTEGER,
    call_starttime   TIMESTAMP,
    agent_name       VARCHAR(200),
    agent_id_verint  VARCHAR(50),
    extension        VARCHAR(20),
    ani              VARCHAR(50),
    dnis             VARCHAR(50),
    direction        VARCHAR(10) CHECK (direction IN ('inbound', 'outbound')),
    skill            VARCHAR(200),
    xml_raw          JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending', 'processing', 'done', 'error')),
    error_msg        TEXT,
    ingested_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at     TIMESTAMP,
    stt_tokens_in    INT NOT NULL DEFAULT 0,
    stt_tokens_out   INT NOT NULL DEFAULT 0,
    stt_model        VARCHAR(100),
    llm_tokens_in    INT NOT NULL DEFAULT 0,
    llm_tokens_out   INT NOT NULL DEFAULT 0,
    llm_model        VARCHAR(100)
);

COMMENT ON TABLE  call_audio_files IS 'Uma linha por gravação (.wav+.xml) descoberta em /opt/audio pelo asteriskia-insights — módulo apartado do VoipIA/Asterisk, sem FK para call_records/uras';
COMMENT ON COLUMN call_audio_files.call_ref IS 'Chave de correlação real .wav<->.xml: prefixo numérico do nome do arquivo (ex: 256001003459910), igual ao atributo x:ref do XML Verint — NÃO é o nome completo do arquivo, que tem um UUID de sufixo diferente em cada um dos dois arquivos';
COMMENT ON COLUMN call_audio_files.agent_id_verint IS 'agent_id como reportado pelo XML Verint — identidade própria do sistema de gravação, não referencia a tabela users deste sistema';
COMMENT ON COLUMN call_audio_files.direction IS 'inbound = cliente ligou; outbound = atendente ligou — usado como âncora heurística na diarização por locutor (áudio é mono, sem canal separado)';
COMMENT ON COLUMN call_audio_files.xml_raw IS 'XML completo convertido para JSON — fallback para qualquer campo ainda não mapeado em coluna própria';
COMMENT ON COLUMN call_audio_files.status IS 'pending: descoberto, aguardando processamento; processing: em andamento; done: transcrito+insights gerados; error: falhou (ver error_msg), elegível a retry';
COMMENT ON COLUMN call_audio_files.stt_tokens_in  IS 'Tokens de entrada (áudio) da chamada Gemini que fez STT+diarização+tom semântico em 1 única ida — mesma nomenclatura de call_records para consistência do dashboard de custos de IA';
COMMENT ON COLUMN call_audio_files.llm_tokens_in  IS 'Tokens de entrada (prompt) da chamada Gemini que gerou os insights estruturados (call_insights.insights_json)';

CREATE INDEX idx_call_audio_files_starttime ON call_audio_files(call_starttime);
CREATE INDEX idx_call_audio_files_status    ON call_audio_files(status);
CREATE INDEX idx_call_audio_files_agent     ON call_audio_files(agent_name);

-- ─── call_transcript_segments — turnos de fala diarizados por locutor ───
CREATE TABLE call_transcript_segments (
    id              BIGSERIAL PRIMARY KEY,
    audio_file_id   BIGINT NOT NULL REFERENCES call_audio_files(id) ON DELETE CASCADE,
    speaker         VARCHAR(20) NOT NULL DEFAULT 'indefinido'
                      CHECK (speaker IN ('agente', 'cliente', 'indefinido')),
    start_ms        INTEGER NOT NULL,
    end_ms           INTEGER NOT NULL,
    text            TEXT NOT NULL,
    tone_acoustic   VARCHAR(20),
    tone_semantic   VARCHAR(20),
    sentiment_score NUMERIC(4,3)
);

COMMENT ON TABLE  call_transcript_segments IS 'Turnos de fala transcritos e diarizados por locutor — diarização feita via LLM (áudio é mono, sem canal separado), papel real (agente/cliente) resolvido por heurística a partir de call_audio_files.direction';
COMMENT ON COLUMN call_transcript_segments.tone_acoustic IS 'Tom estimado por prosódia (librosa: pitch/energia/taxa de fala) no trecho de áudio deste turno — indicativo, não definitivo';
COMMENT ON COLUMN call_transcript_segments.tone_semantic IS 'Tom estimado pelo LLM a partir do conteúdo semântico do texto transcrito';
COMMENT ON COLUMN call_transcript_segments.sentiment_score IS 'Score de sentimento normalizado (-1 a 1) atribuído pelo LLM a este turno';

CREATE INDEX idx_call_transcript_segments_audio  ON call_transcript_segments(audio_file_id);
CREATE INDEX idx_call_transcript_segments_speaker ON call_transcript_segments(speaker);

-- Full-text search em português sobre o texto de cada turno — usado nos filtros de
-- texto livre/frase exata da tela Insights, direto no Postgres (sem stack extra).
ALTER TABLE call_transcript_segments ADD COLUMN text_search tsvector
    GENERATED ALWAYS AS (to_tsvector('portuguese', text)) STORED;

CREATE INDEX idx_call_transcript_segments_search ON call_transcript_segments USING GIN (text_search);

-- ─── call_insights — 1 linha por chamada com o resultado consolidado da análise de IA ───
CREATE TABLE call_insights (
    id                BIGSERIAL PRIMARY KEY,
    audio_file_id     BIGINT NOT NULL UNIQUE REFERENCES call_audio_files(id) ON DELETE CASCADE,
    resumo            TEXT,
    categoria_assunto VARCHAR(100),
    sentimento_geral  VARCHAR(20),
    aderencia_script  NUMERIC(4,3),
    criticidade       VARCHAR(10) NOT NULL DEFAULT 'baixa'
                        CHECK (criticidade IN ('baixa', 'media', 'alta', 'urgente')),
    insights_json     JSONB NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  call_insights IS 'Resultado consolidado da análise de IA de uma chamada — resumo, categoria, sentimento e o JSON completo gerado pelo LLM (fonte de call_insight_findings)';
COMMENT ON COLUMN call_insights.aderencia_script IS 'Score 0 a 1 de quão perto a chamada seguiu o roteiro/script esperado para o assunto — aproximado, calculado pelo LLM';
COMMENT ON COLUMN call_insights.criticidade IS 'Sinaliza chamadas que merecem revisão humana prioritária (ex: risco de churn, ameaça de reclamação formal)';
COMMENT ON COLUMN call_insights.insights_json IS 'JSON estruturado completo retornado pelo LLM (melhorias, falhas, treinamento, tendências) — call_insight_findings é a versão normalizada para agregação';

CREATE INDEX idx_call_insights_categoria    ON call_insights(categoria_assunto);
CREATE INDEX idx_call_insights_criticidade  ON call_insights(criticidade);

-- ─── call_insight_findings — achados normalizados (1 linha por melhoria/falha/treinamento/tendência) ───
CREATE TABLE call_insight_findings (
    id                BIGSERIAL PRIMARY KEY,
    audio_file_id     BIGINT NOT NULL REFERENCES call_audio_files(id) ON DELETE CASCADE,
    tipo              VARCHAR(20) NOT NULL
                        CHECK (tipo IN ('melhoria', 'falha', 'treinamento', 'tendencia')),
    descricao         TEXT NOT NULL,
    trecho_referencia TEXT,
    prioridade        VARCHAR(10) NOT NULL DEFAULT 'media'
                        CHECK (prioridade IN ('baixa', 'media', 'alta')),
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  call_insight_findings IS 'Achados normalizados extraídos de call_insights.insights_json — existe para permitir agregação eficiente (contar/agrupar por tipo e período) no dashboard de tendências sem parsear JSONB a cada consulta';
COMMENT ON COLUMN call_insight_findings.trecho_referencia IS 'Trecho da transcrição que embasa este achado — âncora para o coaching/feedback ao atendente';

CREATE INDEX idx_call_insight_findings_audio ON call_insight_findings(audio_file_id);
CREATE INDEX idx_call_insight_findings_tipo  ON call_insight_findings(tipo);
