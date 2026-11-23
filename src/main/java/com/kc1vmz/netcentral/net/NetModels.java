package com.kc1vmz.netcentral.net;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.kc1vmz.netcentral.communication.RoutingService;

public final class NetModels {
        private NetModels() {
        }

        public record Net(String id, String callsign, String name, String description, String voiceFrequency,
                        String ownerId, String creatorName, boolean open, boolean privateNet, Instant startTime,
                        Instant endTime, long version, int participantCount, String completedNetId) {
        }

        public record NetEdit(String callsign, String name, String description, String voiceFrequency,
                        Boolean open, Boolean privateNet, Long version, String communicationMode,
                        Set<String> communicationInstanceIds, Long routingVersion, Set<String> expectedParticipants) {
        }

        public record Participant(String callsign, String status, String voiceFrequency, String tacticalCallsign,
                        Instant startTime, Instant endTime, Instant lastHeardTime, Double lat, Double lon, long version,
                        String transmitPower, String electricalPowerType, String backupElectricalPowerType,
                        String radioStyle, String statusColor, double uncertaintyMeters) {
                public Participant(String callsign, String status, String voiceFrequency, String tacticalCallsign,
                        Instant startTime, Instant endTime, Instant lastHeardTime, Double lat, Double lon, long version,
                        String transmitPower, String electricalPowerType, String backupElectricalPowerType,
                        String radioStyle, String statusColor) {
                    this(callsign,status,voiceFrequency,tacticalCallsign,startTime,endTime,lastHeardTime,lat,lon,version,
                        transmitPower,electricalPowerType,backupElectricalPowerType,radioStyle,statusColor,0);
                }
                public Participant(String callsign, String status, String voiceFrequency, String tacticalCallsign,
                        Instant startTime, Instant endTime, Instant lastHeardTime, Double lat, Double lon, long version,
                        String transmitPower, String electricalPowerType, String backupElectricalPowerType, String radioStyle) {
                        this(callsign, status, voiceFrequency, tacticalCallsign, startTime, endTime, lastHeardTime, lat, lon, version,
                                transmitPower, electricalPowerType, backupElectricalPowerType, radioStyle, endTime == null ? "GREEN" : "WHITE");
                }
        }

        public record ParticipantEdit(String status, String voiceFrequency, String tacticalCallsign, Long version) {
        }

        public record Message(String id, String completedNetId, String callsignFrom, String recipient, String message,
                        Instant receivedTime, String direction, String delivery, String ackNumber) {
        }

        public record MessageRequest(String recipient, String message, Boolean acknowledgementRequested) {
        }

        public record MapSettings(String tileUrl, String attribution) {
        }

        public record Snapshot(Net net, List<Participant> participants, List<Message> messages,
                        RoutingService.Route routing, Set<String> expectedParticipants, MapSettings map,
                        boolean canManage,
                        List<NetQuestionModels.Question> questions, List<NetQuestionModels.Answer> answers, Instant plannedEndTime) {
                public Snapshot(Net net, List<Participant> participants, List<Message> messages,
                                RoutingService.Route routing, Set<String> expectedParticipants, MapSettings map,
                                boolean canManage, List<NetQuestionModels.Question> questions, List<NetQuestionModels.Answer> answers) {
                        this(net, participants, messages, routing, expectedParticipants, map, canManage, questions, answers, null);
                }
        }

        public record SendRequested(String netId, String messageId, String from, String to,
                        String text, String number, String receivingInstance) {
        }
}
