package ru.hardcode.itsmmock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

@TestConfiguration(proxyBeanMethods = false)
class ControlledScheduler {
    private static final Instant START = Instant.parse("2026-11-21T00:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    @Bean
    TaskScheduler taskScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.fixed(START, ZoneOffset.UTC));
        doReturn(mock(ScheduledFuture.class)).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        return scheduler;
    }

    @Bean
    SchedulingConfigurer useControlledScheduler(TaskScheduler scheduler) {
        return registrar -> registrar.setTaskScheduler(scheduler);
    }

    static Runnable callback(TaskScheduler scheduler) {
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(callback.capture(), eq(START.plus(INTERVAL)), eq(INTERVAL));
        return callback.getValue();
    }
}
