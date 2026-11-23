package com.kc1vmz.netcentral.accessor;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.kc1vmz.netcentral.account.Account;
import com.kc1vmz.netcentral.communication.RoutingService;
import com.kc1vmz.netcentral.net.ScheduleCalendar;
import com.kc1vmz.netcentral.net.NetModels.NetEdit;
import com.kc1vmz.netcentral.net.ScheduleModels.*;

@Service
public class NetSchedulerAccessor {
    private final JdbcTemplate jdbc;
    private final AccountAccessor accounts;
    private final NetAccessor nets;
    private final RoutingService routing;
    private final String defaultZone;

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static final RowMapper<Schedule> SCHEDULE = (r, n) -> new Schedule(r.getString("id"),
            r.getString("callsign"), r.getString("name"),
            r.getString("description"), r.getString("voice_frequency"), r.getString("owner_id"),
            r.getString("creator_name"), r.getBoolean("open"),
            r.getBoolean("private_net"), Type.valueOf(r.getString("type")), r.getString("timezone"),
            r.getObject("first_start", LocalDateTime.class),
            r.getInt("duration"), r.getInt("ordinal"), r.getBoolean("enabled"),
            instant(r.getObject("next_start", OffsetDateTime.class)), r.getLong("version"));
    private static final RowMapper<Occurrence> OCCURRENCE = (r, n) -> new Occurrence(r.getString("id"),
            r.getString("schedule_id"),
            instant(r.getObject("planned_start", OffsetDateTime.class)),
            instant(r.getObject("planned_end", OffsetDateTime.class)),
            instant(r.getObject("activated_at", OffsetDateTime.class)),
            instant(r.getObject("finalized_at", OffsetDateTime.class)), r.getString("net_id"), r.getString("state"));

