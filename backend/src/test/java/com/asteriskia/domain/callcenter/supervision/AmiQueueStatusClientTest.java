package com.asteriskia.domain.callcenter.supervision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * AmiQueueStatusClientTest — cobre só o parsing de múltiplos blocos {@code QueueEntry} (a parte
 * testável sem um Asterisk real; a conexão AMI em si não é validada nesta suíte, mesma ressalva
 * já registrada para {@link CallCenterAmiEventListener}).
 */
class AmiQueueStatusClientTest {

    private final AmiQueueStatusClient client = new AmiQueueStatusClient();

    @Test
    void parseEntries_multipleQueueEntry_correlatedByBlock() {
        String raw =
                "Event: QueueParams\n"
                        + "Queue: 5001\n"
                        + "\n"
                        + "Event: QueueEntry\n"
                        + "Queue: 5001\n"
                        + "Position: 1\n"
                        + "CallerIDNum: 1199999999\n"
                        + "Wait: 12\n"
                        + "Uniqueid: uid-1\n"
                        + "Channel: PJSIP/tronco-0000001a\n"
                        + "\n"
                        + "Event: QueueEntry\n"
                        + "Queue: 5001\n"
                        + "Position: 2\n"
                        + "CallerIDNum: 1188888888\n"
                        + "Wait: 30\n"
                        + "Uniqueid: uid-2\n"
                        + "Channel: PJSIP/tronco-0000001b\n"
                        + "\n"
                        + "Event: QueueStatusComplete\n"
                        + "Queue: 5001\n";

        var entries = client.parseEntries(raw);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).position()).isEqualTo(1);
        assertThat(entries.get(0).ani()).isEqualTo("1199999999");
        assertThat(entries.get(0).waitSeconds()).isEqualTo(12L);
        assertThat(entries.get(0).channelUniqueId()).isEqualTo("uid-1");
        assertThat(entries.get(0).channelName()).isEqualTo("PJSIP/tronco-0000001a");
        assertThat(entries.get(1).position()).isEqualTo(2);
        assertThat(entries.get(1).channelName()).isEqualTo("PJSIP/tronco-0000001b");
    }

    @Test
    void parseEntries_noQueueEntry_returnsEmpty() {
        String raw = "Event: QueueStatusComplete\nQueue: 5001\n";

        assertThat(client.parseEntries(raw)).isEmpty();
    }

    @Test
    void parseEntries_malformedNumbers_doesNotThrow() {
        String raw =
                "Event: QueueEntry\n"
                        + "Position: não-é-número\n"
                        + "Wait: também-não\n"
                        + "CallerIDNum: 1199999999\n"
                        + "Uniqueid: uid-3\n";

        var entries = client.parseEntries(raw);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).position()).isNull();
        assertThat(entries.get(0).waitSeconds()).isNull();
        assertThat(entries.get(0).ani()).isEqualTo("1199999999");
    }

    @Test
    void queueStatus_blankQueueName_returnsEmptyWithoutConnecting() {
        assertThat(client.queueStatus(null)).isEmpty();
        assertThat(client.queueStatus("  ")).isEmpty();
    }
}
