package ru.hardcode.dashboard.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.NotificationConnectionRequest;
import ru.hardcode.dashboard.dto.NotificationConnectionResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.NotificationConnectionService;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = NotificationConnectionController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationConnectionController {

	static final String PATH = "/api/dashboard/notification-connections";

	private final NotificationConnectionService service;
	private final CoordinatorAccessService access;

	public NotificationConnectionController(NotificationConnectionService service, CoordinatorAccessService access) {
		this.service = service;
		this.access = access;
	}

	@GetMapping
	public ResponseEntity<List<NotificationConnectionResponse>> list(Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId(auth)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<NotificationConnectionResponse> get(@PathVariable long id, Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(userId(auth), id));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<NotificationConnectionResponse> create(@RequestBody JsonNode body, Authentication auth) {
		var connection = service.create(userId(auth), NotificationConnectionRequest.from(body));
		return ResponseEntity.created(URI.create(PATH + "/" + connection.id()))
				.cacheControl(CacheControl.noStore()).body(connection);
	}

	@PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<NotificationConnectionResponse> update(@PathVariable long id, @RequestBody JsonNode body,
			Authentication auth) {
		var connection = service.update(userId(auth), id, NotificationConnectionRequest.from(body));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connection);
	}

	private long userId(Authentication auth) {
		return access.requireActiveUser(auth).id();
	}
}
