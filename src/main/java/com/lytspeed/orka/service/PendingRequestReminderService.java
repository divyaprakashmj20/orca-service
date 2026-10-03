package com.lytspeed.orka.service;

import com.lytspeed.orka.config.OrkaPendingReminderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PendingRequestReminderService {

    private static final Logger log = LoggerFactory.getLogger(PendingRequestReminderService.class);

    private final WorkNotificationDispatcher dispatcher;
    private final OrkaPendingReminderProperties properties;

    public PendingRequestReminderService(
            WorkNotificationDispatcher dispatcher,
            OrkaPendingReminderProperties properties
    ) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "#{@orkaPendingReminderProperties.intervalMillis}",
            initialDelayString = "#{@orkaPendingReminderProperties.intervalMillis}"
    )
    public void sendPendingRequestReminders() {
        if (!properties.isEnabled()) {
            return;
        }

        long intervalMinutes = Math.max(1, properties.getInterval().toMinutes());
        int attempted = 0;
        int failed = 0;
        for (var work : dispatcher.unfinished()) {
            attempted++;
            try {
                // Recheck each item in a short transaction; release it before calling FCM.
                dispatcher.dispatch(work, true, intervalMinutes);
            } catch (RuntimeException failure) {
                failed++;
                log.warn("Reminder processing failed for {} {} ({}); continuing with remaining work.",
                        work.kind(), work.id(), failure.getClass().getSimpleName());
            }
        }
        log.info("Processed reminder attempts for {} unfinished work item(s); {} work-item processing errors. This count does not confirm delivery.", attempted, failed);
    }
}
