package com.kc1vmz.netcentral.communication;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoutingService {
    public record Route(String owner, long version, String mode, Set<String> instanceIds) {
    }

    public record RemovedRoutes(String owner, Set<String> removed) {
    }

    private final JdbcTemplate jdbc;
    private final CommunicationRepository instances;
    private final CommunicationService security;
    private final ApplicationEventPublisher events;

    public RoutingService(JdbcTemplate jdbc, CommunicationRepository instances, CommunicationService security,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.instances = instances;
        this.security = security;
        this.events = events;
    }

    public List<Route> list() {
        security.requireAdmin();
        return jdbc.queryForList("SELECT owner_key FROM communication_route ORDER BY owner_key", String.class).stream()
                .map(this::load).toList();
    }

    public Route load(String owner) {
        String key = owner == null ? "DEFAULT" : owner;
        List<Route> routes = jdbc.query("SELECT * FROM communication_route WHERE owner_key=?",
                (row, number) -> new Route(key, row.getLong("version"), row.getString("mode"), Set.of()), key);
        if (routes.isEmpty())
            return key.equals("DEFAULT") ? new Route(key, -1, "SELECTED", Set.of()) : load("DEFAULT");
        // Release the outer query's connection before fetching assignments. Concurrent
        // readers must not each hold one pool connection while waiting for a second.
        Route route = routes.getFirst();
        return new Route(key, route.version(), route.mode(), new HashSet<>(jdbc.queryForList(
            "SELECT instance_id FROM communication_route_instance WHERE owner_key=?", String.class, key)));
    }

    public boolean permits(String owner, String id) {
        Route route = load(owner);
        return route.mode().equals("ALL") || route.instanceIds().contains(id);
    }

    @Transactional
    public Route save(String owner, Route input) {
        security.requireAdmin();
        return saveInternal(owner, input);
    }

    /**
     * Trusted domain callers must authorize the owning entity before invoking this
     * operation.
     */
    @Transactional
    public Route saveInternal(String owner, Route input) {
        instances.lock();
        if (owner == null || !owner.matches("DEFAULT|(NET|SCHEDULE|OBJECT):[A-Za-z0-9_-]{1,100}"))
            throw bad("Invalid routing owner");
        if (input == null || input.mode() == null || !Set.of("ALL", "SELECTED").contains(input.mode())
                || input.instanceIds() == null
                || input.instanceIds().stream().anyMatch(id -> id == null || id.isBlank()))
            throw bad("Select ALL or SELECTED instances");
        Route previous = load(owner);
        boolean exists = jdbc.queryForObject("SELECT COUNT(*) FROM communication_route WHERE owner_key=?",
                Integer.class, owner) == 1;
        if (input.version() != (exists ? previous.version() : -1))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Routing changed; refresh and retry");
        Set<String> selected = input.mode().equals("ALL") ? Set.of() : Set.copyOf(input.instanceIds());
        for (String id : selected)
            if (instances.find(id) == null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A selected instance no longer exists");
        Set<String> removed = new HashSet<>();
        for (CommunicationConfig c : instances.all()) {
            boolean was = previous.mode().equals("ALL") || previous.instanceIds().contains(c.id());
            boolean now = input.mode().equals("ALL") || selected.contains(c.id());
            if (was && !now)
                removed.add(c.id());
        }
        long version = exists ? previous.version() + 1 : 0;
        jdbc.update("MERGE INTO communication_route (owner_key,version,mode) KEY(owner_key) VALUES(?,?,?)", owner,
                version, input.mode());
        jdbc.update("DELETE FROM communication_route_instance WHERE owner_key=?", owner);
        for (String id : selected)
            jdbc.update("INSERT INTO communication_route_instance VALUES(?,?)", owner, id);
        if (!removed.isEmpty())
            events.publishEvent(new RemovedRoutes(owner, Set.copyOf(removed)));
        return new Route(owner, version, input.mode(), selected);
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
