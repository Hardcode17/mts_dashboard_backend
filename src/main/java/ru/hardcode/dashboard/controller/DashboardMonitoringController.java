package ru.hardcode.dashboard.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse;
import ru.hardcode.dashboard.service.DashboardMonitoringService;

@RestController
@RequestMapping("/api/dashboard/monitoring")
public class DashboardMonitoringController {

	private final DashboardMonitoringService service;

	public DashboardMonitoringController(DashboardMonitoringService service) {
		this.service = service;
	}

	@GetMapping(path = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardMonitoringSummaryResponse> summary() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary());
	}
}
