package com.kc1vmz.netcentral.net;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.account.LocalPrincipal;

/**
 * Coalesced committed snapshots: no unbounded per-client queue or lost
 * snapshot/subscription interval.
 */
@Component
public class NetEventStream {
    private final NetAccessor nets;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors
            .newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("net-events").unstarted(r));
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            r -> Thread.ofPlatform().daemon().name("net-event-writer").unstarted(r),
            new ThreadPoolExecutor.AbortPolicy());

    private static final class Client {
        final String net;
        final LocalPrincipal principal;
        final HttpSession session;
        final SseEmitter emitter = new SseEmitter(60000L);
        final AtomicBoolean busy = new AtomicBoolean();
        volatile long version = -1;
        volatile long routingVersion = -1;

        Client(String net, LocalPrincipal principal, HttpSession session) {
            this.net = net;
            this.principal = principal;
            this.session = session;
        }
    }

    public NetEventStream(NetAccessor nets) {
        this.nets = nets;
        timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized SseEmitter open(String id, LocalPrincipal principal, HttpSession session) {
        // Authorize before committing an event-stream response.
        nets.snapshotForPrincipal(id, principal);
        if (clients.size() >= 32)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Live viewer limit reached; retry shortly");
        Client client = new Client(id, principal, session);
        clients.add(client);
        client.emitter.onCompletion(() -> clients.remove(client));
        client.emitter.onTimeout(() -> close(client));
        client.emitter.onError(error -> clients.remove(client));
        submit(client);
        return client.emitter;
    }

    private void tick() {
        clients.forEach(this::submit);
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
                    var snapshot = nets.snapshotForPrincipal(client.net, client.principal);
                    if (snapshot.net().version() != client.version
                            || snapshot.routing().version() != client.routingVersion) {
                        client.emitter.send(SseEmitter.event().name("snapshot")
                                .id(Long.toString(snapshot.net().version())).data(snapshot));
                        client.version = snapshot.net().version();
                        client.routingVersion = snapshot.routing().version();
                    } else
                        client.emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (Exception disconnected) {
                    close(client);
                } finally {
                    client.busy.set(false);
                }
            });
        } catch (RuntimeException saturated) {
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
