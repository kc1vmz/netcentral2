package com.kc1vmz.netcentral.accessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.communication.*;
import com.kc1vmz.netcentral.operations.*;

import static com.kc1vmz.netcentral.operations.OperationsStore.*;

import java.time.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StationAccessor {
    private final OperationsStore store;
    private final RadioPolicy policy;

    public StationAccessor(OperationsStore store, RadioPolicy policy) {
        this.store = store;
        this.policy = policy;
    }

    public List<OperationsStore.Entry> list() {
        store.authenticated();
        return store.list("STATION");
    }

    public OperationsStore.Entry get(String call) {
        store.authenticated();
        return store.require("STATION", NetAccessor.callsign(call));
    }

    @Transactional
    public OperationsStore.Entry update(String call, JsonNode input, long version) {
        store.admin();
        store.lock();
        var old = get(call);
        store.version(version, old);
        ObjectNode data = old.data().deepCopy();
        for (String field : List.of("name", "description", "status", "voiceFrequency", "equipment", "licenseClass"))
            if (input.has(field))
                data.put(field, field(input, field, 200, false));
        if (input.has("tracked")) {
            if (!input.path("tracked").isBoolean())
                throw bad("tracked must be boolean");
            data.put("tracked", input.path("tracked").asBoolean());
        }
        return store.saveInternal("STATION", old.id(), "", data);
    }

    @Transactional
    public OperationsStore.Entry create(JsonNode input) {
        store.admin();
        store.lock();
        String call = NetAccessor.callsign(field(input, "callsign", 9, true));
        if (store.find("STATION", call) != null)
            throw bad("Station already exists");
        var data = store.mapper().createObjectNode().put("callsign", call).put("tracked", true);
        // LOOK HERE
        data.set("types", store.mapper().valueToTree(policy.classify(data)));
        return store.saveInternal("STATION", call, "", data);
    }

    @Transactional
    public void delete(String call) {
        store.admin();
        store.lock();
        call = NetAccessor.callsign(call);
        store.jdbc().update("DELETE FROM domain_record WHERE kind='STATION' AND record_key=?", call);
        store.jdbc().update("DELETE FROM station_position WHERE callsign=?", call);
        clearPosition(call);
    }

    @EventListener
    @Transactional
    @Order(-20)
    public void receive(PacketHub.UniquePacket event) {
        Packet p = event.received().packet();
        if (Set.of("WHO-15", "WHO-IS").contains(p.source())) {
            store.lock();
            receiveIdentity(p);
        }
        String call;
        // Exclude gateway challenge responses heard again through another radio.
        if (p.source().equals("WLNK-1") || p.information().startsWith(":WLNK-1"))
            return;
        try {
            call = NetAccessor.callsign(p.source());
        } catch (RuntimeException invalid) {
            return;
        }
        if (policy.ignored(call))
            return;
        store.lock();
        var old = store.find("STATION", call);
        ObjectNode data = old == null ? store.mapper().createObjectNode() : old.data().deepCopy();
        data.put("callsign", call);
        data.put("lastHeard", event.received().time().toString());
        data.put("destination", p.destination());
        data.put("stale", false);
        // Status, telemetry and other packets must not replace the last position comment.
        if (!p.information().isEmpty() && "!=/@".indexOf(p.information().charAt(0)) >= 0)
            data.put("comment", p.information().substring(0, Math.min(300, p.information().length())));
        if (p.information().startsWith(">"))
            data.put("status", p.information().substring(1));
        var decoded = PacketDecoder.decode(p);
        boolean objectPacket = decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSObject
                || decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSItem;
        var position = objectPacket ? null : PacketDecoder.position(p);
        if (position != null) {
            position.apply(data);
            data.put("positionTime", event.received().time().toString());
        }
        if (decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSWeatherReport)
            data.put("weatherObserved", true);
        if (decoded != null && !objectPacket) {
            JsonNode properties = store.mapper().valueToTree(decoded);
            if (properties.hasNonNull("symbolTableId") && properties.hasNonNull("symbolTableCode"))
                data.put("symbol",
                    properties.path("symbolTableId").asText("") + properties.path("symbolTableCode").asText(""));
            if (properties.hasNonNull("comment"))
                data.put("comment", properties.path("comment").asText());
            if (properties.hasNonNull("status"))
                data.put("status", properties.path("status").asText());
        }
        // LOOK HERE
        data.set("types", store.mapper().valueToTree(policy.classify(data)));
        store.saveInternal("STATION", call, "", data);
    }

    private void receiveIdentity(Packet packet) {
        if (policy.ignored(packet.source())) return;
        String wire = packet.information();
        if (wire.length() < 12 || wire.charAt(0) != ':' || wire.charAt(10) != ':') return;
        String message = wire.substring(11).replaceFirst("\\{[A-Za-z0-9]{1,5}$", "");
        int separator = message.indexOf(':');
        if (separator < 1) return;
        String target;
        try { target = NetAccessor.callsign(message.substring(0, separator).strip()).split("-")[0]; }
        catch (RuntimeException invalid) { return; }
        if (policy.ignored(target)) return;
        String[] parts = message.substring(separator + 1).split("/", -1);
        String[] fields = switch (parts.length) {
            case 4 -> new String[]{"license", "name", "state", "country"};
            case 3 -> new String[]{"name", "state", "country"};
            case 2 -> new String[]{"name", "country"};
            default -> null;
        };
        if (fields == null || message.contains("{") || message.contains("}")) return;
        for (var station : store.list("STATION")) {
            if (!RadioPolicy.root(station.id()).equals(target) || policy.ignored(station.id())) continue;
            ObjectNode data = station.data().deepCopy();
            for (int i = 0; i < fields.length; i++) {
                String value = parts[i].strip();
                if (!value.isEmpty() && data.path(fields[i]).asText("").isBlank()
                        && !(fields[i].equals("license") && !data.path("licenseClass").asText("").isBlank()))
                    data.put(fields[i], value);
            }
            if (!data.equals(station.data()))
                // Identity lookup is not a new observation of the referenced station.
                store.jdbc().update("UPDATE domain_record SET payload=?,version=version+1 WHERE kind='STATION' AND record_key=?",
                        store.encode(data), station.id());
        }
    }

    @EventListener
    @Transactional
    public void observation(PacketHub.Received event) {
        if (!store.enabled("rawPacketLogging"))
            return;
        Packet p = event.packet();
        // Gateway login challenges/responses and message bodies must not enter durable
        // packet exports.
        if (p.source().equals("WLNK-1") || p.information().startsWith(":WLNK-1"))
            return;
        String raw = p.source() + ">" + p.destination() + (p.path().isEmpty() ? "" : "," + String.join(",", p.path()))
                + ":" + p.information();
        store.jdbc().update("INSERT INTO raw_packet(instance_id,source,packet,received_at) VALUES(?,?,?,?)",
                event.instanceId(), p.source(), raw, event.time().atOffset(ZoneOffset.UTC));
    }

    public List<Map<String, Object>> raw(Instant since, long after, int limit) {
        store.admin();
        return store.jdbc().queryForList("SELECT * FROM raw_packet WHERE received_at>=? AND id>? ORDER BY id LIMIT ?",
                since.atOffset(ZoneOffset.UTC), after, Math.clamp(limit, 1, 10000));
    }

    @Transactional
    public void cleanup() {
        store.lock();
        var cutoff = now().minusSeconds(store.seconds("stationRetentionSeconds"));
        var expired = store.jdbc().queryForList(
                "SELECT record_key FROM domain_record WHERE kind='STATION' AND updated_at<?", String.class, cutoff);
        for (String call : expired) {
            var entry = store.find("STATION", call);
            if (entry.data().path("tracked").asBoolean()) {
                ObjectNode data = entry.data().deepCopy();
                data.remove(List.of("lat", "lon", "positionTime"));
                data.put("stale", true);
                store.saveInternal("STATION", call, "", data);
            } else
                store.jdbc().update("DELETE FROM domain_record WHERE kind='STATION' AND record_key=?", call);
            store.jdbc().update("DELETE FROM station_position WHERE callsign=?", call);
            clearPosition(call);
        }
        store.jdbc().update("DELETE FROM raw_packet WHERE received_at<?",
                now().minusSeconds(store.seconds("packetRetentionSeconds")));
        store.jdbc().update("DELETE FROM domain_record WHERE kind='OBSERVED_OBJECT' AND updated_at<?", cutoff);
        long excess = store.jdbc().queryForObject("SELECT COUNT(*) FROM raw_packet", Long.class)
                - store.seconds("packetLimit");
        if (excess > 0)
            store.jdbc().update("DELETE FROM raw_packet WHERE id IN (SELECT id FROM raw_packet ORDER BY id LIMIT ?)",
                    excess);
    }

    @Transactional
    public void clearRaw() {
        store.admin();
        store.jdbc().update("DELETE FROM raw_packet");
    }

    private void clearPosition(String call) {
        store.jdbc().update(
                "UPDATE managed_net SET version=version+1 WHERE closed_at IS NULL AND id IN (SELECT net_id FROM net_participant WHERE callsign=? AND ended_at IS NULL)",
                call);
        store.jdbc().update(
                "UPDATE net_participant SET lat=NULL,lon=NULL,version=version+1 WHERE callsign=? AND ended_at IS NULL AND net_id IN (SELECT id FROM managed_net WHERE closed_at IS NULL)",
                call);
    }
}
