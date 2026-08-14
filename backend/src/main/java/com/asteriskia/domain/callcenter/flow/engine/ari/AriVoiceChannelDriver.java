package com.asteriskia.domain.callcenter.flow.engine.ari;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * AriVoiceChannelDriver — única implementação de {@link ChannelDriver} nesta sub-fase (5b), fala
 * com o canal real via {@link AriClient}. Instanciado uma vez por chamada (por
 * {@code AriEventListener}, ao receber {@code StasisStart}) — não é um bean Spring singleton,
 * guarda estado por canal (fila de dígitos DTMF, flag de encerramento).
 */
@Slf4j
public class AriVoiceChannelDriver implements ChannelDriver {

    // Fixo, mesmo default do asterisk.conf desta VPS (astspooldir=/var/spool/asterisk) — a
    // API de gravação do ARI não devolve o caminho absoluto, só confirma o nome no evento
    // RecordingFinished, então o caminho é resolvido aqui a partir da convenção conhecida.
    private static final String ARI_RECORDING_DIR = "/var/spool/asterisk/recording";

    private final AriClient ariClient;
    private final AriPlaybackTracker playbackTracker;
    private final AriRecordingTracker recordingTracker;
    private final String channelId;
    private final String context;
    private final BlockingQueue<String> dtmfQueue = new LinkedBlockingQueue<>();
    private volatile boolean ended = false;

    public AriVoiceChannelDriver(
            AriClient ariClient,
            AriPlaybackTracker playbackTracker,
            AriRecordingTracker recordingTracker,
            String channelId,
            String context) {
        this.ariClient = ariClient;
        this.playbackTracker = playbackTracker;
        this.recordingTracker = recordingTracker;
        this.channelId = channelId;
        this.context = context;
    }

    /** Chamado pelo {@code AriEventListener} ao receber {@code ChannelDtmfReceived} deste canal. */
    public void onDtmfReceived(String digit) {
        dtmfQueue.offer(digit);
    }

    /** Chamado pelo {@code AriEventListener} ao receber {@code StasisEnd} deste canal. */
    public void onChannelEnded() {
        ended = true;
    }

    @Override
    public void playMessage(String audioPath, String text) {
        if (audioPath == null || audioPath.isBlank()) {
            if (text != null && !text.isBlank()) {
                log.info("Nó tocar_audio sem audioPath, só texto (TTS) — não implementado nesta sub-fase (5b), ignorando: {}", text);
            }
            return;
        }
        var playbackId = ariClient.play(channelId, "sound:" + audioPath);
        if (playbackId == null) {
            return;
        }
        var future = playbackTracker.register(playbackId);
        playbackTracker.awaitFinished(playbackId, future, Duration.ofSeconds(30));
    }

    @Override
    public PromptResult promptChoice(List<String> validChoices, Duration timeout) {
        var deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (ended) {
                return PromptResult.hungUp();
            }
            String digit;
            try {
                digit = dtmfQueue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PromptResult.hungUp();
            }
            if (digit != null) {
                // Fase 5c: dígito fora do menu não é mais descartado em silêncio — o handler
                // decide se repete o prompt ou segue o ramo "opção inválida".
                return validChoices.contains(digit) ? PromptResult.chosen(digit) : PromptResult.invalid(digit);
            }
        }
        return PromptResult.timeout();
    }

    @Override
    public RecordResult recordResponse(Duration maxDuration) {
        if (ended) {
            return RecordResult.hungUp();
        }
        var recordingName = "nps-" + UUID.randomUUID();
        var future = recordingTracker.register(recordingName);
        // beep=true avisa o cliente que a gravação começou; terminateOn="#" deixa ele encerrar
        // antes do maxDuration sem precisar esperar o timeout inteiro.
        ariClient.record(channelId, recordingName, (int) maxDuration.toSeconds(), true, "#");
        recordingTracker.awaitFinished(recordingName, future, maxDuration.plusSeconds(5));
        var file = new File(ARI_RECORDING_DIR, recordingName + ".wav");
        if (ended || !file.exists()) {
            return RecordResult.hungUp();
        }
        return RecordResult.recorded(file.getAbsolutePath());
    }

    @Override
    public TextResult collectText(Duration timeout) {
        // Coleta de texto livre falado (STT) é escopo da Fase 14 (nó coletar_entrada, ainda
        // implementado=false no catálogo) — nenhum fluxo de voz publicado usa "coletar_texto"
        // (canal exclusivo "chat" no catálogo), então nunca deveria chegar aqui em produção.
        throw new UnsupportedOperationException(
                "coletar_texto ainda não implementado para canal de voz — ver Fase 14.");
    }

    @Override
    public void setVariable(String name, String value) {
        ariClient.setChannelVar(channelId, name, value);
    }

    @Override
    public String getVariable(String name) {
        return ariClient.getChannelVar(channelId, name);
    }

    // Fase 5e.2, mesma classe do achado de AriClient.play: o valor de "ramal" chega de um nó de
    // fluxo editável pela UI (PERM_WRITE_callcenter.fluxos) — validado estritamente aqui ANTES de
    // qualquer uso, mesmo que TransferToExtensionNodeHandler já valide a montante (defesa em
    // profundidade, nunca confiar só na validação do chamador). Só dígitos, 3 a 4 caracteres —
    // cobre ramal de agente (4xxx), fila (5xxx) e os ramais internos fixos (1000-1002/9xxx),
    // nunca sintaxe de função de dialplan nem caminho.
    private static final java.util.regex.Pattern SAFE_EXTENSION = java.util.regex.Pattern.compile("^[0-9]{3,4}$");

    @Override
    public void transferToQueue(String queueExtension) {
        ariClient.continueInDialplan(channelId, context, queueExtension, 1);
    }

    @Override
    public void transferToExtension(String extension) {
        if (extension == null || !SAFE_EXTENSION.matcher(extension).matches()) {
            log.warn("transferToExtension recusado — ramal fora do allowlist: {}", extension);
            return;
        }
        ariClient.continueInDialplan(channelId, context, extension, 1);
    }

    @Override
    public void end() {
        ariClient.hangup(channelId);
    }
}
