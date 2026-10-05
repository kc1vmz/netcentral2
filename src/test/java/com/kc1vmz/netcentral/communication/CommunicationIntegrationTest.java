package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kc1vmz.netcentral.NetCentralApplication;
import com.kc1vmz.netcentral.accessor.AccountAccessor;
import com.kc1vmz.netcentral.account.Registration;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static com.kc1vmz.netcentral.communication.ManagedConnectionTest.await;
import static org.assertj.core.api.Assertions.*;

class CommunicationIntegrationTest {
    private static final String PASSWORD = "correct-horse-123";
    @TempDir
    Path directory;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void configurationRoutesAndPasscodeSurviveRestartAndRejectStaleUpdates() throws Exception {
        String id;
        String activeId;
        try (var app = start()) {
            register(app);
            var service = app.getBean(CommunicationService.class);
            var routing = app.getBean(RoutingService.class);
            assertThatThrownBy(() -> service.save(null, config("KENWOOD", false, "COM9"))).hasMessageContaining("400");
            var config = service.save(null, config("APRS_IS", false, "COM9")).configuration();
            id = config.id();
            assertThat(service.get(id).passcodeConfigured()).isTrue();
            assertThat(config.passcode()).isNull();
            assertThat(app.getBean(ObjectMapper.class).writeValueAsString(service.get(id))).doesNotContain("12345",
                    "\"passcode\"");
            var updated = service.save(id, config);
            assertThat(updated.configuration().version()).isEqualTo(1);
            assertThat(app.getBean(CommunicationRepository.class).find(id).passcode()).isEqualTo("12345");
            assertThatThrownBy(() -> service.save(id, config)).hasMessageContaining("409");
            routing.save("SCHEDULE:weekly", new RoutingService.Route("ignored", -1, "SELECTED", Set.of(id)));
            routing.save("NET:active", new RoutingService.Route("ignored", -1, "ALL", Set.of()));
            assertThatThrownBy(() -> service.delete(id, 0)).hasMessageContaining("409");
            activeId = service.save(null, config("TNC2_TCP", true, "COM10")).configuration().id();
            var manager = app.getBean(CommunicationManager.class);
            manager.reconcile();
            await(() -> manager.available(activeId));
        }
        try (var app = start()) {
            identity(app, "root");
            var service = app.getBean(CommunicationService.class);
            var routing = app.getBean(RoutingService.class);
            assertThat(service.get(id).configuration().version()).isEqualTo(1);
            assertThat(service.get(id).passcodeConfigured()).isTrue();
            await(() -> app.getBean(CommunicationManager.class).available(activeId));
            assertThat(app.getBean(FakeFactory.class).transports.get(activeId).sent).isEmpty();
            assertThat(routing.load("SCHEDULE:weekly").instanceIds()).containsExactly(id);
            service.delete(id, 1);
            assertThat(routing.load("SCHEDULE:weekly").mode()).isEqualTo("SELECTED");
            assertThat(routing.load("SCHEDULE:weekly").instanceIds()).isEmpty();
            assertThat(routing.load("SCHEDULE:weekly").version()).isEqualTo(1);
            assertThatThrownBy(() -> routing.save("SCHEDULE:weekly",
                    new RoutingService.Route("", 0, "ALL", Set.of()))).hasMessageContaining("409");
            assertThat(routing.load("NET:active").mode()).isEqualTo("ALL");
        }
    }

