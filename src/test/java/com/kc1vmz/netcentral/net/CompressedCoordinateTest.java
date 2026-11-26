package com.kc1vmz.netcentral.net;

import static org.assertj.core.api.Assertions.*;
import com.kc1vmz.netcentral.aprsobject.utils.CompressedDataFormatUtils;
import com.kc1vmz.netcentral.parser.factory.APRSPositionFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class CompressedCoordinateTest {
    @Test void exportedPositionUsesDecimalMinutes() throws Exception {
        String body = "!/9-_]9/fp-  CGraywolf/0.14.14 with XARA on 7083.5";
        var result = APRSPositionFactory.parse("KF8HRS>APGRWO,WIDE1-1,WIDE2-1".getBytes(StandardCharsets.US_ASCII), body.getBytes(StandardCharsets.US_ASCII));
        assertThat(result.getLat()).isEqualTo("4214.75N");
        assertThat(result.getLon()).isEqualTo("08424.08W");
        var modern = AprsPosition.parse(body);
        assertThat(modern.latitude()).isCloseTo(42.2459007786, within(.00000001));
        assertThat(modern.longitude()).isCloseTo(-84.4014007970, within(.00000001));
    }

    @Test void paddingPrecisionRoundingAndLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertThat(CompressedDataFormatUtils.formatLatitude(42.00130209017)).isEqualTo("4200.08N");
            assertThat(CompressedDataFormatUtils.formatLatitude(-42.00130209017)).isEqualTo("4200.08S");
            assertThat(CompressedDataFormatUtils.formatLongitude(84.401400797)).isEqualTo("08424.08E");
            assertThat(CompressedDataFormatUtils.formatLongitude(-84.401400797)).isEqualTo("08424.08W");
            assertThat(CompressedDataFormatUtils.formatLatitude(42.999999)).isEqualTo("4300.00N");
            assertThat(CompressedDataFormatUtils.formatLongitude(-84.999999)).isEqualTo("08500.00W");
            assertThat(CompressedDataFormatUtils.formatLatitude(0)).isEqualTo("0000.00N");
            assertThat(CompressedDataFormatUtils.formatLatitude(91)).isNull();
            assertThat(CompressedDataFormatUtils.formatLongitude(Double.NaN)).isNull();
        } finally { Locale.setDefault(previous); }
    }
}
