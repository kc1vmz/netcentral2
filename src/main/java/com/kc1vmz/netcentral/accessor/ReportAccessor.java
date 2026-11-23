package com.kc1vmz.netcentral.accessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.aprsobject.object.reports.*;
import com.kc1vmz.netcentral.communication.*;
import com.kc1vmz.netcentral.operations.*;

import static com.kc1vmz.netcentral.operations.OperationsStore.*;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportAccessor {
    public record Report(String id, String callsign, String kind, JsonNode data, Instant recordedAt, boolean remote) {
    }

    private static final Map<String, Class<? extends APRSNetCentralReport>> TYPES = Map.ofEntries(
            Map.entry("status", APRSNetCentralShelterStatusReport.class),
            Map.entry("census", APRSNetCentralShelterCensusReport.class),
            Map.entry("workers", APRSNetCentralShelterWorkerReport.class),
            Map.entry("operationalFoods", APRSNetCentralShelterOperationalFoodReport.class),
            Map.entry("operationalMateriels", APRSNetCentralShelterOperationalMaterielReport.class),
            Map.entry("contacts", APRSNetCentralEOCContactReport.class),
            Map.entry("mobilizations", APRSNetCentralEOCMobilizationReport.class),
            Map.entry("netAnnounce", APRSNetCentralNetAnnounceReport.class),
            Map.entry("netStart", APRSNetCentralNetStartReport.class),
            Map.entry("netSecure", APRSNetCentralNetSecureReport.class),
            Map.entry("netCheckInOut", APRSNetCentralNetCheckInOutReport.class),
            Map.entry("netMessage", APRSNetCentralNetMessageReport.class),
            Map.entry("netQuestion", APRSNetCentralNetQuestionReport.class),
            Map.entry("netAnswer", APRSNetCentralNetQuestionAnswerReport.class),
            Map.entry("objectAnnounce", APRSNetCentralObjectAnnounceReport.class));
    private final OperationsStore store;
    private final RadioDispatch radio;
    private final RadioPolicy policy;
    private final RoutingService routing;

    public ReportAccessor(OperationsStore store, RadioDispatch radio, RadioPolicy policy, RoutingService routing) {
        this.store = store;
        this.radio = radio;
        this.policy = policy;
        this.routing = routing;
    }

    public List<Report> reports(String call, String kind) {
        store.authenticated();
        return store.jdbc().query(
                "SELECT * FROM operational_report WHERE callsign=? AND (?='all' OR kind=?) ORDER BY recorded_at DESC,id",
                (r, n) -> new Report(r.getString("id"), r.getString("callsign"), r.getString("kind"),
                        store.json(r.getString("payload")),
                        r.getObject("recorded_at", OffsetDateTime.class).toInstant(), r.getBoolean("remote")),
                call, kind, kind);
    }

    public Report latest(String call, String kind) {
        return reports(call, kind).stream().findFirst().orElse(null);
    }

    public List<Report> filtered(String call, String kind, String date, Integer shift, Integer timeframe) {
        if (date != null) {
            try {
                java.time.LocalDate.parse(date);
            } catch (java.time.DateTimeException invalid) {
                throw bad("Use reportDate YYYY-MM-DD");
            }
        }
        return reports(call, kind).stream()
                .filter(r -> date == null || r.data().path("dateReported")
                        .asText(r.data().path("lastReportedTime").asText()).startsWith(date))
                .filter(r -> shift == null || r.data().path("shift").asInt(-1) == shift)
                .filter(r -> timeframe == null || r.data().path("infoType").asInt(-1) == timeframe).toList();
    }

    public List<Report> weather() {
        store.authenticated();
        return store.jdbc()
                .queryForList("SELECT DISTINCT callsign FROM operational_report WHERE kind='weather'", String.class)
                .stream().map(c -> latest(c, "weather")).toList();
    }

    @Transactional
    public Report submit(String call, String kind, JsonNode input) {
        store.lock();
        var object = store.require("OBJECT", call);
        store.manage(object);
        if (!Set.of("status", "census", "workers", "operationalFoods", "operationalMateriels", "contacts",
                "mobilizations").contains(kind))
            throw bad("Unknown operational report");
        var report = construct(call, kind, input);
        Report saved = record(call, kind, store.mapper().valueToTree(report), report.getFullReportData(), false);
        if (store.enabled("federationEnabled"))
            radio.federate("OBJECT:" + call, report, object.version());
        return saved;
    }

    private APRSNetCentralReport construct(String call, String kind, JsonNode input) {
        try {
            Constructor<?> constructor = Arrays.stream(TYPES.get(kind).getConstructors())
                    .filter(c -> c.getParameterCount() > 0).findFirst().orElseThrow();
            Parameter[] parameters = constructor.getParameters();
            Object[] values = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                var p = parameters[i];
                JsonNode value = input.path(p.getName());
                if (p.getName().equals("objectName"))
                    values[i] = call;
                else if (p.getType() == ZonedDateTime.class)
                    values[i] = value.isMissingNode() ? ZonedDateTime.now(ZoneOffset.UTC)
                            : ZonedDateTime.parse(value.asText());
                else if (p.getType() == int.class) {
                    int max = p.getName().equals("level") ? 5
                            : kind.equals("mobilizations") && p.getName().equals("status") ? 4
                                    : Set.of("state", "status").contains(p.getName()) ? 2
                                            : Set.of("shift", "infoType").contains(p.getName()) ? 3 : 99999;
                    int min = 0; // Legacy report code zero explicitly represents unknown.
                    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min
                            || value.asInt() > max)
                        throw bad("Invalid count or code: " + p.getName());
                    values[i] = value.asInt();
                } else if (p.getType() == String.class) {
                    String text = field(input, p.getName(), kind.equals("contacts") ? 25
                            : kind.equals("status") ? 40 : kind.equals("mobilizations") ? 30 : 200, false);
                    if (text.chars().anyMatch(c -> c > 126 || c < 32) || text.contains(":"))
                        throw bad("Report text must be printable ASCII without colon separators");
                    values[i] = text;
                } else
                    throw bad("Unsupported report field");
            }
            var report = (APRSNetCentralReport) constructor.newInstance(values);
            if (report.getFullReportData().length() > 253)
                throw bad("Report too long for APRS");
            return report;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception invalid) {
            throw bad("Invalid report fields");
        }
    }

    public record Field(String name, String label, String type) {
    }

    public List<Field> fields(String kind) {
        if (!Set.of("status", "census", "workers", "operationalFoods", "operationalMateriels", "contacts",
                "mobilizations").contains(kind))
            throw bad("Unknown report type");
        var constructor = Arrays.stream(TYPES.get(kind).getConstructors()).filter(c -> c.getParameterCount() > 0)
                .findFirst().orElseThrow();
        return Arrays.stream(constructor.getParameters()).filter(p -> !p.getName().equals("objectName"))
                .map(p -> new Field(p.getName(), p.getName().replaceAll("([a-z])([A-Z])", "$1 $2"),
                        p.getType() == int.class ? "number" : p.getType() == ZonedDateTime.class ? "datetime" : "text"))
                .toList();
    }

    private Report record(String call, String kind, JsonNode data, String wire, boolean remote) {
        String id = UUID.randomUUID().toString();
        var at = now();
        store.jdbc().update("INSERT INTO operational_report VALUES(?,?,?,?,?,?,?)", id, call, kind, store.encode(data),
                wire, at, remote);
        return new Report(id, call, kind, data, at.toInstant(), remote);
    }

    @Transactional
    public void delete(String call) {
        store.admin();
        store.jdbc().update("DELETE FROM operational_report WHERE callsign=?", call);
    }

    @EventListener
    @Transactional
    public void receive(PacketHub.UniquePacket event) {
        var p = event.received().packet();
        if (policy.ignored(p.source()))
            return;
        var decoded = PacketDecoder.decode(p);
        if (decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSWeatherReport weather) {
            ObjectNode data = store.mapper().valueToTree(weather);
            data.remove(List.of("header", "data"));
            record(p.source(), "weather", data, p.information(), true);
        }
        String wire = p.information();
        boolean messageReport = wire.length() > 11 && wire.charAt(0) == ':' && wire.charAt(10) == ':'
                && wire.substring(1, 10).strip().equalsIgnoreCase(p.source());
        boolean federated = wire.startsWith("{ZN") || messageReport;
        String target = p.source();
        OperationsStore.Entry managed = null;
        if (federated) {
            if (!store.enabled("federationEnabled"))
                return;
            wire = messageReport ? wire.substring(11).replaceFirst("\\{[A-Za-z0-9]{1,5}$", "") : wire.substring(3);
        } else if (wire.startsWith(":") && wire.length() > 11 && wire.charAt(10) == ':') {
            target = wire.substring(1, 10).strip();
            wire = wire.substring(11).split("\\{", 2)[0];
            var object = store.find("OBJECT", target);
            if (object == null || !object.data().path("alive").asBoolean()
                    || !routing.permits("OBJECT:" + target, event.received().instanceId()))
                return;
            managed = object;
            if (p.source().equalsIgnoreCase(target) || wire.startsWith("?") || wire.equalsIgnoreCase("R")
                    || wire.contains("=") || wire.matches("(?i)(ack|rej)[A-Za-z0-9]{1,5}")
                    || Set.of("RESOURCE", "NETMGR").contains(object.data().path("type").asText())) return;
            if (!policy.permits(target, p.source(), false)) {
                radio.message("OBJECT:" + target, target, p.source(), "Bad or unauthorized message.", event.received().instanceId());
                return;
            }
            wire = wire.strip();
        } else
            return;
        if (wire.length() < 4 || wire.length() > 500)
            return;
        if (federated && (store.find("OBJECT", target) != null || store.jdbc()
                .queryForObject("SELECT COUNT(*) FROM managed_net WHERE active_callsign=?", Integer.class, target) > 0))
            return;
        for (var type : TYPES.entrySet()) {
            if (!federated) {
                String objectType = managed.data().path("type").asText();
                boolean allowed = objectType.equals("EOC")
                        ? Set.of("contacts", "mobilizations").contains(type.getKey())
                        : objectType.equals("SHELTER") && Set.of("status", "census", "workers", "operationalFoods", "operationalMateriels").contains(type.getKey());
                if (!allowed) continue;
            }
            APRSNetCentralReport report;
            try {
                report = (APRSNetCentralReport) type.getValue().getMethod("isValid", String.class, String.class)
                        .invoke(null, target, wire);
            } catch (Exception invalid) {
                continue;
            }
            if (report == null)
                continue;
            store.lock();
            if (federated && (store.find("OBJECT", target) != null
                    || store.jdbc().queryForObject("SELECT COUNT(*) FROM managed_net WHERE active_callsign=?",
                            Integer.class, target) > 0))
                return;
            if (!federated && store.duplicateRadio(target, p.source(), p.information().substring(11)))
                return;
            JsonNode data = store.mapper().valueToTree(report);
            // Both federation encodings may carry the same report; ingest it once.
            if (federated && store.jdbc().queryForObject("SELECT COUNT(*) FROM operational_report WHERE callsign=? AND kind=? AND wire=? AND recorded_at>?",
                    Integer.class, target, type.getKey(), wire, now().minusMinutes(2)) > 0) return;
            record(target, type.getKey(), data, wire, federated);
            if (type.getKey().startsWith("net")) {
                var old = store.find("FEDERATED_NET", target);
                ObjectNode summary = old == null ? store.mapper().createObjectNode() : old.data().deepCopy();
                summary.put("callsign", target);
                summary.put("remote", true);
                summary.set(type.getKey(), data);
                if (type.getKey().equals("netSecure"))
                    summary.put("active", false);
                else if (type.getKey().equals("netStart") || type.getKey().equals("netAnnounce"))
                    summary.put("active", true);
                if (report instanceof APRSNetCentralNetCheckInOutReport membership) {
                    ObjectNode participants = summary.path("participants").isObject()
                            ? (ObjectNode) summary.path("participants")
                            : store.mapper().createObjectNode();
                    if (membership.isCheckIn())
                        participants.set(membership.getCallsign(), data);
                    else
                        participants.remove(membership.getCallsign());
                    summary.set("participants", participants);
                }
                if (type.getKey().equals("netSecure"))
                    summary.set("participants", store.mapper().createObjectNode());
                store.saveInternal("FEDERATED_NET", target, "", summary);
                if (federated) {
                    var observed = store.find("OBSERVED_OBJECT", target);
                    ObjectNode object = observed == null ? store.mapper().createObjectNode() : observed.data().deepCopy();
                    object.put("callsign", target);object.put("remote", true);object.put("type", "NET");
                    object.put("lastHeard", event.received().time().toString());
                    object.put("alive", summary.path("active").asBoolean(true));
                    if (type.getKey().equals("netAnnounce")) {
                        object.put("name", data.path("name").asText());
                        object.put("comment", data.path("description").asText());
                    }
                    object.set("types", store.mapper().valueToTree(policy.classifyObject(object)));
                store.saveInternal("OBSERVED_OBJECT", target, "", object);
                }
            }
            if (report instanceof APRSNetCentralObjectAnnounceReport announcement) {
                var old = store.find("OBSERVED_OBJECT", target);
                ObjectNode object = old == null ? store.mapper().createObjectNode() : old.data().deepCopy();
                object.put("callsign", target);
                object.put("remote", true);
                object.put("lastHeard", event.received().time().toString());
                object.put("alive", true);
                object.put("type", announcement.getObjectType().name());
                object.put("comment", announcement.getDescription());
                object.set("types", store.mapper().valueToTree(policy.classifyObject(object)));
                store.saveInternal("OBSERVED_OBJECT", target, "", object);
            }
            if (!federated) {
                radio.message("OBJECT:" + target, target, p.source(), "Update message accepted", event.received().instanceId());
                if (store.enabled("federationEnabled"))
                    radio.federate("OBJECT:" + target, report, managed.version());
            }
            return;
        }
        if (!federated)
            radio.message("OBJECT:" + target, target, p.source(), "Bad or unauthorized message.", event.received().instanceId());
    }

    public List<String> lines(String call, String kind) {
        List<String> lines = new ArrayList<>();
        lines.add(call + " — " + kind + " reports");
        for (var r : reports(call, kind)) {
            lines.add(r.recordedAt() + " " + r.kind());
            r.data().properties().forEach(e -> {
                if (!Set.of("bytes", "reportData", "fullReportData", "reportDataReadable").contains(e.getKey()))
                    lines.add(e.getKey() + ": " + e.getValue().asText(e.getValue().toString()));
            });
            lines.add("");
        }
        return lines;
    }
}
