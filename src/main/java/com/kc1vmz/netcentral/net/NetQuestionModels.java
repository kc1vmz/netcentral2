package com.kc1vmz.netcentral.net;

import java.time.Instant;

public final class NetQuestionModels {
    private NetQuestionModels() {
    }

    public record Question(String id, int number, String callsign, String question, Instant askedAt) {
    }

    public record Answer(String questionId, String callsign, String answer, Instant answeredAt) {
    }

    public record QuestionRequest(String question) {
    }

    public record AnswerRequest(String callsign, String answer) {
    }
}
