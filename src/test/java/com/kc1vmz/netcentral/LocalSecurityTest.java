package com.kc1vmz.netcentral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kc1vmz.netcentral.accessor.AccountAccessor;
import com.kc1vmz.netcentral.account.Account;
import com.kc1vmz.netcentral.account.Registration;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSecurityTest {
    private static final String PASSWORD = "correct-horse-123";
    @TempDir
    Path directory;

    @Test
    void swaggerDocumentsOnlyRestRoutesAndRetainsSessionSecurity() throws Exception {
        try (var context = start()) {
            Browser browser = new Browser(context);
            assertThat(browser.get("/swagger-ui/index.html").statusCode()).isEqualTo(302);
            assertThat(browser.get("/v3/api-docs").statusCode()).isEqualTo(302);
            browser.json(browser.send("POST", "/api/v1/users/register", registration("swagger")));
            assertThat(browser.login("swagger").statusCode()).isEqualTo(200);
            assertThat(browser.get("/swagger-ui.html").statusCode()).isEqualTo(302);
            assertThat(browser.get("/swagger-ui/index.html").statusCode()).isEqualTo(200);
            assertThat(browser.get("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
            var initializer = browser.get("/swagger-ui/swagger-initializer.js");
            assertThat(initializer.statusCode()).isEqualTo(200);
            assertThat(initializer.body()).contains("requestInterceptor: async function", "/api/v1/csrf", "csrf.headerName");
            var response = browser.get("/v3/api-docs");
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            var api = browser.json(response);
            assertThat(api.path("info").path("title").asText()).isEqualTo("Net Central REST API");
            assertThat(api.path("paths").properties()).allMatch(entry -> entry.getKey().startsWith("/api/v1/"));
            assertThat(api.path("paths").has("/api/v1/nets")).isTrue();
            assertThat(api.path("paths").has("/api/v1/strip-library")).isTrue();
            assertThat(api.path("paths").path("/api/v1/nets").path("post").path("requestBody").isMissingNode()).isFalse();
            assertThat(api.path("components").path("securitySchemes").path("session").path("name").asText()).isEqualTo("SessionID");
            assertThat(browser.get("/v3/api-docs.yaml").statusCode()).isEqualTo(200);
            assertThat(browser.raw("POST", "/api/v1/nets", "{}", "application/json", null).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void concurrentFirstRegistrationsProduceExactlyOneAdministrator() throws Exception {
        try (var context = start()) {
            AccountAccessor accounts = context.getBean(AccountAccessor.class);
            try (var executor = Executors.newFixedThreadPool(4)) {
                CountDownLatch ready = new CountDownLatch(4);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Account>> results = new ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    String username = "operator" + index;
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        return accounts.register(new Registration(username, PASSWORD, PASSWORD, null, null, null));
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                List<Account> created = new ArrayList<>();
                for (Future<Account> result : results)
                    created.add(result.get(20, TimeUnit.SECONDS));
                assertThat(created.stream().filter(account -> account.role().equals("SYSADMIN")).count()).isEqualTo(1);
                assertThat(created.stream().filter(account -> account.role().equals("USER")).count()).isEqualTo(3);
            }
            assertThatThrownBy(
                    () -> accounts.register(new Registration(" OPERATOR0 ", PASSWORD, PASSWORD, null, null, null)))
                    .hasMessageContaining("already registered");
            assertThatThrownBy(accounts::list).hasMessageContaining("Sign in");
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForList("SELECT password_hash FROM local_account", String.class))
                    .allMatch(hash -> hash.startsWith("$2a$") && !hash.contains(PASSWORD));
        }
    }

    @Test
    void apiEnforcesCsrfAccountBoundariesRolesAndSessionRevocation() throws Exception {
        try (var context = start()) {
            Browser admin = new Browser(context);
            Browser user = new Browser(context);
            assertThat(admin.get("/api/v1/users").statusCode()).isEqualTo(401);
            assertThat(admin.raw("POST", "/api/v1/users/register", "{}", "application/json", null).statusCode())
                    .isEqualTo(403);
            JsonNode first = admin.json(admin.send("POST", "/api/v1/users/register", registration("root")));
            String adminId = first.get("id").asText();
            assertThat(first.get("role").asText()).isEqualTo("SYSADMIN");
            Map<String, Object> malicious = registration("member");
            malicious.put("role", "SYSADMIN");
            HttpResponse<String> registration = user.send("POST", "/api/v1/users/register", malicious);
            assertThat(registration.statusCode()).isEqualTo(200);
            JsonNode second = user.json(registration);
            String userId = second.get("id").asText();
            assertThat(second.get("role").asText()).isEqualTo("USER");
            assertThat(registration.body()).doesNotContain(PASSWORD, "password", "accessToken");
            assertThat(
                    user.send("POST", "/api/v1/login", Map.of("username", "member", "password", "wrong")).statusCode())
                    .isEqualTo(401);
            assertThat(admin.login("root").statusCode()).isEqualTo(200);
            assertThat(user.login("member").statusCode()).isEqualTo(200);
            Browser otherSession = new Browser(context);
            assertThat(otherSession.login("member").statusCode()).isEqualTo(200);
            assertThat(user.get("/api/v1/users").statusCode()).isEqualTo(200);
            assertThat(user.json(user.get("/api/v1/users")).size()).isEqualTo(1);
            assertThat(admin.json(admin.get("/api/v1/users")).size()).isEqualTo(2);
            assertThat(user.get("/api/v1/users/" + adminId).statusCode()).isEqualTo(403);
            assertThat(user.get("/users").statusCode()).isEqualTo(403);
            assertThat(admin.get("/users").statusCode()).isEqualTo(200);
            assertThat(admin.get("/users/" + userId).body()).contains("Edit local account");
            assertThat(user.send("POST", "/api/v1/users", Map.of("emailAddress", "intruder", "password", PASSWORD))
                    .statusCode()).isEqualTo(403);
            assertThat(user.send("PUT", "/api/v1/users/" + userId, Map.of("role", "SYSADMIN")).statusCode())
                    .isEqualTo(403);
            assertThat(user.send("DELETE", "/api/v1/users/" + adminId, Map.of()).statusCode()).isEqualTo(403);
            assertThat(admin.send("DELETE", "/api/v1/users/" + adminId, Map.of()).statusCode()).isEqualTo(409);
            assertThat(admin.send("PUT", "/api/v1/users/" + adminId, Map.of("role", "USER")).statusCode())
                    .isEqualTo(409);
            assertThat(user.send("PUT", "/api/v1/users/" + userId, Map.of("password", "changed-password")).statusCode())
                    .isEqualTo(400);
            assertThat(admin.send("PUT", "/api/v1/users/" + userId, Map.of("role", "ADMIN")).statusCode())
                    .isEqualTo(200);
            assertThat(user.get("/api/v1/users").statusCode()).isEqualTo(401);
            assertThat(otherSession.get("/api/v1/users").statusCode()).isEqualTo(401);
            assertThat(user.login("member").statusCode()).isEqualTo(200);
            assertThat(user.get("/users").statusCode()).isEqualTo(200);
            assertThat(user.send("PUT", "/api/v1/users/" + adminId, Map.of("role", "USER")).statusCode())
                    .isEqualTo(403);
            assertThat(user
                    .send("POST", "/api/v1/users",
                            Map.of("emailAddress", "another-admin", "password", PASSWORD, "role", "ADMIN"))
                    .statusCode()).isEqualTo(403);
            assertThat(user.send("POST", "/api/v1/users", Map.of("emailAddress", "ordinary", "password", PASSWORD))
                    .statusCode()).isEqualTo(200);
            assertThat(user.send("PUT", "/api/v1/users/" + userId,
                    Map.of("password", "changed-password", "currentPassword", PASSWORD)).statusCode()).isEqualTo(200);
            assertThat(user.get("/profile").statusCode()).isEqualTo(302);
            assertThat(user.login("member").statusCode()).isEqualTo(401);
            assertThat(user.send("POST", "/api/v1/login", Map.of("username", "member", "password", "changed-password"))
                    .statusCode()).isEqualTo(200);
            assertThat(user.send("POST", "/api/v1/logout", Map.of()).statusCode()).isEqualTo(200);
            assertThat(user.get("/api/v1/users").statusCode()).isEqualTo(401);
            assertThat(admin.send("POST", "/api/v1/login/token", Map.of()).body()).isEqualTo("true");
            Browser headerOnly = new Browser(context);
            HttpRequest legacy = HttpRequest.newBuilder(admin.base.resolve("/api/v1/users"))
                    .header("SessionID", admin.sessionId()).GET().build();
            assertThat(headerOnly.client.send(legacy, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(401);
            assertThat(admin.send("DELETE", "/api/v1/users/" + userId, Map.of()).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void upgradesFoundationDatabaseWithoutLosingSettings() throws Exception {
        // Build the historical schema without starting today's application listeners.
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:h2:file:" + directory.resolve("accounts").toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE", "netcentral", "");
        var migration = new liquibase.integration.spring.SpringLiquibase();
        migration.setDataSource(dataSource);
        migration.setChangeLog("classpath:db/changelog/001-foundation.xml");
        migration.afterPropertiesSet();
        new JdbcTemplate(dataSource).update(
            "UPDATE application_settings SET setting_value='Existing installation' WHERE setting_key='application.name'");
        try (var context = start()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject(
                    "SELECT setting_value FROM application_settings WHERE setting_key='application.name'",
                    String.class))
                    .isEqualTo("Existing installation");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID='013-position-ambiguity'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM domain_record WHERE kind='CLASSIFY'", Integer.class))
                    .isEqualTo(26);
            assertThat(context.getBean(AccountAccessor.class).register(
                    new Registration("first", PASSWORD, PASSWORD, null, null, null)).role()).isEqualTo("SYSADMIN");
        }
    }

    @Test
    void browserFormsRotateSessionAndAccountsPersistButSessionsDoNot() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var context = start()) {
            Browser browser = new Browser(context, cookies);
            assertThat(browser.get("/register").body()).contains("name=\"_csrf\"", "Create an account", ">Login</span>")
                    .doesNotContain("Use at least", "The first registered", ">My account</span>");
            String token = browser.csrf();
            HttpResponse<String> registered = browser.form("/register",
                    Map.of("username", "local", "password", PASSWORD,
                            "password2", PASSWORD, "firstName", "Local", "lastName", "Operator", "callsign", "KC1VMZ",
                            "_csrf", token));
            assertThat(registered.statusCode()).isEqualTo(302);
            assertThat(browser.get("/login").statusCode()).isEqualTo(200);
            String before = browser.sessionId();
            HttpResponse<String> login = browser.form("/login",
                    Map.of("username", "local", "password", PASSWORD, "_csrf", browser.csrf()));
            assertThat(login.statusCode()).isEqualTo(302);
            assertThat(login.headers().firstValue("Location").orElseThrow()).endsWith("/dashboard");
            assertThat(browser.sessionId()).isNotEqualTo(before);
            assertThat(String.join(";", login.headers().allValues("set-cookie"))).contains("HttpOnly", "SameSite=Lax");
            assertThat(browser.get("/profile").body()).contains("KC1VMZ", "Local", "My account");
            assertThat(browser.get("/users").body()).contains("Change Role", "Create account", "role-editor",
                    "account-delete-confirm");
            assertThat(browser.raw("POST", "/logout", "", "application/x-www-form-urlencoded", null).statusCode())
                    .isEqualTo(403);
            assertThat(browser.form("/logout", Map.of("_csrf", browser.csrf())).statusCode()).isEqualTo(302);
            assertThat(browser.get("/profile").statusCode()).isEqualTo(302);
            assertThat(browser.login("local").statusCode()).isEqualTo(200);
        }
        try (var context = start()) {
            Browser browser = new Browser(context, cookies);
            assertThat(browser.get("/api/v1/users").statusCode()).isEqualTo(401);
            assertThat(browser.login("local").statusCode()).isEqualTo(200);
            assertThat(browser.json(browser.get("/api/v1/users")).get(0).get("role").asText()).isEqualTo("SYSADMIN");
            assertThat(browser.get("/profile").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void containerIdleExpirationRequiresFreshLogin() throws Exception {
        try (var context = start()) {
            Browser browser = new Browser(context);
            browser.send("POST", "/api/v1/users/register", registration("idle-user"));
            assertThat(browser.login("idle-user").statusCode()).isEqualTo(200);
            TomcatWebServer server = (TomcatWebServer) context.getWebServer();
            Context servlet = (Context) server.getTomcat().getHost().findChild("");
            var session = servlet.getManager().findSession(browser.sessionId());
            assertThat(session.getMaxInactiveInterval()).isEqualTo(1800);
            session.setMaxInactiveInterval(1);
            Thread.sleep(1600);
            assertThat(browser.get("/api/v1/users").statusCode()).isEqualTo(401);
            assertThat(browser.login("idle-user").statusCode()).isEqualTo(200);
        }
    }

    private ServletWebServerApplicationContext start(String... overrides) {
        List<String> arguments = new ArrayList<>(List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.datasource.url=jdbc:h2:file:" + directory.resolve("accounts").toString().replace('\\', '/')
                        + ";DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.password=", "--logging.level.root=WARN"));
        arguments.addAll(List.of(overrides));
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class)
                .run(arguments.toArray(String[]::new));
    }

    private static Map<String, Object> registration(String username) {
        return new HashMap<>(Map.of("username", username, "password", PASSWORD, "password2", PASSWORD));
    }

    private static class Browser {
        private final URI base;
        private final CookieManager cookies;
        private final HttpClient client;
        private final ObjectMapper mapper;

        Browser(ServletWebServerApplicationContext context) {
            this(context, new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }

        Browser(ServletWebServerApplicationContext context, CookieManager cookies) {
            this.base = URI.create("http://127.0.0.1:" + context.getWebServer().getPort());
            this.cookies = cookies;
            this.client = HttpClient.newBuilder().cookieHandler(cookies).build();
            this.mapper = new ObjectMapper();
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SessionID"))
                    .findFirst().orElseThrow().getValue();
        }

        JsonNode json(HttpResponse<String> response) throws Exception {
            return mapper.readTree(response.body());
        }

        String csrf() throws Exception {
            return json(get("/api/v1/csrf")).get("token").asText();
        }

        HttpResponse<String> login(String username) throws Exception {
            return send("POST", "/api/v1/login", Map.of("username", username, "password", PASSWORD));
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            return raw(method, path, mapper.writeValueAsString(body), "application/json", csrf());
        }

        HttpResponse<String> form(String path, Map<String, String> fields) throws Exception {
            List<String> pairs = new ArrayList<>();
            fields.forEach((key, value) -> pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8)));
            return raw("POST", path, String.join("&", pairs), "application/x-www-form-urlencoded", null);
        }

        HttpResponse<String> raw(String method, String path, String body, String type, String token) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", type);
            if (token != null)
                request.header("X-CSRF-TOKEN", token);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
