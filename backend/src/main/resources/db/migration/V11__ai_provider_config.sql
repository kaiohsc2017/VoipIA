-- V11: Configuração de provedores de IA com fallback chain
-- Substitui variáveis GEMINI_* do .env por configuração dinâmica em banco.
-- Modelos são buscados via API de cada provedor em tempo real.

-- ─── Tabela 1: Chaves de API por provedor ──────────────────────────────────
CREATE TABLE ai_provider_keys (
    provider    VARCHAR(30)  PRIMARY KEY,
    api_key     TEXT         NOT NULL DEFAULT '',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(100) NOT NULL DEFAULT 'system'
);

COMMENT ON TABLE  ai_provider_keys           IS 'Chaves de API para cada provedor de IA';
COMMENT ON COLUMN ai_provider_keys.provider  IS 'gemini | anthropic | openai | grok | perplexity | elevenlabs | local';
COMMENT ON COLUMN ai_provider_keys.api_key   IS 'Chave de API do provedor (armazenada em plain-text, acesso restrito)';
COMMENT ON COLUMN ai_provider_keys.is_active IS 'FALSE desabilita o provedor sem apagar a key';

-- ─── Tabela 2: Chains de fallback por capability ────────────────────────────
CREATE TABLE ai_capability_chain (
    id          SERIAL       PRIMARY KEY,
    capability  VARCHAR(10)  NOT NULL,   -- STT | LLM | TTS
    priority    INT          NOT NULL,   -- 1 = primário, 2 = fallback 1, etc.
    provider    VARCHAR(30)  NOT NULL,
    model_id    VARCHAR(100) NOT NULL,
    is_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(100) NOT NULL DEFAULT 'system',
    UNIQUE (capability, priority)
);

COMMENT ON TABLE  ai_capability_chain            IS 'Ordem de fallback de modelos por capability de IA';
COMMENT ON COLUMN ai_capability_chain.capability IS 'STT (transcrição) | LLM (raciocínio) | TTS (síntese de voz)';
COMMENT ON COLUMN ai_capability_chain.priority   IS 'Menor número = maior prioridade (1 = primário)';
COMMENT ON COLUMN ai_capability_chain.provider   IS 'Provedor do modelo (deve existir em ai_provider_keys)';
COMMENT ON COLUMN ai_capability_chain.model_id   IS 'ID exato do modelo conforme retornado pela API do provedor';

-- ─── Trigger updated_at para ai_provider_keys ──────────────────────────────
CREATE OR REPLACE FUNCTION update_ai_provider_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ai_provider_keys_updated_at
    BEFORE UPDATE ON ai_provider_keys
    FOR EACH ROW EXECUTE FUNCTION update_ai_provider_timestamp();

CREATE TRIGGER trg_ai_capability_chain_updated_at
    BEFORE UPDATE ON ai_capability_chain
    FOR EACH ROW EXECUTE FUNCTION update_ai_provider_timestamp();

-- ─── Seed: chain padrão usando Gemini (retrocompatibilidade) ────────────────
-- Migra o comportamento anterior (fixo em Gemini via .env) para o novo modelo.
-- A key ainda deve ser configurada via tela de Settings.
INSERT INTO ai_provider_keys (provider, api_key, is_active) VALUES
    ('gemini',      '', TRUE),
    ('anthropic',   '', FALSE),
    ('openai',      '', FALSE),
    ('grok',        '', FALSE),
    ('perplexity',  '', FALSE),
    ('elevenlabs',  '', FALSE),
    ('local',       '', FALSE);

INSERT INTO ai_capability_chain (capability, priority, provider, model_id, is_enabled) VALUES
    ('STT', 1, 'gemini', 'gemini-2.0-flash',                TRUE),
    ('LLM', 1, 'gemini', 'gemini-2.0-flash',                TRUE),
    ('TTS', 1, 'gemini', 'gemini-2.5-flash-preview-tts',    TRUE);
