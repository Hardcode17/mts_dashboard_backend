package ru.hardcode.dashboard.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.DeadlineWarningRuleResponse;
import ru.hardcode.dashboard.dto.SetDeadlineWarningRuleRequest;
import ru.hardcode.dashboard.service.DeadlineWarningService;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = "/api/dashboard/deadline-warning-rule", produces = MediaType.APPLICATION_JSON_VALUE)
public class DeadlineWarningController {

	private final DeadlineWarningService service;

	public DeadlineWarningController(DeadlineWarningService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<DeadlineWarningRuleResponse> rule() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.rule());
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public DeadlineWarningRuleResponse setThreshold(@RequestBody JsonNode body) {
		var request = SetDeadlineWarningRuleRequest.from(body);
		return service.setThreshold(request.thresholdSeconds());
	}
}
