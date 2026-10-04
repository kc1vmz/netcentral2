package com.kc1vmz.netcentral.accessor;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.kc1vmz.netcentral.account.LocalPrincipal;
import com.kc1vmz.netcentral.communication.PacketHub;
import com.kc1vmz.netcentral.operations.AprsSymbols;
import com.kc1vmz.netcentral.operations.OperationsStore;

/**
 * One repeatable-read projection. It never relies on a writer thread's security
 * context.
 */
@Service
public class LiveViewAccessor {
    public record Callsign(String callsign, String name, String country, String state, String license, String lastHeard) {}

    public record Snapshot(String epoch, long revision, Map<String, Object> counts,
            List<OperationsStore.Entry> resources, List<Callsign> callsigns, boolean callsignsLimited, List<String> ignoredCallsigns,
            List<com.kc1vmz.netcentral.net.NetModels.Net> nets, List<Map<String, Object>> schedules,
            List<OperationsStore.Entry> remoteNets, com.kc1vmz.netcentral.net.NetModels.MapSettings map,
            boolean administrator, boolean limited, int resourceLimit, boolean listsLimited) {
    }

    private final OperationsStore store;
    private final NetAccessor nets;
    private final PacketHub packets;
    private final String epoch = UUID.randomUUID().toString();
    private final AtomicLong revision = new AtomicLong();
    private final int limit;

    public LiveViewAccessor(OperationsStore store, NetAccessor nets, PacketHub packets,
            @Value("${netcentral.live-resource-limit:5000}") int limit) {
        this.store = store;
        this.nets = nets;
        this.packets = packets;
        if (limit < 1 || limit > 20000)
            throw new IllegalArgumentException("Live resource limit must be 1â€“20000");
        this.limit = limit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public synchronized Snapshot snapshot(LocalPrincipal principal) {
        if (principal == null || !store.accounts().isCurrent(principal))
            throw new AccessDeniedException("Session revoked");
        boolean admin = principal.getAuthorities().stream().anyMatch(a -> !a.getAuthority().equals("ROLE_USER"));
        var visible = nets.listForPrincipal(principal);
        Set<String> ignored = new HashSet<>(store.jdbc().queryForList(
                "SELECT record_key FROM domain_record WHERE kind='IGNORE'", String.class));
        var resources = store.jdbc().query(
                "SELECT * FROM domain_record WHERE kind IN ('STATION','OBJECT','OBSERVED_OBJECT') ORDER BY record_key,kind LIMIT ?",
                (r, n) -> new OperationsStore.Entry(r.getString("record_key"), r.getString("kind"),
                        r.getString("owner_id"), r.getLong("version"),
                        r.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
                        summary(r.getString("payload"), r.getString("kind"), r.getString("record_key"), ignored, r.getObject("updated_at", java.time.OffsetDateTime.class).toInstant().toString())),
                limit);
        var schedules = store.jdbc().queryForList(
                "SELECT id,callsign,name,type,timezone,next_start,enabled FROM net_schedule WHERE deleted=FALSE AND (private_net=FALSE OR owner_id=? OR ?) ORDER BY callsign,id LIMIT 2000",
                principal.getUsername(), admin);
        long total = store.jdbc().queryForObject(
                "SELECT COUNT(*) FROM domain_record WHERE kind IN ('STATION','OBJECT','OBSERVED_OBJECT')", Long.class);
        var counts = new LinkedHashMap<String, Object>();
        long active = visibleNetCount(principal, admin, false), archived = visibleNetCount(principal, admin, true);
        long scheduled = store.jdbc().queryForObject(
                "SELECT COUNT(*) FROM net_schedule WHERE deleted=FALSE AND (private_net=FALSE OR owner_id=? OR ?)",
                Long.class, principal.getUsername(), admin);
        counts.put("activeNets", active);
        counts.put("archives", archived);
        counts.put("stations", count("STATION"));
        counts.put("objects", count("OBJECT") + count("OBSERVED_OBJECT"));
        counts.put("schedules", scheduled);
        var callsigns = new TreeMap<String, com.fasterxml.jackson.databind.node.ObjectNode>();
        store.jdbc().query("SELECT record_key,payload FROM domain_record WHERE kind='STATION' ORDER BY updated_at DESC,record_key",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    var data = store.json(row.getString("payload"));
                    if (!data.hasNonNull("lastHeard")) return;
                    String root = row.getString("record_key").split("-")[0];
                    var merged = callsigns.computeIfAbsent(root, key -> store.mapper().createObjectNode());
                    String heard = data.path("lastHeard").asText();
                    if (!merged.has("lastHeard") || java.time.Instant.parse(heard).isAfter(java.time.Instant.parse(merged.path("lastHeard").asText())))
                        merged.put("lastHeard", heard);
                    for (String field : List.of("name", "country", "state", "license")) {
                        String value = data.path(field).asText("");
                        if (field.equals("license") && value.isBlank()) value = data.path("licenseClass").asText("");
                        if (!merged.has(field) && !value.isBlank()) merged.put(field, value);
                    }
                });
        counts.put("callsignsHeard", callsigns.size());
        var callsignList = callsigns.entrySet().stream().limit(limit).map(e -> new Callsign(e.getKey(),
                e.getValue().path("name").asText(null), e.getValue().path("country").asText(null),
                e.getValue().path("state").asText(null), e.getValue().path("license").asText(null), e.getValue().path("lastHeard").asText(null))).toList();
        counts.put("duplicates", packets.duplicates());
        counts.put("dropped", packets.dropped());
        var remote = store.jdbc().query(
                "SELECT * FROM domain_record WHERE kind='FEDERATED_NET' ORDER BY record_key LIMIT 2000",
                (r, n) -> new OperationsStore.Entry(r.getString("record_key"), r.getString("kind"), "",
                        r.getLong("version"), r.getObject("updated_at", java.time.OffsetDateTime.class).toInstant(),
                        ((com.fasterxml.jackson.databind.node.ObjectNode) store.json(r.getString("payload")))
                                .retain("callsign", "active")));
        return new Snapshot(epoch, revision.incrementAndGet(), counts, resources, callsignList, callsigns.size() > limit, ignored.stream().sorted().toList(), visible, schedules, remote,
                nets.map(), admin, total > limit, limit,
                active + archived > 2000 || scheduled > 2000 || count("FEDERATED_NET") > 2000);
    }

