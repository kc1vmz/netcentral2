package com.kc1vmz.netcentral.accessor;

import java.time.Instant;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kc1vmz.netcentral.communication.*;
import com.kc1vmz.netcentral.net.NetModels.NetEdit;
import com.kc1vmz.netcentral.operations.*;

@Service
public class NetManagerAccessor {
    private final OperationsStore store;
    private final NetAccessor nets;
    private final RadioPolicy policy;
    private final RoutingService routing;
    private final RadioDispatch radio;
    private final ResourceAccessor objects;

    public NetManagerAccessor(OperationsStore store, NetAccessor nets, RadioPolicy policy, RoutingService routing,
            RadioDispatch radio, ResourceAccessor objects) {
        this.store = store;
        this.nets = nets;
        this.policy = policy;
        this.routing = routing;
        this.radio = radio;
        this.objects = objects;
    }

    @EventListener({OperationsStore.SettingsChanged.class, org.springframework.boot.context.event.ApplicationReadyEvent.class})
    @Transactional
    public void synchronizeObject() {
        store.lock();
        String call = store.setting("netManagerCallsign");
        boolean enabled = store.enabled("netManagerEnabled");
        String latitude = store.setting("netManagerLatitude"), longitude = store.setting("netManagerLongitude");
        for (var old : store.list("OBJECT")) {
            if (old.data().path("configuredNetManager").asBoolean() && (!enabled || !old.id().equals(call) || latitude.isBlank())) {
                if (old.data().path("alive").asBoolean()) {
                    var data = (com.fasterxml.jackson.databind.node.ObjectNode)old.data().deepCopy();
                    data.put("alive", false);
                    objects.beacon(store.saveInternal("OBJECT", old.id(), old.ownerId(), data));
                }
            }
        }
        if (!enabled || latitude.isBlank() || longitude.isBlank()) return;
        var old = store.find("OBJECT", call);
        if ((old != null && !old.data().path("configuredNetManager").asBoolean() && !old.data().path("type").asText().equals("NETMGR"))
                || store.jdbc().queryForObject("SELECT COUNT(*) FROM managed_net WHERE active_callsign=?", Integer.class, call) > 0)
            throw OperationsStore.bad("Net Manager callsign belongs to another managed resource");
        var data = store.mapper().createObjectNode().put("callsign", call).put("type", "NETMGR")
                .put("configuredNetManager", true).put("alive", true).put("lat", Double.parseDouble(latitude))
                .put("lon", Double.parseDouble(longitude)).put("comment", "Net Central Net Manager")
                .put("symbolTableId", "/").put("symbolTableCode", "n");
        data.set("types", store.mapper().valueToTree(policy.classifyObject(data)));
        if (old != null && old.data().equals(data) && old.ownerId().equals(store.setting("netManagerOwner"))) return;
        var saved = store.saveInternal("OBJECT", call, store.setting("netManagerOwner"), data);
        store.jdbc().update("DELETE FROM domain_record WHERE kind='OBSERVED_OBJECT' AND record_key=?", call);
        objects.beacon(saved);
    }

    @EventListener
    @Transactional
    public void receive(PacketHub.UniquePacket event) {
        if (!store.enabled("netManagerEnabled"))
            return;
        Packet p = event.received().packet();
        String info = p.information(), manager = store.setting("netManagerCallsign");
        if (info.length() < 11 || !info.startsWith(":" + String.format("%-9s", manager) + ":"))
            return;
        if (!routing.permits("OBJECT:" + manager, event.received().instanceId()) || !policy.permits(manager, p.source(), false))
            return;
        store.lock();
        String raw = info.substring(11);
        if (raw.stripLeading().startsWith("?")) return; // ResourceAccessor handles object queries and their acknowledgments.
        int marker = raw.lastIndexOf('{');
        if (marker >= 0 && raw.substring(marker + 1).matches("[A-Za-z0-9]{1,5}"))
            radio.message("OBJECT:" + manager, manager, p.source(), "ack" + raw.substring(marker + 1),
                    event.received().instanceId());
        if (store.duplicateRadio(manager, p.source(), raw))
            return;
        String body = raw.split("\\{", 2)[0].strip();
        if (body.startsWith("?")) return; // The managed object answers information queries.
        String[] words = body.split("\\s+");
        String result;
        try {
            switch (words[0].toUpperCase(Locale.ROOT)) {
                case "H" -> result = "L list nets; C CALL create; CP CALL closed-admission; S CALL secure";
                case "L" -> result = "Nets: " + String.join(",",
                        store.jdbc().queryForList(
                                "SELECT callsign FROM managed_net WHERE closed_at IS NULL AND private_net=FALSE",
                                String.class));
                case "C", "CP" -> {
                    if (words.length != 2)
                        return;
                    String call = NetAccessor.callsign(words[1]);
                    boolean open = words[0].equalsIgnoreCase("C");
                    String owner = store.setting("netManagerOwner");
                    if (store.jdbc().queryForObject("SELECT COUNT(*) FROM local_account WHERE id=?", Integer.class,
                            owner) != 1)
                        return;
                    var net = nets.createScheduledInternal(new NetEdit(call, call, "Ad-hoc APRS net", "", open, false,
                            null, null, null, null, Set.of(RadioPolicy.root(p.source()))), owner, p.source(),
                            Instant.now());
                    store.saveInternal("NET_MANAGER", net.id(), owner,
                            store.mapper().createObjectNode().put("creator", p.source()));
                    result = "Net " + call + " now active";
                }
                case "S" -> {
                    if (words.length != 2)
                        return;
                    var net = nets.findInternal(words[1]);
                    var record = store.find("NET_MANAGER", net.id());
                    if (record == null || !record.data().path("creator").asText().equals(p.source()))
                        result = "Only creator can secure this ad-hoc net";
                    else {
                        nets.closeScheduledInternal(net.id(), Instant.now());
                        result = "Net secured";
                    }
                }
                default -> result = "Send H for help";
            }
        } catch (org.springframework.web.server.ResponseStatusException invalid) {
            result = "Net request could not be completed";
        }
        radio.message("OBJECT:" + manager, manager, p.source(), result, event.received().instanceId());
    }
}
