package com.kc1vmz.netcentral.communication;

import com.fazecast.jSerialComm.SerialPortTimeoutException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** Bounded line framing shared by TCP and serial TNC2; partial reads survive timeouts. */
final class PacketLineReader {
    private final boolean carriageReturnTerminates;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean oversized;

    PacketLineReader(boolean carriageReturnTerminates) {
        this.carriageReturnTerminates = carriageReturnTerminates;
    }

    String read(InputStream input) throws IOException {
        while (true) {
            int value;
            try {
                value = input.read();
            } catch (SocketTimeoutException | SerialPortTimeoutException timeout) {
                return null;
            }
            if (value < 0) throw new EOFException("Connection closed");
            if (value == '\n' || (carriageReturnTerminates && value == '\r')) {
                String result = line.toString(StandardCharsets.ISO_8859_1);
                line.reset();
                if (oversized) { oversized = false; return null; }
                return result.endsWith("\r") ? result.substring(0, result.length() - 1) : result;
            }
            if (line.size() >= 1024) oversized = true;
            if (!oversized) line.write(value);
        }
    }
}
