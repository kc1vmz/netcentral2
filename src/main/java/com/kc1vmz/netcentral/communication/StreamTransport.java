package com.kc1vmz.netcentral.communication;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class StreamTransport implements CommunicationTransport {
    private final CommunicationConfig config;
    private final KissCodec.Decoder decoder = new KissCodec.Decoder();
    private final PacketLineReader lines;
    private volatile Socket socket;
    private volatile SerialPort serial;
    private volatile boolean closed;
    private volatile boolean verified;
    private InputStream input;
    private OutputStream output;
    private KenwoodSession kenwood;

    public StreamTransport(CommunicationConfig config) {
        this.config = config;
        this.lines = new PacketLineReader(config.type().startsWith("TNC2_"));
    }

    @Override
    public void connect() throws IOException {
        if (!Set.of("APRS_IS", "KISS_TCP", "KISS_SERIAL", "TNC2_TCP", "TNC2_SERIAL", "KENWOOD_SERIAL").contains(config.type()))
            throw new IOException("Unsupported transport type");
        if (closed)
            throw new IOException("Cancelled");
        if (config.serial()) {
            SerialPort device = SerialPort.getCommPort(config.serialDevice());
            serial = device;
            device.setComPortParameters(config.baudRate(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            device.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            device.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);
            if (closed || !device.openPort())
                throw new IOException("Serial port unavailable");
            if (closed) {
                device.closePort();
                throw new IOException("Cancelled");
            }
            input = device.getInputStream();
            output = device.getOutputStream();
            for (String command : new String[] { config.initCommand1(), config.initCommand2() }) {
                if (command != null && !command.isBlank())
                    output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
            }
            output.flush();
        } else {
            Socket connection = new Socket();
            socket = connection;
            if (closed) {
                connection.close();
                throw new IOException("Cancelled");
            }
            connection.connect(new InetSocketAddress(config.host(), config.port()), 5000);
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(1000);
            input = connection.getInputStream();
            output = connection.getOutputStream();
        }
        if (config.type().equals("KENWOOD_SERIAL")) {
            kenwood = new KenwoodSession(input, output, config);
            kenwood.initialize();
            verified = true;
        } else if (config.type().equals("APRS_IS"))
            login();
        else
            verified = true;
        if (closed)
            throw new IOException("Cancelled");
    }

    private void login() throws IOException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean sent = false;
        while (!closed && System.nanoTime() < deadline) {
            String response = readLine();
            if (response == null)
                continue;
            if (!sent && response.startsWith("#")) {
                String login = "user " + config.username() + " pass " + config.passcode() + " vers NetCentralV2 0.1";
                if (config.filter() != null && !config.filter().isBlank())
                    login += " filter " + config.filter();
                output.write((login + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                sent = true;
            } else if (sent && response.toLowerCase(Locale.ROOT).startsWith("# logresp ")) {
                String[] parts = response.substring(10).split("[ ,]+");
                if (parts.length < 2 || !parts[0].equalsIgnoreCase(config.username()))
                    throw new IOException("Login rejected");
                verified = parts[1].equalsIgnoreCase("verified");
                if (!verified && !parts[1].equalsIgnoreCase("unverified"))
                    throw new IOException("Login rejected");
                return;
            }
        }
        throw new IOException("Login timed out");
    }

    private String readLine() throws IOException {
        if (closed) throw new IOException("Closed");
        return lines.read(input);
    }

    @Override
    public Packet read() throws IOException {
        if (kenwood != null) return kenwood.read();
        if (config.type().equals("APRS_IS") || config.type().startsWith("TNC2_")) {
            String value = readLine();
            if (value == null || value.isBlank() || value.startsWith("#"))
                return null;
            try {
                return Packet.parse(value);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        while (!closed) {
            int value;
            try {
                value = input.read();
            } catch (SocketTimeoutException | SerialPortTimeoutException timeout) {
                return null;
            }
            if (value < 0)
                throw new EOFException();
            byte[] frame = decoder.accept(value);
            if (frame != null) {
                try {
                    return KissCodec.decode(frame, config.kissPort());
                } catch (IllegalArgumentException malformed) {
                    return null;
                }
            }
        }
        throw new IOException("Closed");
    }

    @Override
    public void send(Packet packet) throws IOException {
        if (!canTransmit())
            throw new IOException("Transmission unavailable");
        if (kenwood != null) { kenwood.send(packet); return; }
        if (config.type().equals("APRS_IS")) {
            Packet wire = new Packet(packet.source(), packet.destination(), List.of("TCPIP*"), packet.information());
            output.write((wire.line() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        } else {
            List<String> path = config.digiPath() == null || config.digiPath().isBlank()
                    ? List.of()
                    : List.of(config.digiPath().split(","));
            Packet wire = new Packet(packet.source(), packet.destination(), path, packet.information());
            if (config.type().startsWith("TNC2_"))
                output.write((wire.line() + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            else
                output.write(KissCodec.encode(wire, config.kissPort()));
        }
        output.flush();
    }

    @Override
    public boolean canTransmit() {
        return !closed && verified && config.transmitEnabled();
    }

    @Override
    public void close() {
        closed = true;
        if (kenwood != null) kenwood.close();
        Socket connection = socket;
        if (connection != null)
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        SerialPort device = serial;
        if (device != null && device.isOpen() && !device.closePort() && device.isOpen())
            throw new IllegalStateException("Serial port did not close");
    }
}
