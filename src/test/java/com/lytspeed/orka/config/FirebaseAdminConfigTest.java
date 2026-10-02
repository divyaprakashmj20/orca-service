package com.lytspeed.orka.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirebaseAdminConfigTest {
    @Test void realCredentialsCannotSilentlyEnableEmulatorVerification() {
        var config = new FirebaseAdminConfig();
        var failure = assertThrows(IllegalStateException.class,
                () -> config.firebaseApp("unused-file", "127.0.0.1:9099"));
        assertTrue(failure.getMessage().contains("FIREBASE_AUTH_EMULATOR_HOST"));
    }
}
