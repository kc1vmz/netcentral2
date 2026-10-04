package com.kc1vmz.netcentral.operations;

import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import com.kc1vmz.netcentral.communication.*;

@Service
public class RadioDispatch {
    public record Send(String owner, String target, String receiving, List<Packet> packets, boolean broadcast,
            Long objectVersion) {
    }

    private final ApplicationEventPublisher events;
    private final TransmissionRouter router;
    private final OperationsStore store;

    public RadioDispatch(ApplicationEventPublisher events, TransmissionRouter router, OperationsStore store) {
        this.events = events;
        this.router = router;
        this.store = store;
    }

    public void message(String owner, String from, String to, String text, String receiving) {
        // Long responses are split into independently valid APRS messages, with a
        // bounded fan-out.
        String clean = text.replaceAll("[\\p{Cntrl}{}|~]", " ").replaceAll("[^\\x20-\\x7E]", "?");
        List<Packet> packets = new ArrayList<>();
        for (int i = 0; i < clean.length() && packets.size() < 50; i += 67)
            packets.add(router.message(from, to, clean.substring(i, Math.min(i + 67, clean.length())), false));
        if (!packets.isEmpty())
            events.publishEvent(new Send(owner, to, receiving, packets, false, null));
    }

    public void group(String owner, String from, String to, List<String> lines, String receiving) {
        if (lines.isEmpty() || lines.size() > 50)
            throw OperationsStore.bad("Use 1–50 messages");
        events.publishEvent(new Send(owner, to, receiving,
                lines.stream().map(s -> router.message(from, to, s, false)).toList(), false, null));
    }

    public void federate(String owner, com.kc1vmz.netcentral.aprsobject.object.reports.APRSNetCentralReport report, Long version) {
        if (!store.enabled("federationEnabled")) return;
        String body = report.getFullReportData();
        if (body.length() > 253) return;
        if (store.enabled("federationUserDefinedEnabled"))
            broadcast(owner, new Packet(report.getObjectName(), "APANC1", List.of(), "{ZN" + body), version);
        if (store.enabled("federationMessagesEnabled") && body.length() <= 245)
            // Legacy federation messages are addressed from the resource to itself.
            // Broadcast on its configured instances; this is not a directed user message.
            broadcast(owner, new Packet(report.getObjectName(), "APANC1", List.of(),
                    ":" + String.format("%-9s", report.getObjectName()) + ":" + body), version);
    }

    public void broadcast(String owner, Packet packet, Long objectVersion) {
        events.publishEvent(new Send(owner, null, null, List.of(packet), true, objectVersion));
    }

    public record Withdrawal(String owner, Packet packet, List<String> instances) {}

    public void withdraw(String owner, Packet packet) {
        // Capture routing before deletion. One best-effort send; no acknowledgement or retry record.
        events.publishEvent(new Withdrawal(owner, packet, router.withdrawalInstances(owner)));
    }

    @TransactionalEventListener
    public void deliverWithdrawal(Withdrawal request) {
        router.withdrawDeletedObject(request.owner(),request.packet(),request.instances());
    }

    @TransactionalEventListener
    public void deliver(Send request) {
        java.util.function.BooleanSupplier eligible = () -> {
            if (request.broadcast()) {
                for (Packet packet : request.packets()) {
                    boolean userDefined = packet.information().startsWith("{ZN");
                    boolean messageReport = packet.information().startsWith(":" + String.format("%-9s", packet.source()) + ":");
                    if ((userDefined || messageReport) && (!store.enabled("federationEnabled")
                            || !store.enabled(userDefined ? "federationUserDefinedEnabled" : "federationMessagesEnabled"))) return false;
                }
            }
            if (request.owner().startsWith("NET:")) {
                String id = request.owner().substring(4);
                boolean secured = request.packets().stream()
                        .allMatch(p -> p.information().equals(":BLNNET   :Net " + p.source() + " stopped")
                                || p.information().startsWith("{ZNNENE") || p.information().matches(":.{9}:NENE.*") || (p.information().startsWith(";")
                                && p.information().length() > 10 && p.information().charAt(10) == '_'));
                if (!store.enabled("federationEnabled")
                        && request.packets().stream().anyMatch(p -> (p.information().startsWith("{ZN") || p.information().startsWith(":" + String.format("%-9s", p.source()) + ":"))))
                    return false;
                return store.jdbc().queryForObject(
                        "SELECT COUNT(*) FROM managed_net n WHERE n.id=? AND n.private_net=FALSE AND (? OR (n.closed_at IS NULL AND NOT EXISTS (SELECT 1 FROM net_occurrence o WHERE o.net_id=n.id AND o.planned_end<=?)))",
                        Integer.class, id, secured, OperationsStore.now()) == 1;
            }
            if (request.objectVersion() == null)
                return true;
            var object = store.find("OBJECT", request.owner().substring(7));
            return object != null && object.version() == request.objectVersion();
        };
        if (request.broadcast())
            for (Packet p : request.packets()) {
                if (request.owner().startsWith("OBJECT:") && p.information().startsWith(";"))
                    router.rememberObject(request.owner(), p);
                router.broadcast(request.owner(), p, eligible);
            }
        else
            router.directed(request.owner(), request.target(), request.receiving(), request.packets(), eligible);
    }
}
