package com.lytspeed.orka.service;

import com.lytspeed.orka.repository.DeviceTokenRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Invalid provider tokens are retired in a writable transaction, independent of readers. */
@Service
public class DeviceTokenRetirement {
    private final DeviceTokenRepository tokens;
    private final TransactionTemplate writable;

    public DeviceTokenRetirement(DeviceTokenRepository tokens, PlatformTransactionManager transactions) {
        this.tokens = tokens;
        writable = new TransactionTemplate(transactions);
        writable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void deactivate(String token) {
        writable.executeWithoutResult(status -> tokens.findByFcmToken(token).ifPresent(device -> {
            device.setActive(false);
            device.setLastSeenAt(LocalDateTime.now());
            tokens.saveAndFlush(device);
        }));
    }
}
