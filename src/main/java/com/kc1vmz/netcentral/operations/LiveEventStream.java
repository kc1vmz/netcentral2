package com.kc1vmz.netcentral.operations;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kc1vmz.netcentral.accessor.LiveViewAccessor;
import com.kc1vmz.netcentral.account.LocalPrincipal;

/** Bounded, coalesced current-state delivery, isolated from radio ingestion. */
@Component
public class LiveEventStream {
    private final LiveViewAccessor views;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors
            .newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("live-view-timer").unstarted(r));
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            r -> Thread.ofPlatform().daemon().name("live-view-writer").unstarted(r),
            new ThreadPoolExecutor.AbortPolicy());

    private static final class Client {
        final LocalPrincipal principal;
        final HttpSession session;
        final SseEmitter emitter = new SseEmitter(60000L);
        final AtomicBoolean busy = new AtomicBoolean();

        Client(LocalPrincipal principal, HttpSession session) {
            this.principal = principal;
            this.session = session;
        }
    }

    public LiveEventStream(LiveViewAccessor views) {
        this.views = views;
        timer.scheduleWithFixedDelay(() -> clients.forEach(this::submit), 1, 1, TimeUnit.SECONDS);
    }

    public synchronized SseEmitter open(LocalPrincipal principal, HttpSession session) {
        views.snapshot(principal);
        if (session == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (clients.size() >= 32)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Live viewer limit reached");
        var client = new Client(principal, session);
        clients.add(client);
        client.emitter.onCompletion(() -> clients.remove(client));
        client.emitter.onTimeout(() -> close(client));
        client.emitter.onError(e -> clients.remove(client));
        submit(client);
        return client.emitter;
    }

    private void submit(Client client) {
        if (!client.busy.compareAndSet(false, true))
            return;
        try {
            writers.execute(() -> {
                try {
                    int idle = client.session.getMaxInactiveInterval();
                    if (idle > 0 && System.currentTimeMillis() - client.session.getLastAccessedTime() >= idle * 1000L) {
                        close(client);
                        return;
                    }
                    var snapshot = views.snapshot(client.principal);
                    client.emitter.send(SseEmitter.event().name("snapshot")
                            .id(snapshot.epoch() + ":" + snapshot.revision()).data(snapshot));
                } catch (Exception disconnected) {
                    close(client);
                } finally {
                    client.busy.set(false);
                }
            });
        } catch (RejectedExecutionException full) {
            client.busy.set(false);
            close(client);
        }
    }

    private void close(Client client) {
        clients.remove(client);
        client.emitter.complete();
    }

    @PreDestroy
    public void stop() {
        timer.shutdownNow();
        clients.forEach(this::close);
        writers.shutdownNow();
    }
}
