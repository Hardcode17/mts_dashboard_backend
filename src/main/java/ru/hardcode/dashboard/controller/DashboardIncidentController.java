package ru.hardcode.dashboard.controller;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Membership;
import ru.hardcode.dashboard.service.DashboardIncidentService;

@RestController
@RequestMapping("/api/dashboard/incidents")
public class DashboardIncidentController {

	private static final String CSV = "text/csv";

	private final DashboardIncidentService service;

	public DashboardIncidentController(DashboardIncidentService service) {
		this.service = service;
	}

	@GetMapping(path = "/{incidentId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardIncidentCardResponse> card(@PathVariable String incidentId) {
		// remainingSeconds is calculated at read time, even when the publication is unchanged.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.card(incidentId));
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardIncidentListResponse> list(HttpServletRequest request,
			@RequestParam(defaultValue = "false") boolean includeCompleted,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		var page = service.list(groups(request), includeCompleted, cursor, limit);
		return response(page.etag()).body(page.body());
	}

	@GetMapping(produces = CSV)
	public ResponseEntity<String> export(HttpServletRequest request,
			@RequestParam(defaultValue = "false") boolean includeCompleted) {
		var page = service.export(groups(request), includeCompleted);
		var csv = new StringBuilder("incidentId,priorityId,stateEnum,deadline,groups\r\n");
		for (var item : page.body().items()) {
			csv.append(item.id()).append(',').append(item.priorityId()).append(',').append(item.stateEnum()).append(',')
					.append(item.deadline() == null ? "" : item.deadline()).append(',')
					.append(item.groupMemberships().stream().map(Membership::group).collect(Collectors.joining(";")))
					.append("\r\n");
		}
		return response(page.etag()).contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.body(csv.toString());
	}

	private static List<String> groups(HttpServletRequest request) {
		String[] values = request.getParameterValues("group");
		return values == null ? List.of() : Arrays.asList(values);
	}

	private static ResponseEntity.BodyBuilder response(String etag) {
		return ResponseEntity.ok().eTag(etag).varyBy("Accept")
				.cacheControl(CacheControl.noCache().cachePrivate());
	}
}
