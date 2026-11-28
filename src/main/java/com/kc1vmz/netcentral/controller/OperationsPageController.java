package com.kc1vmz.netcentral.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.accessor.*;
import com.kc1vmz.netcentral.communication.CommunicationService;
import com.kc1vmz.netcentral.operations.*;

import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class OperationsPageController {
    private final OperationsStore store;
    private final StationAccessor stations;
    private final ResourceAccessor objects;
    private final ReportAccessor reports;
    private final CommunicationService communications;
    private final RadioPolicy policy;
    private final NetAccessor nets;
    private final ToolsAccessor tools;
    private final com.kc1vmz.netcentral.communication.RoutingService routing;

    public OperationsPageController(OperationsStore store, StationAccessor stations, ResourceAccessor objects,
            ReportAccessor reports, CommunicationService communications, RadioPolicy policy, NetAccessor nets,
            ToolsAccessor tools, com.kc1vmz.netcentral.communication.RoutingService routing) {
        this.routing = routing;
        this.store = store;
        this.stations = stations;
        this.objects = objects;
        this.reports = reports;
        this.communications = communications;
        this.policy = policy;
        this.nets = nets;
        this.tools = tools;
    }

    @GetMapping("/explorer")
    public String explorer(Model model) {
        model.addAttribute("stations", stations.list());
        model.addAttribute("objects", objects.list());
        model.addAttribute("remoteNets", store.list("FEDERATED_NET"));
        return "explorer";
    }

    @GetMapping("/stations/{call}")
    public String station(@PathVariable String call, Model model) {
        var station = stations.get(call);
        model.addAttribute("station", station);
        ObjectNode details = station.data().deepCopy();
        details.remove("weatherObserved");
        AprsSymbols.decorate(details);
        model.addAttribute("stationDetails", details);
        model.addAttribute("ignored", policy.ignored(station.id()));
        objectSecurity(station.id(), model);
        model.addAttribute("weather", reports.reports(call, "weather"));
        return "station";
    }

    @PostMapping("/stations/{call}")
    public String station(@PathVariable String call, @RequestParam long version,
            @RequestParam Map<String, String> values) {
        ObjectNode data = store.mapper().valueToTree(values);
        data.put("tracked", values.containsKey("tracked"));
        stations.update(call, data, version);
        return "redirect:/stations/" + call;
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        store.authenticated();
        return "dashboard";
    }

    @GetMapping("/setup")
    public String setup() {
        store.admin();
        return "redirect:/setup/operations";
    }

    @GetMapping("/objects/new")
    public String create(Model model) {
        store.authenticated();
        model.addAttribute("object", null);
        model.addAttribute("route", null);
        model.addAttribute("instances", communications.routeChoices());
        model.addAttribute("mapSettings", nets.map());
        return "object-edit";
    }

    @GetMapping("/objects/{id}/edit")
    public String edit(@PathVariable String id, Model model) {
        var object = objects.get(id);
        store.manage(object);
        model.addAttribute("object", object);
        model.addAttribute("route", objects.route(id));
        model.addAttribute("instances", communications.routeChoices());
        model.addAttribute("mapSettings", nets.map());
        return "object-edit";
    }

    @GetMapping("/objects/{id}")
    public String object(@PathVariable String id, Model model) {
        var object = objects.get(id);
        model.addAttribute("object", object);
        model.addAttribute("symbol", AprsSymbols.code(object.data()));
        model.addAttribute("symbolDescription", AprsSymbols.describe(AprsSymbols.code(object.data())));
        model.addAttribute("canManage", object.kind().equals("OBJECT") && store.manages(object));
        model.addAttribute("reports", reports.reports(id, "all"));
        if (object.data().path("type").asText().equals("EOC")) {
            model.addAttribute("eocGroups", List.of(eocGroup(id, "contacts", "Contact information"),
                eocGroup(id, "mobilizations", "Mobilization information")));
            model.addAttribute("mobilizationStatuses", List.of("Unknown", "Normal", "Drill", "Partial", "Full"));
        }
        if (object.data().path("type").asText().equals("SHELTER")) {
            var groups = List.of(shelterGroup(id,"status","Status information"), shelterGroup(id,"census","Population census"),
                shelterGroup(id,"workers","Worker census"), shelterGroup(id,"operationalFoods","Food availability and needs"),
                shelterGroup(id,"operationalMateriels","Materiel availability and needs"));
            model.addAttribute("shelterGroups", groups);
            model.addAttribute("shelterHistories", List.of(new ShelterHistory("Status and census history",groups.subList(0,3)),
                new ShelterHistory("Food and materiel history",groups.subList(3,5))));
        }
        objectSecurity(id, model);
        return "object";
    }

    public record EocGroup(String kind, String title, List<ReportAccessor.Field> fields,
            List<ReportAccessor.Report> history, com.fasterxml.jackson.databind.JsonNode latest) {}

    private EocGroup eocGroup(String id, String kind, String title) {
        var history = reports.reports(id, kind);
        return new EocGroup(kind, title, reports.fields(kind).stream().filter(f -> !f.type().equals("datetime")).toList(),
            history, history.isEmpty() ? store.mapper().createObjectNode() : history.getFirst().data());
    }

    public record ShelterField(String name, String label, String type, List<String> choices) {}
    public record ShelterRow(String title, Map<String,String> values, Map<String,String> display, String reported) {}
    public record ShelterGroup(String kind, String title, List<ShelterField> fields, List<ShelterRow> latest, List<ShelterRow> history) {}
    public record ShelterHistory(String title, List<ShelterGroup> groups) {}

    private ShelterGroup shelterGroup(String id,String kind,String title) {
        var labels = Map.ofEntries(Map.entry("p03","Ages 0–3"),Map.entry("p47","Ages 4–7"),Map.entry("p812","Ages 8–12"),
            Map.entry("p1318","Ages 13–18"),Map.entry("p1965","Ages 19–65"),Map.entry("p66","Ages 66+"),
            Map.entry("health","Health services"),Map.entry("mental","Mental health"),Map.entry("spiritual","Spiritual care"),
            Map.entry("caseworker","Caseworkers"),Map.entry("comfort","Comfort kits"),Map.entry("cleanup","Cleanup kits"),Map.entry("infoType","Report category"));
        var fields = reports.fields(kind).stream().filter(f -> !f.name().equals("dateReported")).map(f ->
            new ShelterField(f.name(), labels.getOrDefault(f.name(), Character.toUpperCase(f.label().charAt(0))+f.label().substring(1)),
                f.name().equals("date") ? "date" : f.type(), switch(f.name()) {
                    case "status" -> List.of("Unknown","Stand-by","Active");
                    case "state" -> List.of("Unknown","Closed","Open");
                    case "shift" -> List.of("Unknown","Shift 1","Shift 2","Shift 3");
                    case "infoType" -> List.of("Unknown","On hand","Needed","Used");
                    default -> List.<String>of();
                })).toList();
        String selector = kind.equals("workers") ? "shift" : kind.startsWith("operational") ? "infoType" : "";
        var history = reports.reports(id,kind).stream().map(r -> shelterRow(fields,r.data(),r.recordedAt().toString(),selector)).toList();
        var latest = new ArrayList<ShelterRow>();
        for (int code = selector.isEmpty() ? 0 : 1; code <= (selector.isEmpty() ? 0 : 3); code++) {
            String value = Integer.toString(code);
            var row = history.stream().filter(r -> selector.isEmpty() || value.equals(r.values().get(selector))).findFirst();
            var blank = store.mapper().createObjectNode(); if(!selector.isEmpty())blank.put(selector,code);
            latest.add(row.orElseGet(() -> shelterRow(fields,blank,"",selector)));
        }
        return new ShelterGroup(kind,title,fields,latest,history);
    }
    private ShelterRow shelterRow(List<ShelterField> fields,com.fasterxml.jackson.databind.JsonNode data,String recorded,String selector) {
        var values = new LinkedHashMap<String,String>(); var display = new LinkedHashMap<String,String>();
        for (var field : fields) {
            String value = data.path(field.name()).asText(field.type().equals("number") ? "0" : "");
            if(field.type().equals("date") && value.length() >= 10)value=value.substring(0,10);
            values.put(field.name(),value);
            int code=data.path(field.name()).asInt();
            display.put(field.name(),field.choices().isEmpty() ? value : field.choices().get(Math.max(0,Math.min(code,field.choices().size()-1))));
        }
        return new ShelterRow(selector.isEmpty() ? "Latest report" : display.get(selector),values,display,
            data.path("dateReported").asText(recorded));
    }

    private void objectSecurity(String id, Model model) {
        var resource = store.find("OBJECT", id);
        if (resource == null) resource = store.find("OBSERVED_OBJECT", id);
        boolean visible = resource != null && Set.of("RESOURCE", "SHELTER", "EOC")
                .contains(resource.data().path("type").asText());
        model.addAttribute("showObjectSecurity", visible);
        model.addAttribute("acls", visible ? policy.acl(id) : List.of());
    }

    @PostMapping("/objects/save")
    public String save(@RequestParam Map<String, String> fields,
            @RequestParam(required = false) Set<String> communicationInstanceIds) {
        ObjectNode input = store.mapper().createObjectNode();
        fields.forEach(input::put);
        try {
            input.put("lat", Double.parseDouble(fields.getOrDefault("lat", "")));
            input.put("lon", Double.parseDouble(fields.getOrDefault("lon", "")));
        } catch (NumberFormatException invalid) {
            throw OperationsStore.bad("Valid decimal latitude and longitude required");
        }
        input.put("alive", fields.containsKey("alive"));
        input.put("routingVersion", Long.parseLong(fields.getOrDefault("routingVersion", "-1")));
        input.set("communicationInstanceIds",
                store.mapper().valueToTree(communicationInstanceIds == null ? Set.of() : communicationInstanceIds));
        String id = fields.getOrDefault("id", "");
        var result = objects.save(id.isBlank() ? null : id, input,
                Long.parseLong(fields.getOrDefault("version", "-1")));
        return "redirect:/objects/" + result.id();
    }

    @PostMapping("/objects/{id}/values")
    public String value(@PathVariable String id, @RequestParam String key, @RequestParam String value,
            @RequestParam long version, @RequestParam(defaultValue = "false") boolean remove) {
        var old = objects.get(id);
        Map<String, String> values = new TreeMap<>();
        old.data().path("keyValues").properties().forEach(e -> values.put(e.getKey(), e.getValue().asText()));
        if (remove)
            values.remove(key);
        else
            values.put(key, value);
        objects.values(id, values, version);
        return "redirect:/objects/" + id;
    }

    @PostMapping("/objects/{id}/withdraw")
    public String withdraw(@PathVariable String id, @RequestParam long version) {
        objects.delete(id, version);
        return "redirect:/explorer";
    }

    @GetMapping("/objects/{id}/reports/{kind}/new")
    public String report(@PathVariable String id, @PathVariable String kind, Model model) {
        store.manage(objects.get(id));
        model.addAttribute("id", id);
        model.addAttribute("kind", kind);
        model.addAttribute("fields", reports.fields(kind));
        return "report-edit";
    }

    @PostMapping("/objects/{id}/reports/{kind}")
    public String report(@PathVariable String id, @PathVariable String kind, @RequestParam Map<String, String> fields) {
        ObjectNode data = store.mapper().createObjectNode();
        for (var field : reports.fields(kind)) {
            String value = fields.getOrDefault(field.name(), "");
            if (field.type().equals("number"))
                data.put(field.name(), Integer.parseInt(value));
            else if (!value.isBlank())
                data.put(field.name(), field.name().equals("date") && value.length() == 10 ? value + "T00:00:00Z" : value);
        }
        reports.submit(id, kind, data);
        return "redirect:/objects/" + id;
    }

    private static final Map<String, List<String>> SETTING_GROUPS = Map.of(
            "external-services", List.of("identityResolver", "winlinkProvider"), "announcements", List.of("beaconSeconds"), "federation", List.of("federationEnabled", "federationUserDefinedEnabled", "federationMessagesEnabled", "federationInterrogateEnabled", "federationCallsign"),
            "net-manager", List.of("netManagerEnabled", "netManagerCallsign", "netManagerOwner", "netManagerLatitude", "netManagerLongitude"),
            "timers", List.of("stationRetentionSeconds", "packetRetentionSeconds", "packetLimit", "reminderSeconds"));
    private static final Map<String, String> SETTING_TITLES = Map.of("external-services", "External Services", "announcements", "Announcements", "federation",
            "Federation", "net-manager", "Net Manager", "timers", "Timers");

    private List<String> settingKeys(String section) {
        if (section.equals("external-services") && !store.accounts().current().role().equals("SYSADMIN"))
            throw new AccessDeniedException("System administrator required");
        var keys = SETTING_GROUPS.get(section);
        if (keys == null)
            throw OperationsStore.bad("Unknown settings section");
        return keys;
    }

    @GetMapping("/setup/operations")
    public String settings(@RequestParam(defaultValue = "announcements") String section, Model model) {
        store.admin();
        if (section.equals("identity")) section = "external-services";
        var keys = settingKeys(section);
        var all = store.settings();
        var selected = new LinkedHashMap<String, String>();
        keys.forEach(key -> selected.put(key, all.get(key)));
        model.addAttribute("settings", selected);
        if (section.equals("net-manager")) {
            String owner = "OBJECT:" + store.setting("netManagerCallsign");
            var route = routing.load(owner);
            model.addAttribute("route", route.owner().equals(owner) ? route :
                new com.kc1vmz.netcentral.communication.RoutingService.Route(owner, -1, "ALL", Set.of()));
            model.addAttribute("instances", communications.routeChoices());
        }
        model.addAttribute("mapSettings", nets.map());
        model.addAttribute("section", section);
        model.addAttribute("title", SETTING_TITLES.get(section));
        model.addAttribute("users", section.equals("net-manager") ? store.accounts().list() : List.of());
        return "operations-settings";
    }

    @PostMapping("/setup/operations")
    @org.springframework.transaction.annotation.Transactional
    public String settings(@RequestParam Map<String, String> values,
            @RequestParam(required = false) Set<String> communicationInstanceIds) {
        store.admin();
        values.remove("_csrf");
        String section = values.getOrDefault("section", "announcements");
        values.remove("section");
        String mode = values.remove("communicationMode");
        String version = values.remove("routingVersion");
        values.remove("communicationInstanceIds");
        if (section.equals("identity")) section = "external-services";
        var keys = settingKeys(section);
        if (mode != null && !section.equals("net-manager")) throw OperationsStore.bad("Setting belongs to another section");
        if (!keys.containsAll(values.keySet()))
            throw OperationsStore.bad("Setting belongs to another section");
        if (mode != null && "true".equals(values.get("netManagerEnabled"))) {
            String call = NetAccessor.callsign(values.getOrDefault("netManagerCallsign", store.setting("netManagerCallsign")));
            String owner = "OBJECT:" + call;
            var previous = routing.load(owner);
            long expected = previous.owner().equals(owner) ? previous.version() : -1;
            if (call.equals(store.setting("netManagerCallsign")) && version != null) expected = Long.parseLong(version);
            routing.saveInternal(owner, new com.kc1vmz.netcentral.communication.RoutingService.Route(owner, expected,
                mode, communicationInstanceIds == null ? Set.of() : communicationInstanceIds));
        }
        store.settings(values);
        return "redirect:/setup/operations?section=" + section + "&saved";
    }

    @GetMapping("/setup/radio")
    public String radio(@RequestParam(defaultValue = "classification") String section, Model model) {
        store.admin();
        if (!Set.of("ignored", "classification", "access").contains(section))
            throw OperationsStore.bad("Unknown radio settings section");
        model.addAttribute("section", section);
        model.addAttribute("title",
                Map.of("ignored", "Ignored", "classification", "Classification", "access", "Object security")
                        .get(section));
        model.addAttribute("rules", policy.rules());
        model.addAttribute("ignores", policy.ignores());
        model.addAttribute("acls", store.list("ACL"));
        return "radio-settings";
    }

    @PostMapping("/setup/radio/ignore")
    public String ignore(@RequestParam String callsign, @RequestParam(defaultValue = "false") boolean remove) {
        policy.ignore(callsign, !remove);
        return "redirect:/setup/radio?section=ignored";
    }

    @PostMapping("/setup/radio/rule")
    public String rule(@RequestParam Map<String, String> values) {
        policy.rule(null, store.mapper().valueToTree(values), null);
        return "redirect:/setup/radio?section=classification";
    }

    @PostMapping("/setup/radio/rule/{id}/delete")
    public String deleteRule(@PathVariable String id, @RequestParam long version) {
        policy.removeRule(id, version);
        return "redirect:/setup/radio?section=classification";
    }

    @PostMapping("/setup/radio/acl")
    public String acl(@RequestParam String target, @RequestParam String checked, @RequestParam String type,
            @RequestParam boolean allowed, @RequestParam(defaultValue = "0") int proximity) {
        policy.acl(target, store.mapper().createObjectNode().put("callsignChecked", checked).put("type", type)
                .put("allowed", allowed).put("proximity", proximity), null);
        return "redirect:/setup/radio?section=access";
    }

    @PostMapping("/setup/radio/acl/delete")
    public String deleteAcl(@RequestParam String target, @RequestParam String checked) {
        policy.removeAcl(target, checked);
        return "redirect:/setup/radio?section=access";
    }

    @GetMapping("/nets/{id}/options")
    public String options(@PathVariable String id) {
        return "redirect:/nets/" + id + "/edit";
    }

    @GetMapping("/setup/strip-library")
    public String stripLibrarySettings() {
        store.authenticated();
        return "strip-library";
    }

    @GetMapping("/tools")
    public String tools(@RequestParam(defaultValue = "winlink") String section, Model model) {
        store.authenticated();
        if (section.equals("strip-library")) return "redirect:/setup/strip-library";
        if (!Set.of("winlink", "nts", "packet-logging").contains(section))
            throw OperationsStore.bad("Unknown tool section");
        model.addAttribute("section", section);
        model.addAttribute("canManage", store.accounts().administrator());
        if (section.equals("packet-logging") && store.accounts().administrator())
            model.addAttribute("settings", store.settings());
        return "tools";
    }

    @PostMapping("/tools/packet-logging")
    public String packetLogging(@RequestParam String rawPacketLogging) {
        store.admin();
        store.settings(Map.of("rawPacketLogging", rawPacketLogging));
        return "redirect:/tools?section=packet-logging&saved";
    }

    @PostMapping("/tools/winlink")
    public String winlink(@RequestParam Map<String, String> values, Model model) {
        model.addAttribute("result", tools.winlink(store.mapper().valueToTree(values)));
        return tools("winlink", model);
    }

    @GetMapping("/tools/winlink/status")
    public String status(@RequestParam String callsign, Model model) {
        model.addAttribute("result", tools.status(callsign));
        return tools("winlink", model);
    }

    @PostMapping("/tools/nts")
    public String nts(@RequestParam Map<String, String> values, Model model) {
        model.addAttribute("result", tools.nts(store.mapper().valueToTree(values)));
        return tools("nts", model);
    }

    @GetMapping("/licenses")
    public String licenses() {
        return "redirect:/help#licenses";
    }

    @ExceptionHandler({ ResponseStatusException.class, IllegalArgumentException.class })
    public String invalid(Exception e, HttpServletResponse response, Model model) {
        response.setStatus(e instanceof ResponseStatusException status ? status.getStatusCode().value() : 400);
        model.addAttribute("message",
                e instanceof ResponseStatusException status ? status.getReason() : "Invalid form value");
        return "account-error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String denied(HttpServletResponse response, Model model) {
        response.setStatus(403);
        model.addAttribute("message", "Owner or administrator permission required");
        return "account-error";
    }
}
