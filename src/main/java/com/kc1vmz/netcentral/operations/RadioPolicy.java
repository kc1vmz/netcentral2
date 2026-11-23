package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.accessor.NetAccessor;

import static com.kc1vmz.netcentral.operations.OperationsStore.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RadioPolicy {
    private final OperationsStore store;

    public RadioPolicy(OperationsStore store) {
        this.store = store;
    }

    public boolean ignored(String call) {
        return store.find("IGNORE", call) != null || store.find("IGNORE", root(call)) != null;
    }

    public static String root(String call) {
        return call.toUpperCase(Locale.ROOT).split("-")[0];
    }

    public List<OperationsStore.Entry> ignores() {
        store.authenticated();
        return store.list("IGNORE");
    }

    @Transactional
    public void ignore(String call, boolean ignored) {
        store.admin();
        store.lock();
        call = NetAccessor.callsign(call);
        if (ignored)
            store.saveInternal("IGNORE", call, store.accounts().current().id(), store.mapper().createObjectNode());
        else
            store.jdbc().update("DELETE FROM domain_record WHERE kind='IGNORE' AND record_key=?", call);
    }

    public List<OperationsStore.Entry> rules() {
        store.authenticated();
        return store.list("CLASSIFY").stream().sorted(Comparator
                .comparing((OperationsStore.Entry e) -> e.data().path("value").asText(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.data().path("ruleTarget").asText()).thenComparing(OperationsStore.Entry::id)).toList();
    }

    @Transactional
    public OperationsStore.Entry rule(String id, JsonNode input, Long version) {
        store.admin();
        store.lock();
        if (id == null)
            id = UUID.randomUUID().toString();
        else
            store.version(version, store.require("CLASSIFY", id));
        if (!Set.of("CONTAINS", "EQUALS", "STARTS_WITH", "ENDS_WITH").contains(input.path("ruleType").asText())
                || !Set.of("STATUS", "COMMENT", "CALLSIGN", "SYMBOL").contains(input.path("ruleTarget").asText())
                || !Set.of("UNKNOWN", "STATION", "OBJECT", "WEATHER", "REPEATER", "DIGIPEATER", "IGATE", "IS",
                        "WINLINK_GATEWAY", "DSTAR", "BBS", "MMDVM").contains(input.path("trackedStationType").asText()))
            throw bad("Invalid classification rule");
        ObjectNode data = input.deepCopy();
        data.put("value", field(input, "value", 120, true).toUpperCase(Locale.ROOT));
        var saved = store.saveInternal("CLASSIFY", id, store.accounts().current().id(), data);
        reclassify();
        return saved;
    }

    @Transactional
    public void removeRule(String id, long version) {
        store.admin();
        store.lock();
        store.version(version, store.require("CLASSIFY", id));
        store.jdbc().update("DELETE FROM domain_record WHERE kind='CLASSIFY' AND record_key=?", id);
        reclassify();
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void reclassify() {
        store.lock();
        var records = new java.util.ArrayList<>(store.list("STATION"));
        records.addAll(store.list("OBSERVED_OBJECT"));
        records.addAll(store.list("OBJECT"));
        for (var station : records) {
            ObjectNode data = station.data().deepCopy();
            if (!data.path("lat").isNumber() || !data.path("lon").isNumber()) {
                var position = PacketDecoder.coordinate(data.path("lat").asText(null), data.path("lon").asText(null));
                if (position == null && station.kind().equals("STATION"))
                    position = com.kc1vmz.netcentral.net.AprsPosition.parse(data.path("comment").asText(null));
                if (position != null) position.apply(data);
            }
            // LOOK HERE
            data.set("types", store.mapper().valueToTree(station.kind().equals("STATION") ? classify(data) : classifyObject(data)));
            if (data.equals(station.data()))
                continue;
            // Rule edits must not extend observation retention.
            store.jdbc().update(
                    "UPDATE domain_record SET payload=?,version=version+1 WHERE kind=? AND record_key=?",
                    store.encode(data), station.kind(), station.id());
        }
    }

    public Set<String> classifyObject(JsonNode object) {
        ObjectNode fields = object.deepCopy();
        // Object beacons carry descriptive text in the comment rather than a separate
        // station status packet. Apply STATUS rules to that text when no status exists.
        if (fields.path("status").asText().isBlank()) fields.put("status", fields.path("comment").asText());
        var types = classify(fields);
        types.remove("STATION");
        types.add("OBJECT");
        return types;
    }

    public Set<String> classify(JsonNode station) {
        Set<String> types = new TreeSet<>();
        types.add("STATION");
        if (station.path("weatherObserved").asBoolean()) {
            types.add("WEATHER");
        }
        for (var entry : store.list("CLASSIFY")) {
            JsonNode r = entry.data();
            String value = station.path(r.path("ruleTarget").asText().toLowerCase(Locale.ROOT)).asText("")
                    .toUpperCase(Locale.ROOT), expected = r.path("value").asText().toUpperCase(Locale.ROOT);
            boolean match = switch (r.path("ruleType").asText()) {
                case "CONTAINS" -> value.contains(expected);
                case "EQUALS" -> value.equals(expected);
                case "STARTS_WITH" -> value.startsWith(expected);
                case "ENDS_WITH" -> value.endsWith(expected);
                default -> false;
            };
            if (match)
                types.add(r.path("trackedStationType").asText());
        }
        return types;
    }

    public List<OperationsStore.Entry> acl(String target) {
        store.authenticated();
        return aclInternal(target);
    }

    private List<OperationsStore.Entry> aclInternal(String target) {
        return store.list("ACL").stream().filter(e -> e.data().path("callsignTarget").asText().equals(target)).toList();
    }

    @Transactional
    public OperationsStore.Entry acl(String target, JsonNode value, Long version) {
        store.admin();
        store.lock();
        target = NetAccessor.callsign(target);
        String type = field(value, "type", 30, true),
                checked = field(value, "callsignChecked", 9, true).toUpperCase(Locale.ROOT);
        if (!Set.of("STATION", "NET", "OBJECT", "WITHIN_PROXIMITY").contains(type))
            throw bad("Unknown access rule type");
        if (!checked.equals("*"))
            checked = NetAccessor.callsign(checked);
        if (type.equals("WITHIN_PROXIMITY")
                && (!value.path("proximity").canConvertToInt() || value.path("proximity").asInt() < 1))
            throw bad("Proximity must be positive feet");
        String id = target + ":" + type + ":" + checked;
        var old = store.find("ACL", id);
        if (old != null)
            store.version(version, old);
        ObjectNode data = value.deepCopy();
        data.put("callsignTarget", target);
        data.put("callsignChecked", checked);
        if (!data.path("allowed").isBoolean())
            throw bad("Specify allowed true/false");
        return store.saveInternal("ACL", id, store.accounts().current().id(), data);
    }

    @Transactional
    public void removeAcl(String target, String checked) {
        store.admin();
        store.lock();
        for (var e : aclInternal(target))
            if (e.data().path("callsignChecked").asText().equals(checked))
                store.jdbc().update("DELETE FROM domain_record WHERE kind='ACL' AND record_key=?", e.id());
    }

    public boolean permits(String target, String call, boolean defaultAllow) {
        if (ignored(call))
            return false;
        boolean allow = false, matched = false;
        for (var entry : aclInternal(target)) {
            JsonNode r = entry.data();
            String checked = r.path("callsignChecked").asText(), type = r.path("type").asText();
            boolean match = false;
            if (type.equals("STATION"))
                match = checked.equals("*") || root(checked).equals(root(call));
            else if (type.equals("NET"))
                match = store.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM net_participant p JOIN managed_net n ON n.id=p.net_id WHERE p.ended_at IS NULL AND n.closed_at IS NULL AND (n.callsign=? OR ?='*') AND (p.callsign=? OR p.callsign LIKE ?)",
                        Integer.class, checked, checked, root(call), root(call) + "-%") > 0;
            else if (type.equals("OBJECT"))
                match = store.find("OBJECT", checked) != null
                        && (checked.equals(call) || root(checked).equals(root(call)));
            else if (type.equals("WITHIN_PROXIMITY")) {
                var station = store.find("STATION", call);
                var object = store.find("OBJECT", target);
                if (station != null && object != null && station.data().hasNonNull("lat")
                        && object.data().hasNonNull("lat")) {
                    double a = station.data().path("lat").asDouble(), b = object.data().path("lat").asDouble();
                    double dl = Math.toRadians(a - b), dn = Math
                            .toRadians(station.data().path("lon").asDouble() - object.data().path("lon").asDouble());
                    double h = Math.pow(Math.sin(dl / 2), 2)
                            + Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(b)) * Math.pow(Math.sin(dn / 2), 2);
                    match = 2 * 6371000 * 3.28084 * Math.asin(Math.sqrt(Math.min(1, h))) <= r.path("proximity").asInt();
                }
            }
            if (match) {
                matched = true;
                if (!r.path("allowed").asBoolean())
                    return false;
                allow = true;
            }
        }
        return matched ? allow : defaultAllow && aclInternal(target).isEmpty();
    }
}
