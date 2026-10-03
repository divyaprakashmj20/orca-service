package com.lytspeed.orka.service;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Submit ID-only work after commit without retaining the request thread or its connection. */
@Service
public class WorkNotificationEvents {
    public enum Kind { SERVICE_REQUEST, FOOD_ORDER }
    public record Created(Kind kind, Long id) {}
    private static final Logger log = LoggerFactory.getLogger(WorkNotificationEvents.class);
    private final WorkNotificationDispatcher dispatcher;
    private final Executor executor;

    public WorkNotificationEvents(WorkNotificationDispatcher dispatcher,
                                 @Qualifier("workNotificationExecutor") Executor executor) {
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void created(Created event) {
        try {
            executor.execute(() -> {
                try {
                    dispatcher.dispatch(event, false, 0);
                } catch (RuntimeException failure) {
                    log.warn("Initial notification attempt failed for {} {} ({}).", event.kind(), event.id(),
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            // Committed work remains successful; the normal reminder sweep retries unfinished work.
            log.warn("Initial notification could not be queued for {} {} ({}).", event.kind(), event.id(),
                    failure.getClass().getSimpleName());
        }
    }
}
