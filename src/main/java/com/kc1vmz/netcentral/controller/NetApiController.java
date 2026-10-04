package com.kc1vmz.netcentral.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kc1vmz.netcentral.accessor.NetAccessor;
import com.kc1vmz.netcentral.account.LocalPrincipal;
import com.kc1vmz.netcentral.net.NetEventStream;
import com.kc1vmz.netcentral.net.NetReport;
import com.kc1vmz.netcentral.net.NetModels.*;

@RestController
@RequestMapping("/api/v1")
public class NetApiController {
    private final NetAccessor nets;
    private final NetEventStream events;
    private final NetReport reports;

    public NetApiController(NetAccessor nets, NetEventStream events, NetReport reports) {
        this.nets = nets;
        this.events = events;
        this.reports = reports;
    }

    @GetMapping("/nets")
    public List<Net> list() {
        return nets.list(false);
    }

    @PostMapping("/nets")
    public Net create(@RequestBody NetEdit request) {
        return nets.create(request);
    }

    @GetMapping("/nets/{id}")
    public Net get(@PathVariable String id) {
        return nets.get(id);
    }

    @PutMapping("/nets/{id}")
    public Net edit(@PathVariable String id, @RequestBody NetEdit request) {
        return nets.update(id, request);
    }

    @DeleteMapping("/nets/{id}")
    public void close(@PathVariable String id, @RequestParam long version) {
        nets.close(id, version);
    }

    @GetMapping("/nets/{id}/participants")
    public List<Participant> participants(@PathVariable String id) {
        return nets.participants(id, false);
    }

    @PostMapping("/nets/{id}/participants/{callsign}")
    public List<Participant> checkIn(@PathVariable String id, @PathVariable String callsign) {
        return nets.checkIn(id, callsign);
    }

    @DeleteMapping("/nets/{id}/participants/{callsign}")
    public List<Participant> checkOut(@PathVariable String id, @PathVariable String callsign) {
        return nets.checkOut(id, callsign);
    }

    @PutMapping("/nets/{id}/participants/{callsign}")
    public Participant participant(@PathVariable String id, @PathVariable String callsign,
            @RequestBody ParticipantEdit edit) {
        return nets.participant(id, callsign, edit);
    }

    @GetMapping("/nets/{id}/expectedParticipants")
    public Set<String> expected(@PathVariable String id) {
        return nets.expected(id);
    }

    @GetMapping("/completedNets/{id}/expectedParticipants")
    public Set<String> archivedExpected(@PathVariable String id) {
        archived(id);
        return nets.expected(id);
    }

    @PostMapping("/nets/{id}/expectedParticipants/{call}")
    public Set<String> expectedAdd(@PathVariable String id, @PathVariable String call, @RequestParam long version) {
        var values = new java.util.HashSet<>(nets.expected(id));
        values.add(call);
        return nets.expected(id, values, version);
    }

    @DeleteMapping("/nets/{id}/expectedParticipants/{call}")
    public Set<String> expectedRemove(@PathVariable String id, @PathVariable String call, @RequestParam long version) {
        var values = new java.util.HashSet<>(nets.expected(id));
        values.remove(NetAccessor.callsign(call).split("-")[0]);
        return nets.expected(id, values, version);
    }

    @PostMapping("/nets/{id}/expectedParticipants/requests")
    public Set<String> expectedReplace(@PathVariable String id, @RequestBody Set<String> values,
            @RequestParam long version) {
        return nets.expected(id, values, version);
    }

    @PostMapping("/nets/{id}/invitations")
    public void invite(@PathVariable String id, @RequestBody Set<String> callsigns) {
        nets.invite(id, callsigns);
    }

    @GetMapping({ "/nets/{id}/questions", "/completedNets/{id}/questions" })
    public Object questions(@PathVariable String id) {
        return nets.questions(id);
    }

    @PostMapping("/nets/{id}/questions")
    public Object question(@PathVariable String id,
            @RequestBody com.kc1vmz.netcentral.net.NetQuestionModels.QuestionRequest input) {
        return nets.ask(id, input.question());
    }

    @GetMapping({ "/nets/{id}/questions/{question}/answers", "/completedNets/{id}/questions/{question}/answers" })
    public Object answers(@PathVariable String id, @PathVariable String question) {
        return nets.answers(id, question);
    }

