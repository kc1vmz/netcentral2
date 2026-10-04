package com.kc1vmz.netcentral.net;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

import com.kc1vmz.netcentral.net.ScheduleModels.Type;

/**
 * Wall-clock recurrences; elapsed duration is deliberately handled separately.
 */
public final class ScheduleCalendar {
    private ScheduleCalendar() {
    }

    public static Instant next(Type type, LocalDateTime anchor, String timezone, int ordinal, Instant after) {
        ZoneId zone = ZoneId.of(timezone);
        Instant first = anchor.atZone(zone).toInstant();
        if (type == Type.ONE_TIME_ONLY)
            return after == null || first.isAfter(after) ? first : null;
        LocalDate date = after == null ? anchor.toLocalDate() : after.atZone(zone).toLocalDate();
        if (date.isBefore(anchor.toLocalDate()))
            date = anchor.toLocalDate();
        // Monthly dates clamp to the last day; relative fifth/last clamps to the last
        // matching weekday.
        for (int i = 0; i < 370; i++, date = date.plusDays(1)) {
            boolean match = switch (type) {
                case DAILY -> true;
                case WEEKLY -> date.getDayOfWeek() == anchor.getDayOfWeek();
                case MONTHLY_DAY -> date.getDayOfMonth() == Math.min(anchor.getDayOfMonth(), date.lengthOfMonth());
                case MONTHLY_RELATIVE -> date.equals(relative(YearMonth.from(date), anchor.getDayOfWeek(), ordinal));
                default -> false;
            };
            if (!match)
                continue;
            // Java shifts a gap forward by the gap length and chooses the earlier offset in
            // a fold.
            Instant candidate = date.atTime(anchor.toLocalTime()).atZone(zone).toInstant();
            if (!candidate.isBefore(first) && (after == null || candidate.isAfter(after)))
                return candidate;
        }
        throw new IllegalStateException("No recurrence within a year");
    }

    private static LocalDate relative(YearMonth month, DayOfWeek day, int ordinal) {
        LocalDate last = month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day));
        if (ordinal == 6)
            return last;
        LocalDate candidate = month.atDay(1).with(TemporalAdjusters.nextOrSame(day)).plusWeeks(ordinal - 1);
        return candidate.getMonth() == month.getMonth() ? candidate : last;
    }
}
