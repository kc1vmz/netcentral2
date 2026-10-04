package com.kc1vmz.netcentral.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.accessor.AccountAccessor;
import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.communication.CommunicationService;
import com.kc1vmz.netcentral.net.NetModels.*;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class NetPageController {
    private final NetAccessor nets;
    private final CommunicationService communications;
    private final AccountAccessor accounts;
    private final com.kc1vmz.netcentral.accessor.NetSchedulerAccessor schedules;

    public NetPageController(NetAccessor nets, CommunicationService communications, AccountAccessor accounts,
            com.kc1vmz.netcentral.accessor.NetSchedulerAccessor schedules) {
        this.nets = nets;
        this.communications = communications;
        this.accounts = accounts;
        this.schedules = schedules;
    }

    @GetMapping("/nets")
    public String list(Model model) {
        model.addAttribute("nets", nets.list(false));
        model.addAttribute("schedules", schedules.list());
        model.addAttribute("archiveOnly", false);
        return "nets";
    }

    @GetMapping("/archives")
    public String archives(Model model) {
        model.addAttribute("nets", java.util.List.of());
        model.addAttribute("archives", nets.list(true));
        model.addAttribute("archiveOnly", true);
        return "nets";
    }

    @GetMapping("/nets/new")
    public String create(Model model) {
        model.addAttribute("net", null);
        model.addAttribute("snapshot", null);
        model.addAttribute("options", JsonNodeFactory.instance.objectNode());
        model.addAttribute("mapSettings", nets.map());
        model.addAttribute("instances", communications.routeChoices());
        return "net-edit";
    }

    @GetMapping("/nets/{id}")
    public String monitor(@PathVariable String id, Model model) {
        model.addAttribute("snapshot", nets.snapshot(id));
        var choices = new java.util.ArrayList<>(nets.list(false));
        choices.addAll(nets.list(true));
        model.addAttribute("netChoices", choices);
        return "net-monitor";
    }

    @GetMapping("/nets/{id}/edit")
    public String edit(@PathVariable String id, Model model) {
        var snapshot = nets.snapshot(id);
        if (!snapshot.canManage())
            throw new AccessDeniedException("Net owner required");
        model.addAttribute("net", snapshot.net());
        model.addAttribute("snapshot", snapshot);
        model.addAttribute("options", nets.options(id));
        model.addAttribute("mapSettings", nets.map());
        model.addAttribute("instances", communications.routeChoices());
        return "net-edit";
    }

    @PostMapping("/nets/save")
    public String save(@RequestParam(defaultValue = "") String id, @RequestParam String callsign,
            @RequestParam String name, @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String voiceFrequency,
            @RequestParam(defaultValue = "false") boolean open,
            @RequestParam(required = false) Boolean privateNet,
            @RequestParam(defaultValue = "-1") Long version, @RequestParam String communicationMode,
            @RequestParam(required = false) Set<String> communicationInstanceIds,
            @RequestParam(defaultValue = "-1") Long routingVersion,
            @RequestParam(defaultValue = "") String expectedParticipants,
            @RequestParam Map<String, String> values, @RequestParam double lat, @RequestParam double lon) {
        Set<String> expected = Arrays.stream(expectedParticipants.split("[,\\s]+")).filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        NetEdit edit = new NetEdit(callsign, name, description, voiceFrequency, open, privateNet, version,
                communicationMode,
                communicationInstanceIds == null ? Set.of() : communicationInstanceIds, routingVersion, expected);
        ObjectNode options = JsonNodeFactory.instance.objectNode();
        options.put("lat", lat).put("lon", lon);
        options.put("checkinMessage", values.getOrDefault("checkinMessage", ""));
        options.put("announce", Boolean.parseBoolean(values.getOrDefault("announce", "false")));
        options.put("checkinReminder", Boolean.parseBoolean(values.getOrDefault("checkinReminder", "false")));
        options.put("questionReminder", Boolean.parseBoolean(values.getOrDefault("questionReminder", "false")));
        options.put("participantInviteAllowed", Boolean.parseBoolean(values.getOrDefault("participantInviteAllowed", "false")));
        options.put("symbolTableId", values.getOrDefault("symbolTableId", "/"));
        options.put("symbolTableCode", values.getOrDefault("symbolTableCode", "n"));
        Net net = nets.saveConfigured(id, edit, options);
        return "redirect:/nets/" + net.id();
    }

    @GetMapping("/setup/map-cache")
    public String map(Model model) {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator required");
        model.addAttribute("settings", nets.map());
        return "map-settings";
    }

    @PostMapping("/setup/map-cache")
    public String map(@RequestParam String tileUrl, @RequestParam String attribution) {
        nets.map(new MapSettings(tileUrl, attribution));
        return "redirect:/setup/map-cache?saved";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String invalid(ResponseStatusException error, HttpServletResponse response, Model model) {
        response.setStatus(error.getStatusCode().value());
        model.addAttribute("message", error.getReason());
        return "account-error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String denied(HttpServletResponse response, Model model) {
        response.setStatus(403);
        model.addAttribute("message", "You do not have access to manage this net or setting.");
        return "account-error";
    }
}
