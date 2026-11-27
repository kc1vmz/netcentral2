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
    private LiveViewAccessor.Snapshot snapshot() {
        return new LiveViewAccessor.Snapshot("fixture", 1, Map.of(), List.of(), List.of(), false,
                List.of(), List.of(), List.of(), List.of(), null, false, false, 5000, false);
    }

    @Test
    void failedWriteRemovesClientWithoutCompletingAndAllowsReconnect() throws Exception {
        var views = mock(LiveViewAccessor.class);
        var principal = new LocalPrincipal("fixture", "", "USER", 0);
        when(views.snapshot(principal)).thenReturn(snapshot());
        try (var emitters = mockConstruction(SseEmitter.class, (emitter, context) -> {
            if (context.getCount() == 1) doThrow(new java.io.IOException(
                    "An established connection was aborted by the software in your host machine"))
                    .when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        })) {
            var stream = new LiveEventStream(views);
            try {
                var first = stream.open(principal, mock(HttpSession.class));
                verify(first, timeout(3000)).send(any(SseEmitter.SseEventBuilder.class));
                Thread.sleep(1200);
                verify(first, times(1)).send(any(SseEmitter.SseEventBuilder.class));
                var second = stream.open(principal, mock(HttpSession.class));
                verify(second, timeout(3000)).send(any(SseEmitter.SseEventBuilder.class));
                stream.stop();
                verify(first, never()).complete();
                verify(second).complete();
            } finally { stream.stop(); }
        }
    }

    @Test
    void snapshotFailureIsLoggedAndTimeoutCompletesNormally() throws Exception {
        var views = mock(LiveViewAccessor.class);
        var principal = new LocalPrincipal("fixture", "", "USER", 0);
        when(views.snapshot(principal)).thenReturn(snapshot()).thenThrow(new IllegalStateException("snapshot failure"));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LiveEventStream.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try (var emitters = mockConstruction(SseEmitter.class)) {
            var stream = new LiveEventStream(views);
            try {
                var first = stream.open(principal, mock(HttpSession.class));
                verify(first, timeout(3000)).complete();
                verify(first, never()).send(any(SseEmitter.SseEventBuilder.class));
                assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && e.getFormattedMessage().contains("generate live-view snapshot") && e.getThrowableProxy() != null);
                doReturn(snapshot()).when(views).snapshot(principal);
                var second = stream.open(principal, mock(HttpSession.class));
                var timeoutCallback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
                verify(second).onTimeout(timeoutCallback.capture());
                timeoutCallback.getValue().run();
                stream.stop();
                verify(second, times(1)).complete();
            } finally { stream.stop(); }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void resolverOnlyHandlesRecognizedDisconnectsOnMarkedStreams() {
        var resolver = new LiveDisconnectHandler();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var aborted = new java.io.IOException("An established connection was aborted by the software in your host machine");
        assertThat(resolver.resolveException(request, response, null, aborted)).isNull();
        request.setAttribute(LiveDisconnectHandler.LIVE_STREAM, true);
        assertThat(resolver.resolveException(request, response, null, new RuntimeException(aborted))).isNotNull();
        assertThat(resolver.resolveException(request, response, null, new java.io.IOException("Disk error"))).isNull();
        assertThat(resolver.resolveException(request, response, null, new IllegalStateException("Snapshot failed"))).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

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
