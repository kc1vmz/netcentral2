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

class NetMessageReportTest {
    @Test
    void excludesProtocolAcknowledgmentsAndRejectionsButRetainsOrdinaryMessages() throws Exception {
        var start = Instant.parse("2026-10-02T14:00:00Z");
        var net = new NetModels.Net("id", "NET001", "Exercise", "Exercise", "145.250", "owner",
                "Operator", false, false, start, start.plusSeconds(3600), 1, 0, "id");
        var messages = new ArrayList<NetModels.Message>();
        for (String body : List.of("ack123", "rejA12", "ACK9", " rej42 ", "Acknowledged delivery", "reject damaged supplies", "Supplies ready"))
            messages.add(new NetModels.Message(UUID.randomUUID().toString(), "id", "N1ABC", "NET001", body,
                    start, "INBOUND", "RECEIVED", null));
        try (var pdf = Loader.loadPDF(new NetMessageReport().render(net, messages))) {
            assertThat(new PDFTextStripper().getText(pdf)).doesNotContain("ack123", "rejA12", "ACK9", "rej42")
                    .contains("Acknowledged delivery", "reject damaged supplies", "Supplies ready");
        }
    }

    @Test
    void generatesOriginalLayoutWithCompleteMessagesAndPagination() throws Exception {
        var start = Instant.parse("2026-10-02T14:00:00Z");
        var net = new NetModels.Net("id", "NET001", "County emergency exercise", "Exercise", "145.250", "owner",
                "John Operator", false, false, start, start.plusSeconds(3600), 1, 0, "id");
        var report = new NetMessageReport();
        List<NetModels.Message> messages = new ArrayList<>();
        for (int count : List.of(0, 25, 26)) {
            messages.clear();
            for (int i = 0; i < count; i++) messages.add(new NetModels.Message("m"+i, "id", "N1ABC", "NET001", "Report " + i,
                    start.plusSeconds(i), "INBOUND", "RECEIVED", null));
            try (var pdf = Loader.loadPDF(report.render(net, messages))) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(count == 26 ? 2 : 1);
                String text = new PDFTextStripper().getText(pdf);
                assertThat(text).contains("MESSAGE LOG", "OPERATIONAL PERIOD", "John Operator", "TASK NAME", "TIME", "FROM", "TO", "MESSAGE", "Net Message Report");
                if (count > 0) assertThat(text).contains("Report 0", "Report " + (count-1));
            }
        }
        messages.add(new NetModels.Message("long", "id", "N2ABC-15", "NET001", "Supplies required: water, batteries, blankets. ".repeat(12) + "END OF MESSAGE",
                start.plusSeconds(50), "INBOUND", "RECEIVED", null));
        byte[] bytes = report.render(net, messages);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("END OF MESSAGE");
            Files.createDirectories(Path.of("target/message-report-preview"));
            Files.write(Path.of("target/message-report-preview/report.pdf"), bytes);
            var renderer = new PDFRenderer(pdf);
            for (int i = 0; i < pdf.getNumberOfPages(); i++)
                ImageIO.write(renderer.renderImageWithDPI(i, 110), "png", Path.of("target/message-report-preview/page-"+(i+1)+".png").toFile());
        }
    }
}
