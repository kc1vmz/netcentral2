package com.kc1vmz.netcentral.accessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.communication.*;
import com.kc1vmz.netcentral.operations.*;

import static com.kc1vmz.netcentral.operations.OperationsStore.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceAccessor {
    private final OperationsStore store;
    private final RoutingService routing;
    private final RadioDispatch radio;
    private final RadioPolicy policy;
    private final FederationDiscovery discovery;

    public ResourceAccessor(OperationsStore store, RoutingService routing, RadioDispatch radio, RadioPolicy policy, FederationDiscovery discovery) {
        this.store = store;
        this.routing = routing;
        this.radio = radio;
        this.policy = policy;
        this.discovery = discovery;
    }

    public List<OperationsStore.Entry> list() {
        store.authenticated();
        var all = new ArrayList<>(store.list("OBJECT"));
        all.addAll(store.list("OBSERVED_OBJECT"));
        return all;
    }

    public OperationsStore.Entry get(String id) {
        store.authenticated();
        var e = store.find("OBJECT", id);
        return e == null ? store.require("OBSERVED_OBJECT", id) : e;
    }

    public RoutingService.Route route(String id) {
        get(id);
        return routing.load("OBJECT:" + id);
    }

    @Transactional
    public OperationsStore.Entry save(String id, JsonNode input, Long version) {
        store.authenticated();
        store.lock();
        boolean creating = id == null;
        String name = NetAccessor.callsign(field(input, "callsign", 9, true));
        if (store.jdbc().queryForObject("SELECT COUNT(*) FROM managed_net WHERE active_callsign=?", Integer.class,
                name) > 0)
            throw bad("Callsign belongs to an active net");
        if (creating) {
            id = name;
            if (store.find("OBJECT", id) != null)
                throw bad("Object callsign already exists");
        } else {
            var old = store.require("OBJECT", id);
            store.manage(old);
            store.version(version, old);
            if (!id.equals(name))
                throw bad("Object callsign cannot change");
        }
        if (!input.path("lat").isNumber() || !input.path("lon").isNumber()
                || !Double.isFinite(input.path("lat").asDouble()) || !Double.isFinite(input.path("lon").asDouble())
                || Math.abs(input.path("lat").asDouble()) > 90 || Math.abs(input.path("lon").asDouble()) > 180)
            throw bad("Valid decimal latitude and longitude required");
        String type = field(input, "type", 30, true);
        if (!Set.of("RESOURCE", "SHELTER", "EOC", "MEDICAL", "NETMGR", "OBJECT").contains(type))
            throw bad("Unknown object type");
        ObjectNode data = store.mapper().createObjectNode();
        data.put("callsign", name);
        data.put("type", type);
        data.put("lat", input.path("lat").asDouble());
        data.put("lon", input.path("lon").asDouble());
        data.put("comment", field(input, "comment", 80, false));
        data.put("alive", !input.has("alive") || input.path("alive").asBoolean());
        String table = input.path("symbolTableId").asText("/"), code = input.path("symbolTableCode").asText(";");
        if (!table.matches("[/\\\\A-Z0-9]") || code.length() != 1 || code.charAt(0) < 33 || code.charAt(0) > 126)
            throw bad("Invalid APRS symbol");
        data.put("symbolTableId", table);
        data.put("symbolTableCode", code);
        var old = store.find("OBJECT", id);
        if (old != null && old.data().has("keyValues"))
            data.set("keyValues", old.data().get("keyValues"));
        data.set("types", store.mapper().valueToTree(policy.classifyObject(data)));
        var saved = store.saveInternal("OBJECT", id, creating ? store.accounts().current().id() : old.ownerId(), data);
        if (creating || input.has("communicationMode")) {
            var defaults = routing.load("DEFAULT");
            Set<String> selected = new HashSet<>();
            input.path("communicationInstanceIds").forEach(v -> selected.add(v.asText()));
            routing.saveInternal("OBJECT:" + id,
                    new RoutingService.Route("OBJECT:" + id, creating ? -1 : input.path("routingVersion").asLong(-2),
                            input.path("communicationMode").asText(defaults.mode()),
                            input.has("communicationMode") ? selected : defaults.instanceIds()));
        }
        store.jdbc().update("DELETE FROM domain_record WHERE kind='OBSERVED_OBJECT' AND record_key=?", id);
        beacon(saved);
        store.activity(store.accounts().current().id(), "Saved object " + id);
        return saved;
    }

    @Transactional
    public void delete(String id, long version) {
        store.lock();
        var old = store.require("OBJECT", id);
        store.manage(old);
        store.version(version, old);
        ObjectNode data = old.data().deepCopy();
        data.put("alive", false);
        boolean manager = old.data().path("configuredNetManager").asBoolean()
                || (old.data().path("type").asText().equals("NETMGR") && id.equals(store.setting("netManagerCallsign")));
        radio.withdraw("OBJECT:" + id, packet(new OperationsStore.Entry(id,"OBJECT",old.ownerId(),old.version(),old.updatedAt(),data)));
        store.jdbc().update("DELETE FROM resource_history WHERE object_id=?",id);
        store.jdbc().update("DELETE FROM operational_report WHERE callsign=?",id);
        for (var rule : store.list("ACL"))
            if (rule.data().path("callsignTarget").asText().equals(id))
                store.jdbc().update("DELETE FROM domain_record WHERE kind='ACL' AND record_key=?",rule.id());
        store.jdbc().update("DELETE FROM communication_route WHERE owner_key=?","OBJECT:"+id);
        store.jdbc().update("DELETE FROM domain_record WHERE kind IN ('OBJECT','OBSERVED_OBJECT','FEDERATION_QUERY') AND record_key=?",id);
        if (manager) {
            // Do not emit SettingsChanged: its object synchronizer would issue another withdrawal.
            store.jdbc().update("UPDATE application_settings SET setting_value='false' WHERE setting_key='operations.netManagerEnabled'");
        }
        store.activity(store.accounts().current().id(),"Withdrew and deleted object " + id);
    }

    @Transactional
    public OperationsStore.Entry values(String id, Map<String, String> values, long version) {
        store.lock();
        var old = store.require("OBJECT", id);
        store.manage(old);
        store.version(version, old);
        return valuesInternal(old, values);
    }

    public OperationsStore.Entry valuesInternal(OperationsStore.Entry old, Map<String, String> values) {
        if (values.size() > 100)
            throw bad("At most 100 resource values");
        ObjectNode data = old.data().deepCopy(), fields = store.mapper().createObjectNode();
        values.forEach((k, v) -> {
            String key = text(k, 80, true), value = text(v, 1000, false);
            fields.put(key, value);
            if (!old.data().path("keyValues").path(key).asText("").equals(value))
                store.jdbc().update(
                        "INSERT INTO resource_history(object_id,field_name,field_value,recorded_at) VALUES(?,?,?,?)",
                        old.id(), key, value, now());
        });
        old.data().path("keyValues").fieldNames().forEachRemaining(k -> {
            if (!fields.has(k))
                store.jdbc().update(
                        "INSERT INTO resource_history(object_id,field_name,field_value,recorded_at) VALUES(?,?,?,?)",
                        old.id(), k, "[removed]", now());
        });
        data.set("keyValues", fields);
        return store.saveInternal("OBJECT", old.id(), old.ownerId(), data);
    }

    public List<Map<String, Object>> history(String id) {
        get(id);
        return store.jdbc().queryForList("SELECT * FROM resource_history WHERE object_id=? ORDER BY id", id);
    }

    public Packet packet(OperationsStore.Entry object) {
        JsonNode d = object.data();
        String stamp = DateTimeFormatter.ofPattern("ddHHmm'z'").withZone(ZoneOffset.UTC).format(Instant.now());
        String position = coordinate(d.path("lat").asDouble(), true) + d.path("symbolTableId").asText()
                + coordinate(d.path("lon").asDouble(), false) + d.path("symbolTableCode").asText();
        return new Packet(object.id(), "APANC1", List.of(), ";" + String.format("%-9s", object.id())
                + (d.path("alive").asBoolean() ? "*" : "_") + stamp + position + d.path("comment").asText());
    }

    public static String coordinate(double v, boolean latitude) {
        long hundredths = Math.round(Math.abs(v) * 6000);
        long deg = hundredths / 6000;
        double minutes = (hundredths % 6000) / 100.0;
        return String.format(Locale.ROOT, latitude ? "%02d%05.2f%c" : "%03d%05.2f%c", deg, minutes,
                latitude ? (v < 0 ? 'S' : 'N') : (v < 0 ? 'W' : 'E'));
    }

    public void beacon(OperationsStore.Entry object) {
        radio.broadcast("OBJECT:" + object.id(), packet(object), object.version());
    }

    @Transactional
    public void beacons() {
        store.lock();
        for (var object : store.list("OBJECT"))
            if (object.data().path("alive").asBoolean()) {
                beacon(object);
                if (store.enabled("federationEnabled") && !object.data().path("type").asText().equals("OBJECT")) {
                    var type = com.kc1vmz.netcentral.aprsobject.enums.ObjectType
                            .valueOf(object.data().path("type").asText());
                    var report = new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralObjectAnnounceReport(
                            object.id(), switch (type) { case EOC -> "EO"; case SHELTER -> "SH"; case MEDICAL -> "MC"; case RESOURCE -> "GN"; case NETMGR -> "NM"; default -> "UK"; }, object.id(),
                            object.data().path("comment").asText().replace(':', ' '), type);
                    radio.federate("OBJECT:" + object.id(), report, object.version());
                }
            }
    }

    @EventListener
    @Transactional
    public void receive(PacketHub.UniquePacket event) {
        var p = event.received().packet();
        if (policy.ignored(p.source()))
            return;
        var decoded = PacketDecoder.decode(p);
        if (decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSObject
                || decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSItem) {
            String id = decoded.getCallsignTo();
            if (id == null || id.isBlank())
                return;
            store.lock();
            if (store.find("OBJECT", id) != null)
                return;
            var existing = store.find("OBSERVED_OBJECT", id);
            ObjectNode data = store.mapper().valueToTree(decoded);
            if (existing != null) {
                for (String field : List.of("type", "keyValues"))
                    if (existing.data().has(field)) data.set(field, existing.data().get(field));
            }
            data.remove(List.of("header", "data"));
            data.put("callsign", id);
            data.put("remote", true);
            data.put("lastHeard", event.received().time().toString());
            var pos = PacketDecoder.coordinate(data.path("lat").asText(null), data.path("lon").asText(null));
            if (pos != null) {
                pos.apply(data);
            }
            // LOOK HERE
            data.set("types", store.mapper().valueToTree(policy.classifyObject(data)));
            store.saveInternal("OBSERVED_OBJECT", id, "", data);
            discovery.observed(id, data.path("alive").asBoolean(true), event);
        }
        String info = p.information();
        if (info.length() < 11 || info.charAt(0) != ':' || info.charAt(10) != ':')
            return;
        String target = info.substring(1, 10).strip();
        var object = store.find("OBJECT", target);
        if (object == null || !object.data().path("alive").asBoolean()
                || !routing.permits("OBJECT:" + target, event.received().instanceId()))
            return;
        String raw = info.substring(11);
        String message = raw.split("\\{", 2)[0].strip();
        if (object.data().path("type").asText().equals("NETMGR") && !message.startsWith("?")) return;
        if (p.source().equalsIgnoreCase(target) || message.matches("(?i)(ack|rej)[A-Za-z0-9]{1,5}"))
            return;
        var number = java.util.regex.Pattern.compile("\\{([A-Za-z0-9]{1,5})$").matcher(raw);
        if (number.find())
            reply(target, p.source(), "ack" + number.group(1), event);
        if (message.isEmpty()) return;
        String query = message.toUpperCase(Locale.ROOT);
        String type = object.data().path("type").asText();
        if (message.startsWith("?")) {
            String commands = type.equals("NETMGR") ? "CMD:?+(CMD,INFO,APRSP,NCOT,H,L,C,CP,S)" : type.equals("RESOURCE")
                    ? "CMD:?+(CMD,INFO,APRSP,NCOT,NCGRI,NCGRV)"
                    : type.equals("EOC") ? "CMD:?+(CMD,INFO,APRSP,NCOT,NCPI,NCPES,NCPEM)"
                    : type.equals("SHELTER") ? "CMD:?+(CMD,INFO,APRSP,NCOT,NCPI,NCPSS,NCPSC,NCPSF,NCPSM,NCPSW)"
                    : "CMD:?+(CMD,INFO,APRSP,NCOT,NCPI)";
            switch (query) {
                case "?CMD" -> reply(target, p.source(), commands, event);
                case "?INFO" -> {
                    reply(target, p.source(), commands, event);
                    reply(target, p.source(), (type.equals("RESOURCE") ? "NCGRI:" : "NCPI:")
                            + target + "," + object.data().path("comment").asText(), event);
                }
                case "?NCOT" -> reply(target, p.source(), "NCOT:" + type, event);
                case "?NCGRI", "?NCPI" -> reply(target, p.source(), query.substring(1) + ":" + target
                        + "," + object.data().path("comment").asText(), event);
                case "?APRSP", "?APRSO" -> beacon(object);
                case "?NCGRV", "?NGTV" -> valuesReply(object, p.source(), "NCGRV:", event);
                default -> {
                    String kind = switch (query) {
                        case "?NCPES" -> "contacts";
                        case "?NCPEM" -> "mobilizations";
                        case "?NCPSS" -> "status";
                        case "?NCPSC" -> "census";
                        case "?NCPSF" -> "operationalFoods";
                        case "?NCPSM" -> "operationalMateriels";
                        case "?NCPSW" -> "workers";
                        default -> null;
                    };
                    boolean eoc = Set.of("contacts", "mobilizations").contains(kind == null ? "" : kind);
                    if (kind != null && (eoc ? type.equals("EOC") : type.equals("SHELTER")))
                        reportReply(target, p.source(), kind, query.substring(1), event);
                    else reply(target, p.source(), "Bad or unauthorized message.", event);
                }
            }
        } else if (query.equals("R")) {
            valuesReply(object, p.source(), "", event);
        } else if (message.contains("=")) {
            // Legacy general resources are writable when no object security rules exist.
            // Once rules exist, only matching allowed stations may update values.
            if (!policy.permits(target, p.source(), type.equals("RESOURCE"))) {
                reply(target, p.source(), "Bad or unauthorized message.", event);
                return;
            }
            store.lock();
            object = store.require("OBJECT", target);
            if (!object.data().path("alive").asBoolean() || store.duplicateRadio(target, p.source(), raw)) return;
            String[] pair = message.split("=", 2);
            if (pair[0].isBlank()) {
                reply(target, p.source(), "Bad or unauthorized message.", event);
                return;
            }
            Map<String, String> values = new TreeMap<>();
            object.data().path("keyValues").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText()));
            if (pair[1].isBlank()) values.remove(pair[0].strip());
            else values.put(pair[0].strip(), pair[1].strip());
            valuesInternal(object, values);
            reply(target, p.source(), "Update message accepted", event);
        } else if (type.equals("RESOURCE")) {
            var value = object.data().path("keyValues").get(message);
            reply(target, p.source(), value == null ? message + " not found" : message + "=" + value.asText(), event);
        }
        // Priority report submissions are processed by ReportAccessor.
    }

    private void reply(String target, String to, String text, PacketHub.UniquePacket event) {
        radio.message("OBJECT:" + target, target, to, text, event.received().instanceId());
    }

    private void valuesReply(OperationsStore.Entry object, String to, String prefix, PacketHub.UniquePacket event) {
        var values = object.data().path("keyValues");
        if (values.isEmpty()) reply(object.id(), to, "No values found", event);
        else values.fields().forEachRemaining(e -> reply(object.id(), to, prefix + e.getKey() + "=" + e.getValue().asText(), event));
    }

    private void reportReply(String target, String to, String kind, String query, PacketHub.UniquePacket event) {
        var reports = store.jdbc().query("SELECT payload FROM operational_report WHERE callsign=? AND kind=? ORDER BY recorded_at DESC,id",
                (r, n) -> store.json(r.getString(1)), target, kind);
        Set<Integer> sent = new HashSet<>();
        boolean grouped = Set.of("workers", "operationalFoods", "operationalMateriels").contains(kind);
        for (var report : reports) {
            int group = grouped ? report.path(kind.equals("workers") ? "shift" : "infoType").asInt() : 0;
            if (grouped && (group < 1 || group > 3)) continue;
            if (!sent.add(group)) continue;
            String prefix = !grouped ? target + ":" : query + ":" + (kind.equals("workers")
                    ? Integer.toString(group) : List.of("", "OnHand", "Req", "Used").get(group)) + ":";
            reply(target, to, prefix + report.path("reportData").asText(), event);
            if (!grouped) break;
        }
        if (sent.isEmpty()) reply(target, to, "No report for " + target, event);
    }
}
