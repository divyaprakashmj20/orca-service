package com.lytspeed.orka.service;

import com.lytspeed.orka.config.NotificationExecutorConfig;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkNotificationEventsTest {
    @TestConfiguration @EnableTransactionManagement
    @Import({WorkNotificationEvents.class, NotificationExecutorConfig.class})
    static class Config {
        @Bean WorkNotificationDispatcher dispatcher() { return mock(WorkNotificationDispatcher.class); }
        @Bean PlatformTransactionManager transactions() { return new NotificationTestTransactions(); }
    }
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
    private final WorkNotificationDispatcher dispatcher = context.getBean(WorkNotificationDispatcher.class);
    private final ThreadPoolTaskExecutor executor = context.getBean("workNotificationExecutor", ThreadPoolTaskExecutor.class);
    private final TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    private final WorkNotificationEvents.Created request = new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.SERVICE_REQUEST, 1L);
    private final WorkNotificationEvents.Created food = new WorkNotificationEvents.Created(WorkNotificationEvents.Kind.FOOD_ORDER, 2L);
    @AfterEach void close() { context.close(); }

    @Test void committedDeliveryRunsOnWorkersAndCannotHoldTheCommittingThread() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        long committingThread = Thread.currentThread().getId();
        var workerThread = new AtomicLong();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getId());
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(dispatcher).dispatch(any(), eq(false), eq(0L));
        try {
            assertTimeout(Duration.ofSeconds(2), () -> transaction.executeWithoutResult(status -> {
                context.publishEvent(request);
                context.publishEvent(food);
                verifyNoInteractions(dispatcher);
                assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNotEquals(committingThread, workerThread.get());
            verify(dispatcher).dispatch(request, false, 0);
            verify(dispatcher).dispatch(food, false, 0);
        } finally { release.countDown(); }
    }

    @Test void rollbackNeverQueuesOrDispatches() {
        transaction.executeWithoutResult(status -> {
            context.publishEvent(request);
            context.publishEvent(food);
            status.setRollbackOnly();
        });
        assertEquals(0, executor.getThreadPoolExecutor().getTaskCount());
        verifyNoInteractions(dispatcher);
    }

    @Test void providerFailureCannotFailCommitOrPreventFollowingWork() throws Exception {
        var done = new CountDownLatch(2);
        doAnswer(invocation -> {
            done.countDown();
            if (invocation.getArgument(0).equals(request)) throw new IllegalArgumentException("Synthetic failure");
            return null;
        }).when(dispatcher).dispatch(any(), eq(false), eq(0L));
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> {
            context.publishEvent(request);
            context.publishEvent(food);
        }));
        assertTrue(done.await(2, TimeUnit.SECONDS));
        verify(dispatcher).dispatch(food, false, 0);
    }

    @Test void boundedQueueRejectionDoesNotRunOnCallerOrFailCommittedWork() throws Exception {
        var release = new CountDownLatch(1);
        Runnable blocked = () -> {
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        try {
            // Four workers plus 256 waiting entries exhaust the exact production bound.
            for (int i = 0; i < 260; i++) executor.execute(blocked);
            assertEquals(256, executor.getThreadPoolExecutor().getQueue().size());
            assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> context.publishEvent(request)));
            verifyNoInteractions(dispatcher);
            assertEquals(256, executor.getThreadPoolExecutor().getQueue().size());
        } finally { release.countDown(); }
    }
}
