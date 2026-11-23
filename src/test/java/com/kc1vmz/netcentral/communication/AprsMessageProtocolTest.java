package com.kc1vmz.netcentral.communication;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AprsMessageProtocolTest {
    @Test
    void parsesMessageNumbersReplyAckAndControlWithoutStrippingSpaces() {
        var message = AprsMessageProtocol.parse(" H {18}6E\r\n");
        assertThat(message.body()).isEqualTo(" H ");
        assertThat(message.number()).isEqualTo("18");
        assertThat(message.replyAck()).isEqualTo("6E");
        assertThat(AprsMessageProtocol.parse("H{18}").replyAck()).isEmpty();
        assertThat(AprsMessageProtocol.parse("H{123456").number()).isNull();
        assertThat(AprsMessageProtocol.control("ack6E\r").number()).isEqualTo("6E");
        assertThat(AprsMessageProtocol.control("rej6E}18\n").kind()).isEqualTo("rej");
    }

    @Test
    void normalizesOnlyTrailingMessageLineEndingsAndPreservesBinaryData() {
        assertThat(new Packet("N1ABC","APRS",List.of(),":NET001   :H{18\r\n").information())
                .isEqualTo(":NET001   :H{18");
        String binary = "`abc" + (char) 0x1c + "xyz ";
        assertThat(new Packet("N1ABC","APRS",List.of(),binary).information()).isEqualTo(binary);
        assertThatThrownBy(() -> new Packet("N1ABC","APRS",List.of(),":NET001   :H\rINJECT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kissDecoderAcceptsMessageCarriageReturnFromRadio() {
        var original = new Packet("N1ABC","APRS",List.of(),":NET001   :H{18");
        byte[] encoded = KissCodec.encode(original,0);
        var decoder = new KissCodec.Decoder();
        for (int i=0; i<encoded.length-1; i++) decoder.accept(encoded[i] & 255);
        decoder.accept(13);
        byte[] frame = decoder.accept(0xc0);
        assertThat(KissCodec.decode(frame,0)).isEqualTo(original);
    }

    @Test
    void repeatedNumberedMessagesReachAckHandlersWithoutRepeatingDomainEvents() {
        var publisher = mock(ApplicationEventPublisher.class);
        var hub = new PacketHub(publisher);
        hub.start();
        try {
            hub.receive("one",new Packet("N1ABC","APRS",List.of(),":NET001   :H{18"));
            hub.receive("two",new Packet("N1ABC","APRS",List.of("WIDE1-1"),":NET001   :H{18\r"));
            verify(publisher,timeout(3000)).publishEvent(isA(PacketHub.RepeatedMessage.class));
            verify(publisher,times(1)).publishEvent(isA(PacketHub.UniquePacket.class));
        } finally { hub.stop(); }
    }
}
