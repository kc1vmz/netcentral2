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

class NetQuestionReportTest {
    private static final Instant START = Instant.parse("2026-10-02T14:00:00Z");
    private NetModels.Snapshot fixture(List<NetQuestionModels.Question> questions, List<NetQuestionModels.Answer> answers) {
        var net = new NetModels.Net("id", "NET001", "County emergency exercise", "Exercise", "145.250", "owner",
                "John Operator", false, false, START, START.plusSeconds(3600), 1, 0, "id");
        return new NetModels.Snapshot(net, List.of(), List.of(), null, Set.of(), null, false, questions, answers);
    }
    @Test
    void groupsQuestionsAndAnswersAndPaginatesWithoutLosingText() throws Exception {
        var report = new NetQuestionReport();
        var question = new NetQuestionModels.Question("q1", 1, "NET001", "Are you ready?", START);
        for (int count : List.of(0, 25, 26)) {
            List<NetQuestionModels.Answer> answers = new ArrayList<>();
            for (int i=1; i<count; i++) answers.add(new NetQuestionModels.Answer("q1", "N1ABC", "Answer " + i, START.plusSeconds(i)));
            try (var pdf = Loader.loadPDF(report.render(fixture(count == 0 ? List.of() : List.of(question), answers)))) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(count == 26 ? 2 : 1);
                assertThat(new PDFTextStripper().getText(pdf)).contains("Q & A LOG", "Q/A", "TIME", "FROM", "TEXT", "John Operator", "Net Q&A Report");
            }
        }
        var unanswered = new NetQuestionModels.Question("q2", 2, "NET001", "Any remaining requirements?", START.plusSeconds(30));
        List<NetQuestionModels.Answer> answers = new ArrayList<>();
        for(int i=0;i<24;i++) answers.add(new NetQuestionModels.Answer("q1", "N1ABC", "Ready " + i, START.plusSeconds(i+1)));
        answers.add(new NetQuestionModels.Answer("q1", "N2ABC", "We need water and batteries. ".repeat(16) + "END OF ANSWER", START.plusSeconds(29)));
        Collections.reverse(answers);
        byte[] bytes = report.render(fixture(List.of(unanswered, question), answers));
        try(var pdf=Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("No answers provided", "END OF ANSWER", "Q1", "A1", "Q2", "A2");
            assertThat(text.indexOf("Are you ready?")).isLessThan(text.indexOf("Ready 0"));
            assertThat(text.indexOf("Ready 0")).isLessThan(text.indexOf("Ready 23"));
            assertThat(text.indexOf("END OF ANSWER")).isLessThan(text.indexOf("Any remaining requirements?"));
            Files.createDirectories(Path.of("target/qa-report-preview"));
            Files.write(Path.of("target/qa-report-preview/report.pdf"),bytes);
            var renderer=new PDFRenderer(pdf);
            for(int i=0;i<pdf.getNumberOfPages();i++) ImageIO.write(renderer.renderImageWithDPI(i,110),"png",Path.of("target/qa-report-preview/page-"+(i+1)+".png").toFile());
        }
    }
}
