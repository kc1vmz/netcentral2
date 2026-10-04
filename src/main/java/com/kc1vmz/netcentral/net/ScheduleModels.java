package com.kc1vmz.netcentral.net;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

import com.kc1vmz.netcentral.communication.RoutingService;

public final class ScheduleModels {
        private ScheduleModels() {
        }

        public enum Type {
                ONE_TIME_ONLY, DAILY, WEEKLY, MONTHLY_DAY, MONTHLY_RELATIVE
        }

        public record Schedule(String id, String callsign, String name, String description, String voiceFrequency,
                        String ownerId, String creatorName, boolean open, boolean privateNet, Type type,
                        String timezone,
                        LocalDateTime firstStart, int duration, int ordinal, boolean enabled, Instant nextStartTime,
                        long version) {
        }

        public record Edit(String callsign, String name, String description, String voiceFrequency,
                        Boolean open, Boolean privateNet, Type type, String timezone, LocalDateTime firstStart,
                        Integer duration, Integer ordinal, Boolean enabled, Long version, String communicationMode,
                        Set<String> communicationInstanceIds, Long routingVersion, Set<String> expectedParticipants) {
        }

        public record Detail(Schedule schedule, RoutingService.Route routing, Set<String> expectedParticipants,
                        boolean canManage) {
        }

        public record Occurrence(String id, String scheduleId, Instant plannedStart, Instant plannedEnd,
                        Instant activatedAt, Instant finalizedAt, String netId, String state) {
        }
}
