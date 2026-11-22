package ru.hardcode.dashboard.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.PostmortemDraftResponse;
import ru.hardcode.dashboard.service.PostmortemDraftService;

@RestController
public class PostmortemDraftController {

	private final PostmortemDraftService drafts;

	public PostmortemDraftController(PostmortemDraftService drafts) {
		this.drafts = drafts;
	}

	@GetMapping("/api/dashboard/incidents/{incidentId}/postmortem")
	public ResponseEntity<PostmortemDraftResponse> latest(@PathVariable String incidentId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(drafts.latest(incidentId));
	}
}
