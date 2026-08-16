-- Fase 20 do plano Call Center Parte III — padroniza os caminhos de mídia sob a raiz do
-- repositório (/opt/VoipIA/media/), git-ignorada (ver .gitignore).
--
-- cc_recordings.file_path e cc_chat_sessions.transcript_path: correção COSMÉTICA. A leitura
-- (CallCenterRecordingService.resolveAudioFile / ChatTranscriptExportService) usa só o nome-base
-- do valor persistido e reconstrói o diretório a partir do base path configurado — o UPDATE aqui
-- é só para o dado não mentir sobre onde o arquivo está fisicamente, mesma disciplina da V60.
UPDATE cc_recordings
   SET file_path = replace(file_path, '/opt/gravacoes/audio', '/opt/VoipIA/media/gravacao')
 WHERE file_path LIKE '/opt/gravacoes/audio%';

UPDATE cc_chat_sessions
   SET transcript_path = replace(transcript_path, '/opt/gravacoes/chat', '/opt/VoipIA/media/chat')
 WHERE transcript_path LIKE '/opt/gravacoes/chat%';

-- call_audio_files.wav_path (source='upload'): correção FUNCIONAL, não cosmética.
-- InsightsController.pathRelativeToBase() faz `stored.getPath().startsWith(baseDir)` contra o
-- baseDir ATUAL (app.insights.upload-audio-path, agora /opt/VoipIA/media/sobdemanda) para
-- preservar o subcaminho {batchId}/{arquivo} — sem este UPDATE, todo áudio de "análise sob
-- demanda" já enviado passaria a cair no fallback (só o nome do arquivo, sem o batchId) e não
-- seria mais encontrado após a migração dos arquivos físicos. Escopo explícito por
-- source='upload' — nunca toca nas linhas source='verint' (/opt/audio, não migrado nesta fase).
UPDATE call_audio_files
   SET wav_path = replace(wav_path, '/opt/audio_upload', '/opt/VoipIA/media/sobdemanda')
 WHERE source = 'upload'
   AND wav_path LIKE '/opt/audio_upload%';
