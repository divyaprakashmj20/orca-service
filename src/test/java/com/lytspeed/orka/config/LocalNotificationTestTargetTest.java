package com.lytspeed.orka.config;

import com.lytspeed.orka.entity.DeviceToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class LocalNotificationTestTargetTest {
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"local", "production"})
    void absentTargetLeavesDeliveryUnrestricted(String profile) {
        LocalNotificationTestTarget target = new LocalNotificationTestTarget(environment(profile), null);

        assertNull(target.getDeviceTokenId());
        assertTrue(target.allows(token(42L, true)));
        assertTrue(target.allows(token(43L, false)));
        assertTrue(target.allows(null));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"production", "test"})
    void configuredTargetFailsWithoutTheLocalProfile(String profile) {
        assertThrows(IllegalStateException.class,
                () -> new LocalNotificationTestTarget(environment(profile), 42L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void localProfileStillRejectsNonPositiveTargetIds(long id) {
        assertThrows(IllegalStateException.class,
                () -> new LocalNotificationTestTarget(environment("local"), id));
    }

    @Test
    void configuredLocalTargetAllowsOnlyTheExactActiveTokenRow() {
        LocalNotificationTestTarget target = new LocalNotificationTestTarget(environment("local"), 42L);

        assertEquals(42L, target.getDeviceTokenId());
        assertTrue(target.allows(token(42L, true)));
        assertFalse(target.allows(token(43L, true)));
        assertFalse(target.allows(token(42L, false)));
        assertFalse(target.allows(token(42L, null)));
        assertFalse(target.allows(token(null, true)));
        assertFalse(target.allows(null));
    }

    @Test
    void localProfileCanBeEnabledAlongsideAnotherProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test", "local");

        LocalNotificationTestTarget target = new LocalNotificationTestTarget(environment, 42L);

        assertTrue(target.allows(token(42L, true)));
        assertFalse(target.allows(token(43L, true)));
    }

    private MockEnvironment environment(String profile) {
        MockEnvironment environment = new MockEnvironment();
        if (!profile.isEmpty()) {
            environment.setActiveProfiles(profile);
        }
        return environment;
    }

    private DeviceToken token(Long id, Boolean active) {
        DeviceToken token = new DeviceToken();
        token.setId(id);
        token.setActive(active);
        return token;
    }
}
