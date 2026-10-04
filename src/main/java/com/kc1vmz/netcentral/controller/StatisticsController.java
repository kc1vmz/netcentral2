package com.kc1vmz.netcentral.controller;

import java.util.*;
import org.springframework.web.bind.annotation.*;

import com.kc1vmz.netcentral.communication.*;
import com.kc1vmz.netcentral.operations.OperationsStore;

@RestController
public class StatisticsController {
    private final OperationsStore store;
    private final PacketHub packets;
    private final CommunicationRepository repository;
    private final CommunicationManager manager;

    public StatisticsController(OperationsStore store, PacketHub packets, CommunicationRepository repository,
            CommunicationManager manager) {
        this.store = store;
        this.packets = packets;
        this.repository = repository;
        this.manager = manager;
    }

    @GetMapping("/api/v1/statistics/communications")
    public Map<String, Object> statistics() {
        store.authenticated();
        var result = packets.statistics();
        var configs = repository.all();
        long queued = 0, failed = 0, discarded = 0;
        var instances = new ArrayList<Map<String, Object>>();
        for (var config : configs) {
            var health = manager.health(config);
            queued += health.queued();
            failed += health.failed();
            discarded += health.discarded();
            if (store.accounts().administrator()) {
                var item = new LinkedHashMap<String, Object>();
                item.put("name", config.name());
                item.put("type", config.type());
                item.put("state", health.state());
                item.put("received", health.received());
                item.put("sent", health.sent());
                item.put("queued", health.queued());
                item.put("lastReceived", health.lastPacket());
                instances.add(item);
            }
        }
        result.put("registeredInstances", configs.size());
        result.put("receiveEnabled", configs.stream().filter(c -> c.enabled()).count());
        result.put("transmitEnabled", configs.stream()
                .filter(c -> c.enabled() && c.transmitEnabled()).count());
        result.put("outgoingQueued", queued);
        result.put("failedWrites", failed);
        result.put("discardedSends", discarded);
        result.put("instances", instances);
        String visibility = " FROM net_message m JOIN managed_net n ON m.net_id=n.id WHERE (n.private_net=FALSE OR n.owner_id=? OR ?)";
        Object[] auth = { store.accounts().current().id(), store.accounts().administrator() };
        result.put("messagesAcknowledged", store.jdbc()
                .queryForObject("SELECT COUNT(*)" + visibility + " AND m.delivery='ACKNOWLEDGED'", Long.class, auth));
        result.put("messagesPending", store.jdbc()
                .queryForObject("SELECT COUNT(*)" + visibility + " AND m.delivery='PENDING'", Long.class, auth));
        result.put("latestAcknowledgedMessageSentAt",
                store.jdbc().queryForObject("SELECT MAX(m.received_at)" + visibility + " AND m.delivery='ACKNOWLEDGED'",
                        java.time.OffsetDateTime.class, auth));
        return result;
    }
}
