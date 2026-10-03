package com.lytspeed.orka.config;

import com.lytspeed.orka.entity.DeviceToken;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Explicit local-test delivery restriction; never bypasses recipient eligibility. */
@Component
public class LocalNotificationTestTarget {
    private final Long deviceTokenId;

    public LocalNotificationTestTarget(Environment environment,
            @Value("${orka.notifications.local-test-device-token-id:#{null}}") Long deviceTokenId) {
        if (deviceTokenId != null && (deviceTokenId <= 0 || !environment.acceptsProfiles(Profiles.of("local"))))
            throw new IllegalStateException("Notification test target requires the local profile and a positive token row ID");
        this.deviceTokenId = deviceTokenId;
        if (deviceTokenId != null) LoggerFactory.getLogger(getClass()).warn(
                "LOCAL TEST ONLY: notification delivery restricted to eligible device-token row {}", deviceTokenId);
    }

    public Long getDeviceTokenId() { return deviceTokenId; }
    public boolean allows(DeviceToken token) {
        return deviceTokenId == null || (token != null && deviceTokenId.equals(token.getId())
                && Boolean.TRUE.equals(token.getActive()));
    }
}
