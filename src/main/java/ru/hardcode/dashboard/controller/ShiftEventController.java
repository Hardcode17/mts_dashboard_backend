package ru.hardcode.dashboard.controller;

import java.math.BigInteger;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.ShiftEventAckRequest;
import ru.hardcode.dashboard.dto.ShiftEventAckResponse;
import ru.hardcode.dashboard.dto.ShiftEventQueueResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.ShiftEventService;

/**
 * Queue of significant shift events (KAN-25) and the group «Увидел» acknowledgement (KAN-29),
 * read by the shift screen and consumed by the notification (KAN-35) flow. Both routes belong
 * to the human coordinator chain under {@code /api/dashboard/**}.
 */
@RestController
@RequestMapping("/api/dashboard/shift")
public class ShiftEventController {

	private static final Pattern NON_NEGATIVE_LONG = Pattern.compile("[0-9]{1,19}");
	private static final long MAX_EVENT_SEQ = Long.MAX_VALUE;

	private final ShiftEventService service;
	private final CoordinatorAccessService access;

	public ShiftEventController(ShiftEventService service, CoordinatorAccessService access) {
		this.service = service;
		this.access = access;
	}

	@GetMapping(path = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ShiftEventQueueResponse> events(
			@RequestParam(name = "sinceEventSeq", required = false) String sinceEventSeq,
			@RequestParam(defaultValue = "100") int limit) {
		long since = parseSinceEventSeq(sinceEventSeq);
		if (limit < 1 || limit > 500) {
			throw new AppException(AppErrors.INVALID_EVENT_LIMIT);
		}
		// The queue is cursor-driven over the durable journal: every poll first folds in the
		// committed changes that no wake-up has processed yet, then reads the queue.
		service.processPending();
		var page = service.events(since, limit);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page.body());
	}

	@PostMapping(path = "/events/ack", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ShiftEventAckResponse> acknowledge(@RequestBody ShiftEventAckRequest request,
			Authentication authentication) {
		// «Увидел» is a personal act of the signed-in coordinator; the local user becomes the
		// stored author of the first acknowledgement of every event in the exact set.
		long actor = access.requireActiveUser(authentication).id();
		var result = service.ack(actor, request.eventIds());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ShiftEventAckResponse(
				"acknowledged", result.confirmedEventIds(), result.events(), result.nextEventSeq()));
	}

	private static long parseSinceEventSeq(String value) {
		if (value == null || value.isBlank() || !NON_NEGATIVE_LONG.matcher(value).matches()
				|| new BigInteger(value).compareTo(BigInteger.valueOf(MAX_EVENT_SEQ)) > 0) {
			throw new AppException(AppErrors.INVALID_EVENT_SEQ);
		}
		return Long.parseLong(value);
	}
}
