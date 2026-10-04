package com.kc1vmz.netcentral.net;

import java.time.Instant;
import java.util.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.assertj.core.api.Assertions.assertThat;

class NetParticipantReportTest {
    private NetModels.Snapshot fixture(int count, Set<String> expected) {
        var start = Instant.parse("2026-10-02T14:00:00Z");
        var end = start.plusSeconds(3600);
        var net = new NetModels.Net("id", "NET001", "County emergency exercise", "Exercise", "145.250", "owner",
                "John Operator", false, false, start, end, 1, count, "id");
        List<NetModels.Participant> participants = new ArrayList<>();
        for (int i = 0; i < count; i++) participants.add(new NetModels.Participant(String.format("N%03dAA-1", i), "Ready", "145.250", "Team " + i,
                start, end, end, null, null, 1, "50", "BATTERY", "", "MOBILE"));
        return new NetModels.Snapshot(net, participants, List.of(), null, expected, null, false, List.of(), List.of());
    }

    @Test
    void includesAttendanceEquipmentAndExpectedStationsWithCorrectPagination() throws Exception {
        var report = new NetParticipantReport();
        for (int count : List.of(0, 25, 26)) {
            try (var pdf = Loader.loadPDF(report.render(fixture(count, Set.of())))) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(count == 26 ? 2 : 1);
            }
        }
        byte[] bytes = report.render(fixture(25, Set.of("N000AA", "ZZ9ZZ")));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("COMMUNICATIONS LOG", "OPERATIONAL PERIOD", "County emergency exercise",
                    "John Operator", "NET001", "CALLSIGN", "START", "END", "DETAILS", "BATTERY:MOBILE:50W",
                    "ZZ9ZZ", "Did not participate", "ICS 309 Form", "Page 2 of 2");
            assertThat(text.split("Did not participate", -1)).hasSize(2);
            Files.createDirectories(Path.of("target/ics309-preview"));
            Files.write(Path.of("target/ics309-preview/report.pdf"), bytes);
            var renderer = new PDFRenderer(pdf);
            for (int i = 0; i < pdf.getNumberOfPages(); i++)
                ImageIO.write(renderer.renderImageWithDPI(i, 110), "png", Path.of("target/ics309-preview/page-" + (i+1) + ".png").toFile());
        }
    }
}
