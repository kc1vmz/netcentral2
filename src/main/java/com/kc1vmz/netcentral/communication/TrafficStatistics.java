package com.kc1vmz.netcentral.communication;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Payload-free transport counters. One successful write is one sent packet. */
public final class TrafficStatistics {
    private final Instant started = Instant.now();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private Instant lastReceived, lastSent, lastMessageReceived, lastMessageSent, lastAckReceived;

    public TrafficStatistics() {
        for (String key : new String[] { "packetsReceived", "packetsSent", "messagesReceived", "messagesSent",
                "acksRequested", "acksReceived", "acksSent", "rejectionsReceived", "rejectionsSent", "reportsSent" })
            counts.put(key, 0L);
    }

    private void increment(String key) {
        counts.merge(key, 1L, Long::sum);
    }

    public synchronized void record(Packet packet, boolean sent) {
        Instant now = Instant.now();
        increment(sent ? "packetsSent" : "packetsReceived");
        if (sent)
            lastSent = now;
        else
            lastReceived = now;
        String info = packet.information();
        if (sent && info.startsWith("{Z"))
            increment("reportsSent");
        if (info.length() < 11 || info.charAt(0) != ':' || info.charAt(10) != ':')
            return;
        String body = info.substring(11);
        if (body.matches("ack[A-Za-z0-9]{1,5}")) {
            increment(sent ? "acksSent" : "acksReceived");
            if (!sent)
                lastAckReceived = now;
        } else if (body.matches("rej[A-Za-z0-9]{1,5}"))
            increment(sent ? "rejectionsSent" : "rejectionsReceived");
        else {
            increment(sent ? "messagesSent" : "messagesReceived");
            if (sent) {
                lastMessageSent = now;
                if (body.matches("(?s).*\\{[A-Za-z0-9]{1,5}$"))
                    increment("acksRequested");
            } else
                lastMessageReceived = now;
        }
    }

    public synchronized Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>(counts);
        Instant now = Instant.now();
        result.put("startedAt", started);
        result.put("uptimeSeconds", java.time.Duration.between(started, now).toSeconds());
        time(result, "lastReceived", lastReceived, now);
        time(result, "lastSent", lastSent, now);
        time(result, "lastMessageReceived", lastMessageReceived, now);
        time(result, "lastMessageSent", lastMessageSent, now);
        time(result, "lastAckReceived", lastAckReceived, now);
        return result;
    }

    private static void time(Map<String, Object> result, String key, Instant value, Instant now) {
        result.put(key, value);
        result.put(key + "SecondsAgo", value == null ? null : java.time.Duration.between(value, now).toSeconds());
    }
}
