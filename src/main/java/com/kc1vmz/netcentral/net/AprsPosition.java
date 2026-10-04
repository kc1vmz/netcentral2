package com.kc1vmz.netcentral.net;

/**
 * Position extraction for standard uncompressed and base-91 compressed APRS
 * position reports.
 */
public final class AprsPosition {
    private AprsPosition() {
    }

    public record Position(double latitude, double longitude, int ambiguity, double uncertaintyMeters,
            String rawLatitude, String rawLongitude) {
        public Position(double latitude, double longitude) {
            this(latitude, longitude, 0, 0, null, null);
        }
        public void apply(com.fasterxml.jackson.databind.node.ObjectNode data) {
            data.put("lat", latitude); data.put("lon", longitude);
            data.put("positionAmbiguity", ambiguity);
            data.put("approximate", ambiguity > 0);
            data.put("uncertaintyMeters", uncertaintyMeters);
            if (rawLatitude == null) data.remove("rawLatitude"); else data.put("rawLatitude", rawLatitude);
            if (rawLongitude == null) data.remove("rawLongitude"); else data.put("rawLongitude", rawLongitude);
        }
    }

    public static Position parse(String information) {
        if (information == null || information.isEmpty())
            return null;
        int start = switch (information.charAt(0)) {
            case '!', '=' -> 1;
            case '/', '@' -> 8;
            default -> -1;
        };
        if (start < 0 || information.length() <= start)
            return null;
        String value = information.substring(start);
        try {
            double lat, lon;
            if (value.length() >= 19 && Character.isDigit(value.charAt(0))) {
                String rawLat = value.substring(0, 8), rawLon = value.substring(9, 18);
                int ambiguity = ambiguity(rawLat, 2, "NS");
                int lonAmbiguity = ambiguity(rawLon, 3, "EW");
                if (lonAmbiguity > ambiguity) return null;
                lat = degrees(rawLat, 2, ambiguity);
                lon = degrees(rawLon, 3, ambiguity);
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
                // Enclose the reported latitude/longitude cell, not an invented exact point.
                double spanMinutes = new double[]{0, .1, 1, 10, 60}[ambiguity];
                double radius = spanMinutes * 1852 / 2 * Math.sqrt(2);
                return new Position(lat, lon, ambiguity, radius, rawLat, rawLon);
            } else if (value.length() >= 13 && (value.charAt(0) == '/' || value.charAt(0) == '\\'
                    || Character.isLetter(value.charAt(0)))) {
                lat = 90 - base91(value.substring(1, 5)) / 380926.0;
                lon = -180 + base91(value.substring(5, 9)) / 190463.0;
            } else
                return null;
            return Double.isFinite(lat) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? new Position(lat, lon) : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static int ambiguity(String raw, int degrees, String hemispheres) {
        if (!raw.substring(0, degrees).matches("[0-9]+") || raw.charAt(degrees + 2) != '.'
                || hemispheres.indexOf(raw.charAt(degrees + 5)) < 0) throw new IllegalArgumentException();
        String digits = raw.substring(degrees, degrees + 2) + raw.substring(degrees + 3, degrees + 5);
        if (!digits.matches("[0-9]* *")) throw new IllegalArgumentException();
        int blanks = digits.length() - digits.stripTrailing().length();
        if (digits.charAt(0) != ' ' && digits.charAt(0) > '5') throw new IllegalArgumentException();
        return blanks;
    }

    private static double degrees(String raw, int count, int ambiguity) {
        String digits = raw.substring(count, count + 2) + raw.substring(count + 3, count + 5);
        String known = digits.substring(0, 4 - ambiguity);
        double minutes = Integer.parseInt((known + "0000").substring(0, 4)) / 100.0;
        minutes += new double[]{0, .05, .5, 5, 30}[ambiguity];
        double value = Integer.parseInt(raw.substring(0, count)) + minutes / 60;
        return "SW".indexOf(raw.charAt(count + 5)) >= 0 ? -value : value;
    }

    private static int base91(String value) {
        int result = 0;
        for (char c : value.toCharArray()) {
            if (c < 33 || c > 123)
                throw new IllegalArgumentException();
            result = result * 91 + c - 33;
        }
        return result;
    }
}
