package com.kc1vmz.netcentral.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import com.kc1vmz.netcentral.accessor.NetAccessor;

@Service
public class NetReport {
    private final NetAccessor nets;

    public NetReport(NetAccessor nets) {
        this.nets = nets;
    }

    public byte[] report(String id, boolean messages) throws IOException {
        var snapshot = nets.snapshot(id); // Enforces the same authorization as the monitor.
        if (!messages)
            return new NetParticipantReport().render(snapshot);
        return new NetMessageReport().render(snapshot.net(), nets.messages(id));
    }

    public byte[] render(String title, List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument();
                var fontStream = PDDocument.class
                        .getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDType0Font font = PDType0Font.load(document, fontStream);
            List<String> wrapped = new ArrayList<>();
            for (String line : lines) {
                String safe = safe(font, line);
                if (safe.isEmpty()) {
                    wrapped.add("");
                    continue;
                }
                StringBuilder current = new StringBuilder();
                for (int offset = 0; offset < safe.length();) {
                    int code = safe.codePointAt(offset);
                    String next = new String(Character.toChars(code));
                    if (font.getStringWidth(current + next) / 1000 * 11 > 504) {
                        wrapped.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(next);
                    offset += Character.charCount(code);
                }
                if (!current.isEmpty())
                    wrapped.add(current.toString());
            }
            List<List<String>> pageLines = new ArrayList<>();
            for (int from = 0; from < wrapped.size();) {
                int to = Math.min(from + 44, wrapped.size());
                if (to < wrapped.size() && !wrapped.get(to - 1).isBlank()) {
                    int boundary = to - 1;
                    while (boundary > from && !wrapped.get(boundary).isBlank())
                        boundary--;
                    if (boundary > from)
                        to = boundary + 1;
                }
                pageLines.add(wrapped.subList(from, to));
                from = to;
            }
            int pages = pageLines.size();
            for (int page = 0; page < pages; page++) {
                PDPage sheet = new PDPage(PDRectangle.LETTER);
                document.addPage(sheet);
                try (PDPageContentStream stream = new PDPageContentStream(document, sheet)) {
                    stream.beginText();
                    stream.setFont(font, 11);
                    stream.setLeading(15);
                    stream.newLineAtOffset(54, 730);
                    for (String line : pageLines.get(page)) {
                        stream.showText(line);
                        stream.newLine();
                    }
                    stream.endText();
                    stream.beginText();
                    stream.setFont(font, 9);
                    stream.newLineAtOffset(54, 36);
                    stream.showText("Net Central | " + title + " | Page " + (page + 1) + " of " + pages);
                    stream.endText();
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private String safe(PDType0Font font, String input) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int code : input.codePoints().toArray()) {
            String character = new String(Character.toChars(code));
            try {
                font.encode(character);
                result.append(character);
            } catch (IllegalArgumentException unsupported) {
                result.append('?');
            }
        }
        return result.toString();
    }
}
