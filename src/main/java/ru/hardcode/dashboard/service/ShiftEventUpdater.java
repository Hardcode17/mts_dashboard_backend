package ru.hardcode.dashboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Wake-up for the KAN-25 shift event queue: every committed snapshot publication triggers one
 * detection pass. The local Spring event is only a signal — the queue is cursor-driven, so a
 * missed wake-up is caught up by the next publication or by the queue poll itself.
 */
@Component
public class ShiftEventUpdater {

	private static final Logger log = LoggerFactory.getLogger(ShiftEventUpdater.class);

	private final ShiftEventService service;

	public ShiftEventUpdater(ShiftEventService service) {
		this.service = service;
	}

	@EventListener
	public void onSnapshotPublished(ItsmSnapshotPublished published) {
		try {
			service.processPending();
		} catch (RuntimeException failure) {
			// The publication has already committed; detection retries on the next wake-up or
			// poll, and its failure must never propagate into the publication path.
			log.error("Shift event detection after revision {} failed: {}", published.changeRevision(),
					failure.getClass().getSimpleName());
		}
	}
}
