package com.kc1vmz.netcentral.operations;

import com.kc1vmz.netcentral.communication.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class KenwoodMessages {
    private final OperationsStore store;
    private final CommunicationRepository configurations;
    private final RoutingService routing;
    private final CommunicationManager manager;
    private final TransmissionRouter router;
    private final RadioPolicy policy;
    private final ApplicationEventPublisher events;
    public KenwoodMessages(OperationsStore store,CommunicationRepository configurations,RoutingService routing,
            CommunicationManager manager,TransmissionRouter router,RadioPolicy policy,ApplicationEventPublisher events) {
        this.store=store;this.configurations=configurations;this.routing=routing;this.manager=manager;
        this.router=router;this.policy=policy;this.events=events;
    }
    public record Acknowledge(String instance,String from,String to,String number) {}

    @EventListener @Order(-10) @Transactional
    public void receive(PacketHub.UniquePacket event) {
        var received=event.received();var packet=received.packet();
        var config=configurations.find(received.instanceId());
        if(config==null || !config.type().equals("KENWOOD_SERIAL") || policy.ignored(packet.source()))return;
        String info=packet.information();
        if(info.length()<11 || info.charAt(0)!=':' || info.charAt(10)!=':'
            || !info.substring(1,10).strip().equalsIgnoreCase(config.username()))return;
        String body=info.substring(11).strip();
        store.lock();
        if(body.matches("(?i)(ack|rej)[A-Za-z0-9]{1,5}")) {
            // Numbers come from the shared database sequence, not a per-net counter.
            var rows=store.jdbc().queryForList("SELECT m.id,m.net_id FROM net_message m JOIN managed_net n ON n.id=m.net_id WHERE m.direction='OUT' AND m.recipient=? AND m.ack_number=? AND n.closed_at IS NULL AND m.delivery IN ('PENDING','SENT')",
                packet.source(),body.substring(3));
            if(rows.size()==1) {
                String net=rows.getFirst().get("NET_ID").toString();
                if(routing.permits("NET:"+net,config.id())) {
                    store.jdbc().update("UPDATE net_message SET delivery=? WHERE id=?",body.toLowerCase(Locale.ROOT).startsWith("ack")?"ACKNOWLEDGED":"REJECTED",rows.getFirst().get("ID"));
                    store.jdbc().update("UPDATE managed_net SET version=version+1 WHERE id=?",net);
                }
            }
            return;
        }
        var number=java.util.regex.Pattern.compile("\\{([A-Za-z0-9]{1,5})$").matcher(body);
        if(number.find())events.publishEvent(new Acknowledge(config.id(),config.username(),packet.source(),number.group(1)));
        if(store.duplicateRadio(config.username(),packet.source(),body))return;
        String text=number.find(0)?body.substring(0,number.start()):body;
        var data=store.mapper().createObjectNode().put("instance",config.name()).put("from",packet.source())
            .put("to",config.username()).put("message",text).put("receivedAt",received.time().toString());
        store.saveInternal("INSTALLATION_MESSAGE",UUID.randomUUID().toString(),"",data);
        store.jdbc().update("DELETE FROM domain_record WHERE kind='INSTALLATION_MESSAGE' AND record_key NOT IN (SELECT record_key FROM domain_record WHERE kind='INSTALLATION_MESSAGE' ORDER BY updated_at DESC,record_key FETCH FIRST 100 ROWS ONLY)");
    }
    @TransactionalEventListener
    public void acknowledge(Acknowledge event) {
        manager.send(event.instance(),List.of(router.message(event.from(),event.to(),"ack"+event.number(),false)),()->true);
    }
    public List<com.fasterxml.jackson.databind.JsonNode> list() {
        store.admin();
        return store.jdbc().query("SELECT payload FROM domain_record WHERE kind='INSTALLATION_MESSAGE' ORDER BY updated_at DESC,record_key FETCH FIRST 100 ROWS ONLY",(r,n)->store.json(r.getString(1)));
    }
}
