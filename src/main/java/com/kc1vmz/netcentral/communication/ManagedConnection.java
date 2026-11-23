package com.kc1vmz.netcentral.communication;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One cancellable connection, reader and bounded writer queue per configured
 * instance.
 */
public final class ManagedConnection {
    public record Outcome(String state, int packetsSent) {
    }

    public record Health(String state, String lastError, Instant lastPacket, long received,
            long sent, long failed, long discarded, int queued) {
    }

    private record Pending(CommunicationTransport connection, List<Packet> packets,
            BooleanSupplier permitted, CompletableFuture<Outcome> result) {
    }

    private final CommunicationConfig config;
    private final TransportFactory factory;
    private final Consumer<Packet> incoming;
    private final Consumer<Packet> outgoing;
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(100);
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private volatile CommunicationTransport connection;
    private volatile boolean stopping;
    private volatile boolean closeFailed;
    private volatile String state = "CONNECTING";
    private volatile String error;
    private volatile Instant lastPacket;
    private volatile long writingSince;
    private volatile long stoppingSince;
    private volatile Pending active;
    private volatile int activeSent;
    private Thread reader;
    private Thread writer;
    private volatile Thread closer;

    public ManagedConnection(CommunicationConfig config, TransportFactory factory, Consumer<Packet> incoming) {
        this(config, factory, incoming, packet -> {
        });
    }

    public ManagedConnection(CommunicationConfig config, TransportFactory factory, Consumer<Packet> incoming,
            Consumer<Packet> outgoing) {
        this.config = config;
        this.factory = factory;
        this.incoming = incoming;
        this.outgoing = outgoing;
    }

    public CommunicationConfig config() {
        return config;
    }

    public void start() {
        reader = Thread.ofPlatform().daemon().name("communication-reader-" + config.id()).start(this::readLoop);
        writer = Thread.ofPlatform().daemon().name("communication-writer-" + config.id()).start(this::writeLoop);
    }

    private void readLoop() {
        long delay = 1000;
        while (!stopping) {
            CommunicationTransport current = null;
            try {
                current = factory.create(config);
                connection = current;
                if (stopping)
                    break;
                state = "CONNECTING";
                current.connect();
                if (stopping)
                    break;
                state = current.canTransmit() ? "CONNECTED" : "RECEIVE_ONLY";
                error = null;
                long connectedAt = System.nanoTime();
                while (!stopping) {
                    Packet packet = current.read();
                    if (packet != null) {
                        lastPacket = Instant.now();
                        received.incrementAndGet();
                        incoming.accept(packet);
                    }
                    if (System.nanoTime() - connectedAt > TimeUnit.SECONDS.toNanos(30))
                        delay = 1000;
                }
            } catch (Exception failure) {
                error = "Connection unavailable (" + failure.getClass().getSimpleName() + ")";
            } finally {
                state = stopping ? "STOPPING" : "RETRYING";
                connection = null;
                drain();
                if (current != null)
                    closeTransport(current);
            }
            if (!stopping) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    break;
                }
                delay = Math.min(30000, delay * 2);
            }
        }
    }

    private void writeLoop() {
        long lastWriteCompleted = 0;
        long spacingNanos = "APRS_IS".equals(config.type()) ? 0 : TimeUnit.MILLISECONDS.toNanos(config.transmitSpacingMs());
        while (!stopping) {
            Pending pending = null;
            int count = 0;
            try {
                pending = queue.take();
                activeSent = 0;
                active = pending;
                for (Packet packet : pending.packets()) {
                    if (lastWriteCompleted != 0 && spacingNanos > 0) {
                        long remaining;
                        while ((remaining = spacingNanos - (System.nanoTime() - lastWriteCompleted)) > 0)
                            TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                    if (stopping || pending.connection() != connection || !pending.connection().canTransmit()
                            || !pending.permitted().getAsBoolean())
                        break;
                    writingSince = System.nanoTime();
                    pending.connection().send(packet);
                    lastWriteCompleted = System.nanoTime();
                    outgoing.accept(packet);
                    writingSince = 0;
                    count++;
                    activeSent = count;
                    sent.incrementAndGet();
                }
                if (count == pending.packets().size())
                    pending.result().complete(new Outcome("SENT", count));
                else {
                    discarded.incrementAndGet();
                    pending.result().complete(new Outcome(count == 0 ? "DISCARDED" : "PARTIAL", count));
                }
            } catch (InterruptedException stop) {
                if (pending != null) {
                    discarded.incrementAndGet();
                    pending.result().complete(new Outcome(count == 0 ? "DISCARDED" : "PARTIAL", count));
                }
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                failed.incrementAndGet();
                error = "Send failed (" + failure.getClass().getSimpleName() + ")";
                if (pending != null) {
                    pending.result().complete(new Outcome(count == 0 ? "FAILED" : "PARTIAL", count));
                    closeAsync(pending.connection());
                }
            } finally {
                writingSince = 0;
                active = null;
            }
        }
        drain();
    }

    public synchronized CompletableFuture<Outcome> send(List<Packet> packets, BooleanSupplier permitted) {
        if (packets.isEmpty() || packets.size() > 50)
            throw new IllegalArgumentException("Send 1 to 50 packets");
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        CommunicationTransport current = connection;
        if (stopping || current == null || !current.canTransmit() || !state.equals("CONNECTED")) {
            result.complete(new Outcome("UNAVAILABLE", 0));
        } else if (!queue.offer(new Pending(current, List.copyOf(packets), permitted, result))) {
            discarded.incrementAndGet();
            result.complete(new Outcome("QUEUE_FULL", 0));
        }
        return result;
    }

    private void drain() {
        Pending pending;
        while ((pending = queue.poll()) != null) {
            discarded.incrementAndGet();
            pending.result().complete(new Outcome("DISCARDED", 0));
        }
    }

    private synchronized void closeAsync(CommunicationTransport current) {
        if (current == null || (closer != null && closer.isAlive()))
            return;
        closer = Thread.ofPlatform().daemon().name("communication-close-" + config.id())
                .start(() -> closeTransport(current));
    }

    private void closeTransport(CommunicationTransport current) {
        try {
            current.close();
        } catch (RuntimeException failure) {
            // A terminated thread alone does not prove that its serial handle was released.
            closeFailed = true;
            requestStop();
        }
    }

    public void watchdog() {
        if (writingSince != 0 && System.nanoTime() - writingSince > TimeUnit.SECONDS.toNanos(5)) {
            error = "Send timed out";
            state = "RETRYING";
            Pending pending = active;
            if (pending != null)
                pending.result().complete(new Outcome("UNKNOWN", activeSent));
            closeAsync(connection);
        }
    }

    public synchronized void requestStop() {
        if (!stopping)
            stoppingSince = System.nanoTime();
        stopping = true;
        state = "STOPPING";
        drain();
        watchdog();
        closeAsync(connection);
        if (reader != null)
            reader.interrupt();
        if (writer != null)
            writer.interrupt();
    }

    public boolean stopped() {
        return !closeFailed && (reader == null || !reader.isAlive()) && (writer == null || !writer.isAlive())
                && (closer == null || !closer.isAlive());
    }

    public Health health() {
        boolean stuck = closeFailed
                || (stopping && !stopped() && System.nanoTime() - stoppingSince > TimeUnit.SECONDS.toNanos(5));
        return new Health(stuck ? "STOP_FAILED" : state,
                stuck ? "Worker has not stopped; replacement is blocked" : error,
                lastPacket, received.get(), sent.get(), failed.get(), discarded.get(), queue.size());
    }
}
