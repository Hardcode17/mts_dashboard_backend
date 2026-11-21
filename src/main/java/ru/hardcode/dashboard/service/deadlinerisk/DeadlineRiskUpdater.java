package ru.hardcode.dashboard.service.deadlinerisk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.service.ItsmSnapshotPublished;

/**
 * Wake-up for the KAN-82 deadline risk pass: every committed snapshot publication triggers one
 * full scoring refresh. The local Spring event is only a signal — the pass is a complete refresh,
 * so a missed or failed wake-up is caught up by the next publication.
 */
@Component
public class DeadlineRiskUpdater {

	private static final Logger log = LoggerFactory.getLogger(DeadlineRiskUpdater.class);

	private final DeadlineRiskService service;

	public DeadlineRiskUpdater(DeadlineRiskService service) {
		this.service = service;
	}

	@EventListener
	public void onSnapshotPublished(ItsmSnapshotPublished published) {
		try {
			service.recompute();
		} catch (RuntimeException failure) {
			// The publication has already committed; the scoring is repeated by the next
			// wake-up, and its failure must never propagate into the publication path.
			log.error("Deadline risk recompute after revision {} failed: {}", published.changeRevision(),
					failure.getClass().getSimpleName());
		}
	}
}