    @Test
    void multipleInstancesRouteRepliesGroupsAndWithdrawalsAndRecheckQueuedAssignments() throws Exception {
        try (var app = start()) {
            register(app);
            var service = app.getBean(CommunicationService.class);
            var routing = app.getBean(RoutingService.class);
            var router = app.getBean(TransmissionRouter.class);
            var manager = app.getBean(CommunicationManager.class);
            var factory = app.getBean(FakeFactory.class);
            List<String> ids = new ArrayList<>();
            for (String type : List.of("APRS_IS", "APRS_IS", "KISS_TCP", "KISS_TCP", "KISS_SERIAL", "KISS_SERIAL",
                    "TNC2_TCP", "TNC2_TCP", "TNC2_SERIAL", "TNC2_SERIAL")) {
                ids.add(service.save(null, config(type, true, "COM" + (ids.size() + 20))).configuration().id());
            }
            manager.reconcile();
            await(() -> ids.stream().allMatch(manager::available));
            assertThat(factory.transports).hasSize(10);
            String first = ids.get(0), second = ids.get(1);
            assertThat(router.broadcast(null, ManagedConnectionTest.PACKET)).isEmpty();
            routing.save("DEFAULT", new RoutingService.Route("", 0, "SELECTED", Set.of(first, second)));
            app.getBean(PacketHub.class).receive(first, new Packet("DEST", "APANC1", List.of(), "position"));
            await(() -> !app.getBean(PacketHub.class).recent("DEST").isEmpty());
            var reply = router.sendGroup("NET:unknown", "N0CALL", "DEST", List.of("one", "two"), true, second);
            assertThat(reply.keySet()).containsExactly(second);
            assertThat(reply.get(second).get(2, TimeUnit.SECONDS).packetsSent()).isEqualTo(2);
            assertThat(factory.transports.get(second).sent.get(0).information()).startsWith(":DEST     :one{");
            var heard = router.sendMessage(null, "N0CALL", "DEST", "heard", false, null);
            assertThat(heard.keySet()).containsExactly(first);
            heard.get(first).get(2, TimeUnit.SECONDS);
            assertThat(router.sendMessage(null, "N0CALL", "UNHEARD", "none", false, null)).isEmpty();
            assertThat(router.acknowledgement("N0CALL", "DEST", "12").information()).isEqualTo(":DEST     :ack12");
            assertThat(router.query("N0CALL", "DEST", "NET").information()).isEqualTo(":DEST     :?NET  ");
            assertThat(router.report("N0CALL", "test").information()).isEqualTo("{ZNtest");

            routing.save("NET:all", new RoutingService.Route("", -1, "ALL", Set.of()));
            String later = service.save(null, config("KISS_TCP", true, "COM50")).configuration().id();
            manager.reconcile();
            await(() -> manager.available(later));
            var broadcast = router.broadcast("NET:all", ManagedConnectionTest.PACKET);
            assertThat(broadcast).containsKey(later);
            for (var result : broadcast.values())
                result.get(2, TimeUnit.SECONDS);

            FakeTransport transport = factory.transports.get(first);
            transport.block = new CountDownLatch(1);
            var sending = router.sendMessage(null, "N0CALL", "DEST", "already sending", false, first).get(first);
            assertThat(transport.entered.await(2, TimeUnit.SECONDS)).isTrue();
            var queued = router.sendMessage(null, "N0CALL", "DEST", "queued", false, first).get(first);
            routing.save("DEFAULT", new RoutingService.Route("", 1, "SELECTED", Set.of(second)));
            transport.block.countDown();
            assertThat(sending.get(2, TimeUnit.SECONDS).state()).isEqualTo("SENT");
            assertThat(queued.get(2, TimeUnit.SECONDS).state()).isEqualTo("DISCARDED");

            Packet beacon = new Packet("N0CALL", "APANC1", List.of(), ";OBJECT   *111111z4903.50N/07201.75W-Test");
            var sentBeacon = router.object("OBJECT:inherited", beacon);
            assertThat(sentBeacon.get(second).get(2, TimeUnit.SECONDS).state()).isEqualTo("SENT");
            routing.save("DEFAULT", new RoutingService.Route("", 2, "SELECTED", Set.of()));
            await(() -> factory.transports.get(second).sent.stream()
                    .anyMatch(p -> p.information().startsWith(";OBJECT   _")));
            assertThat(router.broadcast("OBJECT:inherited", beacon)).isEmpty();
            routing.save("OBJECT:explicit", new RoutingService.Route("", -1, "SELECTED", Set.of(second)));
            router.object("OBJECT:explicit", beacon).get(second).get(2, TimeUnit.SECONDS);
            long before = factory.transports.get(second).sent.stream()
                    .filter(p -> p.information().startsWith(";OBJECT   _")).count();
            routing.save("OBJECT:explicit", new RoutingService.Route("", 0, "SELECTED", Set.of()));
            await(() -> factory.transports.get(second).sent.stream()
                    .filter(p -> p.information().startsWith(";OBJECT   _")).count() > before);
        }
    }

    @Test
    void concurrentSerialClaimsAndUnstoppableWorkerPreventPortReuse() throws Exception {
        try (var app = start()) {
            register(app);
            var service = app.getBean(CommunicationService.class);
            var factory = app.getBean(FakeFactory.class);
            var manager = app.getBean(CommunicationManager.class);
            AtomicInteger saved = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                CountDownLatch go = new CountDownLatch(1);
                var jobs = List.of("KISS_SERIAL", "TNC2_SERIAL").stream().map(type -> pool.submit(() -> {
                    identity(app, "root");
                    try {
                        go.await();
                        service.save(null, config(type, true, "COM60"));
                        saved.incrementAndGet();
                    } catch (org.springframework.web.server.ResponseStatusException conflict) {
                        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                    return null;
                })).toList();
                go.countDown();
                for (var job : jobs)
                    job.get(5, TimeUnit.SECONDS);
            }
            assertThat(saved.get()).isEqualTo(1);
            for (var view : service.list())
                service.delete(view.configuration().id(), view.configuration().version());
            manager.reconcile();

            factory.stubbornDevice = "COM61";
            String id = service.save(null, config("KISS_SERIAL", true, "COM61")).configuration().id();
            manager.reconcile();
            await(() -> manager.available(id));
            FakeTransport old = factory.transports.get(id);
            service.delete(id, 0);
            manager.reconcile();
            assertThatThrownBy(() -> service.save(null, config("TNC2_SERIAL", true, "COM61"))).hasMessageContaining("409");
            assertThat(app.getBean(SerialOwnership.class).available(SerialOwnership.identity("COM61"), "replacement"))
                    .isFalse();
            old.release = true;
            await(() -> {
                manager.reconcile();
                return app.getBean(SerialOwnership.class).available(SerialOwnership.identity("COM61"), "replacement");
            });
            assertThat(service.save(null, config("TNC2_SERIAL", true, "COM61")).configuration().type())
                    .isEqualTo("TNC2_SERIAL");
        }
    }

    @Test
    void httpAndRenderedFormsRequireAdminCsrfAndHidePasscodes() throws Exception {
        try (var app = start()) {
            register(app);
            app.getBean(AccountAccessor.class)
                    .register(new Registration("member", PASSWORD, PASSWORD, null, null, null));
            Browser admin = new Browser(app);
            Browser member = new Browser(app);
            assertThat(admin.get("/api/v1/communicationInstances").statusCode()).isEqualTo(401);
            assertThat(admin.get("/api/v1/communicationInstances/serialPorts").statusCode()).isEqualTo(401);
            admin.send("POST", "/api/v1/login", Map.of("username", "root", "password", PASSWORD));
            member.send("POST", "/api/v1/login", Map.of("username", "member", "password", PASSWORD));
            assertThat(member.get("/communications").statusCode()).isEqualTo(403);
            assertThat(member.get("/api/v1/communicationInstances").statusCode()).isEqualTo(403);
            assertThat(member.get("/api/v1/communicationInstances/serialPorts").statusCode()).isEqualTo(403);
            assertThat(member.send("POST", "/api/v1/communicationInstances", Map.of()).statusCode()).isEqualTo(403);
            assertThat(admin.raw("POST", "/api/v1/communicationInstances", "{}", "application/json", null).statusCode())
                    .isEqualTo(403);
            assertThat(admin.get("/communications/new").body()).contains("KISS_SERIAL", "TNC2_SERIAL")
                    .doesNotContain(">AGW<", ">TEST<");
            Map<String, Object> body = Map.of("name", "Local APRS", "type", "APRS_IS", "enabled", false,
                    "transmitEnabled", false,
                    "host", "127.0.0.1", "port", 14580, "username", "N0CALL", "passcode", "12345");
            var created = admin.send("POST", "/api/v1/communicationInstances", body);
            assertThat(created.statusCode()).isEqualTo(200);
            assertThat(created.body()).doesNotContain("12345", "\"passcode\"");
            String id = admin.json(created).path("configuration").path("id").asText();
            assertThat(admin.get("/communications/" + id + "/edit").body()).doesNotContain("12345");
            assertThat(admin.get("/communications").statusCode()).isEqualTo(200);
            String form = "id=&version=0&name=Radio&type=KISS_TCP&host=127.0.0.1&port=8001&kissPort=0&baudRate=9600&_enabled=on&_transmitEnabled=on";
            assertThat(
                    admin.raw("POST", "/communications/save", form, "application/x-www-form-urlencoded", admin.csrf())
                            .statusCode())
                    .isEqualTo(302);
            assertThat(admin.json(admin.get("/api/v1/communicationInstances")).size()).isEqualTo(2);
            var ports = admin.get("/api/v1/communicationInstances/serialPorts");
            assertThat(ports.statusCode()).isEqualTo(200);
            assertThat(admin.json(ports).path("ports").isArray()).isTrue();
            assertThat(admin.send("PUT", "/api/v1/communicationRoutes/DEFAULT",
                    Map.of("version", 0, "mode", "SELECTED", "instanceIds", List.of(id))).statusCode()).isEqualTo(200);
            assertThat(
                    admin.send("DELETE", "/api/v1/communicationInstances/" + id + "?version=9", Map.of()).statusCode())
                    .isEqualTo(409);
            assertThat(admin.get("/api/v1/communicationObservations").statusCode()).isEqualTo(200);
        }
    }

    private ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class,
                Fakes.class).run(
                        "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN",
                        "--spring.datasource.url=jdbc:h2:file:"
                                + directory.resolve("communications").toString().replace('\\', '/')
                                + ";DB_CLOSE_ON_EXIT=FALSE",
                        "--spring.datasource.password=");
    }

    private void register(ServletWebServerApplicationContext app) {
        app.getBean(AccountAccessor.class).register(new Registration("root", PASSWORD, PASSWORD, null, null, null));
        identity(app, "root");
    }

    private static void identity(ServletWebServerApplicationContext app, String name) {
        var principal = app.getBean(AccountAccessor.class).loadUserByUsername(name);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static CommunicationConfig config(String type, boolean enabled, String device) {
        return new CommunicationConfig(null, 0, type, type, enabled, true, "127.0.0.1", 8001, "N0CALL", "12345",
                "", device, 9600, "", "", "", 0);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        FakeFactory fakeFactory() {
            return new FakeFactory();
        }
    }

    static class FakeFactory implements TransportFactory {
        final Map<String, FakeTransport> transports = new ConcurrentHashMap<>();
        volatile String stubbornDevice;

        public CommunicationTransport create(CommunicationConfig config) {
            FakeTransport result = new FakeTransport(
                    stubbornDevice != null && SerialOwnership.identity(config.serialDevice())
                            .equals(SerialOwnership.identity(stubbornDevice)));
            transports.put(config.id(), result);
            return result;
        }
    }

    static class FakeTransport implements CommunicationTransport {
        final java.util.concurrent.BlockingQueue<Packet> incoming = new java.util.concurrent.LinkedBlockingQueue<>();
        final List<Packet> sent = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final boolean stubborn;
        volatile boolean closed;
        volatile boolean release;
        volatile CountDownLatch block;

        FakeTransport(boolean stubborn) {
            this.stubborn = stubborn;
        }

        public void connect() {
        }

        public Packet read() throws IOException {
            while (stubborn && !release) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException stop) {
                throw new IOException();
            }
            if (closed || release)
                throw new IOException();
            return incoming.poll();
        }

        public void send(Packet packet) throws IOException {
            if (block != null) {
                entered.countDown();
                try {
                    block.await();
                } catch (InterruptedException stop) {
                    throw new IOException();
                }
            }
            sent.add(packet);
        }

        public boolean canTransmit() {
            return !closed;
        }

        public void close() {
            while (stubborn && !release) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
            closed = true;
            if (block != null)
                block.countDown();
        }
    }

    static class Browser {
        final URI base;
        final ObjectMapper mapper = new ObjectMapper();
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

        Browser(ServletWebServerApplicationContext app) {
            base = URI.create("http://127.0.0.1:" + app.getWebServer().getPort());
        }

        JsonNode json(HttpResponse<String> response) throws Exception {
            return mapper.readTree(response.body());
        }

        String csrf() throws Exception {
            return json(get("/api/v1/csrf")).path("token").asText();
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            return raw(method, path, mapper.writeValueAsString(body), "application/json", csrf());
        }

        HttpResponse<String> raw(String method, String path, String body, String type, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", type);
            if (csrf != null)
                request.header("X-CSRF-TOKEN", csrf);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
