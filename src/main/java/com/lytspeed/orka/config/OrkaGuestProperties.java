package com.lytspeed.orka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "orka.guest")
public class OrkaGuestProperties {

    private Duration sessionTtl = Duration.ofHours(1);

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }
}