package ru.hardcode.dashboard.controller;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Membership;
import ru.hardcode.dashboard.dto.DashboardSimilarIncidentsResponse;
import ru.hardcode.dashboard.dto.IncidentChangesPage;
import ru.hardcode.dashboard.dto.IncidentHistoryResponse;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.DashboardIncidentService;
import ru.hardcode.dashboard.service.IncidentListFilter;
import ru.hardcode.dashboard.service.ItsmApiClient;
import ru.hardcode.dashboard.service.ItsmIncidentChangeQueryService;
import ru.hardcode.dashboard.service.SimilarIncidentService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardIncidentController {

	private static final String CSV = "text/csv";

	private final DashboardIncidentService service;
	private final ItsmApiClient itsm;
	private final CoordinatorAccessService access;
	private final ItsmIncidentChangeQueryService changes;
	private final SimilarIncidentService similar;

	public DashboardIncidentController(DashboardIncidentService service, ItsmApiClient itsm,
			CoordinatorAccessService access, ItsmIncidentChangeQueryService changes,
			SimilarIncidentService similar) {
		this.service = service;
		this.itsm = itsm;
		this.access = access;
		this.changes = changes;
		this.similar = similar;
	}

	@GetMapping(path = "/incidents/{incidentId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardIncidentCardResponse> card(@PathVariable String incidentId) {
		// remainingSeconds is calculated at read time, even when the publication is unchanged.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.card(incidentId));
	}

	@GetMapping(path = "/incidents/{incidentId}/similar", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardSimilarIncidentsResponse> similar(@PathVariable String incidentId,
			@RequestParam(required = false) String limit) {
		// Suggestions are derived from the published snapshot only: reading never creates a relation.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(similar.similar(incidentId, limit));
	}

	@PatchMapping("/incidents/{incidentId}")
	public DashboardIncidentCardResponse patch(@PathVariable String incidentId,
			@RequestBody Map<String, Object> changes, Authentication auth) {
		// Changes are applied to the external ITSM first; the local state follows only after 200 OK.
		return service.patch(incidentId, changes, access.requireActiveUser(auth).id());
	}

	@GetMapping("/users")
	public List<Map<String, Object>> users() {
		return itsm.users();
	}

	@GetMapping(path = "/incidents", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DashboardIncidentListResponse> list(HttpServletRequest request,
			@RequestParam(defaultValue = "false") boolean includeCompleted,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String priority,
			@RequestParam(required = false) String product, @RequestParam(required = false) String team,
			@RequestParam(required = false) String state, @RequestParam(required = false) String serviceType,
			@RequestParam(required = false) String deadline, @RequestParam(required = false) String sort) {
		var filter = IncidentListFilter.from(q, priority, product, team, state, serviceType, deadline, sort);
		var page = service.list(groups(request), includeCompleted, filter, cursor, limit);
		return response(page.etag()).body(page.body());
	}

	/**
	 * Polling feed of INC changes by a monotonic event sequence (KAN-28). The cursor is the
	 * {@code lastEventSeq} of the previous page; events never change identity once committed.
	 */
	@GetMapping(path = "/incidents/changes", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<IncidentChangesPage> incidentChanges(
			@RequestParam(defaultValue = "0") long afterEventSeq,
			@RequestParam(defaultValue = "100") int limit) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(changes.changesFeed(afterEventSeq, limit));
	}

	/**
	 * The journal of one incident (KAN-28), including added and missing facts. A disappeared
	 * incident stays readable with the time of its last observation.
	 */
	@GetMapping(path = "/incidents/{incidentId}/history", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<IncidentHistoryResponse> incidentHistory(@PathVariable String incidentId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(changes.history(incidentId));
	}

	@GetMapping(path = "/incidents", produces = CSV)
	public ResponseEntity<String> export(HttpServletRequest request,
			@RequestParam(defaultValue = "false") boolean includeCompleted,
			@RequestParam(required = false) String q, @RequestParam(required = false) String priority,
			@RequestParam(required = false) String product, @RequestParam(required = false) String team,
			@RequestParam(required = false) String state, @RequestParam(required = false) String serviceType,
			@RequestParam(required = false) String deadline, @RequestParam(required = false) String sort) {
		var filter = IncidentListFilter.from(q, priority, product, team, state, serviceType, deadline, sort);
		var page = service.export(groups(request), includeCompleted, filter);
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
