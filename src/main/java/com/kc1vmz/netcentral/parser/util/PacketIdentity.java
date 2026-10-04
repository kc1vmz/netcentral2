package com.kc1vmz.netcentral.parser.util;
import java.nio.charset.StandardCharsets;
/** Internal identity envelope for the retained pure APRS decoders; not a transport frame. */
public final class PacketIdentity {
    private PacketIdentity() {}
    public static byte[] of(String from,String to) { return (from+":"+to).getBytes(StandardCharsets.US_ASCII); }
    public static String getCallsignFrom(byte[] data) { return new String(data,StandardCharsets.US_ASCII).split(":",2)[0]; }
    public static String getCallsignTo(byte[] data) { return new String(data,StandardCharsets.US_ASCII).split(":",2)[1]; }
}
