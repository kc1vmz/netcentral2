package com.kc1vmz.netcentral.communication;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public final class KissCodec {
    private KissCodec() {
    }

    public static byte[] encode(Packet packet, int port) {
        if (port < 0 || port > 15)
            throw new IllegalArgumentException("Invalid KISS port");
        ByteArrayOutputStream ax = new ByteArrayOutputStream();
        List<String> addresses = new ArrayList<>();
        addresses.add(packet.destination());
        addresses.add(packet.source());
        addresses.addAll(packet.path());
        for (int i = 0; i < addresses.size(); i++) {
            String value = addresses.get(i).toUpperCase(Locale.ROOT);
            boolean repeated = value.endsWith("*");
            if (repeated)
                value = value.substring(0, value.length() - 1);
            validateAddress(value);
            String[] parts = value.split("-");
            String root = parts[0];
            int ssid = parts.length == 1 ? 0 : Integer.parseInt(parts[1]);
            for (int n = 0; n < 6; n++)
                ax.write((n < root.length() ? root.charAt(n) : ' ') << 1);
            ax.write(0x60 | (ssid << 1) | (i == addresses.size() - 1 ? 1 : 0)
                    | (i == 0 || (i > 1 && repeated) ? 0x80 : 0));
        }
        ax.write(3);
        ax.write(0xf0);
        ax.writeBytes(packet.information().getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0xc0);
        escape(frame, port << 4);
        for (byte b : ax.toByteArray())
            escape(frame, b & 255);
        frame.write(0xc0);
        return frame.toByteArray();
    }

    public static void validateAddress(String value) {
        if (!value.matches("[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?"))
            throw new IllegalArgumentException("Invalid AX.25 address");
    }

    private static void escape(ByteArrayOutputStream out, int value) {
        if (value == 0xc0) {
            out.write(0xdb);
            out.write(0xdc);
        } else if (value == 0xdb) {
            out.write(0xdb);
            out.write(0xdd);
        } else
            out.write(value);
    }

    public static Packet decode(byte[] data, int port) {
        if (data.length < 18 || (data[0] & 255) != port << 4)
            throw new IllegalArgumentException("Not a KISS data frame for this port");
        List<String> addresses = new ArrayList<>();
        int offset = 1;
        boolean last = false;
        while (!last && addresses.size() < 10) {
            if (offset + 7 > data.length)
                throw new IllegalArgumentException("Truncated address");
            StringBuilder call = new StringBuilder();
            for (int n = 0; n < 6; n++) {
                if ((data[offset + n] & 1) != 0)
                    throw new IllegalArgumentException("Invalid shifted address");
                char c = (char) ((data[offset + n] & 255) >> 1);
                if (c != ' ')
                    call.append(c);
            }
            int flags = data[offset + 6] & 255;
            int ssid = (flags >> 1) & 15;
            String address = call + (ssid == 0 ? "" : "-" + ssid);
            validateAddress(address);
            if (addresses.size() > 1 && (flags & 128) != 0)
                address += "*";
            addresses.add(address);
            last = (flags & 1) != 0;
            offset += 7;
        }
        if (!last || addresses.size() < 2 || offset + 2 >= data.length
                || data[offset++] != 3 || (data[offset++] & 255) != 0xf0) {
            throw new IllegalArgumentException("Not an APRS AX.25 UI frame");
        }
        return new Packet(addresses.get(1), addresses.get(0), addresses.subList(2, addresses.size()),
                new String(data, offset, data.length - offset, StandardCharsets.ISO_8859_1));
    }

    /**
     * Incremental decoder retains state across read timeouts; malformed/oversize
     * frames are discarded.
     */
    public static final class Decoder {
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private boolean escaped;
        private boolean discard = true;

        public byte[] accept(int value) {
            if (value == 0xc0) {
                byte[] result = !discard && !escaped && frame.size() > 0 ? frame.toByteArray() : null;
                frame.reset();
                escaped = false;
                discard = false;
                return result;
            }
            if (discard)
                return null;
            if (escaped) {
                escaped = false;
                if (value == 0xdc)
                    value = 0xc0;
                else if (value == 0xdd)
                    value = 0xdb;
                else {
                    discard = true;
                    return null;
                }
            } else if (value == 0xdb) {
                escaped = true;
                return null;
            }
            if (frame.size() >= 1024) {
                discard = true;
                frame.reset();
            } else
                frame.write(value);
            return null;
        }
    }
}
