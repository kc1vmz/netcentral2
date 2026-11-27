package com.kc1vmz.netcentral.net;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Single-packet, slash-delimited RFI definitions and responses. */
public final class StripFormat {
    private StripFormat() {}
    public record Field(String label, String mask) {}
    public record Definition(String name, List<Field> fields) {}

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    public static void wire(String text) {
        if (text == null || text.length() > 67) throw invalid("Strip exceeds 67 characters including its prefix");
        if (text.chars().anyMatch(c -> c < 32 || c > 126 || c == '{' || c == '}' || c == '|' || c == '~'))
            throw invalid("Strip contains characters unsupported by APRS");
    }
    private static String[] parts(String text) {
        wire(text);
        if (!text.endsWith("//")) throw invalid("Strip must end with //");
        return text.substring(0, text.length() - 2).split("/", -1);
    }
    public static Definition definition(String text) {
        String[] parts = parts(text);
        if (parts.length < 2 || parts[0].isBlank() || parts[0].contains("(") || parts[0].contains(")"))
            throw invalid("Strip needs a name and at least one field");
        var fields = Arrays.stream(parts).skip(1).map(value -> {
            int start = value.indexOf('(');
            if (start < 1 || !value.endsWith(")") || value.indexOf(')', start) != value.length() - 1)
                throw invalid("Use Label(MASK) for each strip field");
            String label = value.substring(0, start).trim();
            String mask = value.substring(start + 1, value.length() - 1).trim();
            if (label.isEmpty() || !supported(mask)) throw invalid("Invalid mask or label: " + value);
            return new Field(label, mask);
        }).toList();
        return new Definition(parts[0], fields);
    }
    private static boolean supported(String mask) {
        return mask.equals("X") || mask.equals("Y,N") || mask.matches("N+|A+|-?#+(\\.#+)?")
                || List.of("DDHHMM", "DDHHMMZ", "DDHHMML", "MM-DD-YYYY").contains(mask);
    }
    public static String answer(String definition, String identifier, String callsign, String answer) {
        var fields = definition(definition).fields();
        String[] values = parts(answer);
        if (values.length != fields.size()) throw invalid("Expected " + fields.size() + " strip fields");
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(" ")) continue;
            if (!valid(fields.get(i).mask(), values[i]))
                throw invalid("Invalid field " + (i + 1) + ": " + fields.get(i).label());
        }
        wire("A " + identifier + " " + answer);
        wire(identifier + " " + callsign + " " + answer);
        return answer;
    }
    private static boolean valid(String mask, String value) {
        if (value.isBlank()) return false;
        if (mask.equals("X")) return true;
        if (mask.equals("Y,N")) return value.matches("[YN]");
        if (mask.matches("N+")) return value.matches("[0-9]{1," + mask.length() + "}");
        if (mask.matches("A+")) return value.matches("[A-Z]{" + mask.length() + "}");
        if (mask.matches("-?#+(\\.#+)?")) {
            String pattern = mask.replace(".", "\\.").replace("#", "[0-9]");
            if (pattern.startsWith("-")) pattern = "-?" + pattern.substring(1);
            return value.matches(pattern);
        }
        if (mask.startsWith("DDHHMM")) {
            String suffix = mask.substring(6);
            if (!value.matches("[0-9]{6}" + suffix)) return false;
            int day = Integer.parseInt(value.substring(0, 2));
            int hour = Integer.parseInt(value.substring(2, 4));
            int minute = Integer.parseInt(value.substring(4, 6));
            return day >= 1 && day <= 31 && hour <= 23 && minute <= 59;
        }
        try {
            return value.matches("[0-9]{2}-[0-9]{2}-[0-9]{4}")
                    && LocalDate.parse(value, DateTimeFormatter.ofPattern("MM-dd-uuuu")
                    .withResolverStyle(ResolverStyle.STRICT)).getYear() > 0;
        } catch (RuntimeException ignored) { return false; }
    }
}
