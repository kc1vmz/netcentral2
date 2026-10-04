package com.kc1vmz.netcentral.communication;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class TransmissionRouter {
    private final RoutingService routing;
    private final CommunicationRepository instances;
    private final CommunicationManager manager;
    private final PacketHub received;
    private final JdbcTemplate jdbc;
    private final Map<String, Packet> beacons = new LinkedHashMap<>();

    public TransmissionRouter(RoutingService routing, CommunicationRepository instances, CommunicationManager manager,
            PacketHub received, JdbcTemplate jdbc) {
        this.routing = routing;
        this.instances = instances;
        this.manager = manager;
        this.received = received;
        this.jdbc = jdbc;
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> broadcast(String owner, Packet packet) {
        return broadcast(owner, packet, () -> true);
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> broadcast(String owner, Packet packet,
            BooleanSupplier eligible) {
        Map<String, CompletableFuture<ManagedConnection.Outcome>> results = new LinkedHashMap<>();
        for (CommunicationConfig c : instances.all()) {
            if (routing.permits(owner, c.id()) && c.enabled() && c.transmitEnabled())
                results.put(c.id(), manager.send(c.id(), List.of(packet),
                        () -> routing.permits(owner, c.id()) && eligible.getAsBoolean()));
        }
        return results;
    }

    public List<String> withdrawalInstances(String owner) {
        return instances.all().stream().filter(c -> routing.permits(owner,c.id()) && c.enabled() && c.transmitEnabled())
            .map(CommunicationConfig::id).toList();
    }

    public void withdrawDeletedObject(String owner, Packet packet, List<String> selected) {
        rememberObject(owner, packet);
        for (String id : selected) manager.send(id,List.of(packet), () ->
            jdbc.queryForObject("SELECT COUNT(*) FROM domain_record WHERE kind='OBJECT' AND record_key=?",
                Integer.class,owner.substring(7)) == 0);
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> directed(String owner, String destination,
            String receivingInstance, List<Packet> packets) {
        return directed(owner, destination, receivingInstance, packets, () -> true);
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> directed(String owner, String destination,
            String receivingInstance, List<Packet> packets, BooleanSupplier eligible) {
        String id = directedInstance(owner, destination, receivingInstance);
        if (id == null)
            return Map.of();
        return Map.of(id, manager.send(id, packets, () -> routing.permits(owner, id) && eligible.getAsBoolean()));
    }

    public boolean canDirect(String owner, String destination) {
        return directedInstance(owner, destination, null) != null;
    }

    private String directedInstance(String owner, String destination, String receivingInstance) {
        List<String> candidates = new ArrayList<>();
        if (receivingInstance != null)
            candidates.add(receivingInstance);
        candidates.addAll(received.recent(destination.toUpperCase(Locale.ROOT)));
        for (String id : candidates) {
            CommunicationConfig config = instances.find(id);
            if (config != null && config.enabled() && config.transmitEnabled()
                    && manager.available(id) && routing.permits(owner, id))
                return id;
        }
        return null; // Never silently flood instances without a recently heard route.
    }

    public Packet message(String from, String to, String content, boolean acknowledgementRequested) {
        if (to == null || !to.matches("[A-Za-z0-9-]{1,9}") || content == null || content.isBlank()
                || content.length() > 67
                || content.chars().anyMatch(ch -> ch < 32 || ch > 126)
                || content.contains("{") || content.contains("|") || content.contains("~"))
            throw new IllegalArgumentException("Invalid APRS message");
        String suffix = "";
        if (acknowledgementRequested) {
            long counter = jdbc.queryForObject("SELECT NEXT VALUE FOR aprs_message_number", Long.class);
            suffix = "{" + Long.toString(counter % 60466176, 36).toUpperCase(Locale.ROOT);
        }
        return new Packet(from, "APANC1", List.of(),
                ":" + String.format("%-9s", to.toUpperCase(Locale.ROOT)) + ":" + content + suffix);
    }

    public Packet acknowledgement(String from, String to, String number) {
        if (number == null || !number.matches("[A-Za-z0-9]{1,5}"))
            throw new IllegalArgumentException("Invalid acknowledgment number");
        return message(from, to, "ack" + number, false);
    }

    public Packet query(String from, String to, String query) {
        if (query == null || !query.matches("[A-Z0-9]{1,12}"))
            throw new IllegalArgumentException("Invalid APRS query");
        return message(from, to, "?" + String.format("%-5s", query), false);
    }

    /**
     * Net Central's existing user-defined report prefix; domain serializers supply
     * the body.
     */
    public Packet report(String from, String body) {
        return new Packet(from, "APANC1", List.of(), "{ZN" + body);
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> sendMessage(String owner, String from, String to,
            String content, boolean ack, String receivingInstance) {
        var packets = List.of(message(from, to, content, ack));
        var result = directed(owner, to, receivingInstance, packets);
        if (!result.isEmpty() || !("WHO-IS".equalsIgnoreCase(to) || "WHO-15".equalsIgnoreCase(to))) return result;
        // Identity resolver discovery explicitly falls back to all usable instances, irrespective
        // of the default selection. Other directed messages retain their route policy.
        Map<String, CompletableFuture<ManagedConnection.Outcome>> fallback = new LinkedHashMap<>();
        for (CommunicationConfig config : instances.all()) {
            if (config.enabled() && config.transmitEnabled() && manager.available(config.id()))
                fallback.put(config.id(), manager.send(config.id(), packets, () -> {
                    var current = instances.find(config.id());
                    return current != null && current.enabled() && current.transmitEnabled();
                }));
        }
        return fallback;
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> sendGroup(String owner, String from, String to,
            List<String> messages, boolean ack, String receivingInstance) {
        if (messages == null || messages.isEmpty() || messages.size() > 50)
            throw new IllegalArgumentException("Use 1 to 50 messages");
        return directed(owner, to, receivingInstance,
                messages.stream().map(value -> message(from, to, value, ack)).toList());
    }

    public Map<String, CompletableFuture<ManagedConnection.Outcome>> bulletin(String owner, String from,
            String bulletinId, String text) {
        return broadcast(owner, message(from, bulletinId, text, false));
    }

    public synchronized Map<String, CompletableFuture<ManagedConnection.Outcome>> object(String owner, Packet beacon) {
        rememberObject(owner, beacon);
        return broadcast(owner, beacon);
    }

    public synchronized void rememberObject(String owner, Packet beacon) {
        if (owner == null || !owner.startsWith("OBJECT:"))
            throw new IllegalArgumentException("Object owner is required");
        if (beacon.information().length() < 11 || !beacon.information().startsWith(";"))
            throw new IllegalArgumentException("Invalid object beacon");
        if (beacon.information().charAt(10) == '*') {
            beacons.put(owner, beacon);
            if (beacons.size() > 1000)
                beacons.remove(beacons.keySet().iterator().next());
        } else if (beacon.information().charAt(10) == '_')
            beacons.remove(owner);
        else
            throw new IllegalArgumentException("Invalid object state");
    }

    @TransactionalEventListener
    public synchronized void removed(RoutingService.RemovedRoutes event) {
        for (var entry : beacons.entrySet()) {
            String owner = entry.getKey();
            boolean inherited = event.owner().equals("DEFAULT") && routing.load(owner).owner().equals("DEFAULT");
            if (!owner.equals(event.owner()) && !inherited)
                continue;
            Packet withdrawal = entry.getValue().withdrawal();
            for (String id : event.removed()) {
                manager.send(id, List.of(withdrawal), () -> !routing.permits(owner, id));
            }
        }
    }
}
