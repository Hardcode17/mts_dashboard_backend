package ru.hardcode.dashboard.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.ItsmSourceStatusResponse;
import ru.hardcode.dashboard.dto.SnapshotStatusResponse;
import ru.hardcode.dashboard.service.SnapshotStatusService;

@RestController
@RequestMapping("/api/snapshot")
public class SnapshotStatusController {

	private final SnapshotStatusService statusService;

	public SnapshotStatusController(SnapshotStatusService statusService) {
		this.statusService = statusService;
	}

	@GetMapping("/status")
	public SnapshotStatusResponse status() {
		return statusService.status();
	}

	@GetMapping("/source-status")
	public ItsmSourceStatusResponse sourceStatus(@RequestParam("sourceId") String sourceId) {
		return statusService.sourceStatus(sourceId);
	}
}
