package ru.hardcode.dashboard.controller;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.DashboardNotificationResponse;
import ru.hardcode.dashboard.dto.NotificationDeliveryResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.NotificationDeliveryService;

/**
 * The recipient's own notifications of KAN-35: the durable list with per-connection delivery
 * states, the app-read confirmation and the retry of one failed delivery. Raw addresses and
 * secret references never appear in the responses; a delivery is identified by its connection.
 */
@RestController
@RequestMapping(path = NotificationController.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class NotificationController {

	static final String PATH = "/api/dashboard/notifications";

	private final NotificationDeliveryService service;
	private final CoordinatorAccessService access;

	public NotificationController(NotificationDeliveryService service, CoordinatorAccessService access) {
		this.service = service;
		this.access = access;
	}

	@GetMapping
	public ResponseEntity<List<DashboardNotificationResponse>> list(Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(userId(auth)));
	}

	@PostMapping("/{id}/read")
	public ResponseEntity<DashboardNotificationResponse> read(@PathVariable long id, Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.markReadInApp(userId(auth), id));
	}

	@PostMapping("/deliveries/{id}/retry")
	public ResponseEntity<NotificationDeliveryResponse> retry(@PathVariable long id, Authentication auth) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.retryDelivery(userId(auth), id));
	}

	private long userId(Authentication auth) {
		return access.requireActiveUser(auth).id();
	}
}
