package com.kc1vmz.netcentral.accessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.kc1vmz.netcentral.communication.*;
import com.kc1vmz.netcentral.operations.*;

import static com.kc1vmz.netcentral.operations.OperationsStore.*;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class ToolsAccessor {
    public record Status(boolean success, String status) {
    }

    private static final class Session {
        final String owner, call, recipient, subject, message;
        char[] password;
        volatile String state = "WAITING";
        final Instant expires = Instant.now().plusSeconds(600);

        Session(String owner, String call, String password, String recipient, String subject, String message) {
            this.owner = owner;
            this.call = call;
            this.password = password.toCharArray();
            this.recipient = recipient;
            this.subject = subject;
            this.message = message;
        }

        void clear() {
            Arrays.fill(password, '\0');
            password = new char[0];
        }
    }

    private final OperationsStore store;
    private final TransmissionRouter router;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ToolsAccessor(OperationsStore store, TransmissionRouter router) {
        this.store = store;
        this.router = router;
    }

    private String sender(String call) {
        call = NetAccessor.callsign(call);
        var account = store.accounts().current();
        if (account.role().equals("USER") && (account.callsign() == null
                || !RadioPolicy.root(account.callsign().callsign()).equals(RadioPolicy.root(call))))
            throw new AccessDeniedException("Use your account callsign");
        return call;
    }

    public Status direct(String target, JsonNode input, boolean identify) {
        String from = sender(field(input, "callsignFrom", 9, true));
        target = NetAccessor.callsign(target);
        String message = identify ? "?APRSD" : field(input, "message", 67, true);
        var sent = router.sendMessage("DEFAULT", from, target, message, false, null);
        return new Status(!sent.isEmpty(), sent.isEmpty() ? "NO_ROUTE" : "QUEUED");
    }

    public Status identifyCallsign(String target) {
        var account = store.accounts().current();
        target = NetAccessor.callsign(target);
        if (account.callsign() == null || account.callsign().callsign() == null
                || account.callsign().callsign().isBlank())
            return new Status(false, "ACCOUNT_CALLSIGN_REQUIRED");
        String from = sender(account.callsign().callsign());
        var sent = router.sendMessage("DEFAULT", from, store.setting("identityResolver"), target, false, null);
        return new Status(!sent.isEmpty(), sent.isEmpty() ? "NO_ROUTE" : "QUEUED");
    }

    public synchronized Status winlink(JsonNode input) {
        cleanup();
        String call = sender(field(input, "callsign", 9, true));
        if (sessions.containsKey(call) || sessions.size() >= 100)
            throw bad("A request already exists or the session limit was reached");
        String recipient = field(input, "recipient", 80, true), subject = field(input, "subject", 50, false),
                message = field(input, "message", 2000, true), password = field(input, "password", 128, true);
        if (!recipient.matches("[A-Za-z0-9@._+-]+") || ("SP " + recipient + " " + subject).length() > 67)
            throw bad("Recipient/subject too long or invalid");
        Session session = new Session(store.accounts().current().id(), call, password, recipient, subject, message);
        sessions.put(call, session);
        send(session, List.of("Net Central login request from " + call), null, false);
        return status(call);
    }

    public Status status(String call) {
        cleanup();
        var session = sessions.get(NetAccessor.callsign(call));
        if (session == null) {
            store.authenticated();
            return new Status(false, "NOT_FOUND");
        }
        authorize(session);
        return new Status(!Set.of("FAILED", "NO_ROUTE", "REJECTED", "EXPIRED").contains(session.state), session.state);
    }

    private void authorize(Session s) {
        var a = store.accounts().current();
        if (a.role().equals("USER") && !s.owner.equals(a.id()))
            throw new AccessDeniedException("Request owner required");
    }

    public synchronized void cancel(String call) {
        call = NetAccessor.callsign(call);
        var s = sessions.get(call);
        if (s != null) {
            authorize(s);
            s.clear();
            s.state = "CANCELLED";
            sessions.remove(call);
        } else
            store.authenticated();
    }

    public void cleanup() {
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().expires.isBefore(Instant.now())) {
                e.getValue().clear();
                return true;
            }
            return false;
        });
    }

    @EventListener
    public synchronized void receive(PacketHub.UniquePacket event) {
        var p = event.received().packet();
        if ((!p.source().equals("WLNK") && (!p.source().equals("WLNK-1"))) || p.information().length() < 11 || p.information().charAt(0) != ':')
            return;
        var session = sessions.get(p.information().substring(1, 10).strip());
        if (session == null || session.expires.isBefore(Instant.now()))
            return;
        String body = p.information().substring(11);
        int ack = body.lastIndexOf('{');
        if (ack >= 0) {
            String number = body.substring(ack + 1);
            if (number.matches("[A-Za-z0-9]{1,5}"))
                router.sendMessage("DEFAULT", session.call, "WLNK", "ack" + number, false,
                        event.received().instanceId());
            body = body.substring(0, ack);
        }
        if (session.state.equals("WAITING")) {
            var challenge = java.util.regex.Pattern.compile("(?<![0-9])([1-9]{3})(?![0-9])").matcher(body);
            if (!challenge.find())
                return;
            String digits = challenge.group(1);
            StringBuilder response = new StringBuilder();
            SecureRandom random = new SecureRandom();
            for (char digit : digits.toCharArray()) {
                int index = digit - '1';
                if (index >= session.password.length) {
                    session.state = "REJECTED";
                    session.clear();
                    return;
                }
                response.append(session.password[index]).append((char) ('a' + random.nextInt(26)));
            }
            session.state = "CHALLENGING";
            session.clear();
            send(session, List.of(response.toString()), event.received().instanceId(), false);
        } else if (session.state.equals("CHALLENGING")) {
            if (!body.toUpperCase(Locale.ROOT).startsWith("HELLO " + session.call)) {
                if (body.toLowerCase(Locale.ROOT).contains("invalid")
                        || body.toLowerCase(Locale.ROOT).contains("denied"))
                    session.state = "REJECTED";
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add("SP " + session.recipient + " " + session.subject);
            for (int i = 0; i < session.message.length(); i += 50) {
                String line = session.message.substring(i, Math.min(i + 50, session.message.length()));
                if (line.startsWith("ack") || line.startsWith("rej"))
                    line = " " + line;
                lines.add(line);
            }
            lines.add("/EX");
            lines.add("BYE");
            session.state = "SENDING";
            send(session, lines, event.received().instanceId(), true);
        }
    }

    private void send(Session session, List<String> lines, String receiving, boolean finish) {
        try {
            var result = router.sendGroup("DEFAULT", session.call, "WLNK", lines, false, receiving);
            if (result.isEmpty()) {
                session.state = "NO_ROUTE";
                session.clear();
                return;
            }
            result.values().iterator().next().whenComplete((outcome, error) -> {
                if (error != null || !outcome.state().equals("SENT")) {
                    session.state = error == null ? outcome.state() : "FAILED";
                    session.clear();
                } else if (finish) {
                    session.state = "SENT";
                    session.clear();
                }
            });
        } catch (RuntimeException invalid) {
            session.state = "FAILED";
            session.clear();
        }
    }

    public Status nts(JsonNode input) {
        String from = sender(field(input, "callsignOrigin", 9, true));
        List<String> lines = new ArrayList<>(), words = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String word = field(input, "message" + i, 30, false);
            if (!word.isEmpty()) {
                if (word.contains(" ") || word.contains("\\"))
                    throw bad("Each message field must contain one word");
                words.add(word.toUpperCase(Locale.ROOT));
            }
        }
        int count;
        try {
            count = Integer.parseInt(input.path("check").asText());
        } catch (NumberFormatException bad) {
            throw bad("Check must be a number");
        }
        if (count < 1 || count != words.size())
            throw bad("Check mismatch");
        lines.add("QTC 1");
        lines.add("N#\\" + String.join("\\", ntsFields(input, "number", "precedence", "hx", "callsignOrigin", "check",
                "locationOrigin", "timeFiled", "dateFiled")));
        lines.add("NA\\" + String.join("\\", ntsFields(input, "name", "address", "cityState", "operatorNote")));
        if (input.hasNonNull("phoneNumber"))
            lines.add("NP\\" + field(input, "phoneNumber", 30, false).replaceAll("[^0-9]", ""));
        if (input.hasNonNull("emailAddress"))
            lines.add("NE\\" + field(input, "emailAddress", 60, false));
        for (int i = 0; i < words.size(); i += 5)
            lines.add("N" + (i / 5 + 1) + "\\" + String.join(" ", words.subList(i, Math.min(i + 5, words.size()))));
        lines.add("NS\\" + field(input, "signature", 60, true).toUpperCase(Locale.ROOT));
        for (String line : lines)
            if (line.length() > 67)
                throw bad("Radiogram line exceeds APRS length; shorten its fields");
        var sent = router.sendGroup("DEFAULT", from, "NTSGTE", lines, false, null);
        return new Status(!sent.isEmpty(), sent.isEmpty() ? "NO_ROUTE" : "QUEUED");
    }

    private List<String> ntsFields(JsonNode input, String... fields) {
        return Arrays.stream(fields).map(f -> {
            String value = field(input, f, 60,
                    Set.of("number", "precedence", "callsignOrigin", "check", "locationOrigin", "dateFiled", "name")
                            .contains(f));
            if (value.contains("\\"))
                throw bad("Backslashes are protocol separators");
            return value.toUpperCase(Locale.ROOT);
        }).toList();
    }
}
