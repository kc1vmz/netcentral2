package com.kc1vmz.netcentral.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.communication.CommunicationConfig;
import com.kc1vmz.netcentral.communication.CommunicationService;
import com.kc1vmz.netcentral.communication.RoutingService;

@Controller
@RequestMapping("/communications")
public class CommunicationPageController {
    private final CommunicationService communications;
    private final RoutingService routing;

    public CommunicationPageController(CommunicationService communications, RoutingService routing) {
        this.communications = communications;
        this.routing = routing;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("instances", communications.list());
        model.addAttribute("routes", routing.list());
        return "communications";
    }

    @GetMapping("/new")
    public String create(Model model) {
        communications.requireAdmin();
        model.addAttribute("config", new CommunicationConfig(null, 0, "", "APRS_IS", false, false,
                "", 14580, "", null, "", "", 9600, "", "", "", 0));
        return "communication-edit";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable String id, Model model) {
        model.addAttribute("config", communications.get(id).configuration());
        return "communication-edit";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute CommunicationConfig config) {
        communications.save(config.id() == null || config.id().isBlank() ? null : config.id(), config);
        return "redirect:/communications";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, @RequestParam long version) {
        communications.delete(id, version);
        return "redirect:/communications";
    }

    @PostMapping("/routes")
    public String route(@RequestParam String owner, @RequestParam long version,
            @RequestParam String mode, @RequestParam(required = false) Set<String> instanceIds) {
        routing.save(owner,
                new RoutingService.Route(owner, version, mode, instanceIds == null ? Set.of() : instanceIds));
        return "redirect:/communications";
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
        model.addAttribute("message", "Administrator access is required.");
        return "account-error";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String invalidValue(HttpServletResponse response, Model model) {
        response.setStatus(400);
        model.addAttribute("message", "Invalid communication configuration.");
        return "account-error";
    }
}
