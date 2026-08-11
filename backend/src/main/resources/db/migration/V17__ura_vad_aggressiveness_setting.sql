-- V17: configuração de agressividade do VAD (Voice Activity Detection) exposta
-- na tela de Fluxo URA — antes era uma constante fixa no código do ai-agent.

INSERT INTO ura_settings (key, label, required, value) VALUES
(
    'vad_aggressiveness',
    'Sensibilidade a ruído de fundo (VAD)',
    TRUE,
    '3'
);
