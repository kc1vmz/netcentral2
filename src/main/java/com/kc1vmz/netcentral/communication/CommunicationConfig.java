package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record CommunicationConfig(String id, long version, String name, String type, boolean enabled,
        boolean transmitEnabled, String host, Integer port, String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String passcode, String filter,
        String serialDevice, Integer baudRate, String initCommand1, String initCommand2,
        String digiPath, Integer kissPort, @JsonDeserialize(using = TransmitSpacingDeserializer.class) Integer transmitSpacingMs) {
    public CommunicationConfig(String id, long version, String name, String type, boolean enabled,
            boolean transmitEnabled, String host, Integer port, String username, String passcode, String filter,
            String serialDevice, Integer baudRate, String initCommand1, String initCommand2,
            String digiPath, Integer kissPort) {
        this(id, version, name, type, enabled, transmitEnabled, host, port, username, passcode, filter,
                serialDevice, baudRate, initCommand1, initCommand2, digiPath, kissPort, 1000);
    }
    public CommunicationConfig {
        if (transmitSpacingMs == null) transmitSpacingMs = 1000;
        if (transmitSpacingMs < 0) throw new IllegalArgumentException("Transmit spacing must be nonnegative");
        // Keep the API's name property, but derive it for both new and stored configurations.
        name = type == null ? "" : switch (type) {
            case "APRS_IS" -> "APRS-IS: " + host + ":" + port + " (" + username + ")";
            case "KISS_TCP" -> "KISS TCP: " + host + ":" + port;
            case "TNC2_TCP" -> "TNC2_TCP: " + host + ":" + port;
            case "TNC2_SERIAL" -> "TNC2_SERIAL: " + serialDevice + " (" + baudRate + ")";
            case "KENWOOD_SERIAL" -> "Kenwood Serial: " + serialDevice + " (" + baudRate + ", " + username + ")";
            case "KISS_SERIAL" -> "KISS Serial: " + serialDevice + " (" + baudRate + ")";
            default -> type;
        };
    }

    public boolean serial() {
        return "KISS_SERIAL".equals(type) || "TNC2_SERIAL".equals(type) || "KENWOOD_SERIAL".equals(type);
    }

    public CommunicationConfig device(String resolvedDevice) {
        return new CommunicationConfig(id, version, name, type, enabled, transmitEnabled, host, port, username,
                passcode, filter, resolvedDevice, baudRate, initCommand1, initCommand2, digiPath, kissPort, transmitSpacingMs);
    }

    public CommunicationConfig identity(String id, long version, String secret) {
        return new CommunicationConfig(id, version, name, type, enabled, transmitEnabled, host, port, username,
                secret, filter, serialDevice, baudRate, initCommand1, initCommand2, digiPath, kissPort, transmitSpacingMs);
    }

    @Override
    public String toString() {
        return "CommunicationConfig[" + id + ", " + type + "]";
    }
}
