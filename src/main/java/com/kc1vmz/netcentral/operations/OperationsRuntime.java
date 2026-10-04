package com.kc1vmz.netcentral.operations;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.kc1vmz.netcentral.accessor.*;

@Component
@ConditionalOnProperty(name = "netcentral.workflow.start", matchIfMissing = true)
public class OperationsRuntime {
    private final OperationsStore store;
    private final StationAccessor stations;
    private final ResourceAccessor objects;
    private final NetAccessor nets;
    private final ToolsAccessor tools;
    private final ScheduledExecutorService timer = Executors
            .newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("operations-timers").unstarted(r));
    private long nextBeacon, nextReminder;

    public OperationsRuntime(OperationsStore store, StationAccessor stations, ResourceAccessor objects,
            NetAccessor nets, ToolsAccessor tools) {
        this.store = store;
        this.stations = stations;
        this.objects = objects;
        this.nets = nets;
        this.tools = tools;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // No missed timer replay after downtime. New intervals begin at startup.
        long now = System.nanoTime();
        nextBeacon = now + TimeUnit.SECONDS.toNanos(5);
        nextReminder = now + TimeUnit.SECONDS.toNanos(store.seconds("reminderSeconds"));
        timer.scheduleWithFixedDelay(() -> {
            try {
                stations.cleanup();
                tools.cleanup();
                long time = System.nanoTime();
                if (time >= nextBeacon) {
                    nextBeacon = time + TimeUnit.SECONDS.toNanos(store.seconds("beaconSeconds"));
                    objects.beacons();
                    nets.announcements();
                }
                if (time >= nextReminder) {
                    nextReminder = time + TimeUnit.SECONDS.toNanos(store.seconds("reminderSeconds"));
                    nets.reminders();
                }
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Operations timer failed ({})",
                        failure.getClass().getSimpleName());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        timer.shutdownNow();
    }
}
