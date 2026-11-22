package ru.hardcode.dashboard.service.mcpdashboard;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.configuration.mcpdashboard.ConditionalOnDashboardMcp;
import ru.hardcode.dashboard.configuration.mcpdashboard.McpDashboardProperties;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.ItsmIncidentChangeRepository;
import ru.hardcode.dashboard.repository.McpDashboardRepository;
import ru.hardcode.dashboard.repository.ItsmIncidentChangeRepository.JournalRow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Business rules of the read-only MCP Dashboard tools (KAN-86) behind the v1 contract of
 * {@code Hardcode17/mts_dashboard_data/docs/mcp}. The service owns argument validation,
 * cursor binding, freshness verdicts and the typed error semantics; the tool layer only
 * serializes the returned envelope. No tool writes anything: {@code assign_incident} is
 * not declared and the machine agent receives no ITSM write scopes.
 */
@Service
@ConditionalOnDashboardMcp
public class McpDashboardService {

	private static final Set<String> ALL_STATES =
			Set.of("Assigned", "WorkInProgress", "Research", "Resolved", "Closed");
	private static final Set<String> RESOLVED_STATES = Set.of("Resolved", "Closed");
	private static final List<String> TRACKED_FIELDS = List.of(
			"stateEnum", "assignedGroupName", "assignedEmployee", "priorityId", "deadline", "solution");
	private static final int TITLE_LIMIT = 500;
	private static final int DESCRIPTION_LIMIT = 4000;
	private static final int SOLUTION_LIMIT = 2000;
	private static final int SUMMARY_LIMIT = 500;
	private static final int IDENTIFIER_LIMIT = 200;
	private static final int CURSOR_LIMIT = 2048;
	private static final int MAX_TOKENS = 8;

	private final McpDashboardRepository repository;
	private final DashboardIncidentRepository dashboards;
	private final ItsmIncidentChangeRepository journal;
	private final McpDashboardCursors cursors;
	private final McpDashboardProperties properties;
	private final ItsmDirectory directory;
	private final Clock clock;
	private final ObjectMapper json;

	public McpDashboardService(McpDashboardRepository repository, DashboardIncidentRepository dashboards,
			ItsmIncidentChangeRepository journal, McpDashboardCursors cursors, McpDashboardProperties properties,
			ItsmDirectory directory, Clock clock, ObjectMapper json) {
		this.repository = repository;
		this.dashboards = dashboards;
		this.journal = journal;
		this.cursors = cursors;
		this.properties = properties;
		this.directory = directory;
		this.clock = clock;
		this.json = json;
	}

	/** Narrow port over the external ITSM directory used by {@code find_assignees}. */
	public interface ItsmDirectory {

		List<Map<String, Object>> users();
	}

	// ---------------------------------------------------------------- list_incidents

