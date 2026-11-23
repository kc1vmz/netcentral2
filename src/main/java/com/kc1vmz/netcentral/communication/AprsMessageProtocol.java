package com.kc1vmz.netcentral.communication;

import java.util.regex.Pattern;

/** Message-only parsing; never trim binary position payloads. */
public final class AprsMessageProtocol {
    private static final Pattern SUFFIX = Pattern.compile("\\{([A-Za-z0-9]{1,5})(?:}([A-Za-z0-9]{0,5}))?$");
    private static final Pattern CONTROL = Pattern.compile("(?i)^(ack|rej)([A-Za-z0-9]{1,5})(?:}[A-Za-z0-9]{0,5})?$");
    private AprsMessageProtocol() {}
    public record Message(String body, String number, String replyAck) {}
    public record Control(String kind, String number) {}
    public static String normalize(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end-1) == '\r' || text.charAt(end-1) == '\n')) end--;
        return text.substring(0,end);
    }
    public static Message parse(String text) {
        text = normalize(text);
        var match = SUFFIX.matcher(text);
        return match.find() ? new Message(text.substring(0,match.start()),match.group(1),match.group(2))
                : new Message(text,null,null);
    }
    public static Control control(String text) {
        var match = CONTROL.matcher(normalize(text));
        return match.matches() ? new Control(match.group(1),match.group(2)) : null;
    }
}
