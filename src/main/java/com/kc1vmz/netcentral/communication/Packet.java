package com.kc1vmz.netcentral.communication;

import java.util.List;
import java.util.Locale;

/**
 * TNC2 packet shared by transports. No AX.25 FCS is included in KISS frames.
 */
public record Packet(String source, String destination, List<String> path, String information) {
    public Packet {
        if (source == null || destination == null || information == null || path == null)
            throw new IllegalArgumentException("Incomplete packet");
        source = source.toUpperCase(Locale.ROOT);
        destination = destination.toUpperCase(Locale.ROOT);
        path = List.copyOf(path);
        if (!source.matches("[A-Z0-9-]{1,10}") || !destination.matches("[A-Z0-9-]{1,10}")
                || information.isEmpty() || information.length() > 256
                || information.chars().anyMatch(ch -> ch == '\r' || ch == '\n' || ch > 255)
                || path.size() > 8 || path.stream().anyMatch(p -> !p.matches("[A-Za-z0-9*-]{1,16}"))) {
            throw new IllegalArgumentException("Invalid APRS packet");
        }
    }

    public String line() {
        return source + ">" + destination + (path.isEmpty() ? "" : "," + String.join(",", path)) + ":" + information;
    }

    public static Packet parse(String line) {
        int separator = line.indexOf('>');
        int colon = line.indexOf(':', separator + 1);
        if (separator < 1 || colon < 0)
            throw new IllegalArgumentException("Invalid TNC2 packet");
        String[] addresses = line.substring(separator + 1, colon).split(",");
        return new Packet(line.substring(0, separator), addresses[0], List.of(addresses).subList(1, addresses.length),
                line.substring(colon + 1));
    }

    public String duplicateKey() {
        return source + ">" + destination + ":" + information;
    }

    public Packet withdrawal() {
        if (!information.startsWith(";") || information.length() < 11 || information.charAt(10) != '*') {
            throw new IllegalArgumentException("An active object beacon is required");
        }
        return new Packet(source, destination, path, information.substring(0, 10) + "_" + information.substring(11));
    }
}
