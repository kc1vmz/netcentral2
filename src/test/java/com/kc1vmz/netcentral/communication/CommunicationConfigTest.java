package com.kc1vmz.netcentral.communication;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommunicationConfigTest {
    @Test
    void namesMatchWelcomeCenterAndIgnoreProvidedNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String[][] cases = {
            {"APRS_IS", "APRS-IS: localhost:8001 (N0CALL)"},
            {"KISS_TCP", "KISS TCP: localhost:8001"},
            {"TNC2_TCP", "TNC2_TCP: localhost:8001"},
            {"KISS_SERIAL", "KISS Serial: COM9 (9600)"},
            {"TNC2_SERIAL", "TNC2_SERIAL: COM9 (9600)"}
        };
        for (String[] example : cases) {
            CommunicationConfig config = new CommunicationConfig("id", 0, "Old custom name", example[0], false,
                    false, "localhost", 8001, "N0CALL", "-1", null, "COM9", 9600, null, null, null, 0);
            assertThat(config.name()).isEqualTo(example[1]);
            String stored = mapper.writeValueAsString(config).replace(example[1], "Old custom name");
            assertThat(mapper.readValue(stored, CommunicationConfig.class).name()).isEqualTo(example[1]);
            assertThat(mapper.readTree(mapper.writeValueAsString(config)).get("name").asText()).isEqualTo(example[1]);
        }
        CommunicationConfig serial = new CommunicationConfig("id", 0, null, "TNC2_SERIAL", false,
                false, null, null, null, null, null, "COM9", 9600, null, null, null, null);
        assertThat(serial.device("COM10").name()).isEqualTo("TNC2_SERIAL: COM10 (9600)");
    }
}