    private long visibleNetCount(LocalPrincipal principal, boolean admin, boolean archived) {
        return store.jdbc().queryForObject(
                "SELECT COUNT(*) FROM managed_net WHERE closed_at IS " + (archived ? "NOT " : "")
                        + "NULL AND (private_net=FALSE OR owner_id=? OR ?)",
                Long.class, principal.getUsername(), admin);
    }

    private com.fasterxml.jackson.databind.JsonNode summary(String json, String kind, String call, Set<String> ignored, String lastUpdated) {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) store.json(json);
        if (kind.equals("OBSERVED_OBJECT") && !data.hasNonNull("lastHeard")) data.put("lastHeard", lastUpdated);
        AprsSymbols.decorate(data);
        data.retain("callsign", "name", "description", "type", "types", "status", "comment", "lat", "lon", "symbol", "symbolDescription",
                "alive", "tracked", "stale", "lastHeard", "voiceFrequency");
        // Old observations may contain a directed packet. Never project its body into
        // public station details.
        if (data.path("comment").asText().startsWith(":"))
            data.remove("comment");
        if (kind.equals("STATION"))
            data.put("ignored", ignored.contains(call) || ignored.contains(call.split("-")[0]));
        return data;
    }

    private long count(String kind) {
        return store.jdbc().queryForObject("SELECT COUNT(*) FROM domain_record WHERE kind=?", Long.class, kind);
    }
}
