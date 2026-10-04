package com.kc1vmz.netcentral.communication;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class PacketHub {
    public record Received(String instanceId, Packet packet, Instant time) {
    }

    /**
     * Only this event is for domain consumers: repeated receptions do not execute
     * commands again.
     */
    public record UniquePacket(Received received) {
    }

    private final ApplicationEventPublisher events;
    private final ArrayBlockingQueue<Received> incoming = new ArrayBlockingQueue<>(1000);
    private final Map<String, Instant> duplicates = new LinkedHashMap<>();
    private final Map<String, Map<String, Instant>> heard = new LinkedHashMap<>();
    private final ArrayDeque<Received> observations = new ArrayDeque<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final TrafficStatistics traffic = new TrafficStatistics();
    private volatile boolean running;
    private Thread processor;

    public PacketHub(ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostConstruct
    public void start() {
        running = true;
        processor = Thread.ofPlatform().daemon().name("communication-ingestion").start(() -> {
            while (running) {
                try {
                    Received value = incoming.take();
                    events.publishEvent(value);
                    if (record(value))
                        events.publishEvent(new UniquePacket(value));
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    dropped.incrementAndGet();
                }
            }
        });
    }

    public void receive(String instance, Packet packet) {
        traffic.record(packet, false);
        if (!incoming.offer(new Received(instance, packet, Instant.now())))
            dropped.incrementAndGet();
    }

    synchronized boolean record(Received value) {
        Instant now = value.time();
        while (observations.size() >= 200)
            observations.removeFirst();
        observations.addLast(value);
        Map<String, Instant> stationRoutes = heard.computeIfAbsent(value.packet().source(),
                key -> new LinkedHashMap<>());
        stationRoutes.entrySet().removeIf(e -> e.getValue().isBefore(now.minusSeconds(1800)));
        stationRoutes.put(value.instanceId(), now);
        if (stationRoutes.size() > 100)
            stationRoutes.remove(stationRoutes.keySet().iterator().next());
        if (heard.size() > 10000)
            heard.remove(heard.keySet().iterator().next());
        duplicates.entrySet().removeIf(e -> e.getValue().isBefore(now.minusSeconds(30)));
        Instant previous = duplicates.putIfAbsent(value.packet().duplicateKey(), now);
        if (duplicates.size() > 10000)
            duplicates.remove(duplicates.keySet().iterator().next());
        if (previous != null)
            duplicateCount.incrementAndGet();
        return previous == null;
    }

    public synchronized List<String> recent(String callsign) {
        Map<String, Instant> instances = heard.getOrDefault(callsign, Map.of());
        return instances.entrySet().stream().filter(e -> e.getValue().isAfter(Instant.now().minusSeconds(1800)))
                .sorted(Map.Entry.<String, Instant>comparingByValue(Comparator.reverseOrder())).map(Map.Entry::getKey)
                .toList();
    }

    public synchronized List<Received> observations() {
        return new ArrayList<>(observations);
    }

    public long dropped() {
        return dropped.get();
    }

    public long duplicates() {
        return duplicateCount.get();
    }

    public void sent(Packet packet) {
        traffic.record(packet, true);
    }

    public Map<String, Object> statistics() {
        var result = traffic.snapshot();
        result.put("incomingQueued", incoming.size());
        result.put("incomingCapacity", 1000);
        result.put("duplicates", duplicates());
        result.put("dropped", dropped());
        return result;
    }

    @org.springframework.transaction.event.TransactionalEventListener
    public synchronized void clearObservedData(com.kc1vmz.netcentral.accessor.DataMaintenanceAccessor.AprsDataDeleted event) {
        incoming.clear();
        observations.clear();
        heard.clear();
        duplicates.clear();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (processor != null)
            processor.interrupt();
        incoming.clear();
    }
}
