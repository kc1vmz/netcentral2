package com.kc1vmz.netcentral.communication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.kc1vmz.netcentral.communication.ManagedConnectionTest.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TransmitSpacingTest {
    private CommunicationConfig config(String type, int spacing) {
        return new CommunicationConfig(UUID.randomUUID().toString(), 0, null, type, true, true,
                null, null, null, null, null, null, null, null, null, null, 0, spacing);
    }

    private ManagedConnection worker(String type, int spacing, LinkedBlockingQueue<Long> writes) throws Exception {
        var transport = mock(CommunicationTransport.class);
        var closed = new CountDownLatch(1);
        when(transport.read()).thenAnswer(call -> { closed.await(); return null; });
        when(transport.canTransmit()).thenReturn(true);
        doAnswer(call -> { writes.add(System.nanoTime()); return null; }).when(transport).send(any());
        doAnswer(call -> { closed.countDown(); return null; }).when(transport).close();
        var worker = new ManagedConnection(config(type, spacing), c -> transport, p -> {});
        worker.start();
        await(() -> worker.health().state().equals("CONNECTED"));
        return worker;
    }

    @Test
    void configurationDefaultsRoundTripsAndRejectsInvalidNumbers() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue("{}", CommunicationConfig.class).transmitSpacingMs()).isEqualTo(1000);
        for (int spacing : new int[] {0, 250, 1000}) {
            var c = config("KISS_TCP", spacing);
            assertThat(c.device("COM9").identity("new", 1, null).transmitSpacingMs()).isEqualTo(spacing);
            assertThat(mapper.readValue(mapper.writeValueAsString(c), CommunicationConfig.class).transmitSpacingMs()).isEqualTo(spacing);
        }
        for (String value : List.of("-1", "1.5", "2147483648"))
            assertThatThrownBy(() -> mapper.readValue("{\"transmitSpacingMs\":" + value + "}", CommunicationConfig.class)).isInstanceOf(Exception.class);
    }

    @Test
    void spacesWithinAndBetweenBatches() throws Exception {
        var writes = new LinkedBlockingQueue<Long>();
        var worker = worker("KISS_TCP", 150, writes);
        var packet = ManagedConnectionTest.PACKET;
        try {
            var batch = worker.send(List.of(packet, packet), () -> true);
            var next = worker.send(List.of(packet), () -> true);
            assertThat(batch.get(3, TimeUnit.SECONDS).packetsSent()).isEqualTo(2);
            assertThat(next.get(3, TimeUnit.SECONDS).state()).isEqualTo("SENT");
            long first = writes.remove(), second = writes.remove(), third = writes.remove();
            assertThat(second - first).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(150));
            assertThat(third - second).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(150));
        } finally { worker.requestStop(); await(worker::stopped); }
    }

    @Test
    void stopDuringWaitCompletesPartialBatchAndOtherInstancesAreIndependent() throws Exception {
        var writes = new LinkedBlockingQueue<Long>();
        var slow = worker("KISS_TCP", 10000, writes);
        try {
            var batch = slow.send(List.of(ManagedConnectionTest.PACKET, ManagedConnectionTest.PACKET), () -> true);
            assertThat(writes.poll(2, TimeUnit.SECONDS)).isNotNull();
            for (String type : List.of("KISS_TCP", "APRS_IS")) {
                var fast = worker(type, type.equals("APRS_IS") ? 10000 : 0, new LinkedBlockingQueue<>());
                try { assertThat(fast.send(List.of(ManagedConnectionTest.PACKET, ManagedConnectionTest.PACKET), () -> true)
                        .get(2, TimeUnit.SECONDS).packetsSent()).isEqualTo(2); }
                finally { fast.requestStop(); await(fast::stopped); }
            }
            slow.watchdog();
            assertThat(batch.isDone()).isFalse();
            slow.requestStop();
            var result = batch.get(2, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo("PARTIAL");
            assertThat(result.packetsSent()).isEqualTo(1);
            assertThat(writes).isEmpty();
        } finally { slow.requestStop(); await(slow::stopped); }
    }

    @Test
    void eligibilityIsRecheckedAfterWait() throws Exception {
        var writes = new LinkedBlockingQueue<Long>();
        var worker = worker("KISS_TCP", 300, writes);
        var allowed = new AtomicBoolean(true);
        try {
            var batch = worker.send(List.of(ManagedConnectionTest.PACKET, ManagedConnectionTest.PACKET), allowed::get);
            assertThat(writes.poll(2, TimeUnit.SECONDS)).isNotNull();
            allowed.set(false);
            var result = batch.get(2, TimeUnit.SECONDS);
            assertThat(result.state()).isEqualTo("PARTIAL");
            assertThat(result.packetsSent()).isEqualTo(1);
            assertThat(writes).isEmpty();
        } finally { worker.requestStop(); await(worker::stopped); }
    }
}
