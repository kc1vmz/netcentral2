package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.kc1vmz.netcentral.NetCentralApplication;
import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.net.AprsPosition;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.kc1vmz.netcentral.communication.ManagedConnectionTest.await;
import static org.assertj.core.api.Assertions.*;

class NetWorkflowTest {
    @TempDir
    Path directory;
    private static final String PASSWORD = "correct-horse-123";

    private ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class,
                CommunicationIntegrationTest.Fakes.class).run(
                        "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--spring.datasource.url=jdbc:h2:file:"
                                + directory.resolve("workflow").toString().replace('\\', '/')
                                + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--spring.datasource.password=");
    }

    private CommunicationIntegrationTest.Browser user(ServletWebServerApplicationContext app, String name)
            throws Exception {
        var browser = new CommunicationIntegrationTest.Browser(app);
        browser.send("POST", "/api/v1/users/register",
                Map.of("username", name, "password", PASSWORD, "password2", PASSWORD));
        assertThat(browser.send("POST", "/api/v1/login", Map.of("username", name, "password", PASSWORD)).statusCode())
                .isEqualTo(200);
        return browser;
    }

    private JsonNode json(CommunicationIntegrationTest.Browser browser, HttpResponse<String> response)
            throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return browser.json(response);
    }

    private String instance(CommunicationIntegrationTest.Browser browser) throws Exception {
        return json(browser, browser.send("POST", "/api/v1/communicationInstances", Map.of(
                "name", "Fixture", "type", "KISS_TCP", "enabled", true, "transmitEnabled", true, "host", "127.0.0.1",
                "port", 8001, "kissPort", 0))).path("configuration").path("id").asText();
    }

    private String net(CommunicationIntegrationTest.Browser browser, String call, String instance, boolean privateNet,
            boolean open) throws Exception {
        return json(browser, browser.send("POST", "/api/v1/nets", Map.of(
                "callsign", call, "name", "Emergency coordination", "description", "Local training net", "open", open,
                "privateNet", privateNet,
                "communicationMode", "SELECTED", "communicationInstanceIds", List.of(instance), "expectedParticipants",
                List.of("N1ABC")))).path("id").asText();
    }

    private void incoming(CommunicationIntegrationTest.FakeTransport transport, String from, String net,
            String command) {
        transport.incoming.add(new Packet(from, "APANC1", List.of(), ":" + String.format("%-9s", net) + ":" + command));
    }

    @Test
    void radioToLiveMonitorThroughRestartArchiveAndPdf() throws Exception {
        String id, connection;
        try (var app = start()) {
            var browser = user(app, "root");
            connection = instance(browser);
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(connection));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(connection);
            id = net(browser, "NET001", connection, false, true);
            assertThat(browser.get("/nets").statusCode()).isEqualTo(302);
            assertThat(browser.get("/nets/new").statusCode()).isEqualTo(200);
            assertThat(browser.get("/nets/" + id + "/edit").statusCode()).isEqualTo(200);
            var page = browser.get("/nets/" + id);
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("net-monitor.js", "Participant map", "Net control");
            assertThat(browser.get("/webjars/leaflet/1.9.4/leaflet.js").statusCode()).isEqualTo(200);
            try (var live = new Live(browser, id)) {
                live.until(s -> s.path("net").path("id").asText().equals(id));
                transport.incoming.add(new Packet("N1ABC", "APRS", List.of(), "!4903.50N/07201.75W-Test"));
                incoming(transport, "N1ABC", "NET001", "CI{11\r");
                JsonNode checkin = live.until(
                        s -> s.path("participants").size() == 1 && !s.path("participants").get(0).path("lat").isNull());
                assertThat(checkin.path("participants").get(0).path("lat").asDouble()).isCloseTo(49.058333,
                        within(0.00001));
                await(() -> transport.sent.stream().anyMatch(p -> p.information().contains("ack11")));
                incoming(transport, "N1ABC", "NET001", "CI{11\r");
                await(() -> transport.sent.stream().filter(p -> p.information().endsWith(":ack11")).count() == 2);
                assertThat(transport.sent.stream().filter(p -> p.information().contains("You checked into net")).count()).isEqualTo(1);
                incoming(transport, "N1ABC", "NET001", "S Ready{12");
                live.until(s -> s.path("participants").get(0).path("status").asText().equals("Ready"));
                transport.incoming.add(new Packet("N1ABC", "APRS", List.of(), "!5003.50N/07301.75W-Moved"));
                live.until(s -> s.path("participants").get(0).path("lat").asDouble() > 50);
                incoming(transport, "N1ABC", "NET001", "MC Supplies needed{13");
                live.until(s -> s.path("messages").findValuesAsText("message").contains("Supplies needed"));
                var sent = json(browser, browser.send("POST", "/api/v1/nets/" + id + "/messages",
                        Map.of("recipient", "N1ABC", "message", "Proceed to staging", "acknowledgementRequested",
                                true)));
                String number = "";
                for (JsonNode message : sent)
                    if (message.path("message").asText().equals("Proceed to staging"))
                        number = message.path("ackNumber").asText();
                assertThat(number).isNotBlank();
                incoming(transport, "N1ABC", "NET001", "S Ready{15}" + number + "\r\n");
                live.until(s -> {
                    for (JsonNode message : s.path("messages"))
                        if (message.path("message").asText().equals("Proceed to staging"))
                            return message.path("delivery").asText().equals("ACKNOWLEDGED");
                    return false;
                });
                incoming(transport, "N1ABC", "NET001", "CO{14");
                live.until(s -> !s.path("participants").get(0).path("endTime").isNull());
                incoming(transport, "N1ABC", "NET001", "CI{15");
                live.until(s -> s.path("participants").get(0).path("endTime").isNull());
            }
            // Simulate a committed pending send at process failure; recovery must not
            // dispatch it.
            app.getBean(JdbcTemplate.class).update("INSERT INTO net_message VALUES(?,?,?,?,?,?,?,?,?)",
                    "pending-at-crash", id, "NET001", "N1ABC", "Do not replay", OffsetDateTime.now(), "OUT", "PENDING",
                    null);
        }
        try (var app = start()) {
            var browser = user(app, "root"); // duplicate registration is harmless; login still succeeds.
            await(() -> app.getBean(CommunicationManager.class).available(connection));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(connection);
            JsonNode recovered = json(browser, browser.get("/api/v1/nets/" + id + "/snapshot"));
            assertThat(recovered.path("net").path("id").asText()).isEqualTo(id);
            assertThat(recovered.path("participants").get(0).path("status").asText()).isEqualTo("Ready");
            assertThat(transport.sent).isEmpty();
            assertThat(recovered.path("messages").findValuesAsText("delivery")).contains("DISCARDED");
            long version = recovered.path("net").path("version").asLong();
            assertThat(browser.send("DELETE", "/api/v1/nets/" + id + "?version=" + version, Map.of()).statusCode())
                    .isEqualTo(200);
            assertThat(browser.send("DELETE", "/api/v1/nets/" + id + "?version=" + version, Map.of()).statusCode())
                    .isEqualTo(200);
            assertThat(json(browser, browser.get("/api/v1/completedNets")).size()).isEqualTo(1);
            var archivePage = browser.get("/archives?net=" + id);
            assertThat(archivePage.statusCode()).isEqualTo(200);
            assertThat(archivePage.body()).contains("archives.js", "Archived nets", "Participant details")
                    .doesNotContain("net-monitor.js", "Participant map");
            assertThat(browser.get("/nets/" + id).headers().firstValue("Location").orElse("")).endsWith("/archives?net=" + id);
            assertThat(json(browser, browser.get("/api/v1/archiveBrowser?limit=1")).get(0).path("id").asText()).isEqualTo(id);
            assertThat(json(browser, browser.get("/api/v1/archiveBrowser?offset=1&limit=1"))).isEmpty();
            var allMessages = json(browser, browser.get("/api/v1/completedNets/" + id + "/messages"));
            var firstMessage = json(browser, browser.get("/api/v1/completedNets/" + id + "/messagePage?limit=1"));
            var secondMessage = json(browser, browser.get("/api/v1/completedNets/" + id + "/messagePage?offset=1&limit=1"));
            assertThat(firstMessage.get(0)).isEqualTo(allMessages.get(0));
            assertThat(secondMessage.get(0)).isEqualTo(allMessages.get(1));

            assertThat(json(browser, browser.get("/api/v1/completedNets/" + id)).path("participantCount").asInt())
                    .isEqualTo(1);
            assertThat(json(browser, browser.get("/api/v1/completedNets/" + id + "/participants")).size()).isEqualTo(1);
            assertThat(browser.send("POST", "/api/v1/nets/" + id + "/participants/N2ABC", Map.of()).statusCode())
                    .isEqualTo(409);
            for (String kind : List.of("participationReports", "messageReports")) {
                var response = browser.client.send(HttpRequest
                        .newBuilder(browser.base.resolve("/api/v1/completedNets/" + id + "/" + kind)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                try (var pdf = Loader.loadPDF(response.body())) {
                    String text = new PDFTextStripper().getText(pdf);
                    assertThat(text).contains("NET001", "N1ABC", "Page 1");
                    if (kind.equals("messageReports"))
                        assertThat(text).contains("MESSAGE LOG", "TIME", "FROM", "TO", "MESSAGE", "Supplies needed", "Do not replay", "Net Message Report");
                    for (int pageIndex = 0; pageIndex < pdf.getNumberOfPages(); pageIndex++) {
                        Path output = Path.of("target", "milestone4-" + kind + "-" + pageIndex + ".png");
                        ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(pageIndex, 100), "PNG", output.toFile());
                    }
                    Files.write(Path.of("target", "milestone4-" + kind + ".pdf"), response.body());
                }
            }
        }
    }

    @Test
    void permissionsClosedAdmissionAndStreamRevocationApplyThroughout() throws Exception {
        try (var app = start()) {
            var admin = user(app, "root");
            var owner = user(app, "owner");
            var outsider = user(app, "outsider");
            String instance = instance(admin);
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            String id = net(owner, "PRIV01", instance, true, false);
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            assertThat(json(outsider, outsider.get("/api/v1/nets")).size()).isZero();
            for (String suffix : List.of("", "/snapshot", "/participants", "/messages", "/events"))
                assertThat(outsider.get("/api/v1/nets/" + id + suffix).statusCode()).isEqualTo(403);
            assertThat(outsider.send("POST", "/api/v1/nets/" + id + "/participants/N2ABC", Map.of()).statusCode())
                    .isEqualTo(403);
            assertThat(owner.raw("POST", "/api/v1/nets/" + id + "/participants/N2ABC", "{}", "application/json", null)
                    .statusCode()).isEqualTo(403);
            incoming(transport, "N2ABC", "PRIV01", "CI{2");
            incoming(transport, "N1ABC-1", "PRIV01", "CI{3");
            try (var live = new Live(owner, id)) {
                var snapshot = live.until(s -> s.path("participants").size() == 1);
                assertThat(snapshot.path("participants").get(0).path("callsign").asText()).isEqualTo("N1ABC-1");
                assertThat(
                        owner.send("PUT", "/api/v1/nets/" + id, Map.of("name", "Changed", "version", -1)).statusCode())
                        .isEqualTo(409);
                assertThat(owner.send("POST", "/api/v1/logout", Map.of()).statusCode()).isEqualTo(200);
                await(live.closed::get);
            }
            assertThat(owner.get("/api/v1/nets/" + id + "/snapshot").statusCode()).isEqualTo(401);
            assertThat(admin.get("/api/v1/nets/" + id).statusCode()).isEqualTo(200);
            long version = json(admin, admin.get("/api/v1/nets/" + id)).path("version").asLong();
            assertThat(admin.send("DELETE", "/api/v1/nets/" + id + "?version=" + version, Map.of()).statusCode())
                    .isEqualTo(200);
            assertThat(outsider.get("/api/v1/completedNets/" + id + "/participationReports").statusCode())
                    .isEqualTo(403);
            assertThat(outsider.get("/api/v1/completedNets/" + id + "/messages").statusCode()).isEqualTo(403);
            assertThat(outsider.get("/archives?net=" + id).statusCode()).isEqualTo(403);
            assertThat(outsider.get("/api/v1/completedNets/" + id + "/messagePage").statusCode()).isEqualTo(403);
            assertThat(json(outsider, outsider.get("/api/v1/archiveBrowser"))).isEmpty();

        }
    }

    @Test
    void retentionTileConfigurationAndPublicReadOnlyAccess() throws Exception {
        try (var app = start()) {
            var admin = user(app, "root");
            var owner = user(app, "owner");
            var reader = user(app, "reader");
            String instance = instance(admin), id = net(owner, "PUBLIC", instance, false, true);
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            assertThat(reader.get("/api/v1/nets/" + id).statusCode()).isEqualTo(200);
            assertThat(reader.send("POST", "/api/v1/nets/" + id + "/messages", Map.of("message", "unauthorized"))
                    .statusCode()).isEqualTo(403);
            assertThat(reader.send("DELETE", "/api/v1/nets/" + id + "?version=0", Map.of()).statusCode())
                    .isEqualTo(403);
            assertThat(reader.send("PUT", "/api/v1/mapSettings",
                    Map.of("tileUrl", "http://localhost/{z}/{x}/{y}", "attribution", "test")).statusCode())
                    .isEqualTo(403);
            assertThat(admin
                    .send("PUT", "/api/v1/mapSettings", Map.of("tileUrl", "javascript:alert(1)", "attribution", "test"))
                    .statusCode()).isEqualTo(400);
            assertThat(admin.get("/setup/map-cache").statusCode()).isEqualTo(200);
            try (var live = new Live(reader, id)) {
                live.until(s -> s.has("net"));
                assertThat(admin
                        .send("PUT", "/api/v1/mapSettings",
                                Map.of("tileUrl", "http://localhost:9999/{z}/{x}/{y}.png", "attribution", "Test tiles"))
                        .statusCode()).isEqualTo(200);
                live.until(s -> s.path("map").path("attribution").asText().equals("Test tiles"));
                incoming(transport, "N1ABC", "PUBLIC", "CI{31");
                transport.incoming.add(new Packet("N1ABC", "APRS", List.of(), "!4903.50N/07201.75W-Test"));
                live.until(
                        s -> s.path("participants").size() == 1 && !s.path("participants").get(0).path("lat").isNull());
                app.getBean(JdbcTemplate.class).update("UPDATE station_position SET heard_at=?",
                        OffsetDateTime.now().minusDays(2));
                app.getBean(NetAccessor.class).expirePositions();
                live.until(s -> s.path("participants").get(0).path("lat").isNull());
                var current = json(owner, owner.get("/api/v1/nets/" + id));
                assertThat(owner.send("PUT", "/api/v1/nets/" + id,
                        Map.of("name", "Now private", "privateNet", true, "version", current.path("version").asLong()))
                        .statusCode()).isEqualTo(200);
                await(live.closed::get);
            }
            assertThat(reader.get("/api/v1/nets/" + id + "/snapshot").statusCode()).isEqualTo(403);
        }
    }

    @Test
    void malformedPositionsDoNotCreateInvalidMapCoordinates() {
        assertThat(AprsPosition.parse("!4963.50N/07201.75W-Bad minutes")).isNull();
        assertThat(AprsPosition.parse("!9903.50N/07201.75W-Bad latitude")).isNull();
        assertThat(AprsPosition.parse("@111111z4903.50S/07201.75E-Test").longitude()).isPositive();
        assertThat(AprsPosition.parse(":NET001   :CI")).isNull();
    }

    static class Live implements AutoCloseable {
        final LinkedBlockingQueue<JsonNode> snapshots = new LinkedBlockingQueue<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream stream;

        Live(CommunicationIntegrationTest.Browser browser, String id) throws Exception {
            this(browser, "/api/v1/nets/" + id + "/events", true);
        }

        Live(CommunicationIntegrationTest.Browser browser, String path, boolean absolute) throws Exception {
            var response = browser.client.sendAsync(HttpRequest.newBuilder(browser.base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream()).get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            stream = response.body();
            Thread.ofPlatform().daemon().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(stream))) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        if (line.startsWith("data:"))
                            snapshots.offer(browser.mapper.readTree(line.substring(5)));
                } catch (Exception ignored) {
                } finally {
                    closed.set(true);
                }
            });
        }

        JsonNode until(Predicate<JsonNode> predicate) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            JsonNode last = null;
            while (System.nanoTime() < deadline) {
                JsonNode next = snapshots.poll(200, TimeUnit.MILLISECONDS);
                if (next != null) {
                    last = next;
                    if (predicate.test(next))
                        return next;
                }
            }
            throw new AssertionError("No matching snapshot. Last: " + last);
        }

        public void close() throws Exception {
            stream.close();
        }
    }
}