	/**
	 * One page of the published snapshot. The first call (no cursor) fixes the coherent
	 * snapshot id and the journal checkpoint in one repeatable-read transaction; later
	 * pages continue the same walk through a signed cursor bound to that snapshot.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public JsonNode listIncidents(Map<String, Object> arguments) {
		Arguments args = Arguments.of(arguments, Set.of("filters", "limit", "cursor"));
		McpDashboardContract.Filters filters = args.filters();
		int limit = args.limit(20);
		String caller = caller();
		Instant now = clock.instant();
		if (args.cursor() == null) {
			String snapshotId = "snap-" + dashboards.publication().eventSeq() + "-" + digest(filters.key());
			String checkpoint = journalCheckpoint(caller, now);
			return pageOfIncidents(filters, limit, 0, snapshotId, checkpoint, caller, now);
		}
		validateCursorText(args.cursor());
		McpDashboardCursors.Cursor cursor = cursors.decode(args.cursor(), "list_incidents", caller, null, now);
		String snapshotId = cursor.binding();
		String expected = "snap-" + snapshotEventSeq(snapshotId) + "-" + digest(filters.key());
		if (!snapshotId.equals(expected)) {
			throw McpDashboardException.invalidCursor("cursor belongs to a different snapshot or filter set");
		}
		// Every page of one walk reports the checkpoint fixed by the first call.
		String checkpoint = cursor.extraString("c") != null ? cursor.extraString("c") : journalCheckpoint(caller, now);
		return pageOfIncidents(filters, limit, cursor.position(), snapshotId, checkpoint, caller, now);
	}

	private JsonNode pageOfIncidents(McpDashboardContract.Filters filters, int limit, long offset,
			String snapshotId, String checkpoint, String caller, Instant now) {
		List<McpDashboardRepository.IncidentRow> rows = repository.incidentsPage(toFilter(filters), limit + 1, offset);
		boolean hasMore = rows.size() > limit;
		List<McpDashboardContract.Incident> items = rows.stream().limit(limit)
				.map(McpDashboardService::toIncident).toList();
		String nextCursor = hasMore
				? cursors.encode("list_incidents", caller, snapshotId, offset + limit,
						properties.cursorTtl(false), now, Map.of("c", checkpoint))
				: null;
		var data = new McpDashboardContract.ListIncidentsData(items, nextCursor, snapshotId, checkpoint);
		return envelope(json.valueToTree(data), meta(now, snapshotWarnings()));
	}

	// ---------------------------------------------------------------- get_incident

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public JsonNode getIncident(Map<String, Object> arguments) {
		Arguments args = Arguments.of(arguments, Set.of("incidentId"));
		String incidentId = args.requiredId("incidentId");
		var card = repository.card(incidentId)
				.orElseThrow(() -> McpDashboardException.notFound(
						"Incident " + incidentId + " is not available in the published snapshot"));
		var warnings = new ArrayList<String>();
		String description = truncatedText(card.description(), DESCRIPTION_LIMIT,
				"Описание обрезано до " + DESCRIPTION_LIMIT + " символов", warnings);
		String solution = truncatedText(card.solution(), SOLUTION_LIMIT,
				"Решение обрезано до " + SOLUTION_LIMIT + " символов", warnings);
		var value = new McpDashboardContract.Card(incidentId, titleOf(card.header(), card.productKey(), card.id()),
				card.state(), card.priorityId(), card.serviceKey(), null, card.groupName(), card.personLogin(),
				card.deadline(), instant(card.updatedAt()), description, solution);
		return envelope(json.valueToTree(value), meta(clock.instant(), warnings));
	}

	// ---------------------------------------------------------------- list_incident_changes

	/**
	 * Replay of the shared KAN-28 journal behind a signed checkpoint. The cursor always
	 * binds to the stored journal epoch; a reset journal immediately expires every cursor.
	 */
	@Transactional(readOnly = true)
	public JsonNode listIncidentChanges(Map<String, Object> arguments) {
		Arguments args = Arguments.of(arguments, Set.of("cursor", "limit"));
		int limit = args.limit(20);
		String caller = caller();
		Instant now = clock.instant();
		String cursorText = args.requiredText("cursor", 1, CURSOR_LIMIT);
		// The binding is checked manually so a reset journal is CURSOR_EXPIRED (resync),
		// not a plain invalid cursor.
		String binding = repository.journalEpoch().toString();
		McpDashboardCursors.Cursor cursor = cursors.decode(cursorText, "list_incident_changes", caller, null, now);
		if (!binding.equals(cursor.binding())) {
			throw McpDashboardException.cursorExpired(
					"journal epoch has changed; resync from a fresh snapshot");
		}
		long afterEventSeq = cursor.position();
		long maxEventSeq = repository.maxEventSeq();
		if (afterEventSeq > maxEventSeq) {
			throw McpDashboardException.cursorExpired(
					"cursor position is beyond the current journal; resync from a fresh snapshot");
		}
		List<JournalRow> rows = new ArrayList<>(
				journal.afterEventSeq(afterEventSeq, limit + 1));
		boolean hasMore = rows.size() > limit;
		if (hasMore) {
			rows.removeLast();
		}
		UUID epoch = UUID.fromString(binding);
		List<McpDashboardContract.Event> events = rows.stream()
				.map(row -> toEvent(row, epoch)).toList();
		long lastSeq = events.isEmpty() ? afterEventSeq : Long.parseLong(events.getLast().eventSeq());
		String nextCursor = cursors.encode("list_incident_changes", caller, binding, lastSeq,
				properties.cursorTtl(true), now);
		var data = new McpDashboardContract.ChangesData(events, nextCursor, hasMore);
		return envelope(json.valueToTree(data), meta(now, snapshotWarnings()));
	}

