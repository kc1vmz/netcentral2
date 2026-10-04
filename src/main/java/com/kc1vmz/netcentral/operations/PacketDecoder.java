package com.kc1vmz.netcentral.operations;

import java.nio.charset.StandardCharsets;

import com.kc1vmz.netcentral.aprsobject.interfaces.APRSPacketInterface;
import com.kc1vmz.netcentral.communication.Packet;
import com.kc1vmz.netcentral.net.AprsPosition;
import com.kc1vmz.netcentral.parser.factory.*;
import com.kc1vmz.netcentral.parser.util.PacketIdentity;

public final class PacketDecoder {
    private PacketDecoder() {
    }

    public static APRSPacketInterface decode(Packet packet) {
        byte[] identity = PacketIdentity.of(packet.source(), packet.destination()),
                data = packet.information().getBytes(StandardCharsets.ISO_8859_1);
        if (data.length == 0)
            return null;
        try {
            return switch (data[0]) {
                case '`', '\'' -> APRSMicEFactory.parse(identity, data);
                case '!', '=', '/', '@' -> {
                    var position = APRSPositionFactory.parse(identity, data);
                    yield position != null && position.isHasWeatherReport()
                            ? APRSWeatherReportFactory.parse(identity, data)
                            : position;
                }
                case ';' -> APRSObjectFactory.parse(identity, data);
                case ')' -> APRSItemFactory.parse(identity, data);
                case '_', '#', '$', '*' -> APRSWeatherReportFactory.parse(identity, data);
                case '>' -> APRSStatusFactory.parse(identity, data);
                case 'T' -> APRSTelemetryFactory.parse(identity, data);
                case '{' -> APRSUserDefinedFactory.parse(identity, data);
                case '[' -> APRSMaidenheadLocatorBeaconFactory.parse(identity, data);
                default -> null;
            };
        } catch (Exception malformed) {
            return null;
        }
    }

    public static AprsPosition.Position position(Packet packet) {
        var standard = AprsPosition.parse(packet.information());
        if (standard != null)
            return standard;
        var decoded = decode(packet);
        if (decoded instanceof com.kc1vmz.netcentral.aprsobject.object.APRSMicE mic)
            return coordinate(mic.getLat(), mic.getLon());
        return null;
    }

    public static AprsPosition.Position coordinate(String lat, String lon) {
        return lat == null || lon == null ? null : AprsPosition.parse("!" + lat + "/" + lon + "> ");
    }
}
