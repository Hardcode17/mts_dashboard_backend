package ru.hardcode.dashboard.worker;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.service.DeadlineWarningService;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "dashboard.deadline-warning.enabled", matchIfMissing = true)
public class DeadlineWarningWorker {

	private final DeadlineWarningService service;
	private final Clock clock;

	public DeadlineWarningWorker(DeadlineWarningService service, Clock clock) {
		this.service = service;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${dashboard.deadline-warning.poll-interval:PT30S}")
	public void check() {
		service.checkDue(clock.instant());
	}
}
