package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Same settings for a running net and the template copied into scheduled
 * occurrences.
 */
public final class NetOptions {
    private NetOptions() {
    }

    public static ObjectNode validate(JsonNode values) {
        ObjectNode clean = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("participantInviteAllowed", "checkinReminder", "questionReminder", "announce")) {
            if (values.has(field) && !values.get(field).isBoolean())
                throw OperationsStore.bad("Use boolean net options");
            clean.put(field, values.path(field).asBoolean(false));
        }
        clean.put("checkinMessage",
                OperationsStore.text(values.path("checkinMessage").asText("Check in with CI"), 67, false));
        if (values.hasNonNull("lat") || values.hasNonNull("lon")) {
            double lat = values.path("lat").asDouble(Double.NaN), lon = values.path("lon").asDouble(Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
                throw OperationsStore.bad("Valid net latitude and longitude required");
            clean.put("lat", lat);
            clean.put("lon", lon);
        }
        String table = values.path("symbolTableId").asText("/"), code = values.path("symbolTableCode").asText("n");
        if (table.length() != 1 || !(table.equals("/") || table.equals("\\") || table.matches("[A-Z0-9]"))
                || code.length() != 1 || code.charAt(0) < '!' || code.charAt(0) > '~')
            throw OperationsStore.bad("Valid APRS symbol table and code required");
        clean.put("symbolTableId", table);
        clean.put("symbolTableCode", code);
        return clean;
    }
}
