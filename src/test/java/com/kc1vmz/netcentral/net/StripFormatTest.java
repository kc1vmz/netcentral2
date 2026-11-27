package com.kc1vmz.netcentral.net;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class StripFormatTest {
    private String answer(String mask, String value) {
        return StripFormat.answer("TEST/Value("+mask+")//", "S1", "N1ABC", value+"//");
    }
    @Test void masksAndEmptyValues() {
        for(String value : new String[]{"6","24"}) assertThat(answer("NN",value)).isEqualTo(value+"//");
        assertThat(answer("-##.#","-12.3")).isEqualTo("-12.3//");
        assertThat(answer("-##.#","12.3")).isEqualTo("12.3//");
        assertThat(answer("####.#","1013.2")).isEqualTo("1013.2//");
        assertThat(answer("AA","MA")).isEqualTo("MA//");
        assertThat(answer("Y,N","Y")).isEqualTo("Y//");
        assertThat(answer("X","N1ABC-7, City Hall!")).isEqualTo("N1ABC-7, City Hall!//");
        assertThat(answer("NN"," ")).isEqualTo(" //");
        assertThat(StripFormat.answer("T/A(X)/B(NN)//","S1","N1ABC"," / //")).isEqualTo(" / //");
        assertThatThrownBy(()->answer("NN","123")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("###","12")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("AA","A")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("Y,N","Yes")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("X","")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("X","a/b")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void datesAreValidated() {
        for(String mask : new String[]{"DDHHMM","DDHHMMZ","DDHHMML"})
            assertThat(answer(mask,"271630"+mask.substring(6))).startsWith("271630");
        assertThat(answer("MM-DD-YYYY","02-29-2028")).isEqualTo("02-29-2028//");
        assertThatThrownBy(()->answer("MM-DD-YYYY","02-29-2027")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("DDHHMM","002460")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("DDHHMMZ","271630L")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void noTruncationOrUnsupportedDefinitions() {
        assertThatThrownBy(()->StripFormat.definition("T/A(BOGUS)//")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->StripFormat.definition("T/A(X)/")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->StripFormat.definition("T/A(X)//\n")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("X","x".repeat(60))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->answer("X","bad|text")).isInstanceOf(ResponseStatusException.class);
        assertThat(answer("X","x".repeat(55))).hasSize(57);
    }
}
