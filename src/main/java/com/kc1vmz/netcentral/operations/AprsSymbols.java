package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/** Offline display descriptions; never changes station classifications or transmitted symbols. */
public final class AprsSymbols {
    private static final JsonNode TABLES = load();

    private AprsSymbols() { }

    private static JsonNode load() {
        try (var input = AprsSymbols.class.getResourceAsStream("/aprs/symbol-descriptions.json")) {
            if (input == null) throw new IllegalStateException("Missing APRS symbol descriptions");
            return new ObjectMapper().readTree(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load APRS symbol descriptions", failure);
        }
    }

    public static String code(JsonNode data) {
        String combined = data.path("symbol").asText("");
        if (!combined.isEmpty()) return combined;
        return data.path("symbolTableId").asText("") + data.path("symbolTableCode").asText("");
    }

    public static String describe(String code) {
        if (code == null || code.isBlank()) return "Not specified";
        if (code.length() != 2 || code.charAt(1) < '!' || code.charAt(1) > '~')
            return "Unknown APRS symbol";
        char table = code.charAt(0);
        if (table == '/' || table == '\\')
            return TABLES.path("primaryAndAlternate").path(code).asText("Unknown APRS symbol");
        // Lowercase overlays can be ambiguous without packet-format context. Preserve them literally.
        if (!((table >= '0' && table <= '9') || (table >= 'A' && table <= 'Z')
                || (table >= 'a' && table <= 'z'))) return "Unknown APRS symbol";
        String specific = TABLES.path("overlays").path(code).asText("");
        if (!specific.isEmpty()) return specific + " (overlay " + table + ")";
        String base = TABLES.path("primaryAndAlternate").path("\\" + code.charAt(1)).asText("Unknown APRS symbol");
        return base + " (overlay " + table + ")";
    }

    /** Call only on display copies, not persisted payloads. */
    public static void decorate(ObjectNode data) {
        String code = code(data);
        data.put("symbol", code);
        data.put("symbolDescription", describe(code));
    }
}
