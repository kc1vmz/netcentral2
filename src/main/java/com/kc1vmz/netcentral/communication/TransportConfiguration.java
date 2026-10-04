package com.kc1vmz.netcentral.communication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TransportConfiguration {
    @Bean
    public TransportFactory transportFactory() {
        return StreamTransport::new;
    }
}
