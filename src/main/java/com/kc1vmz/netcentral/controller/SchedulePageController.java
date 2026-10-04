package com.kc1vmz.netcentral.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.accessor.NetSchedulerAccessor;
import com.kc1vmz.netcentral.communication.CommunicationService;
import com.kc1vmz.netcentral.net.ScheduleModels.*;

@Controller
public class SchedulePageController {
    private final NetSchedulerAccessor schedules;
    private final CommunicationService communications;
    private final com.kc1vmz.netcentral.accessor.NetAccessor nets;

    public SchedulePageController(NetSchedulerAccessor schedules, CommunicationService communications, com.kc1vmz.netcentral.accessor.NetAccessor nets) {
        this.nets = nets;
        this.schedules = schedules;
        this.communications = communications;
    }

    @GetMapping("/schedules")
    public String list(Model model) {
        return "redirect:/nets#scheduled-nets";
    }

    @GetMapping("/schedules/new")
    public String create(Model model) {
        model.addAttribute("detail", null);
        model.addAttribute("schedule", null);
        model.addAttribute("options", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        configure(model);
        return "schedule-edit";
    }

    private void configure(Model model) {
        model.addAttribute("mapSettings", nets.map());
        model.addAttribute("instances", communications.routeChoices());
        model.addAttribute("defaultTimezone", schedules.defaultTimezone());
        var timezones = new TreeSet<>(java.time.ZoneId.getAvailableZoneIds());
        timezones.add(schedules.defaultTimezone());
        if (model.getAttribute("schedule") instanceof Schedule schedule)
            timezones.add(schedule.timezone());
        model.addAttribute("timezones", timezones);
        Schedule schedule = (Schedule) model.getAttribute("schedule");
        var anchor = schedule == null ? java.time.LocalDateTime.now(java.time.ZoneId.of(schedules.defaultTimezone())) : schedule.firstStart();
        model.addAttribute("startTime", anchor.toLocalTime().withSecond(0).withNano(0).toString());
        model.addAttribute("weekday", anchor.getDayOfWeek().getValue());
        model.addAttribute("monthDay", schedule != null && schedule.type() == Type.MONTHLY_DAY && schedule.ordinal() == 6 ? "LAST" : Integer.toString(anchor.getDayOfMonth()));
        model.addAttribute("weekdays", List.of(java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY));
    }

    @GetMapping("/schedules/{id}")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("detail", schedules.detail(id));
        model.addAttribute("occurrences", schedules.occurrences(id));
        return "schedule";
    }

    @GetMapping("/schedules/{id}/edit")
    public String edit(@PathVariable String id, Model model) {
        var detail = schedules.detail(id);
        if (!detail.canManage())
            throw new AccessDeniedException("Schedule owner required");
        model.addAttribute("detail", detail);
        model.addAttribute("schedule", detail.schedule());
        model.addAttribute("options", schedules.options(id));
        configure(model);
        return "schedule-edit";
    }

    @GetMapping("/schedules/{id}/options")
    public String options(@PathVariable String id, Model model) {
        var detail = schedules.detail(id);
        if (!detail.canManage())
            throw new AccessDeniedException("Schedule owner required");
        model.addAttribute("net", detail.schedule());
        model.addAttribute("options", schedules.options(id));
        model.addAttribute("scheduleOptions", true);
        return "net-options";
    }

    @PostMapping("/schedules/{id}/options")
    public String options(@PathVariable String id, @RequestParam long version,
            @RequestParam Map<String, String> values) {
        var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        for (String field : List.of("announce", "checkinReminder", "questionReminder", "participantInviteAllowed"))
            data.put(field, values.containsKey(field));
        data.put("checkinMessage", values.getOrDefault("checkinMessage", "Check in with CI"));
        data.put("symbolTableId", values.getOrDefault("symbolTableId", "/"));
        data.put("symbolTableCode", values.getOrDefault("symbolTableCode", "n"));
        if (!values.getOrDefault("lat", "").isBlank() || !values.getOrDefault("lon", "").isBlank()) {
            data.put("lat", Double.parseDouble(values.get("lat")));
            data.put("lon", Double.parseDouble(values.get("lon")));
        }
        schedules.options(id, data, version);
        return "redirect:/schedules/" + id;
    }

