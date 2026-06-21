-- V12: Atualiza modelos da chain AI que foram descontinuados pelo Google
-- gemini-2.0-flash foi removido em Jun/2025 — substituído por gemini-2.5-flash

UPDATE ai_capability_chain
SET model_id   = 'gemini-2.5-flash',
    updated_at  = CURRENT_TIMESTAMP,
    updated_by  = 'migration-v12'
WHERE provider  = 'gemini'
  AND model_id IN ('gemini-2.0-flash', 'gemini-1.5-flash', 'gemini-1.5-pro');

-- Garante que o modelo TTS está correto
UPDATE ai_capability_chain
SET model_id   = 'gemini-2.5-flash-preview-tts',
    updated_at  = CURRENT_TIMESTAMP,
    updated_by  = 'migration-v12'
WHERE capability = 'TTS'
  AND provider   = 'gemini'
  AND model_id NOT LIKE '%-tts%';
