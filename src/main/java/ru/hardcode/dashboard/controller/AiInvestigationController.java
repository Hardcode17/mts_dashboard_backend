package ru.hardcode.dashboard.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.AiInvestigationRunResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.investigation.AiInvestigationService;

/**
 * Launch and read of an AI investigation of one incident card (KAN-40). The launch is
 * asynchronous: it stores the run bound to the published revision and returns at once,
 * while the model answer is polled from the run endpoint. A run stays readable in every
 * status, including failed and stale ones.
 */
@RestController
@RequestMapping(AiInvestigationController.PATH)
public class AiInvestigationController {

	public static final String PATH = "/api/incidents/{incidentId}/investigations";

	private final AiInvestigationService investigations;

	public AiInvestigationController(AiInvestigationService investigations) {
		this.investigations = investigations;
	}

	@PostMapping
	public ResponseEntity<AiInvestigationRunResponse> launch(@PathVariable String incidentId) {
		AiInvestigationRunResponse run = investigations.launch(incidentId);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(run);
	}

	@GetMapping("/{runId}")
	public ResponseEntity<AiInvestigationRunResponse> run(
			@PathVariable String incidentId, @PathVariable String runId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(investigations.run(incidentId, parseRunId(incidentId, runId)));
	}

	/** A runId outside the UUID format addresses no run of any incident. */
	private static UUID parseRunId(String incidentId, String runId) {
		try {
			return UUID.fromString(runId);
		} catch (IllegalArgumentException exception) {
			throw new AppException(AppErrors.AI_RUN_NOT_FOUND,
					Map.of("runId", String.valueOf(runId), "incidentId", incidentId));
		}
	}
}
