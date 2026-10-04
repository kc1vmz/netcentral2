package com.kc1vmz.netcentral.net;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.accessor.NetSchedulerAccessor;

@Component
@ConditionalOnProperty(name = "netcentral.workflow.start", matchIfMissing = true)
public class NetRuntime {
    private final NetAccessor nets;
    private final NetSchedulerAccessor schedules;
    private final ScheduledExecutorService timer = Executors
            .newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("net-retention").unstarted(r));

    public NetRuntime(NetAccessor nets, NetSchedulerAccessor schedules) {
        this.nets = nets;
        this.schedules = schedules;
    }

    @EventListener(ApplicationStartedEvent.class)
    public void start() {
        nets.recover();
        schedules.reconcile(java.time.Instant.now());
        timer.scheduleWithFixedDelay(() -> {
            try {
                schedules.reconcile(java.time.Instant.now());
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Schedule reconciliation failed ({})",
                        failure.getClass().getSimpleName());
            }
        }, 1, 1, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> {
            try {
                nets.expirePositions();
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Net cleanup failed ({})", failure.getClass().getSimpleName());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        timer.shutdownNow();
    }
}
