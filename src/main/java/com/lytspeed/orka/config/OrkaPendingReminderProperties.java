package com.lytspeed.orka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@ConfigurationProperties(prefix = "orka.notifications.pending-reminder")
@Component("orkaPendingReminderProperties")
public class OrkaPendingReminderProperties {

    private boolean enabled = true;
    private Duration interval = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public long getIntervalMillis() {
        Duration effective = interval == null || interval.isNegative() || interval.isZero()
                ? Duration.ofMinutes(15)
                : interval;
        return effective.toMillis();
    }
}
