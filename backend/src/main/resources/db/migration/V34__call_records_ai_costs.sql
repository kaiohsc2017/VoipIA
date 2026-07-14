-- V34: rastreamento de tokens/custo de IA por chamada (Módulo 1 — aba "Custos IA").
-- Fase 2 do plano de "Custos de IA por chamada + Dashboard de gastos" (Fase 1 já captura
-- os tokens no ai-agent e envia agregados em POST /calls/register — este DTO ainda não
-- os persistia, ficavam ignorados por fail-on-unknown-properties=false).

ALTER TABLE call_records
    ADD COLUMN stt_tokens_in  INT NOT NULL DEFAULT 0,
    ADD COLUMN stt_tokens_out INT NOT NULL DEFAULT 0,
    ADD COLUMN stt_model      VARCHAR(100),
    ADD COLUMN llm_tokens_in  INT NOT NULL DEFAULT 0,
    ADD COLUMN llm_tokens_out INT NOT NULL DEFAULT 0,
    ADD COLUMN llm_model      VARCHAR(100),
    ADD COLUMN tts_tokens_in  INT NOT NULL DEFAULT 0,
    ADD COLUMN tts_tokens_out INT NOT NULL DEFAULT 0,
    ADD COLUMN tts_model      VARCHAR(100);

COMMENT ON COLUMN call_records.stt_tokens_in  IS 'Tokens de entrada (áudio) reportados pelo Gemini na transcrição desta chamada';
COMMENT ON COLUMN call_records.stt_tokens_out IS 'Tokens de saída (texto transcrito) reportados pelo Gemini';
COMMENT ON COLUMN call_records.stt_model      IS 'Model ID usado na capability STT desta chamada — pode variar por fallback';
COMMENT ON COLUMN call_records.llm_tokens_in  IS 'Tokens de entrada (prompt) somados de todas as idas ao LLM na chamada, incl. function calling';
COMMENT ON COLUMN call_records.llm_tokens_out IS 'Tokens de saída (resposta) somados de todas as idas ao LLM na chamada';
COMMENT ON COLUMN call_records.llm_model      IS 'Model ID usado na capability LLM desta chamada';
COMMENT ON COLUMN call_records.tts_tokens_in  IS 'Tokens de entrada (texto a sintetizar) reportados pelo Gemini TTS';
COMMENT ON COLUMN call_records.tts_tokens_out IS 'Tokens de saída (áudio gerado) reportados pelo Gemini TTS';
COMMENT ON COLUMN call_records.tts_model      IS 'Model ID usado na capability TTS desta chamada';

-- ─── Tabela de preço por modelo — editável em Configurações → IA (GET/PUT /api/v1/ai/model-pricing) ───
-- Chave por model_id (não por provider+model_id): CallRecord só grava o model_id usado em cada
-- capability, não o provider — hoje só o Gemini está em produção, então essa é a granularidade
-- que os dados reais suportam. `provider` fica só como metadado de exibição na UI.
CREATE TABLE ai_model_pricing (
    model_id                      VARCHAR(100)  PRIMARY KEY,
    provider                      VARCHAR(30)   NOT NULL,
    price_per_million_input_usd  NUMERIC(12,6) NOT NULL DEFAULT 0,
    price_per_million_output_usd NUMERIC(12,6) NOT NULL DEFAULT 0,
    updated_at                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                    VARCHAR(100)  NOT NULL DEFAULT 'system'
);

COMMENT ON TABLE  ai_model_pricing IS 'Preço por milhão de tokens (entrada/saída) por modelo — usado para estimar o custo de cada chamada. Preço inicial é 0 (placeholder) — precisa ser configurado manualmente com o valor real vigente do provedor antes do dashboard de custos refletir valores confiáveis.';
COMMENT ON COLUMN ai_model_pricing.provider IS 'Metadado de exibição — não usado como chave (ver comentário da tabela)';

CREATE OR REPLACE FUNCTION update_ai_model_pricing_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ai_model_pricing_updated_at
    BEFORE UPDATE ON ai_model_pricing
    FOR EACH ROW EXECUTE FUNCTION update_ai_model_pricing_timestamp();

-- Seed dos modelos atualmente em uso (ver ai_capability_chain) — preço zerado de propósito,
-- precisa ser preenchido manualmente com o valor real vigente na tela de Configurações → IA.
INSERT INTO ai_model_pricing (model_id, provider) VALUES
    ('gemini-2.5-flash',             'gemini'),
    ('gemini-2.5-flash-preview-tts', 'gemini')
ON CONFLICT (model_id) DO NOTHING;
