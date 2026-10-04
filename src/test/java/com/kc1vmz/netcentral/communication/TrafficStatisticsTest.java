package com.kc1vmz.netcentral.communication;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TrafficStatisticsTest {
    private Packet packet(String text) {
        return new Packet("N1AAA", "APANC1", List.of(), text);
    }

    @Test
    void separatesMessagesAcknowledgmentsReportsAndIdleTimes() {
        var stats = new TrafficStatistics();
        assertThat(stats.snapshot().get("lastSent")).isNull();
        stats.record(packet(":N1BBB    :Ready{12"), true);
        stats.record(packet(":N1AAA    :ack12"), false);
        stats.record(packet(":N1AAA    :Hello"), false);
        stats.record(packet(":N1AAA    :Hello"), false);
        stats.record(packet(":N1BBB    :ack22"), true);
        stats.record(packet(":N1AAA    :rej13"), false);
        stats.record(packet("{ZNreport"), true);
        var result = stats.snapshot();
        assertThat(result).containsEntry("packetsSent", 3L).containsEntry("packetsReceived", 4L)
                .containsEntry("messagesSent", 1L).containsEntry("messagesReceived", 2L)
                .containsEntry("acksRequested", 1L)
                .containsEntry("acksReceived", 1L).containsEntry("acksSent", 1L).containsEntry("rejectionsReceived", 1L)
                .containsEntry("reportsSent", 1L);
        assertThat(result.get("lastMessageSent")).isNotNull();
        assertThat(result.get("lastAckReceived")).isNotNull();
        assertThat(result.toString()).doesNotContain("N1AAA", "N1BBB", "Hello", "Ready");
    }
}
