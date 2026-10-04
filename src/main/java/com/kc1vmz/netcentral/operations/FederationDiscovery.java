package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.communication.PacketHub;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Message-based discovery of remotely owned APRS resources. */
@Service
public class FederationDiscovery {
    private final OperationsStore store;
    private final RadioDispatch radio;
    private final RadioPolicy policy;

    public FederationDiscovery(OperationsStore store, RadioDispatch radio, RadioPolicy policy) {
        this.store = store;
        this.radio = radio;
        this.policy = policy;
    }

    private boolean local(String call) {
        return store.find("OBJECT", call) != null || store.jdbc().queryForObject(
                "SELECT COUNT(*) FROM managed_net WHERE active_callsign=?", Integer.class, call) > 0;
    }

    public void observed(String call, boolean alive, PacketHub.UniquePacket event) {
        if (!alive || !call.matches("[A-Za-z0-9-]{1,9}") || policy.ignored(call) || local(call) || !store.enabled("federationEnabled")
                || !store.enabled("federationInterrogateEnabled")) return;
        String from = store.setting("federationCallsign");
        if (from.isBlank() || from.equals(call)) return;
        store.jdbc().update("DELETE FROM domain_record WHERE kind='FEDERATION_QUERY' AND updated_at<?", OperationsStore.now().minusHours(1));
        var previous = store.find("FEDERATION_QUERY", call);
        if (previous != null && previous.updatedAt().isAfter(Instant.now().minus(Duration.ofHours(1)))) return;
        store.saveInternal("FEDERATION_QUERY", call, "", store.mapper().createObjectNode());
        radio.message("DEFAULT", from, call, "?NCOT", event.received().instanceId());
    }

    @EventListener
    @Transactional
    public void receive(PacketHub.UniquePacket event) {
        var packet = event.received().packet();
        if (!store.enabled("federationEnabled") || policy.ignored(packet.source()) || local(packet.source())) return;
        String wire = packet.information();
        if (wire.length() < 11 || wire.charAt(0) != ':' || wire.charAt(10) != ':') return;
        String body = wire.substring(11).replaceFirst("\\{[A-Za-z0-9]{1,5}$", "").strip();
        if (!body.startsWith("NCOT:") && !body.startsWith("NCPI:") && !body.startsWith("NCGRI:")
                && !body.startsWith("NCNTI:") && !body.startsWith("NCGRV:") && !body.startsWith("NCNTP:")) return;
        store.lock();
        if (local(packet.source())) return;
        var old = store.find("OBSERVED_OBJECT", packet.source());
        var net = store.find("FEDERATED_NET", packet.source());
        if (old == null && net == null) return;
        ObjectNode data = old == null ? store.mapper().createObjectNode() : old.data().deepCopy();
        data.put("callsign", packet.source());data.put("remote", true);
        if (body.startsWith("NCOT:")) {
            String type = body.substring(5).strip();
            if (!Set.of("NET", "RESOURCE", "EOC", "SHELTER", "MEDICAL", "NETMGR", "STANDARD", "UNKNOWN").contains(type)) return;
            data.put("type", type);
            if (store.enabled("federationInterrogateEnabled") && !store.setting("federationCallsign").isBlank()
                    && (old == null || !old.data().path("type").asText().equals(type))) {
                String query = type.equals("NET") ? "?NCNTI" : type.equals("RESOURCE") ? "?NCGRI" : "?NCPI";
                radio.message("DEFAULT", store.setting("federationCallsign"), packet.source(), query, event.received().instanceId());
            }
        } else if (body.startsWith("NCGRV:")) {
            String[] pair = body.substring(6).split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) return;
            ObjectNode values = data.path("keyValues").isObject() ? (ObjectNode)data.path("keyValues") : data.putObject("keyValues");
            values.put(pair[0], pair[1]);
        } else if (!body.startsWith("NCNTP:")) {
            String[] info = body.substring(body.indexOf(':') + 1).split(",", 2);
            if (info.length != 2 || !info[0].equals(packet.source())) return;
            data.put("comment", info[1]);
        }
        data.set("types", store.mapper().valueToTree(policy.classifyObject(data)));
        store.saveInternal("OBSERVED_OBJECT", packet.source(), "", data);
        if (data.path("type").asText().equals("NET") || net != null) {
            ObjectNode summary = net == null ? store.mapper().createObjectNode() : net.data().deepCopy();
            summary.put("callsign", packet.source());summary.put("remote", true);
            if (data.has("comment")) summary.put("description", data.path("comment").asText());
            if (body.startsWith("NCNTP:")) {
                ObjectNode participants = summary.path("participants").isObject() ? (ObjectNode)summary.path("participants") : summary.putObject("participants");
                for (String call : body.substring(6).strip().split("\\s+"))
                    if (call.matches("[A-Z0-9]{1,6}(-[0-9]{1,2})?")) participants.putObject(call).put("callsign", call);
            }
            store.saveInternal("FEDERATED_NET", packet.source(), "", summary);
        }
    }
}
