package com.kc1vmz.netcentral;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NetCentralApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(NetCentralApplication.class);
        application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        application.run(args);
    }
}
