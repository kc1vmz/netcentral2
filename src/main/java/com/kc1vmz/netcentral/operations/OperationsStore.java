package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kc1vmz.netcentral.accessor.AccountAccessor;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared persistence primitives. Accessors authorize entity operations before
 * internal writes.
 */
@Service
public class OperationsStore {
    public record Entry(String id, String kind, String ownerId, long version, Instant updatedAt, JsonNode data) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AccountAccessor accounts;
    private final org.springframework.context.ApplicationEventPublisher events;
    public record SettingsChanged() {}

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public AccountAccessor accounts() {
        return accounts;
    }

    public OperationsStore(JdbcTemplate jdbc, ObjectMapper mapper, AccountAccessor accounts, org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.accounts = accounts;
        this.events = events;
    }

    public void lock() {
        jdbc.queryForObject("SELECT id FROM net_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored data", e);
        }
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw bad("Invalid data");
        }
    }

    public Entry find(String kind, String id) {
        var rows = jdbc.query("SELECT * FROM domain_record WHERE kind=? AND record_key=?",
                (r, n) -> new Entry(r.getString("record_key"), r.getString("kind"), r.getString("owner_id"),
                        r.getLong("version"), r.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        json(r.getString("payload"))),
                kind, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Entry require(String kind, String id) {
        Entry e = find(kind, id);
        if (e == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found");
        return e;
    }

    public List<Entry> list(String kind) {
        return jdbc.queryForList("SELECT record_key FROM domain_record WHERE kind=? ORDER BY record_key", String.class,
                kind).stream().map(id -> find(kind, id)).toList();
    }

    public void authenticated() {
        accounts.current();
    }

    public void admin() {
        if (!accounts.administrator())
            throw new AccessDeniedException("Administrator required");
    }

    public boolean manages(Entry e) {
        var a = accounts.current();
        return e.ownerId().equals(a.id()) || !a.role().equals("USER");
    }

    public void manage(Entry e) {
        if (!manages(e))
            throw new AccessDeniedException("Owner or administrator required");
    }

    public void version(Long value, Entry e) {
        if (value == null || value != e.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Record changed; refresh and retry");
    }

    public Entry saveInternal(String kind, String id, String owner, JsonNode data) {
        if (data == null || !data.isObject() || encode(data).length() > 32000)
            throw bad("Use an object of at most 32000 characters");
        Entry old = find(kind, id);
        long version = old == null ? 0 : old.version() + 1;
        jdbc.update("MERGE INTO domain_record KEY(kind,record_key) VALUES(?,?,?,?,?,?)", kind, id, owner, encode(data),
                version, now());
        return require(kind, id);
    }

    public void activity(String actor, String text) {
        jdbc.update("INSERT INTO activity(actor,description,recorded_at) VALUES(?,?,?)", actor, text(text, 500, false),
                now());
    }

    public String setting(String name) {
        return jdbc.queryForObject("SELECT setting_value FROM application_settings WHERE setting_key=?", String.class,
                "operations." + name);
    }

    public boolean enabled(String name) {
        return Boolean.parseBoolean(setting(name));
    }

    /**
     * Called under the shared mutation lock, so duplicate radio commands cannot
     * race.
     */
    public boolean duplicateRadio(String target, String source, String body) {
        int marker = body.lastIndexOf('{');
        if (marker < 0 || !body.substring(marker + 1).matches("[A-Za-z0-9]{1,5}"))
            return false;
        String key;
        try {
            key = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((target + "|" + source + "|" + body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        jdbc.update("DELETE FROM domain_record WHERE kind='RADIO_DEDUP' AND updated_at<?", now().minusMinutes(15));
        if (find("RADIO_DEDUP", key) != null)
            return true;
        saveInternal("RADIO_DEDUP", key, "", mapper.createObjectNode());
        return false;
    }

    public long seconds(String name) {
        return Long.parseLong(setting(name));
    }

    public Map<String, String> settings() {
        authenticated();
        Map<String, String> values = new TreeMap<>();
        jdbc.query("SELECT * FROM application_settings WHERE setting_key LIKE 'operations.%'", r -> {
            values.put(r.getString(1).substring(11), r.getString(2));
        });
        return values;
    }

    @Transactional
    public Map<String, String> settings(Map<String, String> values) {
        admin();
        lock();
        Map<String, String> current = settings();
        for (var item : values.entrySet()) {
            String key = item.getKey(), value = item.getValue();
            if (!current.containsKey(key) || value == null)
                throw bad("Unknown setting");
            if (key.equals("netManagerLatitude") || key.equals("netManagerLongitude")) {
                if (!value.isBlank()) {
                    try {
                        double coordinate = Double.parseDouble(value);
                        if (!Double.isFinite(coordinate) || Math.abs(coordinate) > (key.equals("netManagerLatitude") ? 90 : 180))
                            throw bad("Net Manager coordinates are outside range");
                        value = Double.toString(coordinate);
                    } catch (NumberFormatException invalid) { throw bad("Use decimal coordinates for Net Manager"); }
                } else value = "";
            } else if (key.equals("winlinkProvider")) {
                if (!accounts.current().role().equals("SYSADMIN")) throw new AccessDeniedException("System administrator required");
                if (!Set.of("WLNK", "WLNK-1").contains(value)) throw bad("Choose WLNK or WLNK-1");
            } else if (key.equals("identityResolver")) {
                if (!accounts.current().role().equals("SYSADMIN")) throw new AccessDeniedException("System administrator required");
                if (!Set.of("WHO-IS", "WHO-15").contains(value)) throw bad("Choose WHO-IS or WHO-15");
            } else if (key.endsWith("Enabled") || key.equals("rawPacketLogging")) {
                if (!Set.of("true", "false").contains(value))
                    throw bad("Use true or false");
            } else if (key.equals("federationCallsign"))
                value = value.isBlank() ? "" : com.kc1vmz.netcentral.accessor.NetAccessor.callsign(value);
            else if (key.equals("netManagerCallsign"))
                value = com.kc1vmz.netcentral.accessor.NetAccessor.callsign(value);
            else if (key.equals("netManagerOwner")) {
                if (!value.isEmpty())
                    accounts.get(value);
            } else {
                try {
                    long n = Long.parseLong(value);
                    if (n < 1 || n > 31536000)
                        throw bad("Setting outside range");
                } catch (NumberFormatException e) {
                    throw bad("Use a positive integer");
                }
            }
            current.put(key, value);
        }
        if (Boolean.parseBoolean(current.get("federationInterrogateEnabled")) && current.get("federationCallsign").isBlank())
            throw bad("Set a federation callsign before enabling interrogation");
        if (Boolean.parseBoolean(current.get("netManagerEnabled")) && current.get("netManagerOwner").isBlank())
            throw bad("Select a local owner for Net Manager-created nets");
        if (current.get("netManagerLatitude").isBlank() != current.get("netManagerLongitude").isBlank())
            throw bad("Provide both Net Manager latitude and longitude");
        for (var item : current.entrySet())
            jdbc.update("UPDATE application_settings SET setting_value=? WHERE setting_key=?", item.getValue(),
                    "operations." + item.getKey());
        events.publishEvent(new SettingsChanged());
        activity(accounts.current().id(), "Updated application settings");
        return current;
    }

    public static String text(String value, int max, boolean required) {
        String s = value == null ? "" : value.strip();
        if ((required && s.isEmpty()) || s.length() > max || s.chars().anyMatch(Character::isISOControl))
            throw bad("Invalid text length or characters");
        return s;
    }

    public static String field(JsonNode node, String name, int max, boolean required) {
        return text(node.path(name).asText(""), max, required);
    }

    public static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
