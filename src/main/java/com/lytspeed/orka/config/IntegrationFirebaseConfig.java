package com.lytspeed.orka.config;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.*;
import org.springframework.context.annotation.*;
import java.util.Date;
@Configuration
@Profile("integration")
public class IntegrationFirebaseConfig {
    @Bean FirebaseApp emulatorFirebaseApp() {
        if (!"127.0.0.1:9099".equals(System.getenv("FIREBASE_AUTH_EMULATOR_HOST"))) throw new IllegalStateException("Integration profile requires loopback Firebase Auth emulator");
        if (!FirebaseApp.getApps().isEmpty()) return FirebaseApp.getInstance();
        return FirebaseApp.initializeApp(FirebaseOptions.builder().setProjectId("demo-orka-product")
            .setCredentials(GoogleCredentials.create(new AccessToken("local-emulator-only",new Date(Long.MAX_VALUE)))).build());
    }
}
