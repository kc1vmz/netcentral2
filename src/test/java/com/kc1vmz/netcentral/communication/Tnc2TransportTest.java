package com.kc1vmz.netcentral.communication;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class Tnc2TransportTest {
    @Test void serialAndTcpFramingRetainsWholePacketsAcrossTimeoutsAndResynchronizes() throws Exception {
        byte[] bytes = "N1ABC>APRS:hello\r\nN2ABC>APRS:second\n".getBytes(StandardCharsets.US_ASCII);
        InputStream fragmented = new InputStream() {
            int index; boolean timeout;
            public int read() throws IOException {
                timeout = !timeout;
                if (timeout) throw new SocketTimeoutException();
                return index == bytes.length ? -1 : bytes[index++];
            }
        };
        PacketLineReader reader = new PacketLineReader(true);
        String first = null;
        for (int i = 0; i < 100 && first == null; i++) first = reader.read(fragmented);
        assertThat(first).isEqualTo("N1ABC>APRS:hello");
        String second = null;
        for (int i = 0; i < 100 && (second == null || second.isEmpty()); i++) second = reader.read(fragmented);
        assertThat(second).isEqualTo("N2ABC>APRS:second");
        assertThat(reader.read(fragmented)).isNull();
        assertThatThrownBy(() -> reader.read(fragmented)).isInstanceOf(EOFException.class);
        var oversized = new ByteArrayInputStream(("X".repeat(1200) + "\rN3ABC>APRS:valid\r").getBytes(StandardCharsets.US_ASCII));
        assertThat(reader.read(oversized)).isNull();
        assertThat(reader.read(oversized)).isEqualTo("N3ABC>APRS:valid");
    }

    @Test void tcpReceivesAndSendsCompletePacketsWithAndWithoutPaths() throws Exception {
        for (String path : List.of("", "WIDE1-1,WIDE2-1")) {
            try (var server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
                var exchange = executor.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        socket.getOutputStream().write("N1ABC>APRS:hello\r".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                        String ret = reader.readLine();
                        reader.close();
                        return ret;
                    }
                });
                var config = new CommunicationConfig("test",0,"TNC2","TNC2_TCP",true,true,"127.0.0.1",server.getLocalPort(),null,null,null,null,null,null,null,path,null);
                try (var transport = new StreamTransport(config)) {
                    transport.connect();
                    assertThat(transport.read()).isEqualTo(Packet.parse("N1ABC>APRS:hello"));
                    transport.send(new Packet("N0CALL","APANC1",List.of(),":N1ABC    :ack12"));
                    assertThat(exchange.get(5,TimeUnit.SECONDS)).isEqualTo("N0CALL>APANC1"+(path.isEmpty()?"":","+path)+"::N1ABC    :ack12");
                    assertThatThrownBy(transport::read).isInstanceOf(EOFException.class);
                }
            }
        }
    }

    @Test void receiveOnlyTnc2CannotSend() throws Exception {
        try (var server = new ServerSocket(0); var transport = new StreamTransport(ProtocolTest.config("TNC2_TCP",server.getLocalPort(),false))) {
            transport.connect();
            try (var peer = server.accept()) {
                assertThat(transport.canTransmit()).isFalse();
                assertThatThrownBy(() -> transport.send(ManagedConnectionTest.PACKET)).isInstanceOf(IOException.class);
            }
        }
    }
}
