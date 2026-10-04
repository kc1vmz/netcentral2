package com.kc1vmz.netcentral.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RadioPolicyTest {
    @ParameterizedTest
    @CsvSource({"CONTAINS, Local bBs service, BbS", "EQUALS, bBs, BbS",
            "STARTS_WITH, bBs service, BbS", "ENDS_WITH, Local bBs, BbS"})
    void matchesMixedCaseStationAndStoredRuleValues(String comparison, String actual, String expected) {
        var mapper = new ObjectMapper();
        var store = mock(OperationsStore.class);
        var rule = mapper.createObjectNode().put("ruleTarget", "STATUS").put("ruleType", comparison)
                .put("value", expected).put("trackedStationType", "BBS");
        when(store.list("CLASSIFY")).thenReturn(List.of(
                new OperationsStore.Entry("rule", "CLASSIFY", "owner", 1, Instant.now(), rule)));
        var policy = new RadioPolicy(store);
        assertThat(policy.classify(mapper.createObjectNode().put("status", actual))).contains("BBS");
        assertThat(policy.classify(mapper.createObjectNode().put("status", "Unrelated"))).doesNotContain("BBS");
    }
}
