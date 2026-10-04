package com.kc1vmz.netcentral.operations;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kc1vmz.netcentral.accessor.LiveViewAccessor;
import com.kc1vmz.netcentral.account.LocalPrincipal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class LiveEventStreamTest {
    @Test
    void stalledWritersCoalesceUpdatesAndLimitAdmission() throws Exception {
        var views = mock(LiveViewAccessor.class);
        var principal = new LocalPrincipal("fixture", "", "USER", 0);
        var session = mock(HttpSession.class);
        var snapshot = new LiveViewAccessor.Snapshot("fixture", 1, Map.of(), List.of(), List.of(), false, List.of(), List.of(), List.of(), List.of(),
                null, false, false, 5000, false);
        var snapshots = new java.util.concurrent.atomic.AtomicInteger();
        when(views.snapshot(principal)).thenAnswer(call -> {
            snapshots.incrementAndGet();
            return snapshot;
        });
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        // Simulate clients whose network writes never finish until released. Radio
        // ingestion
        // never calls these writers; repeated timer ticks must not accumulate work for
        // them.
        try (var emitters = mockConstruction(SseEmitter.class, (emitter, context) -> doAnswer(call -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        })
                .when(emitter).send(any(SseEmitter.SseEventBuilder.class)))) {
            var stream = new LiveEventStream(views);
            try {
                for (int i = 0; i < 32; i++)
                    stream.open(principal, session);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> stream.open(principal, session))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
                // Two timer intervals with all four workers blocked: only the admission
                // authorizations and four in-flight snapshots can have been evaluated.
                Thread.sleep(2200);
                assertThat(snapshots.get()).isEqualTo(37);
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (snapshots.get() < 65 && System.nanoTime() < deadline)
                    Thread.sleep(10);
                assertThat(snapshots.get()).isGreaterThanOrEqualTo(65);
                assertThat(emitters.constructed()).hasSize(32);
            } finally {
                release.countDown();
                stream.stop();
            }
        }
    }
}
