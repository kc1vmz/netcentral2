package com.kc1vmz.netcentral.operations;

import com.kc1vmz.netcentral.aprsobject.object.APRSWeatherReport;
import com.kc1vmz.netcentral.communication.Packet;
import com.kc1vmz.netcentral.parser.util.APRSTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WeatherParsingTest {
    private APRSWeatherReport weather(String information) {
        return (APRSWeatherReport) PacketDecoder.decode(Packet.parse("KC1TLF-15>APRS:" + information));
    }

    @Test void validPositionlessAndMalformedRain() {
        var valid = weather("_11270853c330s001g001t043r000P026h99b10107L038wNA");
        assertThat(valid.getTemperature()).isEqualTo(43);
        assertThat(valid.getRainfallSinceMidnight()).isEqualTo(26);
        assertThat(valid.getRainfallLast1Hr()).isZero();
        assertThat(valid.getRainfallLast24Hr()).isNull();
        assertThat(valid.getLuminosity()).isEqualTo(38);
        var broken = weather("_11270122c330s001g001t040r000P14.000000000000002h99b10163L000wNA");
        assertThat(broken.getRainfallSinceMidnight()).isNull();
        assertThat(broken.getTemperature()).isEqualTo(40);
        assertThat(broken.getHumidity()).isEqualTo(99);
        assertThat(broken.getBarometricPressure()).isEqualTo(10163);
        assertThat(broken.getLuminosity()).isZero();
    }

    @Test void shortPressureDoesNotConsumeSuffix() {
        String prefix = "@271628z4133.95N/07129.60W_219/005g011t059r000P001p205h70";
        var broken = weather(prefix + "b9998wU2100 {UIV32N}t999L999");
        assertThat(broken.getBarometricPressure()).isNull();
        assertThat(broken.getWindDirection()).isEqualTo(219);
        assertThat(broken.getWindSpeed()).isEqualTo(5);
        assertThat(broken.getTemperature()).isEqualTo(59);
        assertThat(broken.getRainfallLast24Hr()).isEqualTo(205);
        assertThat(broken.getHumidity()).isEqualTo(70);
        assertThat(broken.getLuminosity()).isNull();
        assertThat(weather(prefix + "b09998wU2100").getBarometricPressure()).isEqualTo(9998);
    }

    @Test void missingWindAndMeasurementsAreNotZero() {
        var report = weather("@271628z4133.95N/07129.60W_.../...g...t-05r000h00b.....");
        assertThat(report.getWindSpeed()).isNull();
        assertThat(report.getGust()).isNull();
        assertThat(report.getTemperature()).isEqualTo(-5);
        assertThat(report.getHumidity()).isEqualTo(100);
        assertThat(report.getBarometricPressure()).isNull();
        assertThat(PacketDecoder.decode(Packet.parse("WX001>APRS:@271628z4133.95N/07129.60W_WX3in1Plus2.0 U=13.9V")))
            .isNotInstanceOf(APRSWeatherReport.class);
    }

    @Test void timestampUsesNearestValidDateAndMinutePrecision() throws Exception {
        var now = ZonedDateTime.parse("2027-01-01T00:01:45.123Z");
        assertThat(APRSTime.convertAPRSTimeToZonedDateTime("12312359", now))
            .isEqualTo(ZonedDateTime.parse("2026-12-31T23:59:00Z"));
        assertThat(APRSTime.convertAPRSTimeToZonedDateTime("312359z", now))
            .isEqualTo(ZonedDateTime.parse("2026-12-31T23:59:00Z"));
    }
}