    @PostMapping("/nets/{id}/questions/{question}/answers")
    public Object answer(@PathVariable String id, @PathVariable String question,
            @RequestBody com.kc1vmz.netcentral.net.NetQuestionModels.AnswerRequest input) {
        return nets.answer(id, question, input.callsign(), input.answer());
    }

    @GetMapping("/nets/{id}/options")
    public Object options(@PathVariable String id) {
        return nets.options(id);
    }

    @PutMapping("/nets/{id}/options")
    public void options(@PathVariable String id, @RequestBody com.fasterxml.jackson.databind.JsonNode input,
            @RequestParam long version) {
        nets.options(id, input, version);
    }

    @GetMapping("/nets/{id}/participants/{call}/equipment")
    public Object equipment(@PathVariable String id, @PathVariable String call) {
        return nets.equipment(id, call);
    }

    @PutMapping("/nets/{id}/participants/{call}/equipment")
    public void equipment(@PathVariable String id, @PathVariable String call, @RequestBody Map<String, String> values) {
        nets.equipment(id, call, values);
    }

    @GetMapping(value = "/completedNets/{id}/questionAnswerReports", produces = "application/pdf")
    public ResponseEntity<byte[]> questionReport(@PathVariable String id) throws IOException {
        archived(id);
        return pdfDownload(id, "qa", new com.kc1vmz.netcentral.net.NetQuestionReport().render(nets.snapshot(id)));
    }

    @GetMapping("/nets/{id}/messages")
    public List<Message> messages(@PathVariable String id) {
        return nets.messages(id);
    }

    @PostMapping("/nets/{id}/messages")
    public List<Message> message(@PathVariable String id, @RequestBody MessageRequest request) {
        return nets.send(id, request);
    }

    @GetMapping("/nets/{id}/snapshot")
    public Snapshot snapshot(@PathVariable String id) {
        return nets.snapshot(id);
    }

    @GetMapping(value = "/nets/{id}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String id, @AuthenticationPrincipal LocalPrincipal principal,
            HttpServletRequest request) {
        return events.open(id, principal, request.getSession(false));
    }

    @GetMapping("/completedNets")
    public List<Net> completed() {
        return nets.list(true);
    }

    private void archived(String id) {
        if (nets.get(id).endTime() == null)
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Archive not found");
    }

    @GetMapping("/completedNets/{id}")
    public Net completed(@PathVariable String id) {
        archived(id);
        return nets.get(id);
    }

    @GetMapping("/completedNets/{id}/participants")
    public List<Participant> archivedParticipants(@PathVariable String id) {
        archived(id);
        return nets.participants(id, true);
    }

    @GetMapping("/completedNets/{id}/messages")
    public List<Message> archivedMessages(@PathVariable String id) {
        archived(id);
        return nets.messages(id);
    }

    @GetMapping(value = "/completedNets/{id}/participationReports", produces = "application/pdf")
    public ResponseEntity<byte[]> participation(@PathVariable String id) throws IOException {
        archived(id);
        return pdf(id, false);
    }

    @GetMapping(value = "/completedNets/{id}/messageReports", produces = "application/pdf")
    public ResponseEntity<byte[]> messagesPdf(@PathVariable String id) throws IOException {
        archived(id);
        return pdf(id, true);
    }

    private ResponseEntity<byte[]> pdf(String id, boolean messages) throws IOException {
        return pdfDownload(id, messages ? "messages" : "ics-309", reports.report(id, messages));
    }

    private ResponseEntity<byte[]> pdfDownload(String id, String reportType, byte[] content) {
        String name = nets.get(id).name().replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "-")
                .strip().replaceAll("\\s+", "-").replaceAll("[. ]+$", "");
        if (name.isBlank()) name = "net";
        if (name.length() > 100) name = name.substring(0, 100);
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String filename = name + "-" + timestamp + "-" + reportType + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(content);
    }

    @GetMapping("/mapSettings")
    public MapSettings map() {
        return nets.map();
    }

    @PutMapping("/mapSettings")
    public MapSettings map(@RequestBody MapSettings value) {
        nets.map(value);
        return nets.map();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", error.getReason()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied() {
        return ResponseEntity.status(403).body(Map.of("message", "Net owner or administrator access required"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidValue() {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid net or APRS message settings"));
    }
}
