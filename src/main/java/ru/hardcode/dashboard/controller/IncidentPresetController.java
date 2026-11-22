package ru.hardcode.dashboard.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.IncidentPresetRequest;
import ru.hardcode.dashboard.dto.IncidentPresetResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.IncidentPresetService;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = IncidentPresetController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class IncidentPresetController {

	static final String PATH = "/api/dashboard/incident-presets";

	private final CoordinatorAccessService access;
	private final IncidentPresetService service;

	public IncidentPresetController(CoordinatorAccessService access, IncidentPresetService service) {
		this.access = access;
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<List<IncidentPresetResponse>> list(Authentication authentication) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(owner(authentication)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<IncidentPresetResponse> get(Authentication authentication, @PathVariable long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(owner(authentication), id));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<IncidentPresetResponse> create(Authentication authentication, @RequestBody JsonNode body) {
		var result = service.create(owner(authentication), IncidentPresetRequest.from(body));
		return ResponseEntity.created(URI.create(PATH + "/" + result.id())).cacheControl(CacheControl.noStore()).body(result);
	}

	@PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<IncidentPresetResponse> update(Authentication authentication, @PathVariable long id,
			@RequestBody JsonNode body) {
		var result = service.update(owner(authentication), id, IncidentPresetRequest.from(body));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id) {
		service.delete(owner(authentication), id);
		return ResponseEntity.noContent().build();
	}

	private long owner(Authentication authentication) {
		return access.requireActiveUser(authentication).id();
	}
}
