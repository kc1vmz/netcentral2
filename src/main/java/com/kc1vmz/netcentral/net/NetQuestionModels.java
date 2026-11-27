package com.kc1vmz.netcentral.net;

import java.time.Instant;
import java.util.List;

public final class NetQuestionModels {
    private NetQuestionModels() {
    }

    public record Question(String id, int number, String callsign, String question, Instant askedAt, boolean strip) {
        public Question(String id, int number, String callsign, String question, Instant askedAt) {
            this(id, number, callsign, question, askedAt, false);
        }
        public String getIdentifier() { return strip ? "S" + number : Integer.toString(number); }
        public String wireText() { return strip ? "QS " + getIdentifier() + "/" + question : "Q " + number + " " + question; }
        public List<StripFormat.Field> getFields() { return strip ? StripFormat.definition(question).fields() : List.of(); }

    }

    public record Answer(String questionId, String callsign, String answer, Instant answeredAt) {
    }

    public record QuestionRequest(String question, boolean strip) {
        public QuestionRequest(String question) { this(question, false); }
    }

    public record AnswerRequest(String callsign, String answer) {
    }
}
