package com.kc1vmz.netcentral.communication;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ManagedConnectionTest {
    static final Packet PACKET = new Packet("N0CALL", "APANC1", List.of(), "test");

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void boundedQueueRechecksRoutesAndDistinguishesAcceptanceFromSending() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean allowed = new AtomicBoolean(true);
        CommunicationTransport transport = new CommunicationTransport() {
            public void connect() {
            }

            public Packet read() throws IOException {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException stop) {
                    throw new IOException();
                }
                if (closed.get())
                    throw new IOException();
                return null;
            }

            public void send(Packet p) throws IOException {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException stop) {
                    throw new IOException();
                }
            }

            public boolean canTransmit() {
                return !closed.get();
            }

            public void close() {
                closed.set(true);
                release.countDown();
            }
        };
        TrafficStatistics statistics = new TrafficStatistics();
        ManagedConnection worker = new ManagedConnection(ProtocolTest.config("KISS_TCP", 1, true), c -> transport,
                p -> {
                }, p -> statistics.record(p, true));
        try {
            worker.start();
            await(() -> worker.health().state().equals("CONNECTED"));
            var first = worker.send(List.of(PACKET), () -> true);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var queued = worker.send(List.of(PACKET), allowed::get);
            for (int i = 0; i < 99; i++)
                worker.send(List.of(PACKET), allowed::get);
            assertThat(worker.send(List.of(PACKET), () -> true).get().state()).isEqualTo("QUEUE_FULL");
            assertThat(first.isDone()).isFalse();
            assertThat(statistics.snapshot()).containsEntry("packetsSent", 0L);
            allowed.set(false);
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).state()).isEqualTo("SENT");
            assertThat(queued.get(3, TimeUnit.SECONDS).state()).isEqualTo("DISCARDED");
            await(() -> worker.health().queued() == 0);
            assertThat(worker.health().sent()).isEqualTo(1);
            assertThat(statistics.snapshot()).containsEntry("packetsSent", 1L);
        } finally {
            worker.requestStop();
            await(worker::stopped);
        }
    }

    @Test
    void failedConnectionRetriesWithoutBlockingOtherWork() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ManagedConnection worker = new ManagedConnection(ProtocolTest.config("KISS_TCP", 1, true),
                c -> new CommunicationTransport() {
                    private volatile boolean closed;

                    public void connect() throws IOException {
                        if (attempts.incrementAndGet() == 1)
                            throw new IOException();
                    }

                    public Packet read() throws IOException {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException stop) {
                            throw new IOException();
                        }
                        if (closed)
                            throw new IOException();
                        return null;
                    }

                    public void send(Packet p) {
                    }

                    public boolean canTransmit() {
                        return !closed;
                    }

                    public void close() {
                        closed = true;
                    }
                }, p -> {
                });
        try {
            worker.start();
            await(() -> worker.health().state().equals("CONNECTED"));
            assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
            assertThat(worker.send(List.of(PACKET, PACKET), () -> true).get(2, TimeUnit.SECONDS).packetsSent())
                    .isEqualTo(2);
        } finally {
            worker.requestStop();
            await(worker::stopped);
        }
    }

    @Test
    void duplicateObservationsKeepRoutesButEmitOneBusinessEvent() throws Exception {
        AtomicInteger unique = new AtomicInteger();
        PacketHub hub = new PacketHub(event -> {
            if (event instanceof PacketHub.UniquePacket)
                unique.incrementAndGet();
        });
        try {
            hub.start();
            hub.receive("one", PACKET);
            hub.receive("two", new Packet("N0CALL", "APANC1", List.of("WIDE1-1"), "test"));
            await(() -> hub.observations().size() == 2);
            assertThat(unique.get()).isEqualTo(1);
            assertThat(hub.duplicates()).isEqualTo(1);
            assertThat(hub.recent("N0CALL")).containsExactlyInAnyOrder("one", "two");
        } finally {
            hub.stop();
        }
    }

    @Test
    void stoppingDiscardsPendingWorkAndDoesNotReplayItOnReplacement() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        ManagedConnection worker = new ManagedConnection(ProtocolTest.config("KISS_TCP", 1, true),
                c -> new CommunicationTransport() {
                    public void connect() {
                    }

                    public Packet read() throws IOException {
                        try {
                            stopped.await();
                        } catch (InterruptedException stop) {
                            throw new IOException();
                        }
                        throw new IOException();
                    }

                    public void send(Packet p) throws IOException {
                        sending.countDown();
                        try {
                            stopped.await();
                        } catch (InterruptedException stop) {
                            throw new IOException();
                        }
                        throw new IOException();
                    }

                    public boolean canTransmit() {
                        return true;
                    }

                    public void close() {
                        stopped.countDown();
                    }
                }, p -> {
                });
        try {
            worker.start();
            await(() -> worker.health().state().equals("CONNECTED"));
            var active = worker.send(List.of(PACKET), () -> true);
            assertThat(sending.await(2, TimeUnit.SECONDS)).isTrue();
            var pending = worker.send(List.of(PACKET), () -> true);
            worker.requestStop();
            assertThat(pending.get(2, TimeUnit.SECONDS).state()).isEqualTo("DISCARDED");
            assertThat(active.get(2, TimeUnit.SECONDS).state()).isEqualTo("FAILED");
            await(worker::stopped);
            ManagedConnection replacement = new ManagedConnection(worker.config(), c -> {
                throw new AssertionError("Not started");
            }, p -> {
            });
            assertThat(replacement.health().queued()).isZero();
            assertThat(worker.send(List.of(PACKET), () -> true).get().state()).isEqualTo("UNAVAILABLE");
        } finally {
            worker.requestStop();
            await(worker::stopped);
        }
    }

    @Test
    void closeFailureDoesNotReleaseOwnershipEvenWhenThreadsExit() throws Exception {
        ManagedConnection worker = new ManagedConnection(ProtocolTest.config("KISS_SERIAL", 1, true),
                c -> new CommunicationTransport() {
                    public void connect() {
                    }

                    public Packet read() throws IOException {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException stop) {
                            throw new IOException();
                        }
                        return null;
                    }

                    public void send(Packet p) {
                    }

                    public boolean canTransmit() {
                        return true;
                    }

                    public void close() {
                        throw new IllegalStateException("fixture close failure");
                    }
                }, p -> {
                });
        worker.start();
        await(() -> worker.health().state().equals("CONNECTED"));
        worker.requestStop();
        await(() -> worker.health().state().equals("STOP_FAILED"));
        assertThat(worker.stopped()).isFalse();
    }
}
