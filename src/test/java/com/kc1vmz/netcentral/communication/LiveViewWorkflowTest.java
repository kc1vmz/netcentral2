package com.kc1vmz.netcentral.communication;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kc1vmz.netcentral.NetCentralApplication;
import com.kc1vmz.netcentral.accessor.StationAccessor;

import static com.kc1vmz.netcentral.communication.ManagedConnectionTest.await;
import static org.assertj.core.api.Assertions.*;

class LiveViewWorkflowTest {
    @TempDir
    Path directory;

    private ServletWebServerApplicationContext start(int limit) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class,
                CommunicationIntegrationTest.Fakes.class).run("--server.port=0", "--server.address=127.0.0.1",
                        "--logging.level.root=WARN", "--spring.main.banner-mode=off",
                        "--netcentral.workflow.start=false", "--netcentral.live-resource-limit=" + limit,
                        "--spring.datasource.url=jdbc:h2:file:"
                                + directory.resolve("live").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE");
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

    private com.fasterxml.jackson.databind.JsonNode ok(CommunicationIntegrationTest.Browser b,
            java.net.http.HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
        return r.body().isBlank() ? null : b.json(r);
    }

    @Test
    void ignoredListIncludesUnheardCallsignsAndReflectsRemoval() throws Exception {
        try (var app = start(1)) {
            var admin = user(app, "ignore-admin");
            var reader = user(app, "ignore-reader");
            var station = ok(admin, admin.send("POST", "/api/v1/trackedStations", Map.of("callsign", "N1TEST")));
            assertThat(station.path("data").path("tracked").asBoolean()).isTrue();
            station = ok(admin, admin.send("PUT", "/api/v1/trackedStations/N1TEST?version=" + station.path("version").asLong(), Map.of("tracked", false)));
            assertThat(station.path("data").path("tracked").asBoolean()).isFalse();
            station = ok(admin, admin.send("PUT", "/api/v1/trackedStations/N1TEST?version=" + station.path("version").asLong(), Map.of("tracked", true)));
            assertThat(station.path("data").path("tracked").asBoolean()).isTrue();
            assertThat(reader.send("PUT", "/api/v1/trackedStations/N1TEST?version=" + station.path("version").asLong(), Map.of("tracked", false)).statusCode()).isEqualTo(403);
            for (String call : List.of("N2TEST", "N1TEST"))
                ok(admin, admin.send("POST", "/api/v1/ignoreStations", Map.of("callsign", call)));
            var snapshot = reader.json(reader.get("/api/v1/live/snapshot"));
            assertThat(snapshot.path("ignoredCallsigns").toString()).isEqualTo("[\"N1TEST\",\"N2TEST\"]");
            assertThat(reader.send("DELETE", "/api/v1/ignoreStations/N1TEST", Map.of()).statusCode()).isEqualTo(403);
            ok(admin, admin.send("DELETE", "/api/v1/ignoreStations/N1TEST", Map.of()));
            assertThat(admin.json(admin.get("/api/v1/live/snapshot")).path("ignoredCallsigns").toString())
                    .isEqualTo("[\"N2TEST\"]");
        }
    }

    @Test
    void callsignProjectionGroupsHeardSsidsAndExposesAttributesWithoutIds() throws Exception {
        try (var app = start(1)) {
            var b = user(app, "callsign-admin");
            var jdbc = app.getBean(JdbcTemplate.class);
            for (String call : List.of("N1ABC", "N1ABC-7", "N2ABC", "N3ABC"))
                ok(b, b.send("POST", "/api/v1/trackedStations", Map.of("callsign", call)));
            jdbc.update("UPDATE domain_record SET payload=?,updated_at=? WHERE kind='STATION' AND record_key=?",
                    "{\"lastHeard\":\"2026-10-01T00:00:00Z\",\"name\":\"Operator\"}", OffsetDateTime.now().minusMinutes(1), "N1ABC");
            jdbc.update("UPDATE domain_record SET payload=?,updated_at=? WHERE kind='STATION' AND record_key=?",
                    "{\"lastHeard\":\"2026-10-01T00:01:00Z\",\"country\":\"US\",\"state\":\"MA\",\"licenseClass\":\"Extra\"}", OffsetDateTime.now(), "N1ABC-7");
            jdbc.update("UPDATE domain_record SET payload=? WHERE kind='STATION' AND record_key=?",
                    "{\"lastHeard\":\"2026-10-01T00:00:00Z\"}", "N2ABC");
            var snapshot = b.json(b.get("/api/v1/live/snapshot"));
            assertThat(snapshot.path("counts").path("callsignsHeard").asInt()).isEqualTo(2);
            assertThat(snapshot.path("callsignsLimited").asBoolean()).isTrue();
            assertThat(snapshot.path("callsigns").size()).isEqualTo(1);
            var call = snapshot.path("callsigns").get(0);
            assertThat(call.path("callsign").asText()).isEqualTo("N1ABC");
            assertThat(call.path("name").asText()).isEqualTo("Operator");
            assertThat(call.path("country").asText()).isEqualTo("US");
            assertThat(call.path("state").asText()).isEqualTo("MA");
            assertThat(call.path("license").asText()).isEqualTo("Extra");
            assertThat(call.has("id")).isFalse();
            assertThat(call.size()).isEqualTo(6);
            assertThat(call.path("lastHeard").asText()).isEqualTo("2026-10-01T00:01:00Z");
            jdbc.update("DELETE FROM domain_record WHERE kind='STATION' AND record_key LIKE 'N1ABC%'");
            snapshot = b.json(b.get("/api/v1/live/snapshot"));
            assertThat(snapshot.path("callsignsLimited").asBoolean()).isFalse();
            assertThat(snapshot.path("callsigns").get(0).path("callsign").asText()).isEqualTo("N2ABC");
            assertThat(snapshot.path("callsigns").get(0).path("name").isNull()).isTrue();
        }
    }

    @Test
    void liveResourcesMoveWithdrawExpireAndReconnectWithoutStaleRows() throws Exception {
        try (var app = start(5000)) {
            var b = user(app, "root");
            String instance = ok(b,
                    b.send("POST", "/api/v1/communicationInstances",
                            Map.of("name", "Disabled fixture", "type", "KISS_TCP", "enabled", false, "transmitEnabled",
                                    false, "host", "localhost", "port", 8001, "kissPort", 0)))
                    .path("configuration").path("id").asText();
            try (var live = new NetWorkflowTest.Live(b, "/api/v1/live/events", true)) {
                live.until(s -> s.path("resources").isEmpty());
                var body = new HashMap<String, Object>(Map.of("callsign", "RES007", "type", "RESOURCE", "lat", 42.0,
                        "lon", -71.0, "communicationMode", "SELECTED", "communicationInstanceIds", List.of()));
                ok(b, b.send("POST", "/api/v1/APRSObjects/requests", body));
                live.until(s -> s.path("resources").path(0).path("data").path("lat").asDouble() == 42);
                body.put("lat", 43.0);
                body.remove("communicationMode");
                ok(b, b.send("PUT", "/api/v1/APRSObjects/RES007?version=0", body));
                live.until(s -> s.path("resources").path(0).path("data").path("lat").asDouble() == 43);
                ok(b, b.send("DELETE", "/api/v1/APRSObjects/RES007?version=1", Map.of()));
                live.until(s -> s.path("resources").isEmpty());
                app.getBean(PacketHub.class).receive(instance,
                        new Packet("N1ABC", "APANC1", List.of(), "!4903.50N/07201.75W-Test"));
                live.until(s -> s.path("counts").path("stations").asInt() == 1);
                app.getBean(JdbcTemplate.class).update("UPDATE domain_record SET updated_at=? WHERE kind='STATION'",
                        OffsetDateTime.now().minusDays(3));
                app.getBean(StationAccessor.class).cleanup();
                live.until(s -> s.path("counts").path("stations").asInt() == 0);
            }
            try (var reconnect = new NetWorkflowTest.Live(b, "/api/v1/live/events", true)) {
                var s = reconnect.until(v -> v.path("resources").isEmpty());
                assertThat(s.path("resources")).isEmpty();
            }
            for (String path : List.of("/dashboard", "/explorer", "/nets", "/archives", "/setup/operations",
                    "/setup/map-cache", "/communications"))
                assertThat(b.get(path).statusCode()).as(path).isEqualTo(200);
        }
    }

    @Test
    void snapshotsFilterPrivateNetsAndRevokedSessionsStopStreaming() throws Exception {
        try (var app = start(5000)) {
            var admin = user(app, "root");
            var owner = user(app, "owner");
            var viewer = user(app, "viewer");
            String id = ok(owner,
                    owner.send("POST", "/api/v1/nets",
                            Map.of("callsign", "PRIV07", "name", "Confidential", "privateNet", true)))
                    .path("id").asText();
            assertThat(ok(viewer, viewer.get("/api/v1/live/snapshot")).path("nets")).isEmpty();
            assertThat(ok(owner, owner.get("/api/v1/live/snapshot")).path("nets").size()).isEqualTo(1);
            var anonymous = new CommunicationIntegrationTest.Browser(app);
            assertThat(anonymous.get("/api/v1/live/snapshot").statusCode()).isEqualTo(401);
            try (var live = new NetWorkflowTest.Live(viewer, "/api/v1/live/events", true)) {
                assertThat(live.until(s -> true).toString()).doesNotContain("PRIV07", "Confidential");
                ok(viewer, viewer.send("POST", "/api/v1/logout", Map.of()));
                await(live.closed::get);
            }
            assertThat(admin.get("/nets/" + id).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void projectionCapsResourcesAndDoesNotExposeDirectedMessageBodies() throws Exception {
        try (var app = start(2)) {
            var b = user(app, "root");
            for (String call : List.of("N1AAA", "N1AAB", "N1AAC"))
                ok(b, b.send("POST", "/api/v1/trackedStations", Map.of("callsign", call)));
            app.getBean(JdbcTemplate.class).update("UPDATE domain_record SET payload=? WHERE record_key='N1AAA'",
                    "{\"callsign\":\"N1AAA\",\"comment\":\":NET001   :MC sensitive\"}");
            var s = ok(b, b.get("/api/v1/live/snapshot"));
            assertThat(s.path("resources").size()).isEqualTo(2);
            assertThat(s.path("limited").asBoolean()).isTrue();
            assertThat(s.toString()).doesNotContain("sensitive");
            assertThat(s.path("counts").path("stations").asInt()).isEqualTo(3);
        }
    }

    @Test
    void dashboardCountsHeardRootsAndStatisticsRequireAuthentication() throws Exception {
        try (var app = start(5000)) {
            var anonymous = new CommunicationIntegrationTest.Browser(app);
            assertThat(anonymous.get("/api/v1/statistics/communications").statusCode()).isEqualTo(401);
            var b = user(app, "root");
            for (String call : List.of("N1AAA-1", "N1AAA-2", "N1BBB", "N1CCC"))
                ok(b, b.send("POST", "/api/v1/trackedStations", Map.of("callsign", call)));
            var jdbc = app.getBean(JdbcTemplate.class);
            for (String call : List.of("N1AAA-1", "N1AAA-2", "N1BBB"))
                jdbc.update("UPDATE domain_record SET payload=? WHERE kind='STATION' AND record_key=?",
                        "{\"callsign\":\"" + call + "\",\"lastHeard\":\"2026-09-29T12:00:00Z\"}", call);
            var snapshot = ok(b, b.get("/api/v1/live/snapshot"));
            assertThat(snapshot.path("counts").path("callsignsHeard").asInt()).isEqualTo(2);
            ok(b, b.send("POST", "/api/v1/ignoreStations", Map.of("callsign", "N1AAA")));
            var ignoredSnapshot = ok(b, b.get("/api/v1/live/snapshot"));
            for (var resource : ignoredSnapshot.path("resources"))
                assertThat(resource.path("data").path("ignored").asBoolean())
                        .isEqualTo(resource.path("id").asText().startsWith("N1AAA"));
            assertThat(b.get("/stations/N1AAA-1").body()).contains("General Information", "Current Weather", "Previous weather reports",
                    "<dt>Ignored</dt><dd>Yes</dd>")
                    .doesNotContain("Object Security", "Save station", "Weather history", "Weather PDF", "name=\"equipment\"");
            var stats = ok(b, b.get("/api/v1/statistics/communications"));
            assertThat(stats.path("packetsSent").asInt()).isZero();
            assertThat(stats.path("incomingQueued").asInt()).isZero();
            assertThat(stats.path("lastSent").isNull()).isTrue();
            String html = b.get("/dashboard").body();
            assertThat(html).contains("Scheduled Nets", "Callsigns heard", ">My account</span>").doesNotContain(
                    "Detailed Statistics", "Create a net", "Find net", "Explore resources",
                    "Communication observations", ">Register</span>");
            assertThat(b.get("/statistics").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void focusedSettingsPreserveOtherGroupsAndPagesMatchNavigation() throws Exception {
        try (var app = start(5000)) {
            var b = user(app, "root");
            var jdbc = app.getBean(JdbcTemplate.class);
            for (var section : Map.of("announcements", "beaconSeconds", "federation", "federationEnabled",
                    "net-manager", "netManagerCallsign", "timers", "stationRetentionSeconds").entrySet()) {
                var response = b.get("/setup/operations?section=" + section.getKey());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("name=\"" + section.getValue() + "\"");
                if (!section.getKey().equals("announcements"))
                    assertThat(response.body()).doesNotContain("name=\"beaconSeconds\"");
            }
            String before = jdbc.queryForObject(
                    "SELECT setting_value FROM application_settings WHERE setting_key='operations.stationRetentionSeconds'",
                    String.class);
            assertThat(b.raw("POST", "/setup/operations", "section=announcements&beaconSeconds=777",
                    "application/x-www-form-urlencoded", b.csrf()).statusCode()).isEqualTo(302);
            assertThat(jdbc.queryForObject(
                    "SELECT setting_value FROM application_settings WHERE setting_key='operations.beaconSeconds'",
                    String.class)).isEqualTo("777");
            assertThat(jdbc.queryForObject(
                    "SELECT setting_value FROM application_settings WHERE setting_key='operations.stationRetentionSeconds'",
                    String.class)).isEqualTo(before);
            assertThat(b.raw("POST", "/setup/operations", "section=announcements&packetLimit=25",
                    "application/x-www-form-urlencoded", b.csrf()).statusCode()).isEqualTo(400);
            assertThat(b.get("/setup/radio?section=ignored").body()).contains("Add ignored station", "ignore-confirm")
                    .doesNotContain("Add classification rule", "Add access rule");
            assertThat(b.get("/setup/radio?section=classification").body()).contains("Add classification rule")
                    .doesNotContain("Save ignore rule", "Add access rule");
            assertThat(b.get("/setup/radio?section=access").body()).contains("Add access rule")
                    .doesNotContain("Save ignore rule", "Add classification rule");
            assertThat(b.get("/nets").body()).contains("Scheduled nets", "Create a scheduled net")
                    .doesNotContain("Find net", "Completed nets", "Setup: Map Cache");
            assertThat(b.get("/archives").body()).contains("Net Archive").doesNotContain("Create a net", "Find net",
                    "Setup: Map Cache");
            assertThat(b.get("/explorer").body()).doesNotContain("Situational awareness", "Map width",
                    "Fit visible resources", "Include withdrawn");
            assertThat(b.get("/help").body()).contains("<h1>Help</h1>", "Online resources", "https://www.kc1vmz.com",
                    "Licenses and acknowledgments");
            assertThat(b.get("/licenses").headers().firstValue("Location").orElse("")).endsWith("/help#licenses");
            assertThat(b.get("/tools?section=winlink").body()).contains("Send via Winlink")
                    .doesNotContain("Send radiogram");
            assertThat(b.get("/tools?section=nts").body()).contains("Send radiogram")
                    .doesNotContain("Send via Winlink");
            assertThat(b.get("/tools?section=packet-logging").body())
                    .contains("Packet logging enabled", "Download raw packets")
                    .doesNotContain("Send radiogram", "Send via Winlink");
            assertThat(b.raw("POST", "/tools/packet-logging", "rawPacketLogging=true",
                    "application/x-www-form-urlencoded", b.csrf()).statusCode()).isEqualTo(302);
            assertThat(jdbc.queryForObject(
                    "SELECT setting_value FROM application_settings WHERE setting_key='operations.rawPacketLogging'",
                    String.class)).isEqualTo("true");
            var ordinary = user(app, "ordinary");
            assertThat(ordinary.raw("POST", "/tools/packet-logging", "rawPacketLogging=false",
                    "application/x-www-form-urlencoded", ordinary.csrf()).statusCode()).isEqualTo(403);
            assertThat(ordinary.get("/setup/operations?section=timers").statusCode()).isEqualTo(403);
        }
    }
}