	// ---------------------------------------------------------------- find_similar_incidents

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public JsonNode findSimilarIncidents(Map<String, Object> arguments) {
		Arguments args = Arguments.of(arguments, Set.of("incidentId", "filters", "limit", "cursor"));
		int limit = args.limit(20);
		McpDashboardContract.Filters filters = args.filters();
		validateSimilarFilters(filters);
		String incidentId = args.requiredId("incidentId");
		String caller = caller();
		Instant now = clock.instant();
		String binding = "similar:" + incidentId;
		long offset = offsetOf(args.cursor(), "find_similar_incidents", caller, binding, now);
		var target = repository.incident(incidentId)
				.orElseThrow(() -> McpDashboardException.notFound(
						"Incident " + incidentId + " is not available in the published snapshot"));
		String[] tokens = tokens(target.header() + " " + target.description());
		List<String> states = filters.states() == null ? List.copyOf(RESOLVED_STATES) : filters.states();
		List<McpDashboardRepository.AnalogueRow> rows = repository.analoguesPage(incidentId, tokens, states,
				filters.serviceId(), filters.assignedGroupId(), filters.unassigned(), filters.query(),
				limit + 1, offset);
		boolean hasMore = rows.size() > limit;
		List<McpDashboardContract.Analogue> items = rows.stream().limit(limit)
				.map(row -> toAnalogue(target, row)).toList();
		String nextCursor = hasMore
				? cursors.encode("find_similar_incidents", caller, binding, offset + limit,
						properties.cursorTtl(false), now)
				: null;
		var data = new McpDashboardContract.SimilarData(items, nextCursor);
		return envelope(json.valueToTree(data), meta(now, snapshotWarnings()));
	}

	// ---------------------------------------------------------------- find_resolver_groups

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public JsonNode findResolverGroups(Map<String, Object> arguments) {
		Arguments args = Arguments.of(arguments, Set.of("incidentId", "limit", "cursor"));
		int limit = args.limit(20);
		String incidentId = args.requiredId("incidentId");
		String caller = caller();
		Instant now = clock.instant();
		String binding = "groups:" + incidentId;
		long offset = offsetOf(args.cursor(), "find_resolver_groups", caller, binding, now);
		var card = repository.card(incidentId)
				.orElseThrow(() -> McpDashboardException.notFound(
						"Incident " + incidentId + " is not available in the published snapshot"));
		List<String> official = repository.officialAssignments(incidentId);
		Instant asOf = snapshotAsOf();
		var warnings = new ArrayList<String>();
		var items = new ArrayList<McpDashboardContract.Group>();
		for (String team : official) {
			items.add(new McpDashboardContract.Group(team, team, "confirmed", List.of(
					new McpDashboardContract.Evidence("directory",
							"coordination:official-assignment:" + incidentId, asOf,
							"Подтверждённый владелец официального назначения ITSM"))));
		}
		if (hasText(card.serviceKey())) {
			List<String> historical = repository.historicalResolverGroups(incidentId, card.serviceKey(),
					Integer.MAX_VALUE, 0);
			for (String group : historical) {
				if (!official.contains(group)) {
					items.add(new McpDashboardContract.Group(group, group, "historical", List.of(
							new McpDashboardContract.Evidence("history", "history:" + group, asOf,
									"Группа решала завершённые случаи этого сервиса"))));
				}
			}
		}
		boolean hasMore = items.size() > offset + limit;
		List<McpDashboardContract.Group> page = items.stream()
				.skip(Math.min(offset, items.size()))
				.limit(limit).toList();
		String nextCursor = hasMore
				? cursors.encode("find_resolver_groups", caller, binding, offset + limit,
						properties.cursorTtl(false), now)
				: null;
		Recommendation recommendation = recommendation(card, official);
		var data = new McpDashboardContract.ResolverGroupsData(page, nextCursor,
				recommendation.groupId(), recommendation.reason());
		return envelope(json.valueToTree(data), meta(now, warnings));
	}

