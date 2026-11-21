package ru.hardcode.dashboard.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.ItsmBatchAcceptedResponse;
import ru.hardcode.dashboard.dto.ItsmBatchMetadataResponse;
import ru.hardcode.dashboard.service.ItsmBatchIntakeService;
import ru.hardcode.dashboard.service.ItsmBatchQueryService;

@RestController
@RequestMapping("/api/integrations/itsm/batches")
public class ItsmBatchController {

	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
	public static final String SOURCE_ID_HEADER = "X-Source-Id";
	public static final String SOURCE_VERSION_HEADER = "X-Source-Version";

	private final ItsmBatchIntakeService intakeService;
	private final ItsmBatchQueryService queryService;

	public ItsmBatchController(ItsmBatchIntakeService intakeService, ItsmBatchQueryService queryService) {
		this.intakeService = intakeService;
		this.queryService = queryService;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ItsmBatchAcceptedResponse> receive(
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
			@RequestHeader(name = SOURCE_ID_HEADER, required = false) String sourceId,
			@RequestHeader(name = SOURCE_VERSION_HEADER, required = false) String sourceVersion,
			@RequestBody(required = false) byte[] body
	) {
		UUID batchId = intakeService.receive(idempotencyKey, sourceId, sourceVersion, body);
		return ResponseEntity.accepted()
				.location(URI.create("/api/integrations/itsm/batches/" + batchId))
				.body(new ItsmBatchAcceptedResponse(batchId));
	}

	@GetMapping("/{batchId}")
	public ItsmBatchMetadataResponse metadata(@PathVariable("batchId") UUID batchId) {
		return queryService.getMetadata(batchId);
	}
}
