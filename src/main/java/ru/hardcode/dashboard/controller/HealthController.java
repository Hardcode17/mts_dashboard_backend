package ru.hardcode.dashboard.controller;

import java.lang.management.ManagementFactory;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.HealthResponse;

/**
 * Простой health-эндпоинт для проверки, что сервис запущен и отвечает.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

	private final String serviceName;

	public HealthController(@Value("${spring.application.name:unknown}") String serviceName) {
		this.serviceName = serviceName;
	}

	@GetMapping
	public ResponseEntity<HealthResponse> health() {
		long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
		HealthResponse response = new HealthResponse(
				"UP",
				serviceName,
				Instant.now(),
				uptimeMillis / 1000,
				Runtime.version().toString()
		);
		return ResponseEntity.ok(response);
	}

}
