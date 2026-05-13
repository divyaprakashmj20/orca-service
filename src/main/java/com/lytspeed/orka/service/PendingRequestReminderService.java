package com.lytspeed.orka.service;

import com.lytspeed.orka.config.OrkaPendingReminderProperties;
import com.lytspeed.orka.entity.Request;
import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.repository.RequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class PendingRequestReminderService {

    private static final Logger log = LoggerFactory.getLogger(PendingRequestReminderService.class);

    private final RequestRepository requestRepository;
    private final FcmNotificationService fcmNotificationService;
    private final OrkaPendingReminderProperties properties;

    public PendingRequestReminderService(
            RequestRepository requestRepository,
            FcmNotificationService fcmNotificationService,
            OrkaPendingReminderProperties properties
    ) {
        this.requestRepository = requestRepository;
        this.fcmNotificationService = fcmNotificationService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "#{@orkaPendingReminderProperties.intervalMillis}",
            initialDelayString = "#{@orkaPendingReminderProperties.intervalMillis}"
    )
    @Transactional(readOnly = true)
    public void sendPendingRequestReminders() {
        if (!properties.isEnabled()) {
            return;
        }

        List<Request> staleRequests = requestRepository.findByStatusIn(
                Arrays.asList(RequestStatus.NEW, RequestStatus.ACCEPTED)
        );

        if (staleRequests.isEmpty()) {
            return;
        }

        long intervalMinutes = Math.max(1, properties.getInterval().toMinutes());
        for (Request request : staleRequests) {
            if (request.getStatus() == RequestStatus.NEW) {
                fcmNotificationService.notifyPendingRequestReminder(request, intervalMinutes);
            } else if (request.getStatus() == RequestStatus.ACCEPTED) {
                fcmNotificationService.notifyAcceptedRequestReminder(request, intervalMinutes);
            }
        }

        log.info("Sent pending-request reminder notifications for {} request(s).", staleRequests.size());
    }
}