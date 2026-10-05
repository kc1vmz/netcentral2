package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.kc1vmz.netcentral.NetCentralApplication;
import com.kc1vmz.netcentral.accessor.NetSchedulerAccessor;
import com.kc1vmz.netcentral.net.ScheduleCalendar;
import com.kc1vmz.netcentral.net.ScheduleModels.Type;

import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ScheduleWorkflowTest {
    @TempDir
    Path directory;

    private ServletWebServerApplicationContext start(boolean runtime) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--logging.level.root=WARN",
                "--netcentral.workflow.start=" + runtime, "--spring.datasource.url=jdbc:h2:file:"
                        + directory.resolve("schedule").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
    }

    private CommunicationIntegrationTest.Browser user(ServletWebServerApplicationContext app, String name)
            throws Exception {
        var b = new CommunicationIntegrationTest.Browser(app);
        b.send("POST", "/api/v1/users/register",
                Map.of("username", name, "password", "test-password-123", "password2", "test-password-123"));
        assertThat(
                b.send("POST", "/api/v1/login", Map.of("username", name, "password", "test-password-123")).statusCode())
                .isEqualTo(200);
        return b;
    }

    private Map<String, Object> definition(String call, Instant start, int duration) {
        var body = new HashMap<String, Object>();
        body.put("callsign", call);
        body.put("name", "Scheduled training");
        body.put("type", "ONE_TIME_ONLY");
        body.put("timezone", "UTC");
        body.put("firstStart", LocalDateTime.ofInstant(start, ZoneOffset.UTC).toString());
        body.put("duration", duration);
        body.put("communicationMode", "SELECTED");
        body.put("communicationInstanceIds", List.of());
        body.put("expectedParticipants", List.of("N1ABC-1"));
        return body;
    }

    private String create(CommunicationIntegrationTest.Browser b, Map<String, Object> body) throws Exception {
        var response = b.send("POST", "/api/v1/scheduledNets", body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return b.json(response).path("id").asText();
    }

    private JsonNode occurrence(CommunicationIntegrationTest.Browser b, String id) throws Exception {
        var response = b.get("/api/v1/scheduledNets/" + id + "/occurrences");
        assertThat(response.statusCode()).isEqualTo(200);
        var values = b.json(response);
        assertThat(values.size()).isEqualTo(1);
        return values.get(0);
    }

    @Test
    void recurringFormControlsPersistDayTimeAndNextOccurrence() throws Exception {
        try (var app = start(false)) {
            var b = user(app, "recurring-admin");
            var jdbc = app.getBean(JdbcTemplate.class);
            int index = 0;
            for (String choice : List.of("DAILY", "WEEKLY", "MONTHLY_DAY", "MONTHLY_RELATIVE")) {
                String call = "FORM" + (++index);
                String fields = "callsign=" + call + "&name=Recurring&type=" + choice
                        + "&timezone=America%2FNew_York&startTime=19%3A30&weekday=7&monthDay=LAST&ordinal=2"
                        + "&durationHours=1&enabled=true&communicationMode=ALL&lat=42&lon=-71";
                var result = b.raw("POST", "/schedules/save", fields, "application/x-www-form-urlencoded", b.csrf());
                assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(302);
                var row = jdbc.queryForMap("SELECT * FROM net_schedule WHERE callsign=?", call);
                String id = row.get("ID").toString();
                var first = jdbc.queryForObject("SELECT first_start FROM net_schedule WHERE id=?", LocalDateTime.class, id);
                var next = ((java.time.OffsetDateTime)row.get("NEXT_START")).toInstant();
                var localNext = next.atZone(java.time.ZoneId.of("America/New_York"));
                assertThat(first.toLocalTime()).isEqualTo(java.time.LocalTime.of(19,30));
                assertThat(next).isAfter(Instant.now().minusSeconds(1));
                assertThat(localNext.toLocalTime()).isEqualTo(java.time.LocalTime.of(19,30));
                if (choice.equals("WEEKLY") || choice.equals("MONTHLY_RELATIVE"))
                    assertThat(localNext.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SUNDAY);
                if (choice.equals("MONTHLY_RELATIVE")) assertThat(localNext.getDayOfMonth()).isBetween(8,14);
                if (choice.equals("MONTHLY_DAY")) assertThat(localNext.getDayOfMonth()).isEqualTo(localNext.toLocalDate().lengthOfMonth());
                var page = b.get("/schedules/" + id + "/edit");
                assertThat(page.statusCode()).isEqualTo(200);
                assertThat(page.body()).contains("name=\"startTime\"", "Sunday", "Saturday", "value=\"LAST\"");
                long route = jdbc.queryForObject("SELECT version FROM communication_route WHERE owner_key=?", Long.class, "SCHEDULE:" + id);
                result = b.raw("POST", "/schedules/save", fields + "&id=" + id + "&version=" + row.get("VERSION") + "&routingVersion=" + route,
                        "application/x-www-form-urlencoded", b.csrf());
                assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(302);
                assertThat(jdbc.queryForObject("SELECT next_start FROM net_schedule WHERE id=?", java.time.OffsetDateTime.class, id).toInstant()).isEqualTo(next);
            }
        }
    }

    @Test
    void configuredScheduleFormPersistsOptionsAndReturnsToCorrectPages() throws Exception {
        try(var app=start(false)) {
            var b=user(app,"form-admin");
            var page=b.get("/schedules/new");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("Just once", "Every day", "Every week", "Monthly on a specific day", "Monthly on a relative day", "net-position-map", "schedule-save-dialog", "data-symbol-picker").doesNotContain("Private web access");
            assertThat(page.body()).contains("<select name=\"timezone\"", "value=\"America/New_York\"", "value=\"Europe/London\"", "value=\"Asia/Tokyo\"", "value=\"Australia/Sydney\"", "value=\"UTC\"");
            String fields="callsign=SCH001&name=Scheduled+test&type=DAILY&timezone=UTC&firstStart=2030-01-01T12%3A00&durationHours=2&open=true&enabled=true&communicationMode=ALL&lat=42&lon=-71&checkinMessage=Welcome&announce=true&checkinReminder=true&questionReminder=true&participantInviteAllowed=true&symbolTableId=%2F&symbolTableCode=n";
            for (String invalid : List.of("0", "-1", "1.5", "169")) {
                assertThat(b.raw("POST","/schedules/save",fields.replace("durationHours=2","durationHours="+invalid),
                    "application/x-www-form-urlencoded",b.csrf()).statusCode()).isEqualTo(400);
            }
            var result=b.raw("POST","/schedules/save",fields,"application/x-www-form-urlencoded",b.csrf());
            assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(302);
            assertThat(result.headers().firstValue("Location").orElseThrow()).endsWith("/nets#scheduled-nets");
            String id=app.getBean(JdbcTemplate.class).queryForObject("SELECT id FROM net_schedule WHERE callsign='SCH001'",String.class);
            assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT duration FROM net_schedule WHERE id=?",Integer.class,id)).isEqualTo(120);
            var options=b.json(b.get("/api/v1/scheduledNets/"+id+"/options"));
            assertThat(options.path("checkinMessage").asText()).isEqualTo("Welcome");
            assertThat(options.path("lat").asDouble()).isEqualTo(42);
            assertThat(options.path("announce").asBoolean()).isTrue();
            assertThat(options.path("participantInviteAllowed").asBoolean()).isTrue();
            var editPage=b.get("/schedules/"+id+"/edit");
            assertThat(editPage.statusCode()).isEqualTo(200);
            assertThat(editPage.body()).contains("value=\"UTC\" selected=\"selected\"");
            assertThat(editPage.body()).contains("Duration (hours)", "name=\"durationHours\"", "value=\"2\"", "Update", "data-return-url=\"/schedules/"+id+"\"");
            long version=app.getBean(JdbcTemplate.class).queryForObject("SELECT version FROM net_schedule WHERE id=?",Long.class,id);
            long route=app.getBean(JdbcTemplate.class).queryForObject("SELECT version FROM communication_route WHERE owner_key=?",Long.class,"SCHEDULE:"+id);
            result=b.raw("POST","/schedules/save",fields.replace("announce=true","announce=false")+"&id="+id+"&version="+version+"&routingVersion="+route,"application/x-www-form-urlencoded",b.csrf());
            assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(302);
            assertThat(result.headers().firstValue("Location").orElseThrow()).endsWith("/schedules/"+id);
            assertThat(b.json(b.get("/api/v1/scheduledNets/"+id+"/options")).path("announce").asBoolean()).isFalse();
        }
    }

    @Test
    void restartResumesOriginalOccurrenceStartsRemainingAndArchivesExpiredOnce() throws Exception {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        String live, expired, missed, remaining, liveNet, expiredNet;
        try (var app = start(false)) {
            var b = user(app, "root");
            live = create(b, definition("LIVE01", now.minusSeconds(5400), 180));
            expired = create(b, definition("OLD001", now.minusSeconds(5400), 60));
            app.getBean(NetSchedulerAccessor.class).reconcile(now.minusSeconds(4800));
            liveNet = occurrence(b, live).path("netId").asText();
            expiredNet = occurrence(b, expired).path("netId").asText();
            assertThat(b.send("POST", "/api/v1/nets/" + liveNet + "/participants/N1ABC", Map.of()).statusCode())
                    .isEqualTo(200);
            missed = create(b, definition("MISS01", now.minusSeconds(3600), 10));
            remaining = create(b, definition("LATE01", now.minusSeconds(600), 60));
        }
        try (var app = start(true)) {
            var b = user(app, "root");
            assertThat(occurrence(b, live).path("netId").asText()).isEqualTo(liveNet);
            assertThat(occurrence(b, live).path("state").asText()).isEqualTo("ACTIVE");
            var endingSnapshot = b.json(b.get("/api/v1/nets/" + liveNet + "/snapshot"));
            assertThat(endingSnapshot.path("plannedEndTime").asText()).isEqualTo(occurrence(b, live).path("plannedEnd").asText());
            assertThat(endingSnapshot.path("plannedEndTime").asText()).isNotBlank();
            var monitor = b.get("/nets/" + liveNet);
            assertThat(monitor.statusCode()).isEqualTo(200);
            assertThat(monitor.body()).contains("Scheduled end", "net-planned-end");

            assertThat(b.json(b.get("/api/v1/nets/" + liveNet + "/participants")).size()).isEqualTo(1);
            assertThat(occurrence(b, expired).path("state").asText()).isEqualTo("COMPLETED");
            assertThat(Instant.parse(b.json(b.get("/api/v1/completedNets/" + expiredNet)).path("endTime").asText()))
                    .isEqualTo(now.minusSeconds(1800));
            assertThat(occurrence(b, missed).path("state").asText()).isEqualTo("SKIPPED");
            JsonNode late = occurrence(b, remaining);
            assertThat(late.path("state").asText()).isEqualTo("ACTIVE");
            assertThat(Instant.parse(late.path("plannedEnd").asText())).isEqualTo(now.plusSeconds(3000));
            assertThat(Instant.parse(late.path("activatedAt").asText())).isAfterOrEqualTo(now);
            var snapshot = b.json(b.get("/api/v1/nets/" + late.path("netId").asText() + "/snapshot"));
            assertThat(snapshot.path("expectedParticipants").get(0).asText()).isEqualTo("N1ABC");
            assertThat(snapshot.path("routing").path("mode").asText()).isEqualTo("SELECTED");
            app.getBean(NetSchedulerAccessor.class).reconcile(Instant.now());
            assertThat(
                    app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM managed_net", Integer.class))
                    .isEqualTo(3);
        }
        try (var app = start(true)) {
            var b = user(app, "root");
            assertThat(occurrence(b, expired).path("netId").asText()).isEqualTo(expiredNet);
            assertThat(
                    app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM managed_net", Integer.class))
                    .isEqualTo(3);
        }
    }

    @Test
    void rollbackAndConcurrentReconciliationDoNotDuplicateAndRoutingCopies() throws Exception {
        try (var app = start(false)) {
            var b = user(app, "root");
            var scheduler = app.getBean(NetSchedulerAccessor.class);
            var jdbc = app.getBean(JdbcTemplate.class);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var body = definition("ATOMIC", now, 60);
            body.put("type", "DAILY");
            body.put("communicationMode", "ALL");
            String id = create(b, body);
            var transaction = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> {
                scheduler.reconcile(now);
                throw new IllegalStateException("crash before commit");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM net_occurrence", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM managed_net", Integer.class)).isZero();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> scheduler.reconcile(now));
                var c = pool.submit(() -> scheduler.reconcile(now));
                a.get(10, TimeUnit.SECONDS);
                c.get(10, TimeUnit.SECONDS);
            }
            JsonNode occurrence = occurrence(b, id);
            String net = occurrence.path("netId").asText();
            assertThat(b.json(b.get("/api/v1/nets/" + net + "/snapshot")).path("routing").path("mode").asText())
                    .isEqualTo("ALL");
            body.put("version", b.json(b.get("/api/v1/scheduledNets/" + id)).path("version").asLong());
            body.put("routingVersion", 0);
            body.put("communicationMode", "SELECTED");
            body.put("duration", 120);
            assertThat(b.send("PUT", "/api/v1/scheduledNets/" + id, body).statusCode()).isEqualTo(200);
            assertThat(occurrence(b, id).path("plannedEnd").asText()).isEqualTo(now.plusSeconds(3600).toString());
            scheduler.reconcile(now.plusSeconds(3600));
            scheduler.reconcile(now.plusSeconds(3600));
            assertThat(occurrence(b, id).path("state").asText()).isEqualTo("COMPLETED");
            scheduler.reconcile(now.plusSeconds(86400));
            var occurrences = b.json(b.get("/api/v1/scheduledNets/" + id + "/occurrences"));
            assertThat(occurrences.size()).isEqualTo(2);
            String nextNet = occurrences.get(0).path("netId").asText();
            assertThat(nextNet).isNotEqualTo(net);
            assertThat(b.json(b.get("/api/v1/nets/" + nextNet + "/snapshot")).path("routing").path("mode").asText())
                    .isEqualTo("SELECTED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM managed_net", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void permissionsValidationPagesAndCallsignConflict() throws Exception {
        try (var app = start(false)) {
            var admin = user(app, "root");
            var owner = user(app, "owner");
            var outsider = user(app, "other");
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var body = definition("PRIV01", now, 60);
            body.put("privateNet", true);
            String id = create(owner, body);
            for (String suffix : List.of("", "/detail", "/occurrences", "/expectedParticipants"))
                assertThat(outsider.get("/api/v1/scheduledNets/" + id + suffix).statusCode()).isEqualTo(403);
            assertThat(outsider.send("PUT", "/api/v1/scheduledNets/" + id, body).statusCode()).isEqualTo(403);
            assertThat(outsider.send("DELETE", "/api/v1/scheduledNets/" + id + "?version=0", Map.of()).statusCode())
                    .isEqualTo(403);
            assertThat(owner.raw("DELETE", "/api/v1/scheduledNets/" + id + "?version=0", "", "application/json", null)
                    .statusCode()).isEqualTo(403);
            assertThat(admin.get("/api/v1/scheduledNets/" + id).statusCode()).isEqualTo(200);
            assertThat(owner.get("/schedules").headers().firstValue("Location").orElseThrow())
                    .endsWith("/nets#scheduled-nets");
            for (String path : List.of("/nets")) {
                var page = owner.get(path);
                assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
            }
/*            for (String path : List.of("/nets", "/schedules/new", "/schedules/" + id, "/schedules/" + id + "/edit")) {
                var page = owner.get(path);
                assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
            }
*/
            body.put("version", 99);
            assertThat(owner.send("PUT", "/api/v1/scheduledNets/" + id, body).statusCode()).isEqualTo(409);
            body.put("timezone", "Invalid/Zone");
            assertThat(owner.send("POST", "/api/v1/scheduledNets", body).statusCode()).isEqualTo(400);
            assertThat(owner.send("POST", "/api/v1/nets", Map.of("callsign", "PRIV01", "name", "Blocking net"))
                    .statusCode()).isEqualTo(200);
            var scheduler = app.getBean(NetSchedulerAccessor.class);
            scheduler.reconcile(now);
            assertThat(occurrence(owner, id).path("state").asText()).isEqualTo("WAITING");
            scheduler.reconcile(now.plusSeconds(3600));
            assertThat(occurrence(owner, id).path("state").asText()).isEqualTo("SKIPPED");
            assertThat(
                    app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM managed_net", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void calendarBoundariesAndDstAreExplicitAndStable() {
        LocalDateTime anchor = LocalDateTime.parse("2024-01-31T09:00");
        assertThat(ScheduleCalendar.next(Type.MONTHLY_DAY, anchor, "UTC", 1, Instant.parse("2024-01-31T09:00:00Z")))
                .isEqualTo(Instant.parse("2024-02-29T09:00:00Z"));
        assertThat(ScheduleCalendar.next(Type.MONTHLY_DAY, anchor, "UTC", 1, Instant.parse("2024-02-29T09:00:00Z")))
                .isEqualTo(Instant.parse("2024-03-31T09:00:00Z"));
        assertThat(ScheduleCalendar.next(Type.WEEKLY, LocalDateTime.parse("2025-12-29T09:00"), "UTC", 1,
                Instant.parse("2025-12-29T09:00:00Z"))).isEqualTo(Instant.parse("2026-01-05T09:00:00Z"));
        assertThat(ScheduleCalendar.next(Type.MONTHLY_RELATIVE, LocalDateTime.parse("2026-01-01T09:00"), "UTC", 5,
                Instant.parse("2026-01-29T09:00:00Z"))).isEqualTo(Instant.parse("2026-02-26T09:00:00Z"));
        var spring = LocalDateTime.parse("2026-03-07T02:30");
        Instant gap = ScheduleCalendar.next(Type.DAILY, spring, "America/New_York", 1,
                Instant.parse("2026-03-07T07:30:00Z"));
        assertThat(gap).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(ScheduleCalendar.next(Type.DAILY, spring, "America/New_York", 1, gap))
                .isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
        var fall = LocalDateTime.parse("2026-10-31T01:30");
        Instant fold = ScheduleCalendar.next(Type.DAILY, fall, "America/New_York", 1,
                Instant.parse("2026-10-31T05:30:00Z"));
        assertThat(fold).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(ScheduleCalendar.next(Type.DAILY, fall, "America/New_York", 1, fold))
                .isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
        assertThat(ScheduleCalendar.next(Type.ONE_TIME_ONLY, fall, "America/New_York", 1, fold)).isNull();
    }

    @Test
    void selectedRoutesDisableDeleteAndManualClosePreserveOccurrences() throws Exception {
        try (var app = start(false)) {
            var b = user(app, "root");
            var scheduler = app.getBean(NetSchedulerAccessor.class);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var response = b.send("POST", "/api/v1/communicationInstances", Map.of("name", "Disabled fixture", "type",
                    "KISS_TCP", "enabled", false, "host", "127.0.0.1", "port", 8001, "kissPort", 0));
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            String instance = b.json(response).path("configuration").path("id").asText();
            var body = definition("SEL001", now, 60);
            body.put("communicationInstanceIds", List.of(instance));
            String id = create(b, body);
            scheduler.reconcile(now);
            String net = occurrence(b, id).path("netId").asText();
            var snapshot = b.json(b.get("/api/v1/nets/" + net + "/snapshot"));
            assertThat(snapshot.path("routing").path("instanceIds").get(0).asText()).isEqualTo(instance);
            body.put("enabled", false);
            body.put("version", b.json(b.get("/api/v1/scheduledNets/" + id)).path("version").asLong());
            body.put("routingVersion", 0);
            assertThat(b.send("PUT", "/api/v1/scheduledNets/" + id, body).statusCode()).isEqualTo(200);
            scheduler.reconcile(now.plusSeconds(60));
            assertThat(occurrence(b, id).path("state").asText()).isEqualTo("ACTIVE");
            long version = b.json(b.get("/api/v1/scheduledNets/" + id)).path("version").asLong();
            assertThat(b.send("DELETE", "/api/v1/scheduledNets/" + id + "?version=" + version, Map.of()).statusCode())
                    .isEqualTo(200);
            assertThat(b.get("/api/v1/scheduledNets/" + id).statusCode()).isEqualTo(404);
            scheduler.reconcile(now.plusSeconds(3600));
            assertThat(b.get("/api/v1/completedNets/" + net).statusCode()).isEqualTo(200);
            String manual = create(b, definition("EARLY1", now, 60));
            scheduler.reconcile(now);
            String earlyNet = occurrence(b, manual).path("netId").asText();
            assertThat(b.send("DELETE", "/api/v1/nets/" + earlyNet + "?version=0", Map.of()).statusCode())
                    .isEqualTo(200);
            scheduler.reconcile(now.plusSeconds(60));
            scheduler.reconcile(now.plusSeconds(120));
            assertThat(occurrence(b, manual).path("state").asText()).isEqualTo("COMPLETED");
        }
    }

    @Test
    void missedRecurrencesAdvanceAndBlockedOccurrenceStartsOnlyForRemainingTime() throws Exception {
        try (var app = start(false)) {
            var b = user(app, "root");
            var scheduler = app.getBean(NetSchedulerAccessor.class);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            var body = definition("DAILY1", now.minusSeconds(3 * 86400), 60);
            body.put("type", "DAILY");
            String id = create(b, body);
            scheduler.reconcile(now.plusSeconds(600));
            var all = b.json(b.get("/api/v1/scheduledNets/" + id + "/occurrences"));
            assertThat(all.size()).isEqualTo(4);
            assertThat(all.get(0).path("state").asText()).isEqualTo("ACTIVE");
            for (int i = 1; i < 4; i++)
                assertThat(all.get(i).path("state").asText()).isEqualTo("SKIPPED");
            assertThat(b.json(b.get("/api/v1/scheduledNets/" + id)).path("nextStartTime").asText())
                    .isEqualTo(now.plusSeconds(86400).toString());
            var blocking = b.send("POST", "/api/v1/nets", Map.of("callsign", "WAIT01", "name", "Blocker"));
            String blockingId = b.json(blocking).path("id").asText();
            String waiting = create(b, definition("WAIT01", now, 60));
            scheduler.reconcile(now);
            assertThat(occurrence(b, waiting).path("state").asText()).isEqualTo("WAITING");
            assertThat(b.send("DELETE", "/api/v1/nets/" + blockingId + "?version=0", Map.of()).statusCode())
                    .isEqualTo(200);
            scheduler.reconcile(now.plusSeconds(600));
            assertThat(occurrence(b, waiting).path("plannedEnd").asText()).isEqualTo(now.plusSeconds(3600).toString());
            assertThat(occurrence(b, waiting).path("activatedAt").asText()).isEqualTo(now.plusSeconds(600).toString());
        }
    }
}
