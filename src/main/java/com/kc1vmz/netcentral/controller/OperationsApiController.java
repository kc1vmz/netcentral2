package com.kc1vmz.netcentral.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.kc1vmz.netcentral.accessor.*;
import com.kc1vmz.netcentral.net.NetReport;
import com.kc1vmz.netcentral.operations.*;

import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class OperationsApiController {
    private final OperationsStore store;
    private final StationAccessor stations;
    private final ResourceAccessor objects;
    private final ReportAccessor reports;
    private final RadioPolicy policy;
    private final ToolsAccessor tools;
    private final NetAccessor nets;
    private final NetReport pdf;

    public OperationsApiController(OperationsStore store, StationAccessor stations, ResourceAccessor objects,
            ReportAccessor reports, RadioPolicy policy, ToolsAccessor tools, NetAccessor nets, NetReport pdf) {
        this.store = store;
        this.stations = stations;
        this.objects = objects;
        this.reports = reports;
        this.policy = policy;
        this.tools = tools;
        this.nets = nets;
        this.pdf = pdf;
    }

    @GetMapping({ "/trackedStations", "/callsigns" })
    public Object stations() {
        return stations.list();
    }

    @PostMapping("/trackedStations")
    public Object createStation(@RequestBody JsonNode input) {
        return stations.create(input);
    }

    @GetMapping("/trackedStations/{call}/objects")
    public Object stationObjects(@PathVariable String call) {
        return objects.list().stream().filter(e -> e.data().path("callsignFrom").asText().equals(call)).toList();
    }

    @GetMapping("/APRSObjects/statusMessages/{call}")
    public Object statusMessages(@PathVariable String call) {
        return stations.get(call).data().path("status");
    }

    @GetMapping("/participants/roots")
    public Object participantRoots() {
        return nets.list(false).stream().flatMap(n -> nets.participants(n.id(), false).stream())
                .map(p -> RadioPolicy.root(p.callsign())).distinct().sorted().toList();
    }

    @GetMapping("/participants/{call}")
    public Object participant(@PathVariable String call) {
        return nets.list(false).stream().flatMap(n -> nets.participants(n.id(), false).stream())
                .filter(p -> p.callsign().equalsIgnoreCase(call)).toList();
    }

    @GetMapping("/netMessages/{id}")
    public Object message(@PathVariable String id) {
        return nets.list(false).stream().flatMap(n -> nets.messages(n.id()).stream()).filter(m -> m.id().equals(id))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping({ "/trackedStations/{id}", "/callsigns/{id}" })
    public Object station(@PathVariable String id) {
        return stations.get(id);
    }

    @GetMapping("/callsigns/roots")
    public Object roots() {
        return stations.list().stream().map(e -> RadioPolicy.root(e.id())).distinct().sorted().toList();
    }

    @PutMapping({ "/trackedStations/{id}", "/callsigns/{id}" })
    public Object station(@PathVariable String id, @RequestBody JsonNode data, @RequestParam long version) {
        return stations.update(id, data, version);
    }

    @DeleteMapping({ "/trackedStations/{id}", "/callsigns/{id}" })
    public void deleteStation(@PathVariable String id) {
        stations.delete(id);
    }

    @GetMapping("/ignoreStations")
    public Object ignores() {
        return policy.ignores();
    }

    @GetMapping("/ignoreStations/{callsign}")
    public Object ignored(@PathVariable String callsign) {
        store.authenticated();
        return Map.of("ignored", policy.ignored(NetAccessor.callsign(callsign)));
    }

    @PostMapping("/ignoreStations")
    public void ignore(@RequestBody JsonNode data) {
        policy.ignore(data.path("callsign").asText(), true);
    }

    @DeleteMapping("/ignoreStations/{callsign}")
    public void unignore(@PathVariable String callsign) {
        policy.ignore(callsign, false);
    }

    @GetMapping("/trackedStationTypeRules")
    public Object rules() {
        return policy.rules();
    }

    @GetMapping("/trackedStationTypeRules/{id}")
    public Object rule(@PathVariable String id) {
        store.authenticated();
        return store.require("CLASSIFY", id);
    }

    @PostMapping("/callsigns/{call}/identityRequests")
    public Object identity(@PathVariable String call) {
        return tools.identifyCallsign(call);
    }

    @PostMapping("/callsigns/{call}/identifyRequests")
    public Object identify(@PathVariable String call, @RequestBody JsonNode input) {
        return tools.direct(call, input, true);
    }

    @PostMapping("/callsigns/{call}/messages")
    public Object direct(@PathVariable String call, @RequestBody JsonNode input) {
        return tools.direct(call, input, false);
    }

    @PostMapping("/callsigns/{call}/messages/{net}")
    public Object directNet(@PathVariable String call, @PathVariable String net, @RequestBody JsonNode input) {
        return nets.send(net,
                new com.kc1vmz.netcentral.net.NetModels.MessageRequest(call, input.path("message").asText(), true));
    }

    @PostMapping("/trackedStationTypeRules")
    public Object rule(@RequestBody JsonNode input) {
        return policy.rule(null, input, null);
    }

    @PutMapping("/trackedStationTypeRules/{id}")
    public Object rule(@PathVariable String id, @RequestBody JsonNode input, @RequestParam long version) {
        return policy.rule(id, input, version);
    }

    @DeleteMapping("/trackedStationTypeRules/{id}")
    public void removeRule(@PathVariable String id, @RequestParam long version) {
        policy.removeRule(id, version);
    }

    @GetMapping("/accessControlLists/{target}")
    public Object acl(@PathVariable String target) {
        return policy.acl(target);
    }

    @RequestMapping(value = "/accessControlLists/{target}", method = { RequestMethod.POST, RequestMethod.PUT })
    public Object acl(@PathVariable String target, @RequestBody JsonNode input,
            @RequestParam(required = false) Long version) {
        return policy.acl(target, input, version);
    }

    @DeleteMapping("/accessControlLists/{target}/{checked}")
    public void acl(@PathVariable String target, @PathVariable String checked) {
        policy.removeAcl(target, checked);
    }

    @GetMapping("/APRSObjects")
    public Object objects() {
        return objects.list();
    }

    @GetMapping("/APRSObjects/{id}")
    public Object object(@PathVariable String id) {
        return objects.get(id);
    }

    @GetMapping("/APRSObjects/{id}/routing")
    public Object route(@PathVariable String id) {
        return objects.route(id);
    }

    @PostMapping("/APRSObjects/requests")
    public Object object(@RequestBody JsonNode data) {
        return objects.save(null, data, null);
    }

    @PutMapping("/APRSObjects/{id}")
    public Object object(@PathVariable String id, @RequestBody JsonNode data, @RequestParam long version) {
        return objects.save(id, data, version);
    }

    @DeleteMapping("/APRSObjects/{id}")
    public void deleteObject(@PathVariable String id, @RequestParam long version) {
        objects.delete(id, version);
    }

    @GetMapping("/APRSObjects/{id}/keyValues")
    public Object values(@PathVariable String id) {
        return objects.get(id).data().path("keyValues");
    }

    @RequestMapping(value = "/APRSObjects/{id}/keyValues", method = { RequestMethod.POST, RequestMethod.PUT })
    public Object values(@PathVariable String id, @RequestBody Map<String, String> values, @RequestParam long version) {
        return objects.values(id, values, version);
    }

    @DeleteMapping("/APRSObjects/{id}/keyValues")
    public Object clearValues(@PathVariable String id, @RequestParam long version) {
        return objects.values(id, Map.of(), version);
    }

    @GetMapping("/APRSObjects/{id}/keyValues/history")
    public Object history(@PathVariable String id) {
        return objects.history(id);
    }

    @GetMapping({ "/shelterReports/{call}/{kind}", "/eocReports/{call}/{kind}" })
    public Object report(@PathVariable String call, @PathVariable String kind,
            @RequestParam(defaultValue = "false") boolean all, @RequestParam(required = false) String reportDate,
            @RequestParam(required = false) Integer shift, @RequestParam(required = false) Integer timeframe) {
        var rows = reports.filtered(call, kind.equals("consolidated") ? "all" : kind, reportDate, shift, timeframe);
        return all || kind.equals("consolidated") ? rows : rows.stream().findFirst().orElse(null);
    }

    @GetMapping({ "/shelterReports/{call}/{kind}/all", "/eocReports/{call}/{kind}/all" })
    public Object reportAll(@PathVariable String call, @PathVariable String kind) {
        return reports.reports(call, kind);
    }

    @GetMapping("/eocReports/{call}/{kind}/latest")
    public Object latest(@PathVariable String call, @PathVariable String kind) {
        return reports.latest(call, kind);
    }

    @PostMapping({ "/shelterReports/{call}/{kind}", "/eocReports/{call}/{kind}" })
    public Object report(@PathVariable String call, @PathVariable String kind, @RequestBody JsonNode data) {
        return reports.submit(call, kind, data);
    }

    @DeleteMapping({ "/shelterReports/{call}", "/eocReports/{call}" })
    public void deleteReport(@PathVariable String call) {
        reports.delete(call);
    }

    @GetMapping(value = { "/shelterReports/{call}/report.pdf",
            "/eocReports/{call}/report.pdf" }, produces = "application/pdf")
    public byte[] reportPdf(@PathVariable String call) throws java.io.IOException {
        return pdf.render(call, reports.lines(call, "all"));
    }

    @GetMapping("/weatherReports")
    public Object weather() {
        return reports.weather();
    }

    @GetMapping("/weatherReports/callsigns/{call}/all")
    public Object weather(@PathVariable String call) {
        return reports.reports(call, "weather");
    }

    @GetMapping("/weatherReports/callsigns/{call}/latest")
    public Object weatherLatest(@PathVariable String call) {
        return reports.latest(call, "weather");
    }

    @DeleteMapping("/weatherReports/callsigns/{call}/all")
    public void weatherDelete(@PathVariable String call) {
        store.admin();
        store.jdbc().update("DELETE FROM operational_report WHERE callsign=? AND kind='weather'", call);
    }

    @GetMapping(value = "/weatherReports/callsigns/{call}/report.pdf", produces = "application/pdf")
    public byte[] weatherPdf(@PathVariable String call) throws java.io.IOException {
        return pdf.render(call, reports.lines(call, "weather"));
    }

    @GetMapping("/federation/nets")
    public Object federation() {
        store.authenticated();
        return store.list("FEDERATED_NET");
    }

    @GetMapping("/federation/{call}/reports")
    public Object federatedReports(@PathVariable String call) {
        return reports.reports(call, "all");
    }

    @GetMapping("/configurations")
    public Object settings() {
        return store.settings();
    }

    @PutMapping("/configurations")
    public Object settings(@RequestBody Map<String, String> values) {
        return store.settings(values);
    }

    @GetMapping("/configParameters/{name}")
    public Object setting(@PathVariable String name) {
        return store.settings().get(name);
    }

    @PutMapping("/configParameters/{name}")
    public Object setting(@PathVariable String name, @RequestBody String value) {
        return store.settings(Map.of(name, value));
    }

    @GetMapping("/activities")
    public Object activities(@RequestParam(defaultValue = "0") long after) {
        store.admin();
        return store.jdbc().queryForList("SELECT * FROM activity WHERE id>? ORDER BY id LIMIT 1000", after);
    }

    @GetMapping({ "/statistics", "/summaries/dashboardCounts", "/summaries/dashboardNetCentral" })
    public Object statistics() {
        store.authenticated();
        return Map.of("activeNets", nets.list(false).size(), "completedNets", nets.list(true).size(), "stations",
                stations.list().size(), "objects", objects.list().size(), "rawPackets",
                store.jdbc().queryForObject("SELECT COUNT(*) FROM raw_packet", Long.class));
    }

    @GetMapping("/participants")
    public Object participants() {
        return nets.list(false).stream().flatMap(n -> nets.participants(n.id(), false).stream()).toList();
    }

    @GetMapping("/participants/{call}/nets")
    public Object participantNets(@PathVariable String call) {
        return nets.list(false).stream().filter(
                n -> nets.participants(n.id(), false).stream().anyMatch(p -> p.callsign().equalsIgnoreCase(call)))
                .toList();
    }

    @GetMapping("/netMessages")
    public Object messages() {
        return nets.list(false).stream().flatMap(n -> nets.messages(n.id()).stream()).toList();
    }

    @PostMapping("/tools/winlink/requests")
    public Object winlink(@RequestBody JsonNode data) {
        return tools.winlink(data);
    }

    @GetMapping("/tools/winlink/requests/{call}")
    public Object winlinkStatus(@PathVariable String call) {
        return tools.status(call);
    }

    @DeleteMapping("/tools/winlink/requests/{call}")
    public void winlinkCancel(@PathVariable String call) {
        tools.cancel(call);
    }

    @PostMapping("/tools/ntsradiogram/requests")
    public Object nts(@RequestBody JsonNode data) {
        return tools.nts(data);
    }

    @GetMapping(value = "/tools/rawPackets", produces = "text/plain")
    public String raw(@RequestParam(defaultValue = "1970-01-01T00:00:00Z") Instant since,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "1000") int limit) {
        StringBuilder out = new StringBuilder();
        for (var row : stations.raw(since, after, limit))
            out.append(row.get("RECEIVED_AT")).append('\t').append(row.get("PACKET")).append('\n');
        return out.toString();
    }

    @DeleteMapping("/APRSObjects/all/rawPackets")
    public void rawDelete() {
        stations.clearRaw();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> invalid(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", Objects.toString(e.getReason(), "Record not found")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied() {
        return ResponseEntity.status(403).body(Map.of("message", "Owner or administrator permission required"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid operation parameters"));
    }
}
