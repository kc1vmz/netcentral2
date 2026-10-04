package com.kc1vmz.netcentral.communication;

import java.io.IOException;

public interface CommunicationTransport extends AutoCloseable {
    void connect() throws IOException;

    Packet read() throws IOException;

    void send(Packet packet) throws IOException;

    boolean canTransmit();

    @Override
    void close();
}