	private record Recommendation(String groupId, String reason) {
	}

	/**
	 * {@code recommendedGroupId} is allowed only for the single confirmed owner of the
	 * official assignment; any current assignment suppresses the recommendation.
	 */
	private Recommendation recommendation(McpDashboardRepository.CardRow card, List<String> official) {
		if (hasText(card.groupName()) || hasText(card.personLogin())) {
			return new Recommendation(null, "already_assigned");
		}
		if (official.size() == 1) {
			return new Recommendation(official.getFirst(), "confirmed_owner");
		}
		if (official.size() > 1) {
			return new Recommendation(null, "ambiguous_owner");
		}
		return new Recommendation(null, "no_owner");
	}

	// ---------------------------------------------------------------- find_assignees

	@Transactional(readOnly = true)
	public JsonNode findAssignees(Map<String, Object> arguments) {
		Arguments args = Arguments.of(arguments, Set.of("groupId", "query", "limit", "cursor"));
		int limit = args.limit(20);
		String groupId = args.requiredText("groupId", 1, IDENTIFIER_LIMIT);
		String query = args.optionalText("query", 1, 200);
		String caller = caller();
		Instant now = clock.instant();
		String binding = "assignees:" + groupId;
		long offset = offsetOf(args.cursor(), "find_assignees", caller, binding, now);
		List<Map<String, Object>> users = directory.users();
		List<McpDashboardContract.Assignee> matched = new ArrayList<>();
		for (Map<String, Object> user : users) {
			String groupName = textValue(user.get("groupName"));
			if (!groupId.equals(groupName)) {
				continue;
			}
			String personId = hasText(textValue(user.get("sysId")))
					? textValue(user.get("sysId")) : textValue(user.get("login"));
			if (!hasText(personId)) {
				continue;
			}
			String displayName = hasText(textValue(user.get("displayName")))
					? textValue(user.get("displayName")) : textValue(user.get("login"));
			if (query != null && !contains(displayName, query) && !contains(textValue(user.get("login")), query)) {
				continue;
			}
			matched.add(new McpDashboardContract.Assignee(personId, displayName == null ? personId : displayName,
					groupId, "unknown", "unknown", List.of()));
		}
		matched.sort(Comparator.comparing(McpDashboardContract.Assignee::personId));
		boolean hasMore = matched.size() > offset + limit;
		List<McpDashboardContract.Assignee> page = matched.stream()
				.skip(Math.min(offset, matched.size()))
				.limit(limit).toList();
		String nextCursor = hasMore
				? cursors.encode("find_assignees", caller, binding, offset + limit, properties.cursorTtl(false), now)
				: null;
		var data = new McpDashboardContract.AssigneesData(page, nextCursor);
		var meta = new McpDashboardContract.Meta(now, List.of(
				new McpDashboardContract.Source("itsm-directory", null, "fresh")), "complete", List.of());
		return envelope(json.valueToTree(data), meta);
	}

	// ---------------------------------------------------------------- helpers

	private McpDashboardRepository.IncidentFilter toFilter(McpDashboardContract.Filters filters) {
		if (filters.siteId() != null) {
			throw McpDashboardException.unsupportedFilter(
					"siteId filter is not supported by this deployment: incidents carry no site data");
		}
		return new McpDashboardRepository.IncidentFilter(filters.states(), filters.priorityIds(),
				filters.serviceId(), filters.assignedGroupId(), filters.unassigned(), filters.query());
	}

	private void validateSimilarFilters(McpDashboardContract.Filters filters) {
		if (filters.siteId() != null) {
			throw McpDashboardException.unsupportedFilter(
					"siteId filter is not supported by this deployment: incidents carry no site data");
		}
		if (filters.states() != null && !RESOLVED_STATES.containsAll(filters.states())) {
			throw McpDashboardException.invalidArgument(
					"find_similar_incidents accepts only Resolved and Closed states");
		}
	}

