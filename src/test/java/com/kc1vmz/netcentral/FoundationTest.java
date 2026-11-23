package com.kc1vmz.netcentral;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoundationTest {
        @TempDir
        Path directory;

        @Test
        void initializesRendersAndRetainsCommittedSettingsAcrossRestart() throws Exception {
                Path database = directory.resolve("installation with spaces").resolve("netcentral");
                HttpClient client = HttpClient.newHttpClient();
                int appliedMigrations;
                try (ServletWebServerApplicationContext context = start(database)) {
                        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                        appliedMigrations = jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class);
                        assertThat(appliedMigrations).isPositive();
                        assertThatThrownBy(() -> jdbc.update(
                                        "INSERT INTO application_settings (setting_key, setting_value) VALUES (?, ?)",
                                        "application.name", "Duplicate")).isInstanceOf(RuntimeException.class);
                        for (String path : new String[] {"/", "/home"}) {
                                var response = get(client, context, path);
                                assertThat(response.statusCode()).isEqualTo(302);
                                assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/dashboard");
                        }
                        assertThat(get(client, context, "/css/application.css").statusCode()).isEqualTo(200);
                        assertThat(get(client, context, "/js/application.js").body()).contains("skip-link");
                        assertThat(get(client, context, "/h2-console").statusCode()).isEqualTo(404);
                        jdbc.update("UPDATE application_settings SET setting_value = ? WHERE setting_key = ?",
                                        "My local installation", "application.name");
                }
                assertThat(Files.exists(Path.of(database + ".mv.db"))).isTrue();
                try (ServletWebServerApplicationContext context = start(database)) {
                        assertThat(get(client, context, "/home").headers().firstValue("Location").orElseThrow()).endsWith("/dashboard");
                        assertThat(context.getBean(JdbcTemplate.class).queryForObject(
                                        "SELECT setting_value FROM application_settings WHERE setting_key='application.name'", String.class))
                                        .isEqualTo("My local installation");
                        assertThat(context.getBean(JdbcTemplate.class)
                                        .queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class))
                                        .isEqualTo(appliedMigrations);
                }
        }

        private ServletWebServerApplicationContext start(Path database) {
                return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class)
                                .run(
                                                "--server.port=0",
                                                "--server.address=127.0.0.1",
                                                "--spring.datasource.url=jdbc:h2:file:"
                                                                + database.toString().replace('\\', '/')
                                                                + ";DB_CLOSE_ON_EXIT=FALSE",
                                                "--spring.datasource.password=",
                                                "--spring.main.banner-mode=off");
        }

        private HttpResponse<String> get(HttpClient client, ServletWebServerApplicationContext context, String path)
                        throws Exception {
                URI uri = URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + path);
                return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
}
