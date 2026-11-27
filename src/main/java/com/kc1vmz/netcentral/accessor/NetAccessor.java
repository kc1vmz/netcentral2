package com.kc1vmz.netcentral.accessor;

import com.kc1vmz.netcentral.communication.AprsMessageProtocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kc1vmz.netcentral.account.Account;
import com.kc1vmz.netcentral.account.LocalPrincipal;
import com.kc1vmz.netcentral.communication.Packet;
import com.kc1vmz.netcentral.communication.PacketHub;
import com.kc1vmz.netcentral.communication.RoutingService;
import com.kc1vmz.netcentral.communication.TransmissionRouter;
import com.kc1vmz.netcentral.net.NetModels.*;
import com.kc1vmz.netcentral.net.NetQuestionModels;
import com.kc1vmz.netcentral.net.StripFormat;
import com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetQuestionReport;
import com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetQuestionAnswerReport;
import com.kc1vmz.netcentral.operations.NetOptions;
import com.kc1vmz.netcentral.operations.RadioDispatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NetAccessor {
    private final JdbcTemplate jdbc;
    private final AccountAccessor accounts;
    private final RoutingService routing;
    private final TransmissionRouter router;
    private final ApplicationEventPublisher events;
    private final RadioDispatch federation;
    private final long positionLifetime;
    private final com.kc1vmz.netcentral.operations.RadioPolicy radioPolicy;
    private static final RowMapper<Net> NET = (r, n) -> new Net(r.getString("id"), r.getString("callsign"),
            r.getString("name"),
            r.getString("description"), r.getString("voice_frequency"), r.getString("owner_id"),
            r.getString("creator_name"),
            r.getBoolean("open"), r.getBoolean("private_net"), instant(r.getObject("started_at", OffsetDateTime.class)),
            instant(r.getObject("closed_at", OffsetDateTime.class)), r.getLong("version"),
            r.getInt("participant_count"), r.getString("id"));
    private static final String NET_SELECT = "SELECT n.*, (SELECT COUNT(*) FROM net_participant p WHERE p.net_id=n.id AND (n.closed_at IS NOT NULL OR p.ended_at IS NULL)) participant_count FROM managed_net n ";
    private static final RowMapper<Participant> PARTICIPANT = (r, n) -> new Participant(r.getString("callsign"),
            r.getString("status"),
            r.getString("voice_frequency"), r.getString("tactical_callsign"),
            instant(r.getObject("started_at", OffsetDateTime.class)),
            instant(r.getObject("ended_at", OffsetDateTime.class)),
            instant(r.getObject("last_heard", OffsetDateTime.class)),
            r.getObject("lat", Double.class), r.getObject("lon", Double.class), r.getLong("version"),
            r.getString("transmit_power"), r.getString("electrical_power"), r.getString("backup_power"),
            r.getString("radio_style"), r.getString("status_color"), r.getDouble("uncertainty_meters"));
    private static final RowMapper<Message> MESSAGE = (r, n) -> new Message(r.getString("id"), r.getString("net_id"),
            r.getString("callsign_from"),
            r.getString("recipient"), r.getString("message"), instant(r.getObject("received_at", OffsetDateTime.class)),
            r.getString("direction"), r.getString("delivery"), r.getString("ack_number"));

    public NetAccessor(JdbcTemplate jdbc, AccountAccessor accounts, RoutingService routing, TransmissionRouter router,
            ApplicationEventPublisher events, RadioDispatch federation,
            @Value("${netcentral.station-visible-seconds:86400}") long positionLifetime,
            com.kc1vmz.netcentral.operations.RadioPolicy radioPolicy) {
        this.radioPolicy = radioPolicy;
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.routing = routing;
        this.router = router;
        this.events = events;
        this.federation = federation;
        if (positionLifetime < 1)
            throw new IllegalArgumentException("Station visibility must be positive");
        this.positionLifetime = positionLifetime;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }

    private void lock() {
        jdbc.queryForObject("SELECT id FROM net_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    private void touch(String id) {
        jdbc.update("UPDATE managed_net SET version=version+1 WHERE id=?", id);
    }

    private boolean admin(Account actor) {
        return !actor.role().equals("USER");
    }

    private boolean manages(Net net, Account actor) {
        return net.ownerId().equals(actor.id()) || admin(actor);
    }

    private void readable(Net net, Account actor) {
        if (net.privateNet() && !manages(net, actor))
            throw new AccessDeniedException("Private net");
    }

    private void manageable(Net net) {
        if (!manages(net, accounts.current()))
            throw new AccessDeniedException("Net owner or administrator required");
    }

    public Net findInternal(String id) {
        List<Net> found = jdbc.query(NET_SELECT + "WHERE n.id=? OR n.active_callsign=?", NET, id,
                id.toUpperCase(Locale.ROOT));
        if (found.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Net not found");
        return found.getFirst();
    }

    public List<Net> listForPrincipal(LocalPrincipal principal) {
        if (!accounts.isCurrent(principal))
            throw new AccessDeniedException("Session revoked");
        boolean administrator = principal.getAuthorities().stream()
                .anyMatch(a -> !a.getAuthority().equals("ROLE_USER"));
        return jdbc.query(NET_SELECT
                + "WHERE n.private_net=FALSE OR n.owner_id=? OR ? ORDER BY n.closed_at NULLS FIRST,n.started_at DESC LIMIT 2000",
                NET, principal.getUsername(), administrator);
    }

    public Net get(String id) {
        Net net = findInternal(id);
        readable(net, accounts.current());
        return net;
    }

    public List<Net> list(boolean completed) {
        Account actor = accounts.current();
        return jdbc
                .query(NET_SELECT + "WHERE n.closed_at IS " + (completed ? "NOT " : "")
                        + "NULL ORDER BY n.started_at DESC", NET)
                .stream().filter(n -> !n.privateNet() || manages(n, actor)).toList();
    }

    public List<Net> archivePage(int offset, int limit) {
        Account actor = accounts.current();
        return jdbc.query(NET_SELECT + "WHERE n.closed_at IS NOT NULL AND (n.private_net=FALSE OR n.owner_id=? OR ?) ORDER BY n.closed_at DESC,n.id LIMIT ? OFFSET ?",
                NET, actor.id(), admin(actor), Math.clamp(limit, 1, 100), Math.max(offset, 0));
    }

    public List<Message> archiveMessagePage(String id, int offset, int limit) {
        Net n = get(id);
        if (n.endTime() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive not found");
        return jdbc.query("SELECT * FROM net_message WHERE net_id=? AND (? OR recipient<>'Net Control') ORDER BY received_at,id LIMIT ? OFFSET ?",
                MESSAGE, n.id(), manages(n, accounts.current()), Math.clamp(limit, 1, 200), Math.max(offset, 0));
    }

    private void active(Net n) {
        if (n.endTime() != null || !activeInternal(n.id()))
            throw conflict("This net is archived or its scheduled end has passed");
    }

    private void version(Long expected, long current) {
        if (expected == null || expected != current)
            throw conflict("Record changed; refresh and retry");
    }

    @Transactional
    public Net create(NetEdit edit) {
        Account actor = accounts.current();
        return createOwned(edit, actor.id(), actor.emailAddress(), Instant.now());
    }

    @Transactional
    public Net saveConfigured(String id, NetEdit edit, JsonNode values) {
        lock();
        ObjectNode merged = id.isBlank() ? values.deepCopy() : optionsInternal(id).deepCopy();
        values.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
        ObjectNode clean = NetOptions.validate(merged);
        if (!clean.has("lat") || !clean.has("lon")) throw bad("Net latitude and longitude are required");
        Net saved = id.isBlank() ? create(edit) : update(id, edit);
        options(saved.id(), clean, saved.version());
        if (id.isBlank()) startedInternal(saved.id());
        return findInternal(saved.id());
    }

    /** Called once after activation and its options have been persisted. */
    public void startedInternal(String id) {
        Net net = findInternal(id);
        netBeacon(net, true);
        lifecycleBulletin(net, true);
    }

    private void lifecycleBulletin(Net net, boolean started) {
        if (net.privateNet() || !option(net.id(), "announce")) return;
        events.publishEvent(new RadioDispatch.Send("NET:" + net.id(), null, null,
                List.of(router.message(net.callsign(), "BLNNET", "Net " + net.callsign()
                        + (started ? " started" : " stopped"), false)), true, null));
    }

    /**
     * Internal scheduler only: caller holds the net lock and supplies the persisted
     * owner.
     */
    @Transactional
    public Net createScheduledInternal(NetEdit edit, String owner, String creator, Instant activated) {
        return createOwned(edit, owner, creator, activated);
    }

    private Net createOwned(NetEdit edit, String owner, String creator, Instant activated) {
        lock();
        String call = callsign(edit.callsign());
        if (jdbc.queryForObject("SELECT COUNT(*) FROM domain_record WHERE kind='OBJECT' AND record_key=?",
                Integer.class, call) > 0)
            throw bad("Callsign belongs to an owned object");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM managed_net WHERE active_callsign=?", Integer.class, call) > 0)
            throw conflict("Net callsign is already active");
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO managed_net(id,callsign,active_callsign,name,description,voice_frequency,owner_id,creator_name,open,private_net,started_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, call, call, text(edit.name(), 120, true), text(edit.description(), 1000, false),
                text(edit.voiceFrequency(), 40, false),
                owner, creator, edit.open() == null || edit.open(), Boolean.TRUE.equals(edit.privateNet()),
                activated.atOffset(java.time.ZoneOffset.UTC));
        saveRoute(id, edit, true);
        saveExpected(id, edit.expectedParticipants());
        federate(id, new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetAnnounceReport(call,
                edit.name(), edit.description() == null ? "" : edit.description()));
        federate(id, new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetStartReport(call,
                activated.atZone(java.time.ZoneOffset.UTC)));
        return findInternal(id);
    }

    @Transactional
    public Net update(String id, NetEdit edit) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        version(edit.version(), n.version());
        if (edit.callsign() != null && !callsign(edit.callsign()).equals(n.callsign()))
            throw bad("Net callsign cannot change");
        jdbc.update(
                "UPDATE managed_net SET name=?,description=?,voice_frequency=?,open=?,private_net=?,version=version+1 WHERE id=?",
                text(edit.name(), 120, true), text(edit.description(), 1000, false),
                text(edit.voiceFrequency(), 40, false),
                edit.open() == null ? n.open() : edit.open(),
                edit.privateNet() == null ? n.privateNet() : edit.privateNet(), n.id());
        if (edit.communicationMode() != null)
            saveRoute(n.id(), edit, false);
        if (edit.expectedParticipants() != null)
            saveExpected(n.id(), edit.expectedParticipants());
        return findInternal(n.id());
    }

    private void saveRoute(String id, NetEdit edit, boolean creating) {
        var defaults = routing.load("DEFAULT");
        String mode = edit.communicationMode() == null ? defaults.mode() : edit.communicationMode();
        Set<String> selected = edit.communicationMode() == null ? defaults.instanceIds()
                : edit.communicationInstanceIds();
        routing.saveInternal("NET:" + id,
                new RoutingService.Route("NET:" + id,
                        creating ? -1 : (edit.routingVersion() == null ? -2 : edit.routingVersion()), mode,
                        selected == null ? Set.of() : selected));
    }

    private void saveExpected(String id, Set<String> selected) {
        if (selected == null)
            return;
        if (selected.size() > 1000)
            throw bad("At most 1000 expected callsigns");
        Set<String> normalized = new TreeSet<>();
        for (String call : selected)
            normalized.add(callsign(call).split("-")[0]);
        jdbc.update("DELETE FROM net_expected WHERE net_id=?", id);
        for (String call : normalized)
            jdbc.update("INSERT INTO net_expected VALUES(?,?)", id, call);
    }

    public Set<String> expected(String id) {
        Net n = get(id);
        return expectedInternal(n.id());
    }

    private Set<String> expectedInternal(String id) {
        return new TreeSet<>(jdbc.queryForList("SELECT callsign FROM net_expected WHERE net_id=?", String.class, id));
    }

    @Transactional
    public Net close(String id, Long expectedVersion) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        if (n.endTime() != null)
            return n;
        version(expectedVersion, n.version());
        closeScheduledInternal(n.id(), Instant.now());
        return findInternal(n.id());
    }

    /**
     * Idempotent internal finalization; scheduled recovery supplies the original
     * planned end.
     */
    @Transactional
    public void closeScheduledInternal(String id, Instant end) {
        lock();
        var time = end.atOffset(java.time.ZoneOffset.UTC);
        if (jdbc.update(
                "UPDATE managed_net SET closed_at=?,active_callsign=NULL,version=version+1 WHERE id=? AND closed_at IS NULL",
                time, id) > 0) {
            jdbc.update("UPDATE net_participant SET ended_at=?,version=version+1 WHERE net_id=? AND ended_at IS NULL",
                    time, id);
            federate(id, new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetSecureReport(
                    findInternal(id).callsign(), end.atZone(java.time.ZoneOffset.UTC)));
            netBeacon(findInternal(id), false);
            lifecycleBulletin(findInternal(id), false);
        }
    }

    public List<Participant> participants(String id, boolean history) {
        Net n = get(id);
        return participantsInternal(n.id(), history || n.endTime() != null);
    }

    private List<Participant> participantsInternal(String id, boolean history) {
        return jdbc.query("SELECT * FROM net_participant WHERE net_id=?" + (history ? "" : " AND ended_at IS NULL")
                + " ORDER BY callsign", PARTICIPANT, id);
    }

    @Transactional
    public List<Participant> checkIn(String id, String call) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        call = callsign(call);
        boolean alreadyJoined = participating(n, call);
        join(n, call);
        String welcome = optionsInternal(n.id()).path("checkinMessage").asText("");
        if (!alreadyJoined && !welcome.isBlank()) reply(n, call, welcome, null);
        return participantsInternal(n.id(), false);
    }

    private void join(Net n, String call) {
        if (!n.open() && !expectedInternal(n.id()).contains(call.split("-")[0]))
            throw bad("This net is closed. Only expected or invited callsigns may check in.");
        List<Participant> previous = jdbc.query("SELECT * FROM net_participant WHERE net_id=? AND callsign=?",
                PARTICIPANT, n.id(), call);
        if (previous.isEmpty())
            jdbc.update("INSERT INTO net_participant(net_id,callsign,started_at,last_heard) VALUES(?,?,?,?)", n.id(),
                    call, now(), now());
        else if (previous.getFirst().endTime() != null)
            jdbc.update(
                    "UPDATE net_participant SET ended_at=NULL,status_color='GREEN',last_heard=?,version=version+1 WHERE net_id=? AND callsign=?",
                    now(), n.id(), call);
        else
            return;
        jdbc.query("SELECT * FROM station_position WHERE callsign=? AND heard_at>?", r -> {
            jdbc.update("UPDATE net_participant SET lat=?,lon=?,uncertainty_meters=? WHERE net_id=? AND callsign=?", r.getDouble("lat"),
                    r.getDouble("lon"), r.getDouble("uncertainty_meters"), n.id(), call);
        }, call, now().minusSeconds(positionLifetime));
        history(n.id(), call, "Net", "Checked in", "EVENT", "RECORDED", null);
        notifyMembership(n, call, true);
        touch(n.id());
    }

    @Transactional
    public List<Participant> checkOut(String id, String call) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        leave(n, callsign(call));
        return participantsInternal(n.id(), false);
    }

    private boolean participating(Net n, String call) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM net_participant WHERE net_id=? AND callsign=? AND ended_at IS NULL",
                Integer.class, n.id(), call) > 0;
    }

    private void leave(Net n, String call) {
        if (jdbc.update(
                "UPDATE net_participant SET ended_at=?,status_color='WHITE',version=version+1 WHERE net_id=? AND callsign=? AND ended_at IS NULL",
                now(), n.id(), call) > 0) {
            history(n.id(), call, "Net", "Checked out", "EVENT", "RECORDED", null);
            touch(n.id());
            notifyMembership(n, call, false);
        }
    }

    @Transactional
    public Participant participant(String id, String call, ParticipantEdit edit) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        call = callsign(call);
        List<Participant> matches = jdbc.query(
                "SELECT * FROM net_participant WHERE net_id=? AND callsign=? AND ended_at IS NULL", PARTICIPANT, n.id(),
                call);
        if (matches.isEmpty())
            throw bad("Station is not checked in");
        version(edit.version(), matches.getFirst().version());
        updateParticipant(n, call, edit);
        return jdbc.queryForObject("SELECT * FROM net_participant WHERE net_id=? AND callsign=?", PARTICIPANT, n.id(),
                call);
    }

    private void updateParticipant(Net n, String call, ParticipantEdit edit) {
        jdbc.update(
                "UPDATE net_participant SET status=?,voice_frequency=?,tactical_callsign=?,version=version+1 WHERE net_id=? AND callsign=?",
                text(edit.status(), 200, false), text(edit.voiceFrequency(), 40, false),
                text(edit.tacticalCallsign(), 30, false), n.id(), call);
        history(n.id(), call, "Net", "Status: " + text(edit.status(), 200, false), "EVENT", "RECORDED", null);
        touch(n.id());
    }

    public List<Message> messages(String id) {
        Net n = get(id);
        return visibleMessages(messagesInternal(n.id(), false), manages(n, accounts.current()));
    }

    private List<Message> visibleMessages(List<Message> messages, boolean manager) {
        return manager ? messages : messages.stream().filter(m -> !m.recipient().equals("Net Control")).toList();
    }

    private List<Message> messagesInternal(String id, boolean bounded) {
        if (!bounded)
            return jdbc.query("SELECT * FROM net_message WHERE net_id=? ORDER BY received_at,id", MESSAGE, id);
        var messages = jdbc.query(
                "SELECT * FROM net_message WHERE net_id=? ORDER BY received_at DESC,id DESC LIMIT 200", MESSAGE, id);
        return messages.reversed();
    }

    private String history(String netId, String from, String recipient, String content, String direction,
            String delivery, String ack) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO net_message VALUES(?,?,?,?,?,?,?,?,?)", id, netId, from, recipient, content, now(),
                direction, delivery, ack);
        if (direction.equals("IN") && recipient.equals("Net"))
            federate(netId, new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetMessageReport(
                    findInternal(netId).callsign(), true, from + ": " + content));
        return id;
    }

    @Transactional
    public List<Message> send(String id, MessageRequest request) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        String content = text(request.message(), 67, true);
        boolean ack = request.acknowledgementRequested() == null || request.acknowledgementRequested();
        String recipient = request.recipient();
        if (recipient == null || recipient.equals("Net")) {
            List<Participant> targets = participantsInternal(n.id(), false);
            if (targets.isEmpty())
                throw bad("No checked-in participants");
            for (Participant target : targets)
                outgoing(n, target.callsign(), content, ack, null);
        } else {
            String call = callsign(recipient);
            if (!participating(n, call))
                throw bad("Recipient is not checked in");
            outgoing(n, call, content, ack, null);
        }
        return messagesInternal(n.id(), true);
    }

    private void outgoing(Net n, String to, String content, boolean ack, String receiving) {
        Packet packet = router.message(n.callsign(), to, content, ack);
        String information = packet.information();
        String number = ack ? information.substring(information.lastIndexOf('{') + 1) : null;
        String id = history(n.id(), n.callsign(), to, content, "OUT", "PENDING", number);
        touch(n.id());
        events.publishEvent(new SendRequested(n.id(), id, n.callsign(), to, content, number, receiving));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(String id) {
        Account actor = accounts.current();
        Net n = findInternal(id);
        readable(n, actor);
        return snapshotInternal(n, manages(n, actor));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshotForPrincipal(String id, LocalPrincipal principal) {
        if (!accounts.isCurrent(principal))
            throw new AccessDeniedException("Session revoked");
        Net n = findInternal(id);
        boolean manager = n.ownerId().equals(principal.getUsername())
                || principal.getAuthorities().stream().anyMatch(a -> !a.getAuthority().equals("ROLE_USER"));
        if (n.privateNet() && !manager)
            throw new AccessDeniedException("Private net");
        return snapshotInternal(n, manager);
    }

    private Snapshot snapshotInternal(Net n, boolean manager) {
        var endings = jdbc.query("SELECT planned_end FROM net_occurrence WHERE net_id=? AND state='ACTIVE' ORDER BY planned_end",
                (r, row) -> instant(r.getObject(1, OffsetDateTime.class)), n.id());
        Instant plannedEnd = n.endTime() == null && !endings.isEmpty() ? endings.getFirst() : null;
        return new Snapshot(n, participantsInternal(n.id(), true),
                visibleMessages(messagesInternal(n.id(), true), manager),
                routing.load("NET:" + n.id()), expectedInternal(n.id()), map(), manager, questionsInternal(n.id()),
                questionsInternal(n.id()).stream().flatMap(q -> answersInternal(q.id()).stream()).toList(), plannedEnd);
    }

    public MapSettings map() {
        return new MapSettings(
                jdbc.queryForObject("SELECT setting_value FROM application_settings WHERE setting_key='map.tileUrl'",
                        String.class),
                jdbc.queryForObject(
                        "SELECT setting_value FROM application_settings WHERE setting_key='map.attribution'",
                        String.class));
    }

    @Transactional
    public void map(MapSettings settings) {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator required");
        String url = text(settings.tileUrl(), 500, true);
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"));
        } catch (IllegalArgumentException invalid) {
            throw bad("Invalid tile URL");
        }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || !url.contains("{z}") || !url.contains("{x}") || !url.contains("{y}"))
            throw bad("Use an HTTP(S) tile URL containing {z}, {x}, and {y}");
        jdbc.update("UPDATE application_settings SET setting_value=? WHERE setting_key='map.tileUrl'", url);
        jdbc.update("UPDATE application_settings SET setting_value=? WHERE setting_key='map.attribution'",
                text(settings.attribution(), 300, true));
        jdbc.update("UPDATE managed_net SET version=version+1");
    }

    @EventListener
    @Transactional
    public void repeated(PacketHub.RepeatedMessage event) {
        receive(new PacketHub.UniquePacket(event.received()));
    }

    @EventListener
    @Transactional
    public void receive(PacketHub.UniquePacket event) {
        var received = event.received();
        Packet packet = received.packet();
        String call;
        try {
            call = callsign(packet.source());
        } catch (ResponseStatusException ignored) {
            return;
        }
        if (radioPolicy.ignored(call))
            return;
        lock();
        var position = com.kc1vmz.netcentral.operations.PacketDecoder.position(packet);
        if (position != null) {
            jdbc.update("MERGE INTO station_position (callsign,lat,lon,heard_at,uncertainty_meters) KEY(callsign) VALUES(?,?,?,?,?)", call, position.latitude(),
                    position.longitude(), now(), position.uncertaintyMeters());
            var ids = jdbc.queryForList(
                    "SELECT p.net_id FROM net_participant p JOIN managed_net n ON n.id=p.net_id WHERE p.callsign=? AND p.ended_at IS NULL AND n.closed_at IS NULL",
                    String.class, call);
            for (String id : ids) {
                jdbc.update(
                        "UPDATE net_participant SET lat=?,lon=?,last_heard=?,uncertainty_meters=?,version=version+1 WHERE net_id=? AND callsign=?",
                        position.latitude(), position.longitude(), now(), position.uncertaintyMeters(), id, call);
                touch(id);
            }
        }
        String value = packet.information();
        if (value.length() < 11 || value.charAt(0) != ':' || value.charAt(10) != ':')
            return;
        String target = value.substring(1, 10).strip().toUpperCase(Locale.ROOT);
        if (target.equals(call)) return; // Ignore our own federation announcements heard again.
        List<Net> nets = jdbc.query(NET_SELECT + "WHERE n.active_callsign=?", NET, target);
        if (nets.isEmpty())
            return;
        Net n = nets.getFirst();
        if (!activeInternal(n.id()))
            return;
        if (!routing.permits("NET:" + n.id(), received.instanceId()))
            return;
        if (!radioPolicy.permits(n.callsign(), call, true))
            return;
        var parsed = AprsMessageProtocol.parse(value.substring(11));
        String body = parsed.body();
        var control = AprsMessageProtocol.control(body);
        if (parsed.replyAck() != null && !parsed.replyAck().isEmpty()) {
            int changed = jdbc.update("UPDATE net_message SET delivery='ACKNOWLEDGED' WHERE net_id=? AND recipient=? AND ack_number=? AND direction='OUT' AND delivery IN ('PENDING','SENT')",
                    n.id(), call, parsed.replyAck());
            if (changed > 0) touch(n.id());
        }
        if (control != null) {
            int changed = jdbc.update(
                    "UPDATE net_message SET delivery=? WHERE net_id=? AND recipient=? AND ack_number=? AND direction='OUT'",
                    control.kind().equalsIgnoreCase("ack") ? "ACKNOWLEDGED" : "REJECTED", n.id(), call, control.number());
            if (changed > 0)
                touch(n.id());
            return;
        }
        String number = parsed.number();
        String command = body.strip();
        if (command.isEmpty() || command.length() > 67)
            return;
        if (!command.matches("\\?[A-Za-z0-9]+") && !n.open() && !expectedInternal(n.id()).contains(call.split("-")[0])) {
            if (command.toUpperCase(java.util.Locale.ROOT).matches(".*\\bCI\\b.*")) {
                if (number != null) outgoing(n, call, "rej" + number, false, received.instanceId());
                reply(n, call, "Check-in denied: closed net; ask net control for an invitation.", received.instanceId());
            }
            return;
        }
        if (number != null) {
            if (duplicate(n.id(), call + "|" + number + "|" + command)) {
                outgoing(n, call, "ack" + number, false, received.instanceId());
                return;
            }
            outgoing(n, call, "ack" + number, false, received.instanceId());
        }
        executeCommands(n, call, command, received.instanceId());
    }

    private void reply(Net n, String to, String value, String receiving) {
        String clean = value.replaceAll("[^\\x20-\\x7E]", "?").replaceAll("[{}|~]", " ");
        for (int i = 0; i < clean.length() && i < 3350; i += 67)
            outgoing(n, to, clean.substring(i, Math.min(i + 67, clean.length())), false, receiving);
    }

    private void executeCommands(Net n, String call, String command, String receiving) {
        var tokens = Pattern.compile("\\S+").matcher(command);
        List<String> tokenValues = new ArrayList<>();
        List<Integer> tokenEnds = new ArrayList<>();
        while (tokens.find()) { tokenValues.add(tokens.group()); tokenEnds.add(tokens.end()); }
        String[] words = tokenValues.toArray(String[]::new);
        for (int i = 0; i < words.length; i++) {
            String op = words[i].toUpperCase(Locale.ROOT);
            String rest = command.substring(tokenEnds.get(i)).replaceFirst("^\\s", "");
            if (op.equals("CI")) {
                join(n, call);
                reply(n, call, "You checked into net " + n.callsign() + " - send H for help", receiving);
                String welcome = optionsInternal(n.id()).path("checkinMessage").asText("");
                if (!welcome.isBlank())
                    reply(n, call, welcome, receiving);
                continue;
            }
            if (op.equals("H")) {
                reply(n, call, "CI CO STG STY STR T M MC R L P I S O F X E B Y Q QS QL QR A INV NCI H", receiving);
                continue;
            }
            if (op.startsWith("?")) {
                switch (op) {
                    case "?CMD" -> reply(n, call, "CMD:CMD,INFO,APRSP,NCOT,NCNTI,NCNTP", receiving);
                    case "?NCOT" -> reply(n, call, "NCOT:NET", receiving);
                    case "?INFO", "?NCNTI" -> {
                        if (op.equals("?INFO")) reply(n, call, "CMD:CMD,INFO,APRSP,NCOT,NCNTI,NCNTP", receiving);
                        // Original Net Central uses NCGRI for its net information response.
                        reply(n, call, "NCGRI:" + n.callsign() + "," + n.description(), receiving);
                    }
                    case "?NCNTP" -> {
                        var calls = participantsInternal(n.id(), false).stream().map(p -> p.callsign() + " (" + ("WHITE".equals(p.statusColor()) ? 'U' : p.statusColor().charAt(0)) + ")").toList();
                        for (int offset = 0; offset < calls.size(); offset += 4)
                            reply(n, call, "NCNTP: " + String.join(" ", calls.subList(offset, Math.min(offset + 4, calls.size()))), receiving);
                    }
                    case "?APRSP", "?APRSO" -> netBeacon(n, true);
                    default -> reply(n, call, "Unknown query; send ?CMD", receiving);
                }
                continue;
            }
            if (op.equals("I")) {
                reply(n, call, n.name() + " /vf:" + n.voiceFrequency() + " /" + n.description(), receiving);
                continue;
            }
            if (!participating(n, call)) {
                reply(n, call, "Check in with CI first", receiving);
                return;
            }
            jdbc.update("UPDATE net_participant SET last_heard=?,version=version+1 WHERE net_id=? AND callsign=?",
                    now(), n.id(), call);
            touch(n.id());
            try {
                switch (op) {
                    case "STG", "STY", "STR" -> {
                        String color = op.equals("STG") ? "GREEN" : op.equals("STY") ? "YELLOW" : "RED";
                        jdbc.update("UPDATE net_participant SET status_color=?,version=version+1 WHERE net_id=? AND callsign=? AND ended_at IS NULL", color, n.id(), call);
                        history(n.id(), call, "Net", "Status color: " + color, "EVENT", "RECORDED", null);
                        reply(n, call, "Status color changed to " + color, receiving);
                    }
                    case "CO" -> { 
                        leave(n, call);
                        reply(n, call, "You checked out of net " + n.callsign(), receiving);
                    }
                    case "NCI" -> {
                        jdbc.update(
                                "UPDATE net_participant SET notify_changes=NOT notify_changes WHERE net_id=? AND callsign=?",
                                n.id(), call);
                        reply(n, call, "Check-in notifications toggled", receiving);
                    }
                    case "S" -> {
                        jdbc.update("UPDATE net_participant SET status=? WHERE net_id=? AND callsign=?",
                                text(rest, 200, true), n.id(), call);
                        history(n.id(), call, "Net", rest, "STATUS", "RECORDED", null);
                        reply(n, call, "Status updated", receiving);
                        return;
                    }
                    case "M", "MC" -> {
                        text(rest, 67, true);
                        history(n.id(), call, op.equals("MC") ? "Net Control" : "Net", rest, "IN", "RECEIVED", null);
                        if (op.equals("M"))
                            for (var p : participantsInternal(n.id(), false))
                                if (!p.callsign().equals(call))
                                    reply(n, p.callsign(), call + ": " + rest, null);
                        return;
                    }
                    case "L" -> {
                        var labels = participantsInternal(n.id(), false).stream()
                                .map(p -> p.callsign() + " (" + ("WHITE".equals(p.statusColor()) ? 'U' : p.statusColor().charAt(0)) + ")").toList();
                        for (int offset = 0; offset < labels.size(); offset += 4)
                            reply(n, call, String.join(", ", labels.subList(offset, Math.min(offset + 4, labels.size()))), receiving);
                    }
                    case "QL" -> {
                        for (var q : questionsInternal(n.id()))
                            if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM net_answer WHERE question_id=? AND callsign=?", Integer.class,
                                    q.id(), call) == 0)
                                reply(n, call, q.wireText(), receiving);
                    }
                    case "QS" -> {
                        var strip = askInternal(n, call, rest, true);
                        reply(n, call, "Strip " + strip.getIdentifier() + " issued", receiving);
                        return;
                    }
                    case "Q" -> {
                        askInternal(n, call, rest);
                        reply(n, call, "Thank you for your question", receiving);
                        return;
                    }
                    case "A" -> {
                        String[] answer = rest.split("\\s", 2);
                        if (answer.length != 2)
                            throw bad("Use A number answer");
                        answerInternal(n, answer[0], call, answer[1]);
                        reply(n, call, "Thank you for your answer", receiving);
                        return;
                    }
                    case "INV" -> {
                        if (!option(n.id(), "participantInviteAllowed"))
                            throw bad("Invitations are disabled");
                        inviteInternal(n, java.util.Arrays.asList(rest.split("[,\\s]+")));
                        return;
                    }
                    case "R" -> {
                        int count = i + 1 < words.length ? Integer.parseInt(words[++i]) : 10;
                        count = Math.clamp(count, 1, 20);
                        var history = messagesInternal(n.id(), false).stream()
                                .filter(m -> m.direction().equals("IN") && m.recipient().equals("Net")).toList();
                        for (var m : history.subList(Math.max(0, history.size() - count), history.size()))
                            reply(n, call, m.callsignFrom() + ": " + m.message(), receiving);
                    }
                    case "QR" -> {
                        if (++i >= words.length)
                            throw bad("Use QR number");
                        var q = questionInternal(n.id(), words[i]);
                        reply(n, call, q.wireText(), receiving);
                        for (var a : answersInternal(q.id()))
                            reply(n, call, q.strip() ? q.getIdentifier() + " " + a.callsign() + " " + a.answer() : a.callsign() + ": " + a.answer(), receiving);
                    }
                    case "P", "O" -> {
                        if (++i >= words.length)
                            throw bad("Callsign required");
                        String target = callsign(words[i]);
                        if (op.equals("P")) {
                            if (!participating(n, target))
                                throw bad("Station is not checked in");
                            reply(n, target, "You were pinged by " + call, null);
                        } else {
                            var rows = jdbc.queryForList(
                                    "SELECT payload FROM domain_record WHERE kind='STATION' AND record_key=?",
                                    String.class, target);
                            String status = "Unknown";
                            if (!rows.isEmpty())
                                try {
                                    status = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getFirst())
                                            .path("status").asText("Heard");
                                } catch (java.io.IOException invalid) {
                                    throw new IllegalStateException(invalid);
                                }
                            reply(n, call, target + ": " + status, receiving);
                        }
                    }
                    case "T", "F", "X", "E", "B", "Y" -> {
                        if (++i >= words.length)
                            throw bad("Value required");
                        String value = words[i];
                        String column = switch (op) {
                            case "T" -> "tactical_callsign";
                            case "F" -> "voice_frequency";
                            case "X" -> "transmit_power";
                            case "E" -> "electrical_power";
                            case "B" -> "backup_power";
                            default -> "radio_style";
                        };
                        String opResponse = switch (op) {
                            case "T" -> "Tactical callsign";
                            case "F" -> "Voice frequency";
                            case "X" -> "Transmit Power";
                            case "E" -> "Primary electrical power";
                            case "B" -> "Backup electrical power";
                            default -> "Radio style";
                        };
                        equipmentValue(op, value);
                        jdbc.update("UPDATE net_participant SET " + column + "=? WHERE net_id=? AND callsign=?", value,
                                n.id(), call);
                        reply(n, call, opResponse + " updated", receiving);
                    }
                    default -> {
                        reply(n, call, "Unsupported command; send H for help", receiving);
                        return;
                    }
                }
            } catch (IllegalArgumentException | ResponseStatusException invalid) {
                reply(n, call, "Invalid command value; send H for help", receiving);
                return;
            }
        }
    }

    private void equipmentValue(String op, String value) {
        text(value, op.equals("T") ? 30 : 40, true);
        if (op.equals("X")) {
            double power = Double.parseDouble(value);
            if (!Double.isFinite(power) || power < 0 || power > 100000)
                throw bad("Invalid transmit power");
        }
        if ((op.equals("E") || op.equals("B"))
                && !Set.of("BATTERY", "COMMERCIAL", "GENERATOR", "NONE", "SOLAR", "UNKNOWN")
                        .contains(value.toUpperCase(Locale.ROOT)))
            throw bad("Invalid power type");
        if (op.equals("Y") && !Set.of("APPLIANCE", "BASE", "HANDHELD", "INTERNET", "MOBILE", "OTHER", "UNKNOWN")
                .contains(value.toUpperCase(Locale.ROOT)))
            throw bad("Invalid radio style");
    }

    public List<NetQuestionModels.Question> questions(String id) {
        return questionsInternal(get(id).id());
    }

    private List<NetQuestionModels.Question> questionsInternal(String id) {
        return jdbc.query("SELECT * FROM net_question WHERE net_id=? ORDER BY number",
                (r, k) -> new NetQuestionModels.Question(r.getString("id"),
                        r.getBoolean("strip") ? r.getInt("strip_number") : r.getInt("number"), r.getString("callsign"), r.getString("question"),
                        instant(r.getObject("asked_at", OffsetDateTime.class)), r.getBoolean("strip")),
                id);
    }

    private NetQuestionModels.Question questionInternal(String id, String question) {
        return questionsInternal(id).stream()
                .filter(q -> q.id().equals(question) || q.getIdentifier().equalsIgnoreCase(question)).findFirst()
                .orElseThrow(() -> bad("Question not found"));
    }

    public List<NetQuestionModels.Answer> answers(String id, String question) {
        return answersInternal(questionInternal(get(id).id(), question).id());
    }

    private List<NetQuestionModels.Answer> answersInternal(String id) {
        return jdbc.query("SELECT * FROM net_answer WHERE question_id=? ORDER BY callsign",
                (r, k) -> new NetQuestionModels.Answer(r.getString("question_id"),
                        r.getString("callsign"), r.getString("answer"),
                        instant(r.getObject("answered_at", OffsetDateTime.class))),
                id);
    }

    @Transactional
    public List<NetQuestionModels.Question> ask(String id, String question) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        askInternal(n, n.callsign(), question);
        return questionsInternal(n.id());
    }

    @Transactional
    public List<NetQuestionModels.Question> askStrip(String id, String question) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        askInternal(n, n.callsign(), question, true);
        return questionsInternal(n.id());
    }

    private void askInternal(Net n, String call, String question) {
        askInternal(n, call, question, false);
    }

    private NetQuestionModels.Question askInternal(Net n, String call, String question, boolean strip) {
        if (strip) StripFormat.wire(question);
        question = text(question, 500, true);
        int number = jdbc.queryForObject("SELECT COALESCE(MAX(number),0)+1 FROM net_question WHERE net_id=?",
                Integer.class, n.id());
        int stripNumber = strip ? jdbc.queryForObject("SELECT COALESCE(MAX(strip_number),0)+1 FROM net_question WHERE net_id=?", Integer.class, n.id()) : 0;
        var issued = new NetQuestionModels.Question(UUID.randomUUID().toString(), strip ? stripNumber : number,
                call, question, now().toInstant(), strip);
        if (strip) {
            StripFormat.definition(question);
            StripFormat.wire(issued.wireText());
        }
        jdbc.update("INSERT INTO net_question(id,net_id,number,callsign,question,asked_at,strip,strip_number) VALUES(?,?,?,?,?,?,?,?)",
                issued.id(), n.id(), number, call, question, now(), strip, strip ? stripNumber : null);
        touch(n.id());
        federate(n.id(), new APRSNetCentralNetQuestionReport(
                n.callsign(), issued.getIdentifier(), question));
        for (var participant : participantsInternal(n.id(), false))
            reply(n, participant.callsign(), issued.wireText(), null);
        return issued;
    }

    @Transactional
    public List<NetQuestionModels.Answer> answer(String id, String question,
            String call, String answer) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        answerInternal(n, question, callsign(call), answer);
        return answers(id, question);
    }

    private void answerInternal(Net n, String question, String call, String answer) {
        var q = questionInternal(n.id(), question);
        if (!participating(n, call))
            throw bad("Station is not checked in");
        if (q.strip()) answer = StripFormat.answer(q.question(), q.getIdentifier(), call, answer);
        jdbc.update("MERGE INTO net_answer KEY(question_id,callsign) VALUES(?,?,?,?)", q.id(), call,
                q.strip() ? answer : text(answer, 500, true), now());
        touch(n.id());
        federate(n.id(), new APRSNetCentralNetQuestionAnswerReport(
                n.callsign(), call, q.getIdentifier(), answer));
        history(n.id(), call, "Net",
                "Answer " + q.getIdentifier() + ": " + answer.substring(0, Math.min(480, answer.length())), "EVENT",
                "RECORDED", null);
    }

    @Transactional
    public Set<String> expected(String id, Set<String> values, long version) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        version(version, n.version());
        saveExpected(n.id(), values);
        touch(n.id());
        return expectedInternal(n.id());
    }

    @Transactional
    public void invite(String id, Set<String> calls) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        if (calls.isEmpty() || calls.size() > 100)
            throw bad("Use 1 to 100 invitation callsigns");
        Set<String> unavailable = new TreeSet<>();
        for (String value : calls)
            for (String target : invitationTargets(callsign(value)))
                if (!router.canDirect("NET:" + id, target))
                    unavailable.add(target);
        if (!unavailable.isEmpty())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No usable communication route for: " + String.join(", ", unavailable)
                    + ". No invitations were sent. In Communications, connect an active instance with transmission enabled, "
                    + "and include it in this net's communication selection. The destination callsign, including its SSID, "
                    + "must be heard on that instance within the last 30 minutes and since Net Central last restarted. "
                    + "Have the station transmit an APRS packet, then retry the invitation.");
        inviteInternal(n, calls);
    }

    private List<String> invitationTargets(String call) {
        var heard = jdbc.queryForList(
                "SELECT record_key FROM domain_record WHERE kind='STATION' AND (record_key=? OR record_key LIKE ?)",
                String.class, call, call + "-%");
        return heard.isEmpty() ? List.of(call) : heard;
    }

    private void inviteInternal(Net n, java.util.Collection<String> calls) {
        if (calls.size() > 100)
            throw bad("At most 100 invitations");
        var expected = expectedInternal(n.id());
        for (String value : calls) {
            String call = callsign(value);
            expected.add(call.split("-")[0]);
            for (String target : invitationTargets(call))
                reply(n, target, "You have been invited to net " + n.callsign() + " - send CI", null);
        }
        saveExpected(n.id(), expected);
        touch(n.id());
    }

    public java.util.Map<String, Object> equipment(String id, String call) {
        Net n = get(id);
        var rows = jdbc.queryForList(
                "SELECT transmit_power, electrical_power, backup_power, radio_style, notify_changes FROM net_participant WHERE net_id=? AND callsign=?",
                n.id(), callsign(call));
        if (rows.isEmpty())
            throw bad("Participant not found");
        return rows.getFirst();
    }

    @Transactional
    public void equipment(String id, String call, java.util.Map<String, String> values) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        call = callsign(call);
        if (!participating(n, call))
            throw bad("Station is not checked in");
        for (var entry : values.entrySet()) {
            String op = entry.getKey(), column = switch (op) {
                case "X" -> "transmit_power";
                case "E" -> "electrical_power";
                case "B" -> "backup_power";
                case "Y" -> "radio_style";
                default -> throw bad("Use X, E, B, Y equipment keys");
            };
            equipmentValue(op, entry.getValue());
            jdbc.update("UPDATE net_participant SET " + column + "=?,version=version+1 WHERE net_id=? AND callsign=?",
                    entry.getValue(), n.id(), call);
        }
        touch(n.id());
    }

    public com.fasterxml.jackson.databind.JsonNode options(String id) {
        Net n = get(id);
        return optionsInternal(n.id());
    }

    private com.fasterxml.jackson.databind.JsonNode optionsInternal(String id) {
        var rows = jdbc.queryForList("SELECT payload FROM domain_record WHERE kind='NET_OPTIONS' AND record_key=?",
                String.class, id);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.isEmpty() ? "{}" : rows.getFirst());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean option(String id, String key) {
        return optionsInternal(id).path(key).asBoolean(false);
    }

    @Transactional
    public void options(String id, com.fasterxml.jackson.databind.JsonNode values, long expectedVersion) {
        lock();
        Net n = findInternal(id);
        manageable(n);
        active(n);
        version(expectedVersion, n.version());
        var clean = com.kc1vmz.netcentral.operations.NetOptions.validate(values);
        jdbc.update("MERGE INTO domain_record KEY(kind,record_key) VALUES('NET_OPTIONS',?,?,?,?,?)", n.id(),
                n.ownerId(), clean.toString(), n.version() + 1, now());
        touch(n.id());
    }

    @Transactional
    public void reminders() {
        lock();
        for (Net n : jdbc.query(NET_SELECT + "WHERE n.closed_at IS NULL", NET)) {
            if (!activeInternal(n.id()))
                continue;
            if (option(n.id(), "checkinReminder"))
                for (var participant : participantsInternal(n.id(), false))
                    reply(n, participant.callsign(), "Please check out of net " + n.callsign() + " with CO when finished", null);
            if (option(n.id(), "questionReminder"))
                for (var q : questionsInternal(n.id()))
                    for (var p : participantsInternal(n.id(), false))
                        if (jdbc.queryForObject("SELECT COUNT(*) FROM net_answer WHERE question_id=? AND callsign=?",
                                Integer.class, q.id(), p.callsign()) == 0)
                            reply(n, p.callsign(), q.wireText(), null);
        }
    }

    private boolean duplicate(String net, String key) {
        String digest;
        try {
            digest = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        jdbc.update("DELETE FROM net_radio_dedup WHERE received_at<?", now().minusMinutes(15));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM net_radio_dedup WHERE net_id=? AND fingerprint=?", Integer.class,
                net, digest) > 0)
            return true;
        jdbc.update("INSERT INTO net_radio_dedup VALUES(?,?,?)", net, digest, now());
        return false;
    }

    private void notifyMembership(Net n, String call, boolean joined) {
        federate(n.id(), new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetCheckInOutReport(
                n.callsign(), call, joined));
        for (String target : jdbc.queryForList(
                "SELECT callsign FROM net_participant WHERE net_id=? AND ended_at IS NULL AND notify_changes=TRUE AND callsign<>?",
                String.class, n.id(), call))
            reply(n, target, call + (joined ? " checked in" : " checked out"), null);
    }

    private void federate(String id, com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralReport report) {
        if (findInternal(id).privateNet() || !Boolean.parseBoolean(jdbc.queryForObject(
                "SELECT setting_value FROM application_settings WHERE setting_key='operations.federationEnabled'",
                String.class)))
            return;
        federation.federate("NET:" + id, report, null);
    }

    @Transactional
    public void announcements() {
        lock();
        for (Net n : jdbc.query(NET_SELECT + "WHERE n.closed_at IS NULL AND n.private_net=FALSE", NET)) {
            if (!activeInternal(n.id()))
                continue;
            netBeacon(n, true);
            federate(n.id(), new com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralNetAnnounceReport(
                    n.callsign(), n.name(), n.description()));

        }
    }

    private void netBeacon(Net n, boolean alive) {
        var options = optionsInternal(n.id());
        if (n.privateNet() || !options.has("lat") || !options.has("lon"))
            return;
        String stamp = java.time.format.DateTimeFormatter.ofPattern("ddHHmm'z'").withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        String info = ";" + String.format("%-9s", n.callsign()) + (alive ? "*" : "_") + stamp
                + ResourceAccessor.coordinate(options.path("lat").asDouble(), true) + options.path("symbolTableId").asText("/")
                + ResourceAccessor.coordinate(options.path("lon").asDouble(), false) + options.path("symbolTableCode").asText("n")
                + n.name().replaceAll("[^\\x20-\\x7E]", "?");
        events.publishEvent(new com.kc1vmz.netcentral.operations.RadioDispatch.Send("NET:" + n.id(), null, null,
                List.of(new Packet(n.callsign(), "APANC1", List.of(), info)), true, null));
    }

    @Transactional
    public void recover() {
        lock();
        List<String> ids = jdbc.queryForList("SELECT DISTINCT net_id FROM net_message WHERE delivery='PENDING'",
                String.class);
        jdbc.update("UPDATE net_message SET delivery='DISCARDED' WHERE delivery='PENDING'");
        ids.forEach(this::touch);
    }

    @Transactional
    public void expirePositions() {
        lock();
        List<String> ids = jdbc.queryForList(
                "SELECT DISTINCT p.net_id FROM net_participant p JOIN managed_net n ON p.net_id=n.id WHERE n.closed_at IS NULL AND p.ended_at IS NULL AND p.lat IS NOT NULL AND p.callsign IN (SELECT callsign FROM station_position WHERE heard_at<?)",
                String.class, now().minusSeconds(positionLifetime));
        for (String id : ids) {
            jdbc.update(
                    "UPDATE net_participant SET lat=NULL,lon=NULL,version=version+1 WHERE net_id=? AND ended_at IS NULL AND callsign IN (SELECT callsign FROM station_position WHERE heard_at<?)",
                    id, now().minusSeconds(positionLifetime));
            touch(id);
        }
        jdbc.update("DELETE FROM station_position WHERE heard_at<?", now().minusSeconds(positionLifetime));
        jdbc.update("DELETE FROM net_radio_dedup WHERE received_at<?", now().minusMinutes(15));
    }

    public boolean activeInternal(String id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM managed_net n WHERE n.id=? AND n.closed_at IS NULL AND NOT EXISTS (SELECT 1 FROM net_occurrence o WHERE o.net_id=n.id AND o.planned_end<=?)",
                Integer.class, id, now()) == 1;
    }

    public static String callsign(String value) {
        String call = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!call.matches("[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?"))
            throw bad("Use a valid AX.25 callsign (up to six characters and SSID 0–15)");
        return call;
    }

    private static String text(String value, int max, boolean required) {
        String result = value == null ? "" : value.strip();
        if ((required && result.isEmpty()) || result.length() > max || result.chars().anyMatch(Character::isISOControl))
            throw bad("Invalid text length or characters");
        return result;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