	private static McpDashboardContract.Incident toIncident(McpDashboardRepository.IncidentRow row) {
		return new McpDashboardContract.Incident(row.id(), titleOf(row.header(), row.productKey(), row.id()),
				row.state(), row.priorityId(), row.serviceKey(), null, row.groupName(), row.personLogin(),
				row.deadline(), instant(row.updatedAt()));
	}

	private McpDashboardContract.Analogue toAnalogue(McpDashboardRepository.IncidentRow target,
			McpDashboardRepository.AnalogueRow row) {
		var signals = new ArrayList<String>();
		if (row.exactCode()) {
			signals.add("Совпадает код ошибки");
		}
		if (target.serviceKey() != null && target.serviceKey().equals(row.serviceKey())) {
			signals.add("Совпадает сервис: «" + row.serviceKey() + "»");
		}
		var differences = new ArrayList<String>();
		if (target.priorityId() != row.priorityId()) {
			differences.add("Другой приоритет: " + target.priorityId() + " → " + row.priorityId());
		}
		return new McpDashboardContract.Analogue(
				toIncident(new McpDashboardRepository.IncidentRow(row.id(), row.state(), row.priorityId(),
						row.deadline(), row.updatedAt(), row.serviceKey(), row.productKey(), row.groupName(),
						row.personLogin(), row.header(), null)),
				truncated(row.solution(), SUMMARY_LIMIT),
				List.copyOf(signals), List.copyOf(differences), row.groupName(),
				new McpDashboardContract.Evidence("history", "itsm:" + row.id(),
						instant(row.updatedAt()) != null ? instant(row.updatedAt()) : row.submitDate(),
						"Решение завершённого случая " + row.id()));
	}

	private McpDashboardContract.Event toEvent(JournalRow row, UUID epoch) {
		return new McpDashboardContract.Event(
				"inc-change:" + epoch + ":" + row.eventSeq(),
				Long.toString(row.eventSeq()),
				row.sourceId(), row.incidentId(),
				Long.toString(row.sourceVersion()), Long.toString(row.changeRevision()),
				row.changeKind(), row.observedAt(), row.sourceTime(),
				changedFields(row.beforeData(), row.afterData()));
	}

	/** Real differences among the six tracked fields, in the contract order. */
	private List<String> changedFields(String beforeJson, String afterJson) {
		JsonNode before = readDocument(beforeJson);
		JsonNode after = readDocument(afterJson);
		var changed = new ArrayList<String>();
		for (String field : TRACKED_FIELDS) {
			Object was = normalize(before.get(field));
			Object now = normalize(after.get(field));
			if (!Objects.equals(was, now)) {
				changed.add(field);
			}
		}
		return List.copyOf(changed);
	}

	private JsonNode readDocument(String document) {
		if (document == null || document.isBlank()) {
			return json.createObjectNode();
		}
		try {
			return json.readTree(document);
		} catch (RuntimeException broken) {
			return json.createObjectNode();
		}
	}

