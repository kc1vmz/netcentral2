package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.kc1vmz.netcentral.NetCentralApplication;
import com.kc1vmz.netcentral.accessor.*;
import com.kc1vmz.netcentral.operations.*;

import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
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

class OperationsWorkflowTest {
    @TempDir
    Path directory;

    @Test
    void withdrawingDeletesObjectAndDisablesConfiguredManager() throws Exception {
        try(var app=start()) {
            var b=user(app,"withdraw-delete");
            String instance=instance(b,"Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport=app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            var created=ok(b,b.send("POST","/api/v1/APRSObjects/requests",object(instance)));
            ok(b,b.send("POST","/api/v1/accessControlLists/SHEL01",Map.of("type","STATION","callsignChecked","N1ABC","allowed",true)));
            var page=b.get("/objects/SHEL01");
            assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
            assertThat(page.body()).contains("Withdraw and delete object?");
            var removed=b.raw("POST","/objects/SHEL01/withdraw","version="+created.path("version").asLong(),"application/x-www-form-urlencoded",b.csrf());
            assertThat(removed.statusCode()).isEqualTo(302);
            assertThat(removed.headers().firstValue("Location").orElse("")).endsWith("/explorer");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(";SHEL01   _")));
            var store=app.getBean(OperationsStore.class);
            assertThat(store.find("OBJECT","SHEL01")).isNull();
            assertThat(store.list("ACL")).isEmpty();
            assertThat(app.getBean(RoutingService.class).load("OBJECT:SHEL01").owner()).isEqualTo("DEFAULT");
            ok(b,b.send("POST","/api/v1/APRSObjects/requests",object(instance)));
            String owner=get(b,"/api/v1/users").get(0).path("id").asText();
            ok(b,b.send("PUT","/api/v1/configurations",Map.of("netManagerEnabled","true","netManagerCallsign","NETMGR","netManagerOwner",owner,"netManagerLatitude","42","netManagerLongitude","-71")));
            long version=store.find("OBJECT","NETMGR").version();
            ok(b,b.send("DELETE","/api/v1/APRSObjects/NETMGR?version="+version,Map.of()));
            assertThat(store.enabled("netManagerEnabled")).isFalse();
            app.getBean(NetManagerAccessor.class).synchronizeObject();
            assertThat(store.find("OBJECT","NETMGR")).isNull();
        }
        try(var app=start()) {
            assertThat(app.getBean(OperationsStore.class).enabled("netManagerEnabled")).isFalse();
            assertThat(app.getBean(OperationsStore.class).find("OBJECT","NETMGR")).isNull();
        }
    }

    @Test
    void generalResourceRadioValuesDefaultToOpenButRespectConfiguredSecurity() throws Exception {
        try (var app = start()) {
            var b = user(app,"resource-values");
            String instance = instance(b,"Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            var body=object(instance);body.put("callsign","RES001");body.put("type","RESOURCE");
            ok(b,b.send("POST","/api/v1/APRSObjects/requests",body));
            command(app,instance,"N1ABC","RES001","WATER=20{v1");
            await(() -> get(b,"/api/v1/APRSObjects/RES001/keyValues").path("WATER").asText().equals("20"));
            command(app,instance,"N1ABC","RES001","R{r1");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("RES001") && p.information().equals(":N1ABC    :WATER=20")));
            assertThat(b.get("/objects/RES001").body()).contains("WATER","20");
            ok(b,b.send("POST","/api/v1/accessControlLists/RES001",Map.of("type","STATION","callsignChecked","N1ABC","allowed",true)));
            command(app,instance,"N2ABC","RES001","WATER=99{v2");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("RES001") && p.information().equals(":N2ABC    :Bad or unauthorized message.")));
            assertThat(get(b,"/api/v1/APRSObjects/RES001/keyValues").path("WATER").asText()).isEqualTo("20");
            command(app,instance,"N1ABC","RES001","WATER=30{v3");
            await(() -> get(b,"/api/v1/APRSObjects/RES001/keyValues").path("WATER").asText().equals("30"));
            command(app,instance,"N1ABC","RES001","WATER={v4");
            await(() -> !get(b,"/api/v1/APRSObjects/RES001/keyValues").has("WATER"));
            command(app,instance,"N1ABC","RES001","R{r2");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("RES001") && p.information().equals(":N1ABC    :No values found")));
        }
    }

    @Test
    void shelterDetailsKeepLatestCategoriesAndAllHistory() throws Exception {
        try (var app = start()) {
            var b = user(app,"shelter-details");
            String instance = instance(b,"Radio");
            ok(b,b.send("POST","/api/v1/APRSObjects/requests",object(instance)));
            var empty=b.get("/objects/SHEL01");
            assertThat(empty.statusCode()).withFailMessage(empty.body()).isEqualTo(200);
            assertThat(empty.body()).contains("Status information","Population census","Worker census","Food availability and needs","Materiel availability and needs","Stand-by","Active","Closed","Open","Ages 0–3");
            for (String kind:List.of("status","census","workers","operationalFoods","operationalMateriels")) {
                for(int iteration=1;iteration<=2;iteration++) {
                    var values=new ArrayList<String>();
                    for(var field:app.getBean(ReportAccessor.class).fields(kind)) {
                        if(field.type().equals("number"))values.add(field.name()+"="+iteration);
                        if(field.type().equals("text"))values.add(field.name()+"=Report"+iteration);
                        if(field.name().equals("date"))values.add("date=2026-10-04");
                    }
                    var response=b.raw("POST","/objects/SHEL01/reports/"+kind,String.join("&",values),"application/x-www-form-urlencoded",b.csrf());
                    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(302);
                }
            }
            var page=b.get("/objects/SHEL01");
            assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
            assertThat(page.body()).contains("Status and census history","Food and materiel history","Report1","Report2","2026-10-04","On hand","Needed","Shift 1","Shift 2","data-object-confirm=\"Confirm update\"");
            // Latest values are retained independently by category and shift.
            var firstFood=page.body().substring(page.body().indexOf("id=\"shelter-operationalFoods-0\""));
            assertThat(firstFood.substring(0,firstFood.indexOf("</dialog>"))).contains("name=\"breakfast\" value=\"1\"");
            var secondFood=page.body().substring(page.body().indexOf("id=\"shelter-operationalFoods-1\""));
            assertThat(secondFood.substring(0,secondFood.indexOf("</dialog>"))).contains("name=\"breakfast\" value=\"2\"");
            assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM operational_report WHERE callsign='SHEL01'",Integer.class)).isEqualTo(10);
        }
    }

    @Test
    void eocDetailsShowLatestReportsAndSeparateHistory() throws Exception {
        try (var app = start()) {
            var b = user(app, "eoc-details");
            String instance = instance(b, "Radio");
            var body = object(instance);body.put("type", "EOC");body.put("callsign", "EOC001");
            ok(b, b.send("POST", "/api/v1/APRSObjects/requests", body));
            var empty = b.get("/objects/EOC001");
            assertThat(empty.statusCode()).withFailMessage(empty.body()).isEqualTo(200);
            assertThat(empty.body()).contains("Contact information", "Mobilization information", "No information reported yet.", "eoc-contacts", "eoc-mobilizations");
            for (String name : List.of("First", "Latest")) {
                var contact = b.raw("POST", "/objects/EOC001/reports/contacts", "directorName="+name+"&incidentCommanderName=Commander", "application/x-www-form-urlencoded", b.csrf());
                assertThat(contact.statusCode()).withFailMessage(contact.body()).isEqualTo(302);
                var mobilization = b.raw("POST", "/objects/EOC001/reports/mobilizations", "eocName="+name+"&status=1&level=3", "application/x-www-form-urlencoded", b.csrf());
                assertThat(mobilization.statusCode()).withFailMessage(mobilization.body()).isEqualTo(302);
            }
            var page = b.get("/objects/EOC001");
            assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
            assertThat(page.body()).contains("Contact information history", "Mobilization information history", "First", "Latest", "Normal", "value=\"Latest\"", "data-object-confirm=\"Confirm update\"");
            assertThat(app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM operational_report WHERE callsign='EOC001'", Integer.class)).isEqualTo(4);
        }
    }

    @Test
    void ambiguousPositionsAreReceivedAndRecovered() throws Exception {
        try (var app = start()) {
            var b = user(app, "ambiguity-admin");
            String connection = instance(b, "ambiguity");
            var store = app.getBean(OperationsStore.class);
            packet(app, connection, "N1AMB", "!4258.  N/07127.  W>Approximate");
            await(() -> store.find("STATION", "N1AMB") != null);
            String netId = net(b, connection);
            command(app, connection, "N1AMB", "NET006", "CI{1");
            String snapshotUrl = "/api/v1/nets/" + netId + "/snapshot";
            await(() -> get(b, snapshotUrl).path("participants").size() == 1);
            assertThat(get(b, snapshotUrl).path("participants").get(0).path("uncertaintyMeters").asDouble()).isPositive();
            var station = store.find("STATION", "N1AMB").data();
            assertThat(station.path("lat").isNumber()).isTrue();
            assertThat(station.path("positionAmbiguity").asInt()).isEqualTo(2);
            assertThat(station.path("rawLongitude").asText()).isEqualTo("07127.  W");
            packet(app, connection, "N1AMB", ";AMBTEST  *041200z4258.  N/07127.  W>Object");
            await(() -> store.find("OBSERVED_OBJECT", "AMBTEST") != null);
            assertThat(store.find("OBSERVED_OBJECT", "AMBTEST").data().path("approximate").asBoolean()).isTrue();
            packet(app, connection, "N1AMB", ")AMBITEM!4258.  N/07127.  W>Item");
            await(() -> store.find("OBSERVED_OBJECT", "AMBITEM") != null);
            assertThat(store.find("OBSERVED_OBJECT", "AMBITEM").data().path("lat").isNumber()).isTrue();
            packet(app, connection, "N1AMB", "!4258.12N/07127.12W>Exact");
            await(() -> !store.find("STATION", "N1AMB").data().path("approximate").asBoolean());
            await(() -> get(b, snapshotUrl).path("participants").get(0).path("uncertaintyMeters").asDouble() == 0);
            var raw = store.mapper().createObjectNode().put("lat", "4258.  N").put("lon", "07127.  W");
            store.jdbc().update("INSERT INTO domain_record(kind,record_key,owner_id,payload,version,updated_at) VALUES(?,?,?,?,?,?)",
                "OBSERVED_OBJECT", "OLDAMB", "", raw.toString(), 0, OffsetDateTime.parse("2026-10-04T00:00:00Z"));
        }
        try (var app = start()) {
            var old = app.getBean(OperationsStore.class).find("OBSERVED_OBJECT", "OLDAMB");
            assertThat(old.data().path("lat").isNumber()).isTrue();
            assertThat(old.data().path("approximate").asBoolean()).isTrue();
            assertThat(old.updatedAt()).isEqualTo(Instant.parse("2026-10-04T00:00:00Z"));
        }
    }

    @Test
    void legacyClassificationDefaultsAreInstalledOnceAndRemainRemovable() throws Exception {
        String removed;
        try (var app = start()) {
            var b = user(app, "seed-admin");
            var store = app.getBean(OperationsStore.class);
            var policy = app.getBean(RadioPolicy.class);
            var rules = store.list("CLASSIFY");
            assertThat(rules).hasSize(26);
            assertThat(rules.stream().map(r -> r.data().path("ruleTarget").asText() + "|"
                    + r.data().path("trackedStationType").asText() + "|" + r.data().path("value").asText()))
                    .containsExactlyInAnyOrder(
                        "STATUS|WINLINK_GATEWAY|WINLINK VARA FM GATEWAY",
                        "STATUS|WINLINK_GATEWAY|WINLINK VARA FM WIDE GATEWAY",
                        "STATUS|BBS|BBS", "STATUS|DSTAR|(D-STAR)", "STATUS|DIGIPEATER|DIGI",
                        "STATUS|MMDVM|MMDVM", "STATUS|IGATE|APRS IGATE", "STATUS|IGATE|APRS_IGATE",
                        "STATUS|IGATE|BI-DIRECTIONAL-I-GATE", "STATUS|WEATHER|PYTHON APRS WX",
                        "STATUS|UNKNOWN|UI-VIEW32", "STATUS|STATION|APRSISCE", "STATUS|WINLINK_GATEWAY|WINLINK PACKET",
                        "COMMENT|BBS|BBS", "COMMENT|DSTAR|(D-STAR)", "COMMENT|DIGIPEATER|DIGI",
                        "COMMENT|MMDVM|MMDVM", "COMMENT|REPEATER|REPEATER", "COMMENT|REPEATER|VOICE",
                        "COMMENT|WINLINK_GATEWAY|WINLINK PACKET GATEWAY",
                        "COMMENT|WINLINK_GATEWAY|WINLINK VARA FM GATEWAY",
                        "COMMENT|WINLINK_GATEWAY|WINLINK VARA FM WIDE GATEWAY",
                        "STATUS|DIGIPEATER|WX3IN1", "STATUS|IGATE|WX3IN1",
                        "COMMENT|DIGIPEATER|WX3IN1", "COMMENT|IGATE|WX3IN1");
            for (var rule : rules) {
                var data = rule.data();
                assertThat(data.path("ruleType").asText()).isEqualTo("CONTAINS");
                var station = store.mapper().createObjectNode().put(
                        data.path("ruleTarget").asText().toLowerCase(Locale.ROOT),
                        "prefix " + data.path("value").asText().toLowerCase(Locale.ROOT) + " suffix");
                assertThat(policy.classify(station)).contains(data.path("trackedStationType").asText());
            }
            assertThat(policy.classify(store.mapper().createObjectNode().put("comment", "wx3in1")))
                    .contains("DIGIPEATER", "IGATE");
            assertThat(b.json(b.get("/api/v1/trackedStationTypeRules")).size()).isEqualTo(26);
            removed = rules.getFirst().id();
            ok(b, b.send("DELETE", "/api/v1/trackedStationTypeRules/" + removed + "?version=0", Map.of()));
            var staleStation = store.mapper().createObjectNode().put("callsign", "N1OLD")
                    .put("status", "438.850MHz Winlink VARA FM Gateway");
            staleStation.putArray("types").add("STATION");
            store.jdbc().update("INSERT INTO domain_record(kind,record_key,owner_id,payload,version,updated_at) VALUES(?,?,?,?,?,?)",
                    "STATION", "N1OLD", "", staleStation.toString(), 0, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        }
        try (var app = start()) {
            var store = app.getBean(OperationsStore.class);
            assertThat(store.list("CLASSIFY")).hasSize(25);
            assertThat(store.find("CLASSIFY", removed)).isNull();
            assertThat(store.find("STATION", "N1OLD").data().path("types").toString()).contains("WINLINK_GATEWAY");
            assertThat(store.find("STATION", "N1OLD").updatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    private ServletWebServerApplicationContext start() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(NetCentralApplication.class,
                CommunicationIntegrationTest.Fakes.class).run("--server.port=0", "--server.address=127.0.0.1",
                        "--logging.level.root=WARN", "--spring.main.banner-mode=off",
                        "--netcentral.workflow.start=false",
                        "--spring.datasource.url=jdbc:h2:file:"
                                + directory.resolve("operations").toString().replace('\\', '/')
                                + ";DB_CLOSE_ON_EXIT=FALSE");
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

    private JsonNode ok(CommunicationIntegrationTest.Browser b, HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return response.body().isBlank() ? null : b.json(response);
    }

    private String instance(CommunicationIntegrationTest.Browser b, String name) throws Exception {
        return ok(b,
                b.send("POST", "/api/v1/communicationInstances", Map.of("name", name, "type", "KISS_TCP", "enabled",
                        true, "transmitEnabled", true, "host", "127.0.0.1", "port", 8001, "kissPort", 0)))
                .path("configuration").path("id").asText();
    }

    private void packet(ServletWebServerApplicationContext app, String instance, String from, String body) {
        app.getBean(PacketHub.class).receive(instance, new Packet(from, "APANC1", List.of(), body));
    }

    private void command(ServletWebServerApplicationContext app, String instance, String from, String target,
            String body) {
        packet(app, instance, from, ":" + String.format("%-9s", target) + ":" + body);
    }

    private JsonNode get(CommunicationIntegrationTest.Browser b, String path) {
        try {
            return b.json(b.get(path));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String net(CommunicationIntegrationTest.Browser b, String instance) throws Exception {
        return ok(b, b.send("POST", "/api/v1/nets", Map.of("callsign", "NET006", "name", "Command net",
                "communicationMode", "SELECTED", "communicationInstanceIds", List.of(instance)))).path("id").asText();
    }

    private Map<String, Object> object(String instance) {
        var body = new HashMap<String, Object>();
        body.put("callsign", "SHEL01");
        body.put("type", "SHELTER");
        body.put("lat", 42.5);
        body.put("lon", -71.3);
        body.put("comment", "Shelter coordination");
        body.put("communicationMode", "SELECTED");
        body.put("communicationInstanceIds", List.of(instance));
        return body;
    }

    @Test
    void dataMaintenanceIsSysadminOnlyAndDeletesOnlyTheChosenScope() throws Exception {
        try (var app = start()) {
            var admin = user(app, "data-root");
            var reader = user(app, "data-reader");
            var jdbc = app.getBean(JdbcTemplate.class);
            assertThat(reader.get("/setup/data").statusCode()).isEqualTo(403);
            assertThat(reader.send("DELETE", "/api/v1/data/aprs", Map.of()).statusCode()).isEqualTo(403);
            jdbc.update("UPDATE local_account SET role='ADMIN' WHERE username=?", "data-reader");
            reader = user(app, "data-reader");
            assertThat(reader.send("DELETE", "/api/v1/data/nets", Map.of()).statusCode()).isEqualTo(403);
            assertThat(admin.raw("DELETE", "/api/v1/data/aprs", "", "application/json", null).statusCode()).isEqualTo(403);
            assertThat(admin.get("/setup/data").body()).contains("APRS Data", "Net Central Data", "data-system-administrator=\"true\"");
            String instance = instance(admin, "Preserved connection");
            String netId = net(admin, instance);
            ok(admin, admin.send("POST", "/api/v1/nets/" + netId + "/participants/N1ABC", Map.of()));
            ok(admin, admin.send("POST", "/api/v1/nets/" + netId + "/questions", Map.of("question", "Ready?")));
            ok(admin, admin.send("POST", "/api/v1/nets/" + netId + "/questions/1/answers", Map.of("callsign", "N1ABC", "answer", "Yes")));
            String archived = ok(admin, admin.send("POST", "/api/v1/nets", Map.of("callsign", "OLD001", "name", "Archive"))).path("id").asText();
            ok(admin, admin.send("DELETE", "/api/v1/nets/" + archived + "?version=0", Map.of()));
            var schedule = new HashMap<String, Object>();
            schedule.put("callsign", "SCH001");schedule.put("name", "Schedule");schedule.put("type", "ONE_TIME_ONLY");
            schedule.put("timezone", "UTC");schedule.put("duration", 60);
            schedule.put("firstStart", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5).withSecond(0).withNano(0).toString());
            ok(admin, admin.send("POST", "/api/v1/scheduledNets", schedule));
            app.getBean(NetSchedulerAccessor.class).reconcile(Instant.now());
            ok(admin, admin.send("POST", "/api/v1/APRSObjects/requests", object(instance)));
            ok(admin, admin.send("POST", "/api/v1/ignoreStations", Map.of("callsign", "N9ABC")));
            ok(admin, admin.send("PUT", "/api/v1/configurations", Map.of("rawPacketLogging", "true")));
            packet(app, instance, "WX001", "_09281800c180s010g015t070r000p000P000h50b10150");
            await(() -> get(admin, "/api/v1/weatherReports").size() == 1);
            assertThat(app.getBean(PacketHub.class).recent("WX001")).isNotEmpty();
            ok(admin, admin.send("DELETE", "/api/v1/APRSObjects/all/now", Map.of()));
            assertThat(get(admin, "/api/v1/trackedStations").size()).isZero();
            assertThat(get(admin, "/api/v1/weatherReports").size()).isZero();
            assertThat(app.getBean(PacketHub.class).recent("WX001")).isEmpty();
            assertThat(app.getBean(PacketHub.class).observations()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_packet", Integer.class)).isZero();
            assertThat(get(admin, "/api/v1/nets/" + netId).path("id").asText()).isEqualTo(netId);
            assertThat(get(admin, "/api/v1/ignoreStations").size()).isEqualTo(1);
            assertThat(get(admin, "/api/v1/APRSObjects/SHEL01").path("data").path("alive").asBoolean()).isTrue();
            ok(admin, admin.send("DELETE", "/api/v1/data/nets", Map.of()));
            for (String table : List.of("managed_net", "net_schedule", "net_occurrence", "net_participant", "net_expected", "schedule_expected", "net_message", "net_question", "net_answer"))
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM communication_route WHERE owner_key LIKE 'NET:%' OR owner_key LIKE 'SCHEDULE:%'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM communication_instance", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_account", Integer.class)).isEqualTo(2);
            assertThat(get(admin, "/api/v1/APRSObjects/SHEL01").path("data").path("alive").asBoolean()).isTrue();
            ok(admin, admin.send("DELETE", "/api/v1/data/nets", Map.of()));
        }
    }

    @Test
    void invitationsRequireUsableRoutesBeforeRecordingOrSendingAnyInvitations() throws Exception {
        try (var app = start()) {
            var b = user(app, "invite-admin");
            String instance = instance(b, "Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            String id = net(b, instance);
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            var netState = get(b, "/api/v1/nets/" + id);
            ok(b, b.send("PUT", "/api/v1/nets/" + id, Map.of("version", netState.path("version").asLong(), "name", "Closed net", "open", false)));
            assertThat(b.send("POST", "/api/v1/nets/" + id + "/participants/N1ABC", Map.of()).statusCode()).isEqualTo(400);
            command(app, instance, "N3ABC", "NET006", "CI");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().contains("Check-in denied")));
            assertThat(get(b, "/api/v1/nets/" + id + "/participants").size()).isZero();
            var before = get(b, "/api/v1/nets/" + id + "/snapshot");
            var denied = b.send("POST", "/api/v1/nets/" + id + "/invitations", List.of("N1ABC"));
            assertThat(denied.statusCode()).isEqualTo(422);
            assertThat(denied.body()).contains("N1ABC", "No invitations were sent", "30 minutes", "transmission enabled");
            packet(app, instance, "N1ABC", ">Ready for invitations");
            await(() -> !app.getBean(PacketHub.class).recent("N1ABC").isEmpty());
            denied = b.send("POST", "/api/v1/nets/" + id + "/invitations", List.of("N1ABC", "N2ABC"));
            assertThat(denied.statusCode()).isEqualTo(422);
            assertThat(denied.body()).contains("No usable communication route for: N2ABC");
            var after = get(b, "/api/v1/nets/" + id + "/snapshot");
            assertThat(after.path("expectedParticipants")).isEqualTo(before.path("expectedParticipants"));
            assertThat(after.path("messages")).isEqualTo(before.path("messages"));
            assertThat(transport.sent.stream().noneMatch(p -> p.information().contains("invited"))).isTrue();
            ok(b, b.send("POST", "/api/v1/nets/" + id + "/invitations", List.of("N1ABC")));
            await(() -> transport.sent.stream().anyMatch(p -> p.information().contains(":N1ABC    :You have been invited")));
            assertThat(get(b, "/api/v1/nets/" + id + "/snapshot").path("expectedParticipants").toString()).contains("N1ABC");
            ok(b, b.send("POST", "/api/v1/nets/" + id + "/participants/N1ABC-7", Map.of()));
            command(app, instance, "N1ABC-8", "NET006", "CI");
            await(() -> get(b, "/api/v1/nets/" + id + "/participants").size() == 2);

        }
    }

    @Test
    void netEditFormSavesReminderAndInvitationOptionsAndReturnsToMonitor() throws Exception {
        try (var app = start()) {
            var b = user(app, "form-admin");
            String fields = "callsign=NET201&name=Form+net&communicationMode=ALL&lat=42&lon=-71"
                    + "&questionReminder=true&participantInviteAllowed=true";
            var created = b.raw("POST", "/nets/save", fields, "application/x-www-form-urlencoded", b.csrf());
            assertThat(created.statusCode()).withFailMessage(created.body()).isEqualTo(302);
            String monitor = created.headers().firstValue("Location").orElseThrow();
            String id = monitor.substring(monitor.lastIndexOf('/') + 1);
            var options = get(b, "/api/v1/nets/" + id + "/options");
            assertThat(options.path("questionReminder").asBoolean()).isTrue();
            assertThat(options.path("participantInviteAllowed").asBoolean()).isTrue();
            assertThat(b.get(monitor + "/edit").body()).contains("name=\"questionReminder\"",
                    "name=\"participantInviteAllowed\"", "data-return-url=\"/nets/" + id + "\"");
            var oldPage = b.get(monitor + "/options");
            assertThat(oldPage.statusCode()).isEqualTo(302);
            assertThat(oldPage.headers().firstValue("Location").orElseThrow()).isEqualTo(monitor + "/edit");
            var snapshot = get(b, "/api/v1/nets/" + id + "/snapshot");
            String edit = fields.replace("questionReminder=true", "questionReminder=false")
                    .replace("participantInviteAllowed=true", "participantInviteAllowed=false")
                    + "&id=" + id + "&version=" + snapshot.path("net").path("version").asLong()
                    + "&routingVersion=" + snapshot.path("routing").path("version").asLong();
            var saved = b.raw("POST", "/nets/save", edit, "application/x-www-form-urlencoded", b.csrf());
            assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(302);
            assertThat(saved.headers().firstValue("Location").orElseThrow()).isEqualTo(monitor);
            options = get(b, "/api/v1/nets/" + id + "/options");
            assertThat(options.path("questionReminder").asBoolean()).isFalse();
            assertThat(options.path("participantInviteAllowed").asBoolean()).isFalse();
        }
    }

    @Test
    void createFormPersistsRadioSettingsAndSendsConfiguredBeaconsAndLifecycleBulletins() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            String instance = instance(b, "Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            String html = b.get("/nets/new").body();
            assertThat(html).contains("checkinMessage", "symbolTableCode", "net-position-map", "Remind participants")
                    .doesNotContain("Private web access", "name=\"privateNet\"");
            for (boolean announce : List.of(true, false)) {
                String call = announce ? "NET101" : "NET102";
                String fields = "callsign=" + call + "&name=Test+net&open=true&communicationMode=SELECTED"
                        + "&communicationInstanceIds=" + instance + "&checkinMessage=Welcome+aboard"
                        + "&lat=42.5&lon=-71.3&symbolTableId=%5C&symbolTableCode=z"
                        + "&announce=" + announce + "&checkinReminder=true";
                var saved = b.raw("POST", "/nets/save", fields, "application/x-www-form-urlencoded", b.csrf());
                assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(302);
                String location = saved.headers().firstValue("Location").orElseThrow();
                String id = location.substring(location.lastIndexOf('/') + 1);
                var options = get(b, "/api/v1/nets/" + id + "/options");
                assertThat(options.path("checkinMessage").asText()).isEqualTo("Welcome aboard");
                assertThat(options.path("symbolTableId").asText()).isEqualTo("\\");
                assertThat(options.path("lat").asDouble()).isEqualTo(42.5);
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call)
                        && p.information().startsWith(";" + call + "   *")
                        && p.information().contains("4230.00N\\07118.00Wz")));
                packet(app, instance, "N2ABC", ">Ready " + call);
                await(() -> !app.getBean(PacketHub.class).recent("N2ABC").isEmpty());
                ok(b, b.send("POST", "/api/v1/nets/" + id + "/participants/N2ABC", Map.of()));
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call)
                        && p.information().contains(":N2ABC    :Welcome aboard")));
                command(app, instance, "N1ABC", call, "CI");
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call)
                        && p.information().contains(":N1ABC    :Welcome aboard")));
                app.getBean(NetAccessor.class).reminders();
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call)
                        && p.information().contains("Please check out")));
                long version = get(b, "/api/v1/nets/" + id).path("version").asLong();
                ok(b, b.send("DELETE", "/api/v1/nets/" + id + "?version=" + version, Map.of()));
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call)
                        && p.information().startsWith(";" + call + "   _")));
                if (announce) {
                    await(() -> transport.sent.stream().anyMatch(p -> p.information().equals(":BLNNET   :Net " + call + " stopped")));
                    assertThat(transport.sent.stream().anyMatch(p -> p.information().equals(":BLNNET   :Net " + call + " started"))).isTrue();
                } else assertThat(transport.sent.stream().anyMatch(p -> p.source().equals(call)
                        && p.information().startsWith(":BLNNET"))).isFalse();
            }
            var invalid = b.raw("POST", "/nets/save", "callsign=NETBAD&name=Bad&communicationMode=SELECTED&lat=91&lon=0",
                    "application/x-www-form-urlencoded", b.csrf());
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(app.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT COUNT(*) FROM managed_net WHERE callsign='NETBAD'", Integer.class)).isZero();
        }
    }

    @Test
    void participantStatusColorsFollowCommandsCheckoutAndRestart() throws Exception {
        String id;
        try (var app = start()) {
            var b = user(app, "color-admin");
            String instance = instance(b, "Radio");
            id = net(b, instance);
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            command(app, instance, "N1ABC", "NET006", "CI{1");
            String participants = "/api/v1/nets/" + id + "/participants";
            await(() -> get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").size() == 1);
            assertThat(get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText()).isEqualTo("GREEN");
            for (var choice : List.of("STY:YELLOW:2", "STG:GREEN:3", "STR:RED:4")) {
                var parts = choice.split(":");
                command(app, instance, "N1ABC", "NET006", parts[0] + "{" + parts[2]);
                await(() -> get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText().equals(parts[1]));
            }
            command(app, instance, "N1ABC", "NET006", "L{5");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().endsWith(":N1ABC (R)")));
            command(app, instance, "N1ABC", "NET006", "?NCNTP{6");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().contains("NCNTP: N1ABC (R)")));
            command(app, instance, "N1ABC", "NET006", "STY CO{7");
            await(() -> !get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("endTime").isNull());
            assertThat(get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText()).isEqualTo("RED");
            command(app, instance, "N1ABC", "NET006", "CI{8");
            await(() -> get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText().equals("GREEN"));
            command(app, instance, "N1ABC", "NET006", "STY{9");
            await(() -> get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText().equals("YELLOW"));
            assertThat(b.get("/nets/" + id).body()).contains("Status color");
        }
        try (var app = start()) {
            var b = user(app, "color-admin");
            String participants = "/api/v1/nets/" + id + "/participants";
            assertThat(get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText()).isEqualTo("YELLOW");
            ok(b, b.send("DELETE", participants + "/N1ABC", Map.of()));
            assertThat(get(b, "/api/v1/nets/" + id + "/snapshot").path("participants").get(0).path("statusColor").asText()).isEqualTo("RED");
        }
    }

    @Test
    void commandsQuestionsPrivacyRemindersAndArchivePdf() throws Exception {
        String id;
        try (var app = start()) {
            var b = user(app, "root");
            var other = user(app, "other");
            String instance = instance(b, "Radio");
            id = net(b, instance);
            command(app, instance, "N1ABC", "NET006", "CI F 146.520 X 5 E BATTERY B SOLAR Y HANDHELD T TEAM1");
            await(() -> get(b, "/api/v1/nets/" + id + "/participants").size() == 1);
            await(() -> get(b, "/api/v1/nets/" + id + "/participants/N1ABC/equipment").path("RADIO_STYLE").asText()
                    .equals("HANDHELD"));
            command(app, instance, "N1ABC", "NET006", "Q Are supplies available?");
            await(() -> get(b, "/api/v1/nets/" + id + "/questions").size() == 1);
            command(app, instance, "N1ABC", "NET006", "A 1 Yes, ready");
            await(() -> get(b, "/api/v1/nets/" + id + "/questions/1/answers").size() == 1);
            var snapshot = get(b, "/api/v1/nets/" + id + "/snapshot");
            assertThat(snapshot.path("answers").get(0).path("answer").asText()).isEqualTo("Yes, ready");
            assertThat(other.send("POST", "/api/v1/nets/" + id + "/questions", Map.of("question", "Unauthorized"))
                    .statusCode()).isEqualTo(403);
            long version = snapshot.path("net").path("version").asLong();
            ok(b, b.send("PUT", "/api/v1/nets/" + id + "/options?version=" + version,
                    Map.of("participantInviteAllowed", true, "questionReminder", true, "checkinReminder", true,
                            "checkinMessage", "Check your status", "announce", true, "lat", 42.5, "lon", -71.3)));
            command(app, instance, "N1ABC", "NET006", "INV N2ABC");
            await(() -> get(b, "/api/v1/nets/" + id + "/expectedParticipants").toString().contains("N2ABC"));
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            app.getBean(NetAccessor.class).reminders();
            app.getBean(NetAccessor.class).announcements();
            app.getBean(StationAccessor.class).cleanup();
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(";NET006   *")));
            command(app, instance, "N1ABC", "NET006", "MC private logistics");
            await(() -> get(b, "/api/v1/nets/" + id + "/messages").toString().contains("private logistics"));
            assertThat(get(other, "/api/v1/nets/" + id + "/messages").toString()).doesNotContain("private logistics");
            version = get(b, "/api/v1/nets/" + id).path("version").asLong();
            ok(b, b.send("DELETE", "/api/v1/nets/" + id + "?version=" + version, Map.of()));
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(";NET006   _")));
            var response = b.client.send(HttpRequest
                    .newBuilder(b.base.resolve("/api/v1/completedNets/" + id + "/questionAnswerReports")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            for (String reportKind : List.of("participationReports", "messageReports", "questionAnswerReports")) {
                var download = b.client.send(HttpRequest.newBuilder(b.base.resolve("/api/v1/completedNets/" + id + "/" + reportKind)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(download.statusCode()).isEqualTo(200);
                String filename = org.springframework.http.ContentDisposition.parse(download.headers().firstValue("Content-Disposition").orElseThrow()).getFilename();
                assertThat(filename).matches("Command-net-[0-9]{12}-(ics-309|messages|qa)\\.pdf");
            }
            try (var doc = Loader.loadPDF(response.body())) {
                assertThat(new PDFTextStripper().getText(doc)).contains("supplies", "Yes, ready");
                ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(0, 100), "png",
                        Path.of("target/milestone6-questions.png").toFile());
            }
            assertThat(b.get("/nets/" + id).statusCode()).isEqualTo(200);
        }
        try (var app = start()) {
            var b = user(app, "root");
            assertThat(get(b, "/api/v1/completedNets/" + id + "/questions").size()).isEqualTo(1);
        }
    }

    @Test
    void objectsReportsRulesAndRenderedPages() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            var outsider = user(app, "other");
            var createPage = b.get("/objects/new");
            assertThat(createPage.statusCode()).isEqualTo(200);
            assertThat(createPage.body()).contains("Net Central General Resource", "Net Central Shelter Object", "Net Central EOC Object", "Net Central Medical Station Object", "Generic APRS Object", "net-position-map", "data-symbol-picker", "object-save-dialog", "net-cancel-dialog", "value=\"ALL\" selected=\"selected\"").doesNotContain("value=\"NETMGR\"");
            var missingLocation = b.raw("POST", "/objects/save", "callsign=OBJ001&type=OBJECT&communicationMode=ALL", "application/x-www-form-urlencoded", b.csrf());
            assertThat(missingLocation.statusCode()).isEqualTo(400);
            String instance = instance(b, "Radio");
            var saved = ok(b, b.send("POST", "/api/v1/APRSObjects/requests", object(instance)));
            assertThat(saved.path("id").asText()).isEqualTo("SHEL01");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            app.getBean(ResourceAccessor.class).beacons();
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(";SHEL01   *")));
            assertThat(outsider.send("PUT", "/api/v1/APRSObjects/SHEL01/keyValues?version=0", Map.of("water", "10"))
                    .statusCode()).isEqualTo(403);
            ok(b, b.send("PUT", "/api/v1/APRSObjects/SHEL01/keyValues?version=0", Map.of("water", "10")));
            ok(b, b.send("POST", "/api/v1/accessControlLists/SHEL01",
                    Map.of("type", "STATION", "callsignChecked", "N1ABC", "allowed", true)));
            command(app, instance, "N1ABC-1", "SHEL01", "water=20");
            await(() -> get(b, "/api/v1/APRSObjects/SHEL01/keyValues").path("water").asText().equals("20"));
            command(app, instance, "N1ABCD", "SHEL01", "water=99"); // longer callsign root must not match N1ABC
            for (String kind : List.of("status", "census", "workers", "operationalFoods", "operationalMateriels",
                    "contacts", "mobilizations")) {
                var input = new HashMap<String, Object>();
                for (var field : app.getBean(ReportAccessor.class).fields(kind))
                    if (field.type().equals("number"))
                        input.put(field.name(), 1);
                    else if (field.type().equals("text"))
                        input.put(field.name(), "Ready");
                ok(b, b.send("POST", "/api/v1/shelterReports/SHEL01/" + kind, input));
                assertThat(get(b, "/api/v1/shelterReports/SHEL01/" + kind).path("kind").asText()).isEqualTo(kind);
                var page = b.get("/objects/SHEL01/reports/" + kind + "/new");
                assertThat(page.statusCode()).withFailMessage(page.body()).isEqualTo(200);
            }
            assertThat(get(b, "/api/v1/APRSObjects/SHEL01/keyValues").path("water").asText()).isEqualTo("20");
            assertThat(get(b, "/api/v1/APRSObjects/SHEL01/keyValues/history").size()).isEqualTo(2);
            var resource = object(instance);
            resource.put("callsign", "RES001");resource.put("type", "RESOURCE");
            ok(b, b.send("POST", "/api/v1/APRSObjects/requests", resource));
            var resourcePage = b.get("/objects/RES001");
            assertThat(resourcePage.statusCode()).isEqualTo(200);
            assertThat(resourcePage.body()).contains("Object Details", "Location", "-71.3, 42.5", "Resource values", "add-resource-value", "Withdraw object", ">Edit</a>").doesNotContain("Resource history", "Retains its values");
            assertThat(b.get("/objects/SHEL01").body()).doesNotContain("Resource values", "Resource history");
            for (String path : List.of("/explorer", "/objects/new", "/objects/SHEL01", "/objects/SHEL01/edit",
                    "/setup/operations", "/setup/radio", "/tools", "/help")) {
                var response = b.get(path);
                assertThat(response.statusCode()).withFailMessage(path + " " + response.body()).isEqualTo(200);
            }
            var response = b.client.send(
                    HttpRequest.newBuilder(b.base.resolve("/api/v1/shelterReports/SHEL01/report.pdf")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var pdf = Loader.loadPDF(response.body())) {
                assertThat(new PDFTextStripper().getText(pdf)).contains("SHEL01", "census", "contacts");
                for (int i = 0; i < pdf.getNumberOfPages(); i++)
                    ImageIO.write(new PDFRenderer(pdf).renderImageWithDPI(i, 80), "png",
                            Path.of("target/milestone6-reports-" + i + ".png").toFile());
            }
            long version = get(b, "/api/v1/APRSObjects/SHEL01").path("version").asLong();
            ok(b, b.send("DELETE", "/api/v1/APRSObjects/SHEL01?version=" + version, Map.of()));
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(";SHEL01   _")));
            assertThat(get(b, "/api/v1/shelterReports/SHEL01/consolidated").size()).isZero();
        }
    }

    @Test
    void whoRepliesPopulateReferencedCallsignsWithoutChangingObservationTime() throws Exception {
        try(var app=start()) {
            var b=user(app,"who-admin");
            String instance=instance(b,"Radio");
            for(String call : List.of("K7TRT-7", "K7TRT-9", "N1ABC", "N2ABC")) {
                packet(app,instance,call,">Ready");
                await(()->get(b,"/api/v1/trackedStations/"+call).has("data"));
            }
            var before=get(b,"/api/v1/trackedStations/K7TRT-7");
            command(app,instance,"WHO-15","OTHER","K7TRT:General/Dave Magnuson/MT/United States{1");
            await(()->get(b,"/api/v1/trackedStations/K7TRT-7").path("data").path("name").asText().equals("Dave Magnuson"));
            for(String call:List.of("K7TRT-7","K7TRT-9")) {
                var data=get(b,"/api/v1/trackedStations/"+call).path("data");
                assertThat(data.path("license").asText()).isEqualTo("General");
                assertThat(data.path("state").asText()).isEqualTo("MT");
                assertThat(data.path("country").asText()).isEqualTo("United States");
            }
            var after=get(b,"/api/v1/trackedStations/K7TRT-7");
            assertThat(after.path("data").path("lastHeard")).isEqualTo(before.path("data").path("lastHeard"));
            assertThat(after.path("updatedAt")).isEqualTo(before.path("updatedAt"));
            command(app,instance,"WHO-IS","OTHER","N1ABC:Alice/MA/United States{2");
            command(app,instance,"WHO-15","OTHER","N2ABC:Bob/Canada{3");
            await(()->get(b,"/api/v1/trackedStations/N1ABC").path("data").path("name").asText().equals("Alice"));
            await(()->get(b,"/api/v1/trackedStations/N2ABC").path("data").path("country").asText().equals("Canada"));
            assertThat(get(b,"/api/v1/trackedStations/N2ABC").path("data").has("license")).isFalse();
            assertThat(get(b,"/api/v1/live/snapshot").path("callsigns").toString()).contains("Dave Magnuson", "General", "Alice", "Bob");
        }
    }

    @Test
    void objectBeaconsClassifyEmbeddedObjectWithoutChangingSender() throws Exception {
        try (var app = start()) {
            var b = user(app, "object-classification");
            String instance = instance(b, "Radio");
            var store = app.getBean(OperationsStore.class);
            packet(app, instance, "N1ABC", "!4200.00N/07100.00W-Sender position");
            await(() -> store.find("STATION", "N1ABC") != null);
            for (var example : Map.of("WINLINK", "438.850MHz Winlink VARA FM Gateway", "PACKET", "145.530MHz Winlink Packet Gateway",
                    "DSTAR", "RNG0001/A=000010 2m Voice (D-Star) 145.25000MHz -0.6000MHz").entrySet()) {
                packet(app, instance, "N1ABC", ";" + String.format("%-9s", example.getKey()) + "*031200z4903.50N/07201.75W-" + example.getValue());
                await(() -> store.find("OBSERVED_OBJECT", example.getKey()) != null);
                assertThat(store.find("OBSERVED_OBJECT", example.getKey()).data().path("types").toString())
                        .contains(example.getKey().equals("DSTAR") ? "DSTAR" : "WINLINK_GATEWAY");
            }
            var sender = store.find("STATION", "N1ABC").data();
            assertThat(sender.path("types").toString()).doesNotContain("WINLINK_GATEWAY", "DSTAR");
            assertThat(sender.path("comment").asText()).contains("Sender position");
            assertThat(sender.path("lat").asDouble()).isEqualTo(42);
            var before = store.find("OBSERVED_OBJECT", "WINLINK");
            ok(b, b.send("POST", "/api/v1/trackedStationTypeRules", Map.of("ruleTarget", "CALLSIGN", "ruleType", "EQUALS",
                    "value", "winlink", "trackedStationType", "BBS")));
            assertThat(store.find("OBSERVED_OBJECT", "WINLINK").data().path("types").toString()).contains("BBS");
            assertThat(store.find("OBSERVED_OBJECT", "WINLINK").updatedAt()).isEqualTo(before.updatedAt());
        }
    }

    @Test
    void gatewayClassificationSurvivesUnrelatedPackets() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            String instance = instance(b, "Radio");
            String[] examples = {"438.850MHz Winlink VARA FM Gateway", "145.530MHz Winlink Packet Gateway",
                    "RNG0001/A=000010 2m Voice (D-Star) 145.25000MHz -0.6000MHz"};
            for (int i = 0; i < examples.length; i++) {
                String call = "N" + (i + 1) + "TEST";
                String expected = i == 2 ? "DSTAR" : "WINLINK_GATEWAY";
                String statusCall = "K" + (i + 1) + "TEST";
                packet(app, instance, statusCall, ">" + examples[i]);
                await(() -> get(b, "/api/v1/trackedStations/" + statusCall).path("data").path("types").toString().contains(expected));
                packet(app, instance, statusCall, "!4903.50N/07201.75W>Location");
                await(() -> get(b, "/api/v1/trackedStations/" + statusCall).path("data").path("comment").asText().equals("Location"));
                assertThat(get(b, "/api/v1/trackedStations/" + statusCall).path("data").path("types").toString()).contains(expected);
                packet(app, instance, call, "!4903.50N/07201.75W>" + examples[i]);
                await(() -> get(b, "/api/v1/trackedStations/" + call).path("data").path("types").toString().contains(expected));
                packet(app, instance, call, ">Online");
                await(() -> get(b, "/api/v1/trackedStations/" + call).path("data").path("status").asText().equals("Online"));
                assertThat(get(b, "/api/v1/trackedStations/" + call).path("data").path("types").toString()).contains(expected);
            }
        }
    }

    @Test
    void observationsRetentionAndFederationCannotOverwriteOwnedState() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            String instance = instance(b, "Radio");
            ok(b, b.send("PUT", "/api/v1/configurations",
                    Map.of("rawPacketLogging", "true", "federationEnabled", "true")));
            ok(b, b.send("POST", "/api/v1/trackedStationTypeRules", Map.of("ruleTarget", "CALLSIGN", "ruleType",
                    "STARTS_WITH", "value", "N1", "trackedStationType", "REPEATER")));
            packet(app, instance, "N1ABC", "!4903.50N/07201.75W-Test");
            await(() -> get(b, "/api/v1/trackedStations").size() == 1);
            var rawDownload = b.get("/api/v1/tools/rawPackets");
            assertThat(rawDownload.statusCode()).isEqualTo(200);
            assertThat(rawDownload.body()).doesNotContain(instance);
            String[] rawColumns = rawDownload.body().strip().split("\t");
            assertThat(rawColumns).hasSize(2);
            assertThat(java.time.OffsetDateTime.parse(rawColumns[0])).isNotNull();
            assertThat(rawColumns[1]).contains("N1ABC>", "!4903.50N/07201.75W-Test");

            assertThat(get(b, "/api/v1/trackedStations/N1ABC").path("data").path("types").toString())
                    .contains("REPEATER");
            ok(b, b.send("POST", "/api/v1/ignoreStations", Map.of("callsign", "N2ABC")));
            packet(app, instance, "N2ABC", "!4903.50N/07201.75W-Ignored");
            packet(app, instance, "REMOTE", "{ZNNENARegional:Remote coordination");
            await(() -> get(b, "/api/v1/federation/nets").size() == 1);
            ok(b, b.send("POST", "/api/v1/APRSObjects/requests", object(instance)));
            packet(app, instance, "SHEL01", "{ZNSHST22Forged ownership");
            var weatherRule = ok(b, b.send("POST", "/api/v1/trackedStationTypeRules", Map.of(
                    "ruleTarget", "CALLSIGN", "ruleType", "STARTS_WITH", "value", "WX", "trackedStationType", "REPEATER")));
            packet(app, instance, "WX001", "_09281800c180s010g015t070r000p000P000h50b10150");
            await(() -> get(b, "/api/v1/weatherReports").size() == 1);
            packet(app, instance, "WX002", "!4903.50N/07201.75W_180/010g015t070r000p000P000h50b10150");
            await(() -> get(b, "/api/v1/weatherReports").size() == 2);
            for (String call : List.of("WX001", "WX002"))
                assertThat(get(b, "/api/v1/trackedStations/" + call).path("data").path("types").toString())
                        .contains("WEATHER", "REPEATER", "STATION");
            packet(app, instance, "WX001", ">Still reporting");
            await(() -> get(b, "/api/v1/trackedStations/WX001").path("data").path("status").asText().equals("Still reporting"));
            assertThat(get(b, "/api/v1/trackedStations/WX001").path("data").path("types").toString())
                    .contains("WEATHER", "REPEATER");
            ok(b, b.send("DELETE", "/api/v1/trackedStationTypeRules/" + weatherRule.path("id").asText()
                    + "?version=" + weatherRule.path("version").asLong(), Map.of()));
            for (String call : List.of("WX001", "WX002"))
                assertThat(get(b, "/api/v1/trackedStations/" + call).path("data").path("types").toString())
                        .contains("WEATHER", "STATION").doesNotContain("REPEATER");

            assertThat(b.get("/stations/WX002").statusCode()).isEqualTo(200);
            assertThat(b.get("/stations/WX002").body()).contains("symbolDescription", "Weather station");
            assertThat(b.get("/stations/N1ABC").body()).contains("No weather reports.", "No previous weather reports.");
            assertThat(b.get("/stations/WX002").body()).contains("Current Weather", "No previous weather reports.");
            packet(app, instance, "WX002", "!4903.50N/07201.75W_190/012g015t075r000p000P000h55b10160");
            await(() -> get(b, "/api/v1/weatherReports/callsigns/WX002/all").size() == 2);
            var weatherPage = b.get("/stations/WX002");
            assertThat(weatherPage.statusCode()).isEqualTo(200);
            String latestWeather = weatherPage.body().split("id=\"latest-weather\"")[1].split("</section>")[0];
            String previousWeather = weatherPage.body().split("id=\"previous-weather\"")[1].split("</section>")[0];
            assertThat(latestWeather).contains(">75</span>", ">1016.0</span>", "Atmosphere", "Precipitation", "Gust:", "Since 00:00:").doesNotContain(">70</span>");
            assertThat(previousWeather).contains("ldTime (local time)", "datetime=", ">70</td>", ">50</td>",
                    ">1015.0</td>", ">10</td>", ">180</td>").doesNotContain(">75</td>");

            assertThat(b.get("/objects/SHEL01").body()).contains("<dt>Symbol</dt>");
            assertThat(get(b, "/api/v1/live/snapshot").path("resources").toString())
                    .contains("symbolDescription", "Weather station");
            packet(app, instance, "REMOTE", "{ZNNECIN1ABC");
            await(() -> get(b, "/api/v1/federation/nets").get(0).path("data").path("participants").has("N1ABC"));
            packet(app, instance, "REMOTE", "{ZNNECON1ABC");
            await(() -> !get(b, "/api/v1/federation/nets").get(0).path("data").path("participants").has("N1ABC"));
            packet(app, instance, "N1ABC", ")ITEM!4903.50N/07201.75W-Test");
            await(() -> get(b, "/api/v1/APRSObjects/ITEM").path("data").path("alive").asBoolean());
            assertThat(get(b, "/api/v1/shelterReports/SHEL01/consolidated").size()).isZero();
            assertThat(get(b, "/api/v1/trackedStations").toString()).doesNotContain("N2ABC");
            var jdbc = app.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE domain_record SET updated_at=? WHERE kind='STATION'",
                    OffsetDateTime.now().minusDays(3));
            jdbc.update("UPDATE raw_packet SET received_at=?", OffsetDateTime.now().minusDays(10));
            app.getBean(StationAccessor.class).cleanup();
            assertThat(get(b, "/api/v1/trackedStations").size()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_packet", Integer.class)).isZero();
            assertThat(get(b, "/api/v1/APRSObjects/SHEL01").path("data").path("alive").asBoolean()).isTrue();
        }
    }

    @Test
    void scheduleOptionsCopyAndNumberedManagerCommandsDoNotReplay() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            var input = new HashMap<String, Object>();
            input.put("callsign", "SCH006");
            input.put("name", "Scheduled options");
            input.put("type", "ONCE");
            input.put("timezone", "UTC");
            input.put("firstStart",
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5).withSecond(0).withNano(0).toString());
            input.put("duration", 60);
            // Use the recurrence enum exposed by the schedule implementation.
            input.put("type", "ONE_TIME_ONLY");
            var created = b.send("POST", "/api/v1/scheduledNets", input);
            var schedule = ok(b, created);
            String id = schedule.path("id").asText();
            ok(b, b.send("PUT", "/api/v1/scheduledNets/" + id + "/options?version=0", Map.of("questionReminder", true,
                    "participantInviteAllowed", true, "checkinMessage", "Welcome to the exercise")));
            assertThat(b.get("/schedules/" + id + "/options").statusCode()).isEqualTo(200);
            app.getBean(NetSchedulerAccessor.class).reconcile(Instant.now());
            String netId = get(b, "/api/v1/nets").get(0).path("id").asText();
            assertThat(get(b, "/api/v1/nets/" + netId + "/options").path("checkinMessage").asText())
                    .isEqualTo("Welcome to the exercise");
            var store = app.getBean(OperationsStore.class);
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(t -> {
                store.lock();
                assertThat(store.duplicateRadio("NETMGR", "N1ABC", "C ADHOC{001")).isFalse();
                assertThat(store.duplicateRadio("NETMGR", "N1ABC", "C ADHOC{001")).isTrue();
                assertThat(store.duplicateRadio("NETMGR", "N2ABC", "C ADHOC{001")).isFalse();
            });
        }
    }

    @Test
    void netManagerLocationPersistsAndControlsObjectBeacon() throws Exception {
        try (var app = start()) {
            var b = user(app, "manager-location");
            String instance = instance(b, "Radio");
            ok(b, b.send("PUT", "/api/v1/communicationRoutes/DEFAULT", Map.of("version", 0, "mode", "ALL", "instanceIds", List.of())));
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            String owner = get(b, "/api/v1/users").get(0).path("id").asText();
            var page = b.get("/setup/operations?section=net-manager");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat(page.body()).contains("netManagerLatitude", "netManagerLongitude", "location-manual", "location-map", "net-position-map", "communicationMode", "communicationInstanceIds");
            assertThat(page.body()).doesNotContain("Net Manager also requires");
            var saved = b.raw("POST", "/setup/operations", "section=net-manager&netManagerEnabled=true&netManagerCallsign=NETMGR&netManagerOwner=" + owner
                    + "&netManagerLatitude=42.5&netManagerLongitude=-71.3&communicationMode=SELECTED&routingVersion=-1&communicationInstanceIds=" + instance, "application/x-www-form-urlencoded", b.csrf());
            assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(302);
            var route = app.getBean(RoutingService.class).load("OBJECT:NETMGR");
            assertThat(route.mode()).isEqualTo("SELECTED");
            assertThat(route.instanceIds()).containsExactly(instance);
            assertThat(b.get("/setup/operations?section=net-manager").body()).contains("value=\"SELECTED\" selected=\"selected\"");
            var object = app.getBean(OperationsStore.class).find("OBJECT", "NETMGR");
            assertThat(object.data().path("lat").asDouble()).isEqualTo(42.5);
            assertThat(object.data().path("lon").asDouble()).isEqualTo(-71.3);
            assertThat(object.data().path("type").asText()).isEqualTo("NETMGR");
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(";NETMGR   *") && p.information().contains("4230.00N/07118.00W")));
            assertThat(b.send("PUT", "/api/v1/configurations", Map.of("netManagerLatitude", "91")).statusCode()).isEqualTo(400);
            assertThat(app.getBean(OperationsStore.class).setting("netManagerLatitude")).isEqualTo("42.5");
        }
        try (var app = start()) {
            var b = user(app, "manager-location");
            var store = app.getBean(OperationsStore.class);
            assertThat(app.getBean(RoutingService.class).load("OBJECT:NETMGR").mode()).isEqualTo("SELECTED");
            assertThat(app.getBean(RoutingService.class).load("OBJECT:NETMGR").instanceIds()).hasSize(1);
            assertThat(store.setting("netManagerLongitude")).isEqualTo("-71.3");
            assertThat(store.find("OBJECT", "NETMGR").data().path("lat").asDouble()).isEqualTo(42.5);
            ok(b, b.send("PUT", "/api/v1/configurations", Map.of("netManagerEnabled", "false")));
            assertThat(store.find("OBJECT", "NETMGR").data().path("alive").asBoolean()).isFalse();
            assertThat(store.setting("netManagerLatitude")).isEqualTo("42.5");
        }
    }

    @Test
    void netManagerRequiresAccessRuleAndOnlyCreatorCanSecure() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            String instance = instance(b, "Radio");
            String owner = get(b, "/api/v1/users").get(0).path("id").asText();
            ok(b, b.send("PUT", "/api/v1/communicationRoutes/DEFAULT",
                    Map.of("version", 0, "mode", "ALL", "instanceIds", List.of())));
            ok(b, b.send("PUT", "/api/v1/configurations",
                    Map.of("netManagerEnabled", "true", "netManagerOwner", owner, "netManagerCallsign", "NETMGR")));
            ok(b, b.send("POST", "/api/v1/accessControlLists/NETMGR",
                    Map.of("type", "STATION", "callsignChecked", "*", "allowed", true)));
            command(app, instance, "N1ABC", "NETMGR", "CP ADHOC1");
            await(() -> get(b, "/api/v1/nets").size() == 1);
            String id = get(b, "/api/v1/nets").get(0).path("id").asText();
            assertThat(get(b, "/api/v1/nets/" + id).path("open").asBoolean()).isFalse();
            command(app, instance, "N2ABC", "NETMGR", "S ADHOC1");
            command(app, instance, "N1ABC", "NETMGR", "S ADHOC1");
            await(() -> get(b, "/api/v1/completedNets").size() == 1);
            assertThat(get(b, "/api/v1/completedNets").get(0).path("id").asText()).isEqualTo(id);
        }
    }

    @Test
    void federationSupportsBothEncodingsAndRemoteDiscovery() throws Exception {
        try (var app = start()) {
            var b = user(app, "federation-admin");
            String instance = instance(b, "Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            ok(b, b.send("PUT", "/api/v1/communicationRoutes/DEFAULT",
                    Map.of("version", 0, "mode", "ALL", "instanceIds", List.of())));
            assertThat(b.send("PUT", "/api/v1/configurations", Map.of("federationInterrogateEnabled", "true")).statusCode()).isEqualTo(400);
            ok(b, b.send("PUT", "/api/v1/configurations", Map.of("federationEnabled", "true",
                    "federationMessagesEnabled", "true", "federationInterrogateEnabled", "true", "federationCallsign", "N1ABC")));
            assertThat(b.get("/setup/operations?section=federation").body()).contains("federationMessagesEnabled", "federationCallsign");
            ok(b, b.send("POST", "/api/v1/APRSObjects/requests", object(instance)));
            app.getBean(ResourceAccessor.class).beacons();
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith("{ZNSHPASH:")));
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(":SHEL01   :SHPASH:")));
            command(app, instance, "EMPTY", "EMPTY", "SHPASH:EMPTY:");
            await(() -> app.getBean(OperationsStore.class).find("OBSERVED_OBJECT", "EMPTY") != null);
            assertThat(com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetAnnounceReport.isValid("NET123", "NENAName:")).isNotNull();
            String netId = net(b, instance);
            String closedId = ok(b, b.send("POST", "/api/v1/nets", Map.of("callsign", "CLOSED", "name", "Closed net",
                    "open", false, "communicationMode", "SELECTED", "communicationInstanceIds", List.of(instance)))).path("id").asText();
            command(app, instance, "N2ABC", "CLOSED", "?NCOT");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("CLOSED") && p.information().endsWith(":NCOT:NET")));
            command(app, instance, "N2ABC", "CLOSED", "?CMD CI");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("CLOSED") && p.information().contains("Check-in denied")));
            assertThat(get(b, "/api/v1/nets/" + closedId + "/participants").size()).isZero();
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(":NET006   :NENA")));
            command(app, instance, "N2ABC", "NET006", "?NCOT");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("NET006") && p.information().endsWith(":NCOT:NET")));
            command(app, instance, "REMOTE", "REMOTE", "NENARegional:Remote coordination");
            await(() -> get(b, "/api/v1/federation/nets").size() == 1);
            packet(app, instance, "REMOTE", "{ZNNENARegional:Remote coordination");
            String secure = new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetSecureReport("REMOTE", ZonedDateTime.now()).getFullReportData();
            command(app, instance, "REMOTE", "REMOTE", secure);
            await(() -> !get(b, "/api/v1/federation/nets").get(0).path("data").path("active").asBoolean(true));
            var store = app.getBean(OperationsStore.class);
            await(() -> store.find("OBSERVED_OBJECT", "REMOTE") != null);
            assertThat(store.find("OBSERVED_OBJECT", "REMOTE").data().path("type").asText()).isEqualTo("NET");
            packet(app, instance, "GATE01", ";REMOBJ   *031200z4903.50N/07201.75W-Remote object");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("N1ABC") && p.information().equals(":REMOBJ   :?NCOT")));
            command(app, instance, "REMOBJ", "N1ABC", "NCOT:RESOURCE");
            await(() -> store.find("OBSERVED_OBJECT", "REMOBJ").data().path("type").asText().equals("RESOURCE"));
            await(() -> transport.sent.stream().anyMatch(p -> p.information().equals(":REMOBJ   :?NCGRI")));
            command(app, instance, "REMOBJ", "N1ABC", "NCGRI:REMOBJ,Water supplies");
            await(() -> store.find("OBSERVED_OBJECT", "REMOBJ").data().path("comment").asText().equals("Water supplies"));
            packet(app, instance, "GATE01", ";REMOBJ   *031201z4903.50N/07201.75W-Updated beacon");
            command(app, instance, "SHEL01", "N1ABC", "NCOT:RESOURCE");
            command(app, instance, "REMOBJ", "N1ABC", "NCGRV:water=20");
            await(() -> store.find("OBSERVED_OBJECT", "REMOBJ").data().path("keyValues").path("water").asText().equals("20"));
            assertThat(store.find("OBJECT", "SHEL01").data().path("type").asText()).isEqualTo("SHELTER");
            assertThat(store.find("OBSERVED_OBJECT", "REMOBJ").data().path("type").asText()).isEqualTo("RESOURCE");
            assertThat(transport.sent.stream().filter(p -> p.information().equals(":REMOBJ   :?NCOT")).count()).isEqualTo(1);
            assertThat(store.jdbc().queryForObject("SELECT COUNT(*) FROM operational_report WHERE callsign='REMOTE' AND kind='netAnnounce'", Integer.class)).isEqualTo(1);
            ok(b, b.send("PUT", "/api/v1/configurations", Map.of("federationUserDefinedEnabled", "false")));
            long rawCount = transport.sent.stream().filter(p -> p.information().startsWith("{ZNSHPA")).count();
            long messageCount = transport.sent.stream().filter(p -> p.information().startsWith(":SHEL01   :SHPA")).count();
            app.getBean(ResourceAccessor.class).beacons();
            await(() -> transport.sent.stream().filter(p -> p.information().startsWith(":SHEL01   :SHPA")).count() > messageCount);
            assertThat(transport.sent.stream().filter(p -> p.information().startsWith("{ZNSHPA")).count()).isEqualTo(rawCount);
            ok(b, b.send("DELETE", "/api/v1/nets/" + netId + "?version=" + get(b, "/api/v1/nets/" + netId).path("version").asLong(), null));
            await(() -> transport.sent.stream().anyMatch(p -> p.information().startsWith(":NET006   :NENE")));
            ok(b, b.send("PUT", "/api/v1/configurations", Map.of("federationEnabled", "false")));
            command(app, instance, "OTHER", "OTHER", "NENAOther:Disabled federation");
            // A subsequent request confirms processing without relying on a fixed sleep.
            command(app, instance, "N2ABC", "SHEL01", "?NCOT");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("SHEL01") && p.information().contains(":NCOT:SHELTER")));
            assertThat(store.find("FEDERATED_NET", "OTHER")).isNull();
        }
    }

    @Test
    void priorityObjectQueriesReturnOnlyRequestedInformation() throws Exception {
        try (var app = start()) {
            var b = user(app, "priority-queries");
            String instance = instance(b, "Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            for (String type : List.of("EOC", "SHELTER")) {
                String call = type.equals("EOC") ? "EOC001" : "SHEL01";
                var body = object(instance);
                body.put("callsign", call);body.put("type", type);body.put("comment", "Emergency coordination");
                ok(b, b.send("POST", "/api/v1/APRSObjects/requests", body));
                String commands = type.equals("EOC") ? "CMD:?+(CMD,INFO,APRSP,NCOT,NCPI,NCPES,NCPEM)"
                    : "CMD:?+(CMD,INFO,APRSP,NCOT,NCPI,NCPSS,NCPSC,NCPSF,NCPSM,NCPSW)";
                String description = "NCPI:" + call + ",Emergency coordination";
                command(app, instance, "N1CMD", call, "?cmd{c1");
                command(app, instance, "N1PI", call, "?ncpi{p1");
                command(app, instance, "N1INFO", call, "?info{i1");
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call)
                    && p.information().equals(":N1INFO   :" + description)));
                assertThat(transport.sent.stream().filter(p -> p.source().equals(call) && p.information().startsWith(":N1CMD    :"))
                    .map(Packet::information).toList()).containsExactlyInAnyOrder(":N1CMD    :ackc1", ":N1CMD    :" + commands);
                assertThat(transport.sent.stream().filter(p -> p.source().equals(call) && p.information().startsWith(":N1PI     :"))
                    .map(Packet::information).toList()).containsExactlyInAnyOrder(":N1PI     :ackp1", ":N1PI     :" + description);
                assertThat(transport.sent.stream().filter(p -> p.source().equals(call) && p.information().startsWith(":N1INFO   :"))
                    .map(Packet::information).toList()).containsExactlyInAnyOrder(":N1INFO   :acki1", ":N1INFO   :" + commands, ":N1INFO   :" + description);
            }
        }
    }

    @Test
    void managedObjectRadioCommandsReturnLegacyResponses() throws Exception {
        try (var app = start()) {
            var b = user(app, "object-radio");
            String instance = instance(b, "Radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            for (String type : List.of("RESOURCE", "SHELTER", "EOC")) {
                var body = object(instance);
                String call = type.equals("RESOURCE") ? "RES001" : type.equals("EOC") ? "EOC001" : "SHEL01";
                body.put("callsign", call);body.put("type", type);
                ok(b, b.send("POST", "/api/v1/APRSObjects/requests", body));
                ok(b, b.send("POST", "/api/v1/accessControlLists/" + call,
                        Map.of("type", "STATION", "callsignChecked", "N1ABC", "allowed", true)));
                command(app, instance, "N1ABC", call, "?NCOT{123");
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call) && p.information().endsWith(":NCOT:" + type)));
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call) && p.information().endsWith(":ack123")));
                command(app, instance, "N1ABC", call, "?INFO");
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call) && p.information().contains(":CMD:?+(CMD,INFO")));
            }
            command(app, instance, "N1ABC", "RES001", "water=20");
            await(() -> get(b, "/api/v1/APRSObjects/RES001/keyValues").path("water").asText().equals("20"));
            command(app, instance, "N1ABC", "RES001", "water");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("RES001") && p.information().endsWith(":water=20")));
            command(app, instance, "N1ABC", "RES001", "?NCGRV");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().endsWith(":NCGRV:water=20")));
            command(app, instance, "N2ABC", "RES001", "water=99");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().equals(":N2ABC    :Bad or unauthorized message.")));
            assertThat(get(b, "/api/v1/APRSObjects/RES001/keyValues").path("water").asText()).isEqualTo("20");
            command(app, instance, "N1ABC", "RES001", "water=");
            await(() -> !get(b, "/api/v1/APRSObjects/RES001/keyValues").has("water"));
            var queries = Map.of("status", "NCPSS", "census", "NCPSC", "workers", "NCPSW",
                    "operationalFoods", "NCPSF", "operationalMateriels", "NCPSM", "contacts", "NCPES", "mobilizations", "NCPEM");
            for (var entry : queries.entrySet()) {
                String kind = entry.getKey();
                String call = Set.of("contacts", "mobilizations").contains(kind) ? "EOC001" : "SHEL01";
                var input = new HashMap<String, Object>();
                for (var field : app.getBean(ReportAccessor.class).fields(kind)) {
                    if (field.type().equals("number")) input.put(field.name(), 1);
                    else if (field.type().equals("text")) input.put(field.name(), "Ready");
                }
                var report = ok(b, b.send("POST", "/api/v1/shelterReports/" + call + "/" + kind, input));
                String data = report.path("data").path("reportData").asText();
                String prefix = kind.equals("workers") ? entry.getValue() + ":1:"
                        : kind.startsWith("operational") ? entry.getValue() + ":OnHand:" : call + ":";
                command(app, instance, "N1ABC", call, "?" + entry.getValue());
                await(() -> transport.sent.stream().anyMatch(p -> p.source().equals(call) && p.information().endsWith(":" + prefix + data)));
                command(app, instance, "N1ABC", call, report.path("data").path("fullReportData").asText() + "{321");
                await(() -> get(b, "/api/v1/shelterReports/" + call + "/" + kind + "/all").size() == 2);
            }
        }
    }

    @Test
    void callsignIdentificationSendsSelectedCallsignToWhoIs() throws Exception {
        try (var app = start()) {
            var b = user(app, "identity-admin");
            var jdbc = app.getBean(JdbcTemplate.class);
            jdbc.update("UPDATE local_account SET callsign=NULL");
            String url = "/api/v1/callsigns/K7TRT/identityRequests";
            assertThat(ok(b, b.send("POST", url, Map.of())).path("status").asText())
                    .isEqualTo("ACCOUNT_CALLSIGN_REQUIRED");
            jdbc.update("UPDATE local_account SET callsign='N1ABC'");
            assertThat(ok(b, b.send("POST", url, Map.of())).path("status").asText()).isEqualTo("NO_ROUTE");
            String instance = instance(b, "Radio");
            ok(b, b.send("PUT", "/api/v1/communicationRoutes/DEFAULT",
                    Map.of("version", 0, "mode", "ALL", "instanceIds", List.of())));
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            String second = instance(b, "Second radio");
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(second));
            var secondTransport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(second);
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            assertThat(ok(b, b.send("POST", url, Map.of())).path("status").asText()).isEqualTo("QUEUED");
            await(() -> transport.sent.stream().anyMatch(p -> p.source().equals("N1ABC")
                    && p.information().equals(":WHO-IS   :K7TRT")));
            await(() -> secondTransport.sent.stream().anyMatch(p -> p.information().equals(":WHO-IS   :K7TRT")));
            packet(app, instance, "WHO-IS", ">Directory ready");
            await(() -> !app.getBean(PacketHub.class).recent("WHO-IS").isEmpty());
            assertThat(ok(b, b.send("POST", "/api/v1/callsigns/N2ABC/identityRequests", Map.of())).path("status").asText()).isEqualTo("QUEUED");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().equals(":WHO-IS   :N2ABC")));
            assertThat(secondTransport.sent.stream().anyMatch(p -> p.information().equals(":WHO-IS   :N2ABC"))).isFalse();
            assertThat(b.get("/setup/operations?section=identity").body()).contains("Identity", "WHO-IS", "WHO-15");
            assertThat(b.raw("POST", "/setup/operations", "section=identity&identityResolver=WHO-15", "application/x-www-form-urlencoded", b.csrf()).statusCode()).isEqualTo(302);
            assertThat(ok(b, b.send("POST", url, Map.of())).path("status").asText()).isEqualTo("QUEUED");
            await(() -> secondTransport.sent.stream().anyMatch(p -> p.information().equals(":WHO-15   :K7TRT")));
            assertThat(b.send("PUT", "/api/v1/configurations", Map.of("identityResolver", "INVALID")).statusCode()).isEqualTo(400);
            var other = user(app, "identity-other");
            jdbc.update("UPDATE local_account SET role='ADMIN' WHERE username='identity-other'");
            other = user(app, "identity-other");
            assertThat(other.get("/setup/operations?section=identity").statusCode()).isEqualTo(403);
            assertThat(other.send("PUT", "/api/v1/configurations", Map.of("identityResolver", "WHO-IS")).statusCode()).isEqualTo(403);
            assertThat(other.raw("POST", "/setup/operations", "section=identity&identityResolver=WHO-IS", "application/x-www-form-urlencoded", other.csrf()).statusCode()).isEqualTo(403);

        }
        try (var app = start()) {
            assertThat(app.getBean(OperationsStore.class).setting("identityResolver")).isEqualTo("WHO-15");
        }
    }

    @Test
    void winlinkAndNtsUseHeardRouteWithoutPersistingSecrets() throws Exception {
        try (var app = start()) {
            var b = user(app, "root");
            var ntsPage = b.get("/tools?section=nts");
            assertThat(ntsPage.statusCode()).isEqualTo(200);
            assertThat(ntsPage.body()).contains("nts-preamble", "nts-recipient", "nts-words", "nts-signature");
            for (int word = 1; word <= 25; word++)
                assertThat(ntsPage.body()).contains("name=\"message" + word + "\"");
            assertThat(ntsPage.body().indexOf("name=\"check\"")).isLessThan(ntsPage.body().indexOf("name=\"timeFiled\""));
            assertThat(ntsPage.body().indexOf("name=\"message25\"")).isLessThan(ntsPage.body().indexOf("name=\"signature\""));
            String instance = instance(b, "Radio");
            ok(b, b.send("PUT", "/api/v1/communicationRoutes/DEFAULT",
                    Map.of("version", 0, "mode", "ALL", "instanceIds", List.of())));
            app.getBean(CommunicationManager.class).reconcile();
            await(() -> app.getBean(CommunicationManager.class).available(instance));
            var transport = app.getBean(CommunicationIntegrationTest.FakeFactory.class).transports.get(instance);
            packet(app, instance, "WLNK-1", ">Gateway ready");
            await(() -> !app.getBean(PacketHub.class).recent("WLNK-1").isEmpty());
            var response = ok(b,
                    b.send("POST", "/api/v1/tools/winlink/requests",
                            Map.of("callsign", "N1ABC", "password", "Secret123", "recipient", "user@example.test",
                                    "subject", "Status", "message", "All supplies ready")));
            assertThat(response.path("success").asBoolean()).isTrue();
            command(app, instance, "WLNK-1", "N1ABC", "Login challenge: 123");
            await(() -> get(b, "/api/v1/tools/winlink/requests/N1ABC").path("status").asText().equals("CHALLENGING"));
            command(app, instance, "WLNK-1", "N1ABC", "Hello N1ABC");
            await(() -> get(b, "/api/v1/tools/winlink/requests/N1ABC").path("status").asText().equals("SENT"));
            assertThat(transport.sent.stream().map(Packet::information).toList().toString()).contains("/EX", "BYE")
                    .doesNotContain("Secret123");
            packet(app, instance, "NTSGTE", ">Gateway ready");
            await(() -> !app.getBean(PacketHub.class).recent("NTSGTE").isEmpty());
            var nts = new HashMap<String, Object>();
            nts.put("number", "1");
            nts.put("precedence", "R");
            nts.put("callsignOrigin", "N1ABC");
            nts.put("locationOrigin", "Boston");
            nts.put("dateFiled", "SEP28");
            nts.put("name", "Recipient");
            nts.put("signature", "Operator");
            nts.put("check", "2");
            nts.put("message1", "SUPPLIES");
            nts.put("message2", "READY");
            assertThat(ok(b, b.send("POST", "/api/v1/tools/ntsradiogram/requests", nts)).path("status").asText())
                    .isEqualTo("QUEUED");
            await(() -> transport.sent.stream().anyMatch(p -> p.information().contains("N1\\SUPPLIES READY")));
            nts.put("check", "3");
            assertThat(b.send("POST", "/api/v1/tools/ntsradiogram/requests", nts).statusCode()).isEqualTo(400);
        }
        try (var app = start()) {
            var b = user(app, "root");
            assertThat(get(b, "/api/v1/tools/winlink/requests/N1ABC").path("status").asText()).isEqualTo("NOT_FOUND");
        }
    }
}
