package com.kc1vmz.netcentral.controller;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.accessor.NetSchedulerAccessor;
import com.kc1vmz.netcentral.net.ScheduleModels.*;

@RestController
@RequestMapping("/api/v1/scheduledNets")
public class ScheduleApiController {
    private final NetSchedulerAccessor schedules;

    public ScheduleApiController(NetSchedulerAccessor schedules) {
        this.schedules = schedules;
    }

    @GetMapping
    public List<Schedule> list() {
        return schedules.list();
    }

    @PostMapping
    public Schedule create(@RequestBody Edit edit) {
        return schedules.create(edit);
    }

    @GetMapping("/{id}")
    public Schedule get(@PathVariable String id) {
        return schedules.get(id);
    }

    @GetMapping("/{id}/detail")
    public Detail detail(@PathVariable String id) {
        return schedules.detail(id);
    }

    @GetMapping("/{id}/occurrences")
    public List<Occurrence> occurrences(@PathVariable String id) {
        return schedules.occurrences(id);
    }

    @GetMapping("/{id}/options")
    public Object options(@PathVariable String id) {
        return schedules.options(id);
    }

    @PutMapping("/{id}/options")
    public void options(@PathVariable String id, @RequestBody com.fasterxml.jackson.databind.JsonNode values,
            @RequestParam long version) {
        schedules.options(id, values, version);
    }

    @PutMapping("/{id}")
    public Schedule update(@PathVariable String id, @RequestBody Edit edit) {
        return schedules.update(id, edit);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id, @RequestParam long version) {
        schedules.delete(id, version);
    }

    @GetMapping("/{id}/expectedParticipants")
    public Set<String> expected(@PathVariable String id) {
        return schedules.expected(id);
    }

    @PostMapping("/{id}/expectedParticipants/{callsign}")
    public Set<String> add(@PathVariable String id, @PathVariable String callsign, @RequestParam long version) {
        Set<String> values = new HashSet<>(schedules.expected(id));
        values.add(callsign);
        return schedules.expected(id, values, version);
    }

    @DeleteMapping("/{id}/expectedParticipants/{callsign}")
    public Set<String> remove(@PathVariable String id, @PathVariable String callsign, @RequestParam long version) {
        Set<String> values = new HashSet<>(schedules.expected(id));
        values.remove(com.kc1vmz.netcentral.accessor.NetAccessor.callsign(callsign).split("-")[0]);
        return schedules.expected(id, values, version);
    }

    @PostMapping("/{id}/expectedParticipants/requests")
    public Set<String> replace(@PathVariable String id, @RequestBody Set<String> callsigns,
            @RequestParam long version) {
        return schedules.expected(id, callsigns, version);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(403).body(Map.of("message", "Schedule owner or administrator access required"));
    }
}
