package com.kc1vmz.netcentral.net;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.communication.Packet;
import com.kc1vmz.netcentral.communication.TransmissionRouter;
import com.kc1vmz.netcentral.net.NetModels.SendRequested;

@Component
public class NetDelivery {
    private final NetAccessor nets;
    private final TransmissionRouter router;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public NetDelivery(NetAccessor nets, TransmissionRouter router, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.nets = nets;
        this.router = router;
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void send(SendRequested request) {
        try {
            Packet packet = router.message(request.from(), request.to(), request.text(), false);
            if (request.number() != null)
                packet = new Packet(packet.source(), packet.destination(), packet.path(),
                        packet.information() + "{" + request.number());
            var results = router.directed("NET:" + request.netId(), request.to(), request.receivingInstance(),
                    List.of(packet),
                    () -> nets.activeInternal(request.netId()));
            if (results.isEmpty())
                finish(request, "NO_ROUTE");
            else
                results.values().iterator().next()
                        .whenComplete((result, error) -> finish(request, error == null ? result.state() : "FAILED"));
        } catch (RuntimeException error) {
            finish(request, "FAILED");
        }
    }

    private void finish(SendRequested request, String state) {
        transactions.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM net_lock WHERE id=1 FOR UPDATE", Integer.class);
            if (jdbc.update("UPDATE net_message SET delivery=? WHERE id=? AND delivery='PENDING'", state,
                    request.messageId()) > 0)
                jdbc.update("UPDATE managed_net SET version=version+1 WHERE id=?", request.netId());
        });
    }
}