    public NetSchedulerAccessor(JdbcTemplate jdbc, AccountAccessor accounts, NetAccessor nets, RoutingService routing,
            @Value("${netcentral.schedule-timezone:UTC}") String defaultZone) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.nets = nets;
        this.routing = routing;
        this.defaultZone = ZoneId.of(defaultZone).getId();
    }

    public String defaultTimezone() {
        return defaultZone;
    }

    private void lock() {
        jdbc.queryForObject("SELECT id FROM net_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    private boolean manages(Schedule s, Account a) {
        return s.ownerId().equals(a.id()) || !a.role().equals("USER");
    }

    private Schedule find(String id) {
        var list = jdbc.query("SELECT * FROM net_schedule WHERE deleted=FALSE AND (id=? OR callsign=?) ORDER BY id",
                SCHEDULE, id, id.toUpperCase(Locale.ROOT));
        if (list.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found");
        if (list.size() > 1)
            throw conflict("Use the schedule UUID; this callsign has multiple definitions");
        return list.getFirst();
    }

    public Schedule get(String id) {
        Schedule s = find(id);
        Account actor = accounts.current();
        if (s.privateNet() && !manages(s, actor))
            throw new AccessDeniedException("Private schedule");
        return s;
    }

    public List<Schedule> list() {
        Account a = accounts.current();
        return jdbc.query("SELECT * FROM net_schedule WHERE deleted=FALSE ORDER BY callsign,id", SCHEDULE).stream()
                .filter(s -> !s.privateNet() || manages(s, a)).toList();
    }

    public Detail detail(String id) {
        Schedule s = get(id);
        return new Detail(s, routing.load("SCHEDULE:" + s.id()), expectedInternal(s.id()),
                manages(s, accounts.current()));
    }

    public List<Occurrence> occurrences(String id) {
        Schedule s = get(id);
        return jdbc.query("SELECT * FROM net_occurrence WHERE schedule_id=? ORDER BY planned_start DESC", OCCURRENCE,
                s.id());
    }

    public Set<String> expected(String id) {
        return expectedInternal(get(id).id());
    }

    public com.fasterxml.jackson.databind.JsonNode options(String id) {
        Schedule s = get(id);
        var rows = jdbc.queryForList("SELECT payload FROM domain_record WHERE kind='SCHEDULE_OPTIONS' AND record_key=?",
                String.class, s.id());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.isEmpty() ? "{}" : rows.getFirst());
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    @Transactional
    public void options(String id, com.fasterxml.jackson.databind.JsonNode values, long expectedVersion) {
        lock();
        Schedule s = find(id);
        manager(s);
        version(expectedVersion, s.version());
        var clean = com.kc1vmz.netcentral.operations.NetOptions.validate(values);
        jdbc.update("MERGE INTO domain_record KEY(kind,record_key) VALUES('SCHEDULE_OPTIONS',?,?,?,?,?)", s.id(),
                s.ownerId(), clean.toString(), s.version() + 1, time(Instant.now()));
        jdbc.update("UPDATE net_schedule SET version=version+1 WHERE id=?", s.id());
    }

    private Set<String> expectedInternal(String id) {
        return new TreeSet<>(
                jdbc.queryForList("SELECT callsign FROM schedule_expected WHERE schedule_id=?", String.class, id));
    }

    private void manager(Schedule s) {
        if (!manages(s, accounts.current()))
            throw new AccessDeniedException("Schedule owner or administrator required");
    }

    private void version(Long value, long actual) {
        if (value == null || value != actual)
            throw conflict("Schedule changed; refresh and retry");
    }

    @Transactional
    public Schedule saveConfigured(String id, Edit edit, com.fasterxml.jackson.databind.JsonNode values) {
        return saveConfigured(id, edit, values, false);
    }

    @Transactional
    public Schedule saveConfigured(String id, Edit edit, com.fasterxml.jackson.databind.JsonNode values, boolean startFromNow) {
        lock();
        Schedule previous = id.isBlank() ? null : find(id);
        var merged = id.isBlank() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : (com.fasterxml.jackson.databind.node.ObjectNode) options(id).deepCopy();
        values.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
        var clean = com.kc1vmz.netcentral.operations.NetOptions.validate(merged);
        if (!clean.has("lat") || !clean.has("lon")) throw bad("Net latitude and longitude are required");
        Schedule saved = id.isBlank() ? create(edit) : update(id, edit);
        options(saved.id(), clean, saved.version());
        if (startFromNow && edit.type() != Type.ONE_TIME_ONLY && (previous == null || previous.type() != saved.type()
                || !previous.firstStart().equals(saved.firstStart()) || !previous.timezone().equals(saved.timezone()) || previous.ordinal() != saved.ordinal())) {
            Instant next = ScheduleCalendar.next(saved.type(), saved.firstStart(), saved.timezone(), saved.ordinal(), Instant.now());
            jdbc.update("UPDATE net_schedule SET next_start=? WHERE id=?", time(next), saved.id());
        }
        return find(saved.id());
    }

    @Transactional
    public Schedule create(Edit edit) {
        lock();
        return save(null, edit);
    }

    @Transactional
    public Schedule update(String id, Edit edit) {
        lock();
        Schedule s = find(id);
        manager(s);
        version(edit.version(), s.version());
        return save(s, edit);
    }

    private Schedule save(Schedule previous, Edit edit) {
        Account actor = accounts.current();
        String call = NetAccessor.callsign(edit.callsign()), name = text(edit.name(), 120, true),
                description = text(edit.description(), 1000, false), frequency = text(edit.voiceFrequency(), 40, false);
        if (edit.type() == null || edit.firstStart() == null || edit.duration() == null || edit.duration() < 1
                || edit.duration() > 10080)
            throw bad("Choose a recurrence, first start, and duration of 1–10080 minutes");
        if (edit.firstStart().getYear() < 1970 || edit.firstStart().getYear() > 9990)
            throw bad("Start year must be 1970–9990");
        String zone = edit.timezone() == null ? defaultZone : edit.timezone();
        try {
            zone = ZoneId.of(zone).getId();
        } catch (DateTimeException invalid) {
            throw bad("Use an IANA timezone such as America/New_York or UTC");
        }
        int ordinal = edit.ordinal() == null ? 1 : edit.ordinal();
        if (ordinal < 1 || ordinal > 6)
            throw bad("Relative week must be 1–5, or 6 for last");
        String id = previous == null ? UUID.randomUUID().toString() : previous.id();
        boolean enabled = edit.enabled() == null || edit.enabled();
        LocalDateTime start = edit.firstStart().withSecond(0).withNano(0);
        // Keep the cursor for metadata/routing edits. Timing edits affect only
        // unmaterialized occurrences.
        boolean timing = previous == null || previous.type() != edit.type() || !previous.timezone().equals(zone)
                || !previous.firstStart().equals(start) || previous.ordinal() != ordinal;
        Instant next = timing ? ScheduleCalendar.next(edit.type(), start, zone, ordinal, null)
                : previous.nextStartTime();
        boolean open = edit.open() == null || edit.open(), privateNet = Boolean.TRUE.equals(edit.privateNet());
        if (previous == null)
            jdbc.update(
                    "INSERT INTO net_schedule(id,callsign,name,description,voice_frequency,owner_id,creator_name,open,private_net,type,timezone,first_start,duration,ordinal,enabled,next_start) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, call, name, description, frequency, actor.id(), actor.emailAddress(), open, privateNet,
                    edit.type().name(), zone, start, edit.duration(), ordinal, enabled, time(next));
        else
            jdbc.update(
                    "UPDATE net_schedule SET callsign=?,name=?,description=?,voice_frequency=?,open=?,private_net=?,type=?,timezone=?,first_start=?,duration=?,ordinal=?,enabled=?,next_start=?,version=version+1 WHERE id=?",
                    call, name, description, frequency, open, privateNet, edit.type().name(), zone, start,
                    edit.duration(), ordinal, enabled, time(next), id);
        if (previous == null || edit.communicationMode() != null) {
            var defaults = routing.load("DEFAULT");
            routing.saveInternal("SCHEDULE:" + id, new RoutingService.Route("SCHEDULE:" + id,
                    previous == null ? -1 : edit.routingVersion() == null ? -2 : edit.routingVersion(),
                    edit.communicationMode() == null ? defaults.mode() : edit.communicationMode(),
                    edit.communicationMode() == null ? defaults.instanceIds()
                            : edit.communicationInstanceIds() == null ? Set.of() : edit.communicationInstanceIds()));
        }
        if (edit.expectedParticipants() != null)
            replaceExpected(id, edit.expectedParticipants());
        // An edited/disabled definition must not activate a previously blocked
        // occurrence with new settings.
        if (previous != null)
            jdbc.update(
                    "UPDATE net_occurrence SET state='CANCELLED',finalized_at=? WHERE schedule_id=? AND state='WAITING'",
                    time(Instant.now()), id);
        return find(id);
    }

    private void replaceExpected(String id, Set<String> values) {
        if (values.size() > 1000)
            throw bad("At most 1000 expected callsigns");
        Set<String> calls = new TreeSet<>();
        for (String call : values)
            calls.add(NetAccessor.callsign(call).split("-")[0]);
        jdbc.update("DELETE FROM schedule_expected WHERE schedule_id=?", id);
        for (String call : calls)
            jdbc.update("INSERT INTO schedule_expected VALUES(?,?)", id, call);
    }

    @Transactional
    public Set<String> expected(String id, Set<String> values, long expectedVersion) {
        lock();
        Schedule s = find(id);
        manager(s);
        version(expectedVersion, s.version());
        replaceExpected(s.id(), values);
        jdbc.update("UPDATE net_schedule SET version=version+1 WHERE id=?", s.id());
        return expectedInternal(s.id());
    }

    @Transactional
    public void delete(String id, long expectedVersion) {
        lock();
        Schedule s = find(id);
        manager(s);
        version(expectedVersion, s.version());
        jdbc.update("UPDATE net_schedule SET enabled=FALSE,deleted=TRUE,version=version+1 WHERE id=?", s.id());
        jdbc.update(
                "UPDATE net_occurrence SET state='CANCELLED',finalized_at=? WHERE schedule_id=? AND state='WAITING'",
                time(Instant.now()), s.id());
    }

    /**
     * One atomic reconciliation: occurrence identity, net creation, route copy and
     * cursor commit together.
     */
    @Transactional
    public void reconcile(Instant now) {
        lock();
        for (Occurrence o : jdbc.query("SELECT * FROM net_occurrence WHERE state='ACTIVE' ORDER BY planned_end",
                OCCURRENCE)) {
            var net = nets.findInternal(o.netId());
            if (net.endTime() == null && !o.plannedEnd().isAfter(now))
                nets.closeScheduledInternal(o.netId(), o.plannedEnd());
            net = nets.findInternal(o.netId());
            if (net.endTime() != null)
                jdbc.update("UPDATE net_occurrence SET state='COMPLETED',finalized_at=? WHERE id=?",
                        time(net.endTime()), o.id());
        }
        for (Schedule s : jdbc.query("SELECT * FROM net_schedule WHERE enabled=TRUE AND deleted=FALSE ORDER BY id",
                SCHEDULE)) {
            Instant cursor = s.nextStartTime();
            int budget = 128;
            while (cursor != null && !cursor.isAfter(now) && budget-- > 0) {
                Instant end = cursor.plusSeconds(s.duration() * 60L);
                if (jdbc.queryForObject("SELECT COUNT(*) FROM net_occurrence WHERE schedule_id=? AND planned_start=?",
                        Integer.class, s.id(), time(cursor)) == 0)
                    jdbc.update(
                            "INSERT INTO net_occurrence(id,schedule_id,planned_start,planned_end,state,finalized_at) VALUES(?,?,?,?,?,?)",
                            UUID.randomUUID().toString(), s.id(), time(cursor), time(end),
                            end.isAfter(now) ? "WAITING" : "SKIPPED", end.isAfter(now) ? null : time(end));
                cursor = ScheduleCalendar.next(s.type(), s.firstStart(), s.timezone(), s.ordinal(), cursor);
            }
            if (!Objects.equals(cursor, s.nextStartTime()))
                jdbc.update("UPDATE net_schedule SET next_start=?,version=version+1 WHERE id=?", time(cursor), s.id());
        }
        for (Occurrence o : jdbc.query("SELECT * FROM net_occurrence WHERE state='WAITING' ORDER BY planned_start,id",
                OCCURRENCE)) {
            if (!o.plannedEnd().isAfter(now)) {
                jdbc.update("UPDATE net_occurrence SET state='SKIPPED',finalized_at=? WHERE id=?", time(o.plannedEnd()),
                        o.id());
                continue;
            }
            Schedule s = find(o.scheduleId());
            if (!s.enabled())
                continue;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM managed_net WHERE active_callsign=?", Integer.class,
                    s.callsign()) > 0)
                continue;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM domain_record WHERE kind='OBJECT' AND record_key=?",
                    Integer.class, s.callsign()) > 0)
                continue;
            var route = routing.load("SCHEDULE:" + s.id());
            var net = nets.createScheduledInternal(
                    new NetEdit(s.callsign(), s.name(), s.description(), s.voiceFrequency(), s.open(), s.privateNet(),
                            null,
                            route.mode(), route.instanceIds(), null, expectedInternal(s.id())),
                    s.ownerId(), s.creatorName(), now);
            jdbc.update("UPDATE net_occurrence SET state='ACTIVE',activated_at=?,net_id=? WHERE id=?", time(now),
                    net.id(), o.id());
            jdbc.update(
                    "INSERT INTO domain_record(kind,record_key,owner_id,payload,version,updated_at) SELECT 'NET_OPTIONS',?,owner_id,payload,0,? FROM domain_record WHERE kind='SCHEDULE_OPTIONS' AND record_key=?",
                    net.id(), time(now), s.id());
            nets.startedInternal(net.id());
        }
    }

    private static String text(String value, int max, boolean required) {
        String s = value == null ? "" : value.strip();
        if ((required && s.isEmpty()) || s.length() > max || s.chars().anyMatch(Character::isISOControl))
            throw bad("Invalid schedule text length or characters");
        return s;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
