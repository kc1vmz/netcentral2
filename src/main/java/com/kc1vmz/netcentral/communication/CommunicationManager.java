package com.kc1vmz.netcentral.communication;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CommunicationManager {
    private final CommunicationRepository repository;
    private final TransportFactory factory;
    private final PacketHub packets;
    private final SerialOwnership serial;
    private final TransactionTemplate transactions;
    private final Map<String, ManagedConnection> workers = new ConcurrentHashMap<>();
    private final Map<String, String> serialKeys = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("communication-manager").unstarted(task));
    private volatile boolean running;

    public CommunicationManager(CommunicationRepository repository, TransportFactory factory, PacketHub packets,
            SerialOwnership serial,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.factory = factory;
        this.packets = packets;
        this.serial = serial;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                reconcile();
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Communication reconciliation failed ({})",
                        failure.getClass().getSimpleName());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized void reconcile() {
        if (!running)
            return;
        transactions.executeWithoutResult(status -> {
            // Serialize snapshot/reservation with edits so a stale snapshot cannot open a
            // newly reassigned port.
            repository.lock();
            reconcileLocked();
        });
    }

    private void reconcileLocked() {
        Map<String, CommunicationConfig> desired = new HashMap<>();
        repository.all().forEach(c -> desired.put(c.id(), c));
        var iterator = workers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ManagedConnection worker = entry.getValue();
            CommunicationConfig config = desired.get(entry.getKey());
            if (config == null || !config.enabled() || config.version() != worker.config().version()) {
                worker.requestStop();
                if (worker.stopped()) {
                    String key = serialKeys.remove(entry.getKey());
                    if (key != null)
                        serial.release(key, entry.getKey());
                    iterator.remove();
                }
            } else
                worker.watchdog();
        }
        for (CommunicationConfig config : desired.values()) {
            if (!config.enabled() || workers.containsKey(config.id()))
                continue;
            String key = config.serial() ? SerialOwnership.identity(config.serialDevice()) : null;
            if (key != null && desired.values().stream().anyMatch(other -> !other.id().equals(config.id())
                    && other.enabled() && other.serial() && key.equals(SerialOwnership.identity(other.serialDevice()))))
                continue;
            if (key != null && !serial.claim(key, config.id()))
                continue;
            if (key != null)
                serialKeys.put(config.id(), key);
            // Open the exact resolved device that was reserved, even if an alias is
            // retargeted later.
            ManagedConnection worker = new ManagedConnection(key == null ? config : config.device(key), factory,
                    packet -> packets.receive(config.id(), packet), packet -> packets.sent(
                        config.type().equals("KENWOOD_SERIAL") ? new Packet(config.username(), packet.destination(),
                            config.digiPath() == null || config.digiPath().isBlank() ? List.of() : List.of(config.digiPath().split(",")), packet.information()) : packet));
            workers.put(config.id(), worker);
            worker.start();
        }
    }

    public ManagedConnection.Health health(CommunicationConfig config) {
        ManagedConnection worker = workers.get(config.id());
        if (worker != null)
            return worker.health();
        String state = !config.enabled() ? "DISABLED" : "WAITING";
        return new ManagedConnection.Health(state, null, null, 0, 0, 0, 0, 0);
    }

    public synchronized boolean available(String id) {
        ManagedConnection worker = workers.get(id);
        return worker != null && worker.health().state().equals("CONNECTED");
    }

    public synchronized CompletableFuture<ManagedConnection.Outcome> send(String id, List<Packet> packets,
            BooleanSupplier allowed) {
        ManagedConnection worker = workers.get(id);
        if (worker == null)
            return CompletableFuture.completedFuture(new ManagedConnection.Outcome("UNAVAILABLE", 0));
        long version = worker.config().version();
        return worker.send(packets, () -> {
            CommunicationConfig latest = repository.find(id);
            return latest != null && latest.enabled() && latest.transmitEnabled() && latest.version() == version
                    && allowed.getAsBoolean();
        });
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        List<ManagedConnection> active;
        synchronized (this) {
            active = List.copyOf(workers.values());
            active.forEach(ManagedConnection::requestStop);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (active.stream().anyMatch(w -> !w.stopped()) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (this) {
            workers.forEach((id, worker) -> {
                if (worker.stopped() && serialKeys.containsKey(id))
                    serial.release(serialKeys.get(id), id);
            });
        }
    }
}
