package com.kc1vmz.netcentral.controller;

import com.kc1vmz.netcentral.accessor.NetSchedulerAccessor;
import com.kc1vmz.netcentral.net.ScheduleModels.Schedule;
import com.kc1vmz.netcentral.net.ScheduleModels.Type;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TreeSet;
import org.springframework.ui.Model;

final class NetFormSupport {
    private NetFormSupport() {}
    static void scheduleFields(Model model, NetSchedulerAccessor schedules) {
        model.addAttribute("defaultTimezone", schedules.defaultTimezone());
        var timezones = new TreeSet<>(ZoneId.getAvailableZoneIds());
        timezones.add(schedules.defaultTimezone());
        if (model.getAttribute("schedule") instanceof Schedule schedule)
            timezones.add(schedule.timezone());
        model.addAttribute("timezones", timezones);
        Schedule schedule = (Schedule) model.getAttribute("schedule");
        var anchor = schedule == null ? LocalDateTime.now(ZoneId.of(schedules.defaultTimezone())) : schedule.firstStart();
        model.addAttribute("startTime", anchor.toLocalTime().withSecond(0).withNano(0).toString());
        model.addAttribute("weekday", anchor.getDayOfWeek().getValue());
        model.addAttribute("monthDay", schedule != null && schedule.type() == Type.MONTHLY_DAY && schedule.ordinal() == 6 ? "LAST" : Integer.toString(anchor.getDayOfMonth()));
        model.addAttribute("weekdays", List.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY));
    }
}