	private static Object normalize(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return null;
		}
		if (value.isNumber()) {
			return new BigDecimal(value.numberValue().toString());
		}
		if (value.isString()) {
			return value.stringValue();
		}
		return value.toString();
	}

	private String journalCheckpoint(String caller, Instant now) {
		return cursors.encode("list_incident_changes", caller, repository.journalEpoch().toString(),
				repository.maxEventSeq(), properties.cursorTtl(true), now);
	}

	private long offsetOf(String cursor, String tool, String caller, String binding, Instant now) {
		if (cursor == null) {
			return 0;
		}
		validateCursorText(cursor);
		return cursors.decode(cursor, tool, caller, binding, now).position();
	}

	private McpDashboardContract.Meta meta(Instant now, List<String> warnings) {
		Instant asOf = snapshotAsOf();
		String status = asOf != null
				&& Duration.between(asOf, now).abs().compareTo(properties.snapshotFresh()) <= 0
						? "fresh" : "stale";
		return new McpDashboardContract.Meta(now, List.of(
				new McpDashboardContract.Source("itsm-snapshot", asOf, status)),
				warnings.isEmpty() ? "complete" : "partial", List.copyOf(warnings));
	}

	private JsonNode envelope(Object data, McpDashboardContract.Meta meta) {
		ObjectNode root = json.createObjectNode();
		root.put("contractVersion", McpDashboardContract.CONTRACT_VERSION);
		root.put("ok", true);
		root.set("data", json.valueToTree(data));
		root.set("meta", json.valueToTree(meta));
		return root;
	}

	public JsonNode errorEnvelope(McpDashboardException error) {
		ObjectNode root = json.createObjectNode();
		root.put("contractVersion", McpDashboardContract.CONTRACT_VERSION);
		root.put("ok", false);
		ObjectNode failure = root.putObject("error");
		failure.put("code", error.wireCode());
		failure.put("message", truncated(error.getMessage() == null
				? "MCP dashboard tool call failed" : error.getMessage(), SUMMARY_LIMIT));
		failure.put("retryable", error.retryable());
		failure.put("recovery", error.wireRecovery());
		return root;
	}

	private Instant snapshotAsOf() {
		return dashboards.publication().updatedAt();
	}

	private List<String> snapshotWarnings() {
		var warnings = new ArrayList<String>();
		Instant asOf = snapshotAsOf();
		if (asOf == null) {
			warnings.add("Нет успешной публикации снимка ITSM");
		} else if (Duration.between(asOf, clock.instant()).abs().compareTo(properties.snapshotFresh()) > 0) {
			warnings.add("Снимок ITSM старше заявленной свежести " + properties.snapshotFresh());
		}
		return warnings;
	}

	private String caller() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt && hasText(jwt.getName())) {
			return jwt.getName();
		}
		return "anonymous";
	}

	private static String titleOf(String header, String productKey, String incidentId) {
		String title = hasText(header) ? header : hasText(productKey) ? productKey : incidentId;
		return truncated(title, TITLE_LIMIT);
	}

	private static String truncated(String text, int limit) {
		if (text == null || text.length() <= limit) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, limit));
	}

	private static String truncatedText(String value, int limit, String warning, List<String> warnings) {
		if (value == null) {
			return null;
		}
		String cut = truncated(value, limit);
		if (!cut.equals(value)) {
			warnings.add(warning);
		}
		return cut;
	}

	private static String textValue(Object value) {
		return value instanceof String text && !text.isBlank() ? text : null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static boolean contains(String value, String query) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
	}

	private static Instant instant(String isoDate) {
		if (isoDate == null || isoDate.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(isoDate);
		} catch (java.time.format.DateTimeParseException unparsable) {
			return null;
		}
	}

	private static void validateCursorText(String cursor) {
		if (cursor == null || cursor.isBlank() || cursor.length() > CURSOR_LIMIT) {
			throw McpDashboardException.invalidCursor("cursor must be a string of 1..2048 characters");
		}
	}

	private static long snapshotEventSeq(String snapshotId) {
		int second = snapshotId.indexOf('-', "snap-".length());
		if (!snapshotId.startsWith("snap-") || second <= 0) {
			throw McpDashboardException.invalidCursor("cursor belongs to a different snapshot or filter set");
		}
		try {
			return Long.parseLong(snapshotId.substring("snap-".length(), second));
		} catch (NumberFormatException broken) {
			throw McpDashboardException.invalidCursor("cursor belongs to a different snapshot or filter set");
		}
	}

	private static String digest(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hash, 0, 8);
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 is unavailable", unavailable);
		}
	}

	private static String[] tokens(String text) {
		if (!hasText(text)) {
			return new String[0];
		}
		var unique = new LinkedHashSet<String>();
		for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
			if (raw.length() >= 3) {
				unique.add(raw);
			}
			if (unique.size() == MAX_TOKENS) {
				break;
			}
		}
		return unique.toArray(new String[0]);
	}

	/**
	 * Validated arguments of one tool call: unknown fields are rejected (a v1 client must
	 * not send them), enums and ranges are checked before any business read.
	 */
	private record Arguments(Map<String, Object> source) {

		static Arguments of(Map<String, Object> arguments, Set<String> allowed) {
			Map<String, Object> map = arguments == null ? Map.of() : arguments;
			for (String key : map.keySet()) {
				if (!allowed.contains(key)) {
					throw McpDashboardException.invalidArgument("Unknown argument " + key);
				}
			}
			return new Arguments(map);
		}

		int limit(int fallback) {
			Object value = source.get("limit");
			if (value == null) {
				return fallback;
			}
			if (!(value instanceof Number number) || !(number instanceof Integer || number instanceof Long)
					|| number.longValue() < 1 || number.longValue() > 100) {
				throw McpDashboardException.invalidArgument("limit must be an integer between 1 and 100");
			}
			return number.intValue();
		}

		String cursor() {
			Object value = source.get("cursor");
			return value instanceof String text ? text : null;
		}

		String requiredText(String name, int minLength, int maxLength) {
			Object value = source.get(name);
			if (!(value instanceof String text) || text.length() < minLength || text.length() > maxLength) {
				throw McpDashboardException.invalidArgument(
						name + " must be a string of " + minLength + ".." + maxLength + " characters");
			}
			return text;
		}

		String optionalText(String name, int minLength, int maxLength) {
			return source.get(name) == null ? null : requiredText(name, minLength, maxLength);
		}

		String requiredId(String name) {
			String value = requiredText(name, 1, 200);
			if (!value.matches("INC[0-9]{6,18}")) {
				throw McpDashboardException.invalidArgument(name + " must match INC[0-9]{6,18}");
			}
			return value;
		}

		McpDashboardContract.Filters filters() {
			Object value = source.get("filters");
			if (value == null) {
				return new McpDashboardContract.Filters(null, null, null, null, null, null, null);
			}
			if (!(value instanceof Map<?, ?> map)) {
				throw McpDashboardException.invalidArgument("filters must be an object");
			}
			for (Object key : map.keySet()) {
				if (!Set.of("query", "states", "priorityIds", "serviceId", "siteId", "assignedGroupId",
						"unassigned").contains(key)) {
					throw McpDashboardException.invalidArgument("Unknown filter " + key);
				}
			}
			Object unassigned = map.get("unassigned");
			if (unassigned != null && !(unassigned instanceof Boolean)) {
				throw McpDashboardException.invalidArgument("unassigned must be a boolean");
			}
			return new McpDashboardContract.Filters(
					optionalFilterText(map.get("query"), "query"),
					filterStates(map.get("states")),
					filterPriorities(map.get("priorityIds")),
					optionalFilterText(map.get("serviceId"), "serviceId"),
					optionalFilterText(map.get("siteId"), "siteId"),
					optionalFilterText(map.get("assignedGroupId"), "assignedGroupId"),
					(Boolean) unassigned);
		}

		private static String optionalFilterText(Object value, String name) {
			if (value == null) {
				return null;
			}
			if (!(value instanceof String text) || text.length() < 1 || text.length() > IDENTIFIER_LIMIT) {
				throw McpDashboardException.invalidArgument(name + " must be a string of 1..200 characters");
			}
			return text;
		}

		private static List<String> filterStates(Object value) {
			if (value == null) {
				return null;
			}
			if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 5) {
				throw McpDashboardException.invalidArgument("states must be a non-empty array of at most 5");
			}
			var states = new ArrayList<String>();
			for (Object item : list) {
				if (!(item instanceof String state) || !ALL_STATES.contains(state)) {
					throw McpDashboardException.invalidArgument("states contains an unknown state");
				}
				states.add(state);
			}
			if (new HashSet<>(states).size() != states.size()) {
				throw McpDashboardException.invalidArgument("states must be unique");
			}
			return List.copyOf(states);
		}

		private static List<Integer> filterPriorities(Object value) {
			if (value == null) {
				return null;
			}
			if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 4) {
				throw McpDashboardException.invalidArgument("priorityIds must be an array of 1..4");
			}
			var priorities = new ArrayList<Integer>();
			for (Object item : list) {
				if (!(item instanceof Number number) || !(number instanceof Integer || number instanceof Long)
						|| number.intValue() < 1 || number.intValue() > 4) {
					throw McpDashboardException.invalidArgument("priorityIds must contain integers 1..4");
				}
				priorities.add(number.intValue());
			}
			if (new HashSet<>(priorities).size() != priorities.size()) {
				throw McpDashboardException.invalidArgument("priorityIds must be unique");
			}
			return List.copyOf(priorities);
		}
	}
}
