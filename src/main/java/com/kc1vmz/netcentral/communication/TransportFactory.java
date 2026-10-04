package com.kc1vmz.netcentral.communication;

@FunctionalInterface
public interface TransportFactory {
    CommunicationTransport create(CommunicationConfig config);
}
