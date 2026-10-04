package com.kc1vmz.netcentral.communication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class ProtocolTest {
    @TempDir
    Path directory;

    static CommunicationConfig config(String type, int port, boolean transmit) {
        return new CommunicationConfig("id", 0, "Test", type, true, transmit, "127.0.0.1", port,
                "N0CALL", "12345", "r/0/0/10", "COM9", 9600, null, null, "WIDE1-1", 0);
    }

    @Test
    void kissRoundTripHandlesEscapedPortAndPartialFrames() {
        Packet original = new Packet("N0CALL-15", "APANC1", List.of("WIDE1-1", "N1ABC*"),
                ":N2ABC    :hello" + (char) 0xc0 + (char) 0xdb + (char) 0x1c);
        byte[] frame = KissCodec.encode(original, 12); // command byte C0 must itself be escaped
        assertThat(frame[1]).isEqualTo((byte) 0xdb);
        assertThat(frame[2]).isEqualTo((byte) 0xdc);
        KissCodec.Decoder decoder = new KissCodec.Decoder();
        byte[] decoded = null;
        for (byte value : frame) {
            byte[] next = decoder.accept(value & 255);
            if (next != null)
                decoded = next;
        }
        assertThat(KissCodec.decode(decoded, 12)).isEqualTo(original);
        assertThatThrownBy(() -> KissCodec.encode(new Packet("TOOLONG", "APANC1", List.of(), "test"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Packet("N0CALL", "APANC1", List.of(), "bad\r\npacket"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KissCodec.decode(new byte[] { 0, 1, 2 }, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedEscapesAndOversizeFramesResynchronize() {
        KissCodec.Decoder decoder = new KissCodec.Decoder();
        for (int value : new int[] { 0xc0, 0, 0xdb, 0x01, 0xc0 })
            assertThat(decoder.accept(value)).isNull();
        decoder.accept(0xc0);
        for (int i = 0; i < 2048; i++)
            decoder.accept(1);
        assertThat(decoder.accept(0xc0)).isNull();
        decoder.accept(0xdb);
        decoder.accept(0xdd);
        assertThat(decoder.accept(0xc0)).containsExactly((byte) 0xdb);
    }

    @Test
    void serialIdentitiesRespectPlatformCaseAndAliases() throws Exception {
        assertThat(SerialOwnership.identity("com09", true)).isEqualTo("COM9");
        assertThat(SerialOwnership.identity("\\\\.\\COM9", true)).isEqualTo("COM9");
        assertThat(SerialOwnership.identity("/dev/ttyS0", false))
                .isNotEqualTo(SerialOwnership.identity("/dev/ttys0", false));
        Path port = Files.createFile(directory.resolve("port"));
        assertThat(SerialOwnership.identity(directory.resolve("x/../port").toString(), false))
                .isEqualTo(port.toRealPath().toString());
        SerialOwnership ownership = new SerialOwnership();
        assertThat(ownership.claim("COM9", "first")).isTrue();
        assertThat(ownership.claim("COM9", "second")).isFalse();
        ownership.release("COM9", "second");
        assertThat(ownership.available("COM9", "second")).isFalse();
    }

    @Test
    void aprsLoginReadAndSendUseLocalServerOnly() throws Exception {
        try (ServerSocket server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var exchange = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream output = socket.getOutputStream();
                    output.write("# local fixture\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    String login = input.readLine();
                    assertThat(login).contains("user N0CALL pass 12345 vers NetCentralV2", "filter r/0/0/10");
                    output.write("# logresp N0CALL verified, server LOCAL\r\nN1ABC>APRS:!4903.50N/07201.75W-Test\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    return input.readLine();
                }
            });
            try (StreamTransport client = new StreamTransport(config("APRS_IS", server.getLocalPort(), true))) {
                client.connect();
                assertThat(client.canTransmit()).isTrue();
                assertThat(client.read().source()).isEqualTo("N1ABC");
                client.send(new Packet("N0CALL", "APANC1", List.of("WIDE1-1"), ":N1ABC    :hello"));
                assertThat(exchange.get(5, TimeUnit.SECONDS)).isEqualTo("N0CALL>APANC1,TCPIP*::N1ABC    :hello");
            }
        }
    }

    @Test
    void unverifiedAprsConnectionCannotTransmit() throws Exception {
        try (ServerSocket server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var exchange = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    OutputStream output = socket.getOutputStream();
                    output.write("# fixture\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
                    output.write("# logresp N0CALL unverified, server LOCAL\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                }
                return true;
            });
            try (StreamTransport client = new StreamTransport(config("APRS_IS", server.getLocalPort(), true))) {
                client.connect();
                assertThat(client.canTransmit()).isFalse();
                assertThatThrownBy(() -> client.send(new Packet("N0CALL", "APANC1", List.of(), "test")))
                        .isInstanceOf(IOException.class);
            }
            assertThat(exchange.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void kissTcpSendsAndReceivesFramesOnLocalSocket() throws Exception {
        Packet packet = new Packet("N0CALL", "APANC1", List.of("WIDE1-1"), ":N1ABC    :hello");
        try (ServerSocket server = new ServerSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            var exchange = executor.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(KissCodec.encode(packet, 0));
                    socket.getOutputStream().flush();
                    KissCodec.Decoder decoder = new KissCodec.Decoder();
                    byte[] frame = null;
                    while (frame == null)
                        frame = decoder.accept(socket.getInputStream().read());
                    return KissCodec.decode(frame, 0);
                }
            });
            try (StreamTransport client = new StreamTransport(config("KISS_TCP", server.getLocalPort(), true))) {
                client.connect();
                assertThat(client.read()).isEqualTo(packet);
                client.send(packet);
                assertThat(exchange.get(5, TimeUnit.SECONDS)).isEqualTo(packet);
            }
        }
    }
}