    @PostMapping("/schedules/save")
    public String save(@RequestParam(defaultValue = "") String id, @RequestParam String callsign,
            @RequestParam String name, @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String voiceFrequency,
            @RequestParam(defaultValue = "false") boolean open,
            @RequestParam(defaultValue = "false") boolean privateNet,
            @RequestParam Type type, @RequestParam String timezone, @RequestParam(required = false) LocalDateTime firstStart,
            @RequestParam int duration,
            @RequestParam(defaultValue = "1") int ordinal, @RequestParam(defaultValue = "false") boolean enabled,
            @RequestParam(defaultValue = "-1") Long version, @RequestParam String communicationMode,
            @RequestParam(required = false) Set<String> communicationInstanceIds,
            @RequestParam(defaultValue = "-1") Long routingVersion,
            @RequestParam(defaultValue = "") String expectedParticipants,
            @RequestParam Map<String, String> values, @RequestParam double lat, @RequestParam double lon) {
        boolean recurrenceControls = type != Type.ONE_TIME_ONLY && values.containsKey("startTime");
        if (recurrenceControls) {
            try {
                var time = java.time.LocalTime.parse(values.get("startTime"));
                var zone = java.time.ZoneId.of(timezone);
                Schedule previous = id.isBlank() ? null : schedules.detail(id).schedule();
                int day = type == Type.MONTHLY_DAY ? ("LAST".equals(values.get("monthDay")) ? 31 : Integer.parseInt(values.get("monthDay"))) : 1;
                if (day < 1 || day > 31) throw new IllegalArgumentException();
                int weekday = type == Type.WEEKLY || type == Type.MONTHLY_RELATIVE ? Integer.parseInt(values.get("weekday")) : 1;
                var weekDay = java.time.DayOfWeek.of(weekday);
                if (type == Type.MONTHLY_DAY) ordinal = "LAST".equals(values.get("monthDay")) ? 6 : 1;
                // January represents every numbered month day, including 29–31.
                var date = java.time.LocalDate.now(zone).withDayOfYear(1);
                if (type == Type.MONTHLY_DAY) date = date.withDayOfMonth(day);
                if (type == Type.WEEKLY || type == Type.MONTHLY_RELATIVE)
                    date = date.with(java.time.temporal.TemporalAdjusters.nextOrSame(weekDay));
                firstStart = date.atTime(time);
                // Retain the existing anchor/cursor when only unrelated fields change.
                if (previous != null && previous.type() == type && previous.timezone().equals(timezone)
                        && previous.firstStart().toLocalTime().equals(time) && previous.ordinal() == ordinal
                        && (type != Type.MONTHLY_DAY || previous.firstStart().getDayOfMonth() == day)
                        && (!(type == Type.WEEKLY || type == Type.MONTHLY_RELATIVE) || previous.firstStart().getDayOfWeek() == weekDay))
                    firstStart = previous.firstStart();
            } catch (java.time.DateTimeException | IllegalArgumentException invalid) {
                throw com.kc1vmz.netcentral.operations.OperationsStore.bad("Choose a valid schedule day, time, and time zone");
            }
        }
        var expected = Arrays.stream(expectedParticipants.split("[,\\s]+")).filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        Edit edit = new Edit(callsign, name, description, voiceFrequency, open, privateNet, type, timezone, firstStart,
                duration, ordinal, enabled, version,
                communicationMode, communicationInstanceIds == null ? Set.of() : communicationInstanceIds,
                routingVersion, expected);
        var options = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        options.put("lat", lat).put("lon", lon);
        for (String field : List.of("announce", "checkinReminder", "questionReminder", "participantInviteAllowed"))
            options.put(field, Boolean.parseBoolean(values.getOrDefault(field, "false")));
        options.put("checkinMessage", values.getOrDefault("checkinMessage", ""));
        options.put("symbolTableId", values.getOrDefault("symbolTableId", "/"));
        options.put("symbolTableCode", values.getOrDefault("symbolTableCode", "n"));
        Schedule saved = schedules.saveConfigured(id, edit, options, recurrenceControls);
        return id.isBlank() ? "redirect:/nets#scheduled-nets" : "redirect:/schedules/" + saved.id();
    }

    @PostMapping("/schedules/{id}/delete")
    public String delete(@PathVariable String id, @RequestParam long version) {
        schedules.delete(id, version);
        return "redirect:/nets#scheduled-nets";
    }

    @ExceptionHandler(ResponseStatusException.class)
    public String invalid(ResponseStatusException e, HttpServletResponse response, Model model) {
        response.setStatus(e.getStatusCode().value());
        model.addAttribute("message", e.getReason());
        return "account-error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String denied(HttpServletResponse response, Model model) {
        response.setStatus(403);
        model.addAttribute("message", "Schedule owner or administrator access required");
        return "account-error";
    }
}
