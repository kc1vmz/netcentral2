package com.kc1vmz.netcentral.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.communication.CommunicationConfig;
import com.kc1vmz.netcentral.communication.CommunicationService;
import com.kc1vmz.netcentral.communication.PacketHub;
import com.kc1vmz.netcentral.communication.RoutingService;
import com.kc1vmz.netcentral.communication.SerialPorts;

@RestController
@RequestMapping("/api/v1")
public class CommunicationApiController {
    private final CommunicationService communications;
    private final RoutingService routing;
    private final PacketHub packets;

    public CommunicationApiController(CommunicationService communications, RoutingService routing, PacketHub packets) {
        this.communications = communications;
        this.routing = routing;
        this.packets = packets;
    }

    @GetMapping("/communicationInstances")
    public List<CommunicationService.View> list() {
        return communications.list();
    }

    @GetMapping("/communicationInstances/serialPorts")
    public SerialPorts.Result serialPorts() {
        return SerialPorts.discover(communications.list().stream().map(CommunicationService.View::configuration)
                .filter(CommunicationConfig::serial)
                .map(c -> new SerialPorts.Assignment(c.serialDevice(), c.name(), c.enabled())).toList());
    }

    @GetMapping("/communicationInstances/{id}")
    public CommunicationService.View get(@PathVariable String id) {
        return communications.get(id);
    }

    @PostMapping("/communicationInstances")
    public CommunicationService.View create(@RequestBody CommunicationConfig config) {
        return communications.save(null, config);
    }

    @PutMapping("/communicationInstances/{id}")
    public CommunicationService.View update(@PathVariable String id, @RequestBody CommunicationConfig config) {
        return communications.save(id, config);
    }

    @DeleteMapping("/communicationInstances/{id}")
    public void delete(@PathVariable String id, @RequestParam long version) {
        communications.delete(id, version);
    }

    @GetMapping("/communicationRoutes")
    public List<RoutingService.Route> routes() {
        return routing.list();
    }

    @PutMapping("/communicationRoutes/{owner}")
    public RoutingService.Route route(@PathVariable String owner, @RequestBody RoutingService.Route route) {
        return routing.save(owner, route);
    }

    @GetMapping("/communicationObservations")
    public List<PacketHub.Received> observations() {
        communications.requireAdmin();
        return packets.observations();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidValue() {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid communication settings"));
    }
}
