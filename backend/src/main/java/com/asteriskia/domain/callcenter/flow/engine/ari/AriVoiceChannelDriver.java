package com.asteriskia.domain.callcenter.flow.engine.ari;

import com.asteriskia.domain.callcenter.flow.engine.ChannelDriver;
import java.time.Duration;
import java.util.List;
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

    private final AriClient ariClient;
    private final AriPlaybackTracker playbackTracker;
    private final String channelId;
    private final String context;
    private final BlockingQueue<String> dtmfQueue = new LinkedBlockingQueue<>();
    private volatile boolean ended = false;

    public AriVoiceChannelDriver(AriClient ariClient, AriPlaybackTracker playbackTracker, String channelId, String context) {
        this.ariClient = ariClient;
        this.playbackTracker = playbackTracker;
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
            if (digit != null && validChoices.contains(digit)) {
                return PromptResult.chosen(digit);
            }
        }
        return PromptResult.timeout();
    }

    @Override
    public void setVariable(String name, String value) {
        ariClient.setChannelVar(channelId, name, value);
    }

    @Override
    public String getVariable(String name) {
        return ariClient.getChannelVar(channelId, name);
    }

    @Override
    public void transferToQueue(String queueExtension) {
        ariClient.continueInDialplan(channelId, context, queueExtension, 1);
    }

    @Override
    public void end() {
        ariClient.hangup(channelId);
    }
}
