package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AprsSymbolsTest {
    @Test void distinguishesTablesAndOverlaysAndHandlesInvalidCodes() {
        assertThat(AprsSymbols.describe("/>" )).isEqualTo("Car");
        assertThat(AprsSymbols.describe("\\>" )).isEqualTo("Vehicle");
        assertThat(AprsSymbols.describe("I&")).isEqualTo("IGate (overlay I)");
        assertThat(AprsSymbols.describe("D^")).isEqualTo("Drone (overlay D)");
        assertThat(AprsSymbols.describe("Zz")).isEqualTo("Shelter (overlay Z)");
        assertThat(AprsSymbols.describe("/o")).isEqualTo("Emergency operations center");
        assertThat(AprsSymbols.describe("/O")).isNotEqualTo(AprsSymbols.describe("/o"));
        assertThat(AprsSymbols.describe(null)).isEqualTo("Not specified");
        assertThat(AprsSymbols.describe("")).isEqualTo("Not specified");
        for (String invalid : new String[]{"/", "/>>", "?z", "/ "})
            assertThat(AprsSymbols.describe(invalid)).isEqualTo("Unknown APRS symbol");
        for (char code='!';code<='~';code++) {
            assertThat(AprsSymbols.describe("/"+code)).isNotEqualTo("Unknown APRS symbol");
            assertThat(AprsSymbols.describe("\\"+code)).isNotEqualTo("Unknown APRS symbol");
        }
    }
    @Test void acceptsSplitObjectSymbolsAndDecoratesOnlyDisplayCopy() {
        var data = new ObjectMapper().createObjectNode().put("symbolTableId", "\\").put("symbolTableCode", "z");
        var copy=data.deepCopy();
        AprsSymbols.decorate(copy);
        assertThat(copy.path("symbol").asText()).isEqualTo("\\z");
        assertThat(copy.path("symbolDescription").asText()).isEqualTo("Shelter");
        assertThat(data.has("symbolDescription")).isFalse();
    }
}
