package ru.hardcode.dashboard.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates the canonical KAN-20 OpenAPI documents and their synthetic fixtures.
 *
 * <p>Checks, in order: document structure and every internal {@code $ref}; every named example
 * of every request body, response and webhook against the schema of its media type, with
 * {@code x-fixture} examples checked against the same fixture file; the scenarios and the agreed
 * model (string INC, nullable fields, UTC, one canonical route per reading, {@code revision}
 * versus {@code sourceVersion} versus {@code eventSeq}, exact ack set, full snapshots with one
 * changed INC, {@code 401}/{@code 404} examples with code and message); and, when a
 * {@link PublishedHost} is given, the published runtime document, the published YAML, the
 * specification, every frame document and every schema resource.
 *
 * <p>Invalid input yields violations and never changes anything.
 */
final class OpenApiContractValidator {

	static final String FRAME_POINTER = "$frame";
	static final String FIXTURE = "x-fixture";
	static final java.util.regex.Pattern FIXTURE_NAME = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]*$");
	static final String FRAME_INDEX = PublishedSpecPublisher.FRAME_INDEX;

	/** Fixture names (without {@code .json}) that must exist in the synthetic directory. */
	static final Set<String> REQUIRED_FIXTURES = Set.of("snapshot-minimal", "snapshot-next-tick",
			"source-batch-request", "ingest-accepted-202", "ingest-accepted-replay-202",
			"ingest-batch-received-200", "ingest-batch-next-attempt-200", "ingest-batch-succeeded-200",
			"snapshot-status-stale", "snapshot-status-published", "ingest-batch-invalid-200",
			"list-default", "list-critical", "list-empty-group", "list-before-first-success",
			"card-without-deadline", "card-research", "card-completed", "card-before-first-success",
			"card-not-found", "card-deadline-risk", "sources-published", "sources-stale", "events-poll-two-events",
			"events-heartbeat", "events-ack-request", "events-ack-response");

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Pattern INC = Pattern.compile("^INC[0-9]{6,18}$");
	private static final Pattern UTC = Pattern.compile(
			"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|\\+00:00)$");
	private static final Set<String> METHODS =
			Set.of("get", "post", "put", "patch", "delete", "options", "head", "trace");

	static final List<String> DOCUMENT_NAMES = List.of("itsm-ingest", "dashboard-api");

	private static final String INGEST = "/api/integrations/itsm/batches";
	private static final String DASHBOARD_INCIDENTS = "/api/dashboard/incidents";
	private static final String DASHBOARD_CARD = "/api/dashboard/incidents/{incidentId}";
	private static final String DASHBOARD_SIMILAR = "/api/dashboard/incidents/{incidentId}/similar";
	private static final String DASHBOARD_MONITORING = "/api/dashboard/monitoring/summary";
	private static final String DASHBOARD_RELATIONSHIPS = "/api/incidents/{id}/relationships";
	private static final String DASHBOARD_SOURCES = "/api/dashboard/sources";
	private static final String DASHBOARD_EVENTS = "/api/dashboard/events";
	private static final String DASHBOARD_ACK = "/api/dashboard/events/ack";

	private final Path docs;
	private final PublishedHost published;
	private final List<String> violations = new ArrayList<>();
	private final Map<String, JsonNode> documents = new LinkedHashMap<>();
	private final Map<String, JsonNode> synthetic = new LinkedHashMap<>();
	private final Map<String, JsonNode> invalid = new LinkedHashMap<>();
	private final Map<String, JsonNode> frames = new LinkedHashMap<>();

	private OpenApiContractValidator(Path docs, PublishedHost published) {
		this.docs = docs;
		this.published = published;
	}

	/**
	 * @param docs directory with the two canonical documents
	 * @return every violation; empty when the contract and fixtures are valid
	 */
	static List<String> check(Path docs) {
		return check(docs, null);
	}

	/**
	 * @param docs directory with the two canonical documents
	 * @param published documents published by the application; {@code null} skips those checks
	 * @return every violation; empty when the contract, fixtures and published documents agree
	 */
	static List<String> check(Path docs, PublishedHost published) {
		OpenApiContractValidator validator = new OpenApiContractValidator(docs, published);
		validator.run();
		return validator.violations;
	}

	/**
	 * Reads a canonical document from a directory.
	 */
	static JsonNode readCanonical(Path docs, String id) {
		Path path = docs.resolve(id + ".openapi.yaml");
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException(id + ": canonical document is missing in " + docs.toAbsolutePath());
		}
		return ContractResources.readYaml(path);
	}

	private void run() {
		for (String id : DOCUMENT_NAMES) {
			try {
				documents.put(id, readCanonical(docs, id));
			}
			catch (RuntimeException exception) {
				violations.add(id + ": " + message(exception));
			}
		}
		loadFixtures("synthetic", synthetic);
		loadFixtures("invalid", invalid);
		loadPublishedFrames();
		documents.forEach((id, document) -> {
			checkStructure(id, document);
			checkSchemas(id, document);
			checkExamples(id, document);
			checkErrorExamples(id, document);
		});
		checkFixtureUsage();
		if (documents.size() == DOCUMENT_NAMES.size()) {
			checkIngestionScenarios();
			checkDashboardScenarios();
			checkSharedModel();
		}
		checkPublished();
	}

	static Path fixtureDirectory(String directory) {
		return ContractResources.directory(directory);
	}

	// --- loading ---------------------------------------------------------------------------

	private void loadFixtures(String directory, Map<String, JsonNode> target) {
		try (Stream<Path> files = Files.list(ContractResources.directory(directory))) {
			for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
				String name = file.getFileName().toString().replace(".json", "");
				try (InputStream input = Files.newInputStream(file)) {
					target.put(name, ContractResources.readJson(input));
				}
				catch (IOException exception) {
					violations.add(directory + "/" + file.getFileName() + ": cannot be parsed");
				}
			}
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/**
	 * Frames are indexed by the application and point to a fixture whose values they publish.
	 */
	private void loadPublishedFrames() {
		if (published == null) {
			return;
		}
		JsonNode index = publishedFrame(FRAME_INDEX);
		if (index == null) {
			return;
		}
		index.path("linksets").forEach(linkset -> linkset.path("item").forEach(link -> {
			// A frame document is published as name.json for the href "name.json".
			String href = link.path("href").stringValue("");
			if (href.endsWith(".json")) {
				frames.put(href.substring(0, href.length() - ".json".length()), publishedFrame(href));
			}
		}));
	}

	private JsonNode publishedFrame(String name) {
		byte[] body = published.read(name.endsWith(".json") ? name : name + ".json");
		if (body == null) {
			violations.add("published document " + name + " is missing");
			return null;
		}
		try {
			return OBJECT_MAPPER.readTree(body);
		}
		catch (RuntimeException exception) {
			violations.add("published document " + name + " is not JSON: " + message(exception));
			return null;
		}
	}

	private void checkFixtureUsage() {
		for (String name : REQUIRED_FIXTURES) {
			if (!synthetic.containsKey(name)) {
				violations.add("fixture " + name + ".json is required");
			}
		}
		if (!invalid.containsKey("snapshot-numeric-id")) {
			violations.add("negative fixture snapshot-numeric-id.json is required");
		}
	}

	// --- published documents ---------------------------------------------------------------

	/**
	 * The application publishes each document twice: the runtime form (fixture-backed examples
	 * as {@code $frame}/{@code $schema} frames) and the YAML form (inline values). Both must
	 * reproduce the contract, and the published specifications must be the canonical documents.
	 */
	private void checkPublished() {
		if (published == null) {
			return;
		}
		for (String id : DOCUMENT_NAMES) {
			JsonNode contract = documents.get(id);
			JsonNode runtime = publishedFrame(PublishedSpecPublisher.RUNTIME.replace("itsm-ingest", id));
			if (runtime == null) {
				continue;
			}
			checkStructure(id + " published runtime", runtime);
			collectFrames(id, runtime, contract);
			JsonNode yaml = publishedFrame(PublishedSpecPublisher.YAML.replace("itsm-ingest", id));
			if (yaml != null) {
				checkStructure(id + " published YAML", yaml);
				checkPublishedValues(id, yaml, contract);
			}
			byte[] specification = published.read("openapi." + id + ".openapi.yaml");
			if (specification == null || !new String(specification, StandardCharsets.UTF_8)
					.equals(canonicalText(id))) {
				violations.add(id + " published specification differs from the canonical document");
			}
		}
	}

	/**
	 * Every JSON example of the runtime document must be a published frame: the pointer names a
	 * frame document, that document points back to it, and its fixture equals the contract example
	 * of the same name.
	 */
	private void collectFrames(String id, JsonNode runtime, JsonNode contract) {
		operations(runtime).properties().forEach(path -> path.getValue().properties().stream()
				.filter(entry -> METHODS.contains(entry.getKey()))
				.forEach(entry -> entry.getValue().path("responses").properties().forEach(response ->
						response.getValue().path("content").properties().forEach(content -> {
							if (!"application/json".equals(content.getKey())) {
								return;
							}
							JsonNode contractExamples = contractExampleList(contract, path.getKey(), entry.getKey(),
									response.getKey(), content.getKey());
							content.getValue().path("examples").properties().forEach(example -> {
								String location = id + " published runtime: " + response.getKey() + " "
										+ entry.getKey() + " example " + example.getKey();
								String pointer = example.getValue().path("value").path(FRAME_POINTER)
										.stringValue("");
								boolean fixtureExample = !contractExamples.path(example.getKey())
										.path(FIXTURE).stringValue("").isEmpty();
								if (pointer.isEmpty() && !fixtureExample) {
									// Inline examples are published with their value.
									if (!contractExamples.path(example.getKey()).path("value")
											.equals(example.getValue().path("value"))) {
										violations.add(location + " differs from the contract example");
									}
									return;
								}
								String prefix = id + "/";
								if (!pointer.startsWith(prefix)) {
									violations.add(location + " does not name a frame of this document");
									return;
								}
								String frameName = pointer;
								String fixture = pointer.substring(prefix.length());
								JsonNode frame = frames.get(frameName);
								if (frame == null) {
									violations.add(location + " has no published frame " + frameName);
									return;
								}
								if (!frame.path(FRAME_POINTER).stringValue().equals(pointer)
										|| !frame.path("$schema").stringValue().endsWith("/schema/" + fixture + ".json")) {
									violations.add(location + " has a frame that points to another fixture or schema");
								}
								JsonNode exampleValue = contractExample(contract, path.getKey(), entry.getKey(),
										response.getKey(), content.getKey().replace('/', '_'), example.getKey());
								if (!synthetic.containsKey(fixture) || exampleValue == null
										|| !exampleValue.equals(synthetic.get(fixture))) {
									violations.add(location + " fixture " + fixture
											+ " differs from the contract example");
								}
							});
						}))));
	}

	private static JsonNode contractExampleList(JsonNode contract, String path, String method, String status,
			String media) {
		JsonNode operationPath = contract.has("webhooks") ? contract.path("webhooks") : contract.path("paths");
		return operationPath.path(path).path(method).path("responses").path(status).path("content")
				.path(media).path("examples");
	}

	private static JsonNode contractExample(JsonNode contract, String path, String method, String status,
			String media, String exampleName) {
		JsonNode operationPath = contract.has("webhooks") ? contract.path("webhooks") : contract.path("paths");
		return operationPath.path(path).path(method).path("responses").path(status).path("content")
				.path(media.replace('_', '/')).path("examples").path(exampleName).path("value");
	}

	private void checkPublishedValues(String id, JsonNode yaml, JsonNode contract) {
		for (var path : contract.path("paths").properties()) {
			for (var entry : path.getValue().properties()) {
				if (!METHODS.contains(entry.getKey())) {
					continue;
				}
				String method = entry.getKey();
				JsonNode published = yaml.path("paths").path(path.getKey()).path(method);
				checkPublishedValues(id, published.path("requestBody").path("content"),
						entry.getValue().path("requestBody").path("content"), method + " " + path.getKey());
				entry.getValue().path("responses").properties().forEach(response ->
						checkPublishedValues(id, published.path("responses").path(response.getKey()).path("content"),
								response.getValue().path("content"), method + " " + path.getKey() + " " + response.getKey()));
			}
		}
	}

	private void checkPublishedValues(String id, JsonNode publishedContent, JsonNode contractContent,
			String location) {
		contractContent.properties().forEach(media -> contractContent.path(media.getKey()).path("examples")
				.properties().forEach(example -> {
					JsonNode published = publishedContent.path(media.getKey()).path("examples")
							.path(example.getKey()).path("value");
					if (!published.isMissingNode() && !published.equals(example.getValue().path("value"))) {
						violations.add(id + " published YAML: " + location + " example " + example.getKey()
								+ " differs from the contract");
					}
				}));
	}

	private String canonicalText(String id) {
		try {
			return Files.readString(docs.resolve(id + ".openapi.yaml"), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			return "";
		}
	}

	// --- structure -------------------------------------------------------------------------

	private static JsonNode operations(JsonNode document) {
		JsonNode operations = document.has("webhooks") ? document.get("webhooks") : document.get("paths");
		return operations == null ? tools.jackson.databind.node.MissingNode.getInstance() : operations;
	}

	private void checkStructure(String id, JsonNode document) {
		if (!"3.1.0".equals(document.path("openapi").stringValue(""))) {
			violations.add(id + ": openapi must be 3.1.0");
		}
		for (String field : List.of("title", "version", "description")) {
			if (document.path("info").path(field).stringValue("").isBlank()) {
				violations.add(id + ": info." + field + " is required");
			}
		}
		Set<String> operationIds = new LinkedHashSet<>();
		operations(document).properties().forEach(path -> path.getValue().properties().stream()
				.filter(entry -> METHODS.contains(entry.getKey()))
				.forEach(entry -> {
					String operationId = entry.getValue().path("operationId").stringValue("");
					if (operationId.isBlank() || !operationIds.add(operationId)) {
						violations.add(id + ": " + entry.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey()
								+ " has a missing or duplicated operationId");
					}
					if (entry.getValue().path("responses").isEmpty()) {
						violations.add(id + ": " + operationId + " has no responses");
					}
				}));
		refs(document, new ArrayList<>()).forEach(ref -> {
			if (ref.startsWith("#/") && resolve(document, ref) == null) {
				violations.add(id + ": unresolved $ref " + ref);
			}
		});
	}

	private static List<String> refs(JsonNode node, List<String> refs) {
		if (node.isObject()) {
			node.properties().forEach(entry -> {
				if ("$ref".equals(entry.getKey()) && entry.getValue().isString()) {
					refs.add(entry.getValue().stringValue());
				}
				else {
					refs(entry.getValue(), refs);
				}
			});
		}
		else if (node.isArray()) {
			node.values().forEach(value -> refs(value, refs));
		}
		return refs;
	}

	private static JsonNode resolve(JsonNode root, String ref) {
		JsonNode current = root;
		for (String token : ref.substring(2).split("/")) {
			current = current.path(token.replace("~1", "/").replace("~0", "~"));
			if (current.isMissingNode()) {
				return null;
			}
		}
		return current;
	}

	// --- schemas and examples --------------------------------------------------------------

	private void checkSchemas(String id, JsonNode document) {
		for (var schema : document.path("components").path("schemas").properties()) {
			if (!schema.getValue().has("type") && !schema.getValue().has("$ref")
					&& !schema.getValue().has("anyOf")) {
				violations.add(id + ": schema " + schema.getKey() + " declares no type");
			}
		}
	}

	private void checkExamples(String id, JsonNode document) {
		operations(document).properties().forEach(path -> path.getValue().properties().stream()
				.filter(entry -> METHODS.contains(entry.getKey()))
				.forEach(entry -> {
					String location = id + ": " + entry.getValue().path("operationId").stringValue();
					JsonNode operation = entry.getValue();
					operation.path("requestBody").path("content").properties().forEach(content ->
							checkMedia(id, location + " request " + content.getKey(), content.getValue()));
					operation.path("responses").properties().forEach(response -> {
						JsonNode responseNode = response.getValue();
						if (responseNode.path("description").stringValue("").isBlank()) {
							violations.add(location + " " + response.getKey() + " has no description");
						}
						responseNode.path("content").properties().forEach(content -> checkMedia(
								id, location + " " + response.getKey() + " " + content.getKey(),
								content.getValue()));
					});
				}));
		document.path("webhooks").properties().forEach(webhook -> webhook.getValue().properties().stream()
				.filter(entry -> METHODS.contains(entry.getKey()))
				.forEach(entry -> entry.getValue().path("requestBody").path("content").properties()
						.forEach(content -> checkMedia(
								id, id + ": webhook " + webhook.getKey() + " " + content.getKey(),
								content.getValue()))));
	}

	private void checkMedia(String id, String location, JsonNode media) {
		JsonNode examples = media.path("examples");
		if (!examples.isObject() || examples.isEmpty()) {
			violations.add(location + " has no named examples");
			return;
		}
		JsonNode mediaSchema = media.path("schema");
		for (var example : examples.properties()) {
			checkExample(id, location, example.getKey(), example.getValue(), mediaSchema);
		}
	}

	/**
	 * Validates one named example. Its value must pass the media type schema. When the example
	 * carries {@link #FIXTURE}, its value must also equal that fixture, so frontend and tests
	 * start from the same object.
	 */
	private void checkExample(String id, String location, String name, JsonNode example, JsonNode mediaSchema) {
		String exampleLocation = location + " example " + name;
		if (example.path("summary").stringValue("").isBlank()) {
			violations.add(exampleLocation + " has no summary");
		}
		if (!example.has("value")) {
			violations.add(exampleLocation + " has no value");
			return;
		}
		JsonNode instance = example.get("value");
		try {
			ContractSchema.assertValid(documents.get(id), mediaSchema, "", instance, exampleLocation);
		}
		catch (RuntimeException exception) {
			violations.add(exampleLocation + ": " + message(exception));
			return;
		}
		JsonNode fixture = example.get(FIXTURE);
		if (fixture != null && (!fixture.isString() || !instance.equals(synthetic.get(fixture.stringValue())))) {
			violations.add(exampleLocation + " must equal fixture " + fixture);
		}
	}

	// --- ingestion scenarios ---------------------------------------------------------------

	private void checkIngestionScenarios() {
		JsonNode ingest = documents.get("itsm-ingest");
		Map<String, JsonNode> requests = examples(ingest, "post", INGEST, "request", "application/json");
		JsonNode first = requests.get("minimal");
		JsonNode second = requests.get("nextTick");
		if (!isFullSnapshotPair(first, second)) {
			violations.add("itsm-ingest: minimal and nextTick must be full snapshots that differ in exactly one INC");
		}
		if (first == null || !first.equals(synthetic.get("snapshot-minimal"))
				|| second == null || !second.equals(synthetic.get("snapshot-next-tick"))) {
			violations.add("itsm-ingest: request examples minimal and nextTick must equal their fixtures");
		}
		JsonNode numeric = invalid.get("snapshot-numeric-id");
		JsonNode snapshotSchema = ingest.path("components").path("schemas").path("ItsmIncidentSnapshot");
		if (numeric == null || !numeric.isArray() || !first.isArray()
				|| ContractSchema.errors(ingest, snapshotSchema, "/components/schemas/ItsmIncidentSnapshot",
						numeric).isEmpty()) {
			violations.add("itsm-ingest: a numeric INC must be rejected by the snapshot schema");
		}
		if (operation(ingest, "post", INGEST).path("security").isEmpty()
				|| !operation(ingest, "post", INGEST).path("security").get(0).has("machineBearer")) {
			violations.add("itsm-ingest: the ingestion operation must require the machine bearer");
		}
		JsonNode accepted = operation(ingest, "post", INGEST).path("responses").path("202");
		if (!accepted.path("headers").has("Location")) {
			violations.add("itsm-ingest: 202 must document the Location header");
		}
	}

	private static boolean isFullSnapshotPair(JsonNode first, JsonNode second) {
		if (first == null || second == null || !first.isArray() || !second.isArray()
				|| first.size() != second.size() || first.size() < 2) {
			return false;
		}
		int changed = 0;
		for (int index = 0; index < first.size(); index++) {
			if (!first.get(index).equals(second.get(index))) {
				changed++;
			}
		}
		return changed == 1;
	}

	// --- dashboard scenarios ---------------------------------------------------------------

	private void checkDashboardScenarios() {
		JsonNode dashboard = documents.get("dashboard-api");
		Map<String, List<String>> routes = new LinkedHashMap<>();
		dashboard.path("paths").properties().forEach(path -> path.getValue().properties().stream()
				.filter(entry -> METHODS.contains(entry.getKey()))
				.forEach(entry -> routes.computeIfAbsent(entry.getValue().path("operationId").stringValue(""),
						key -> new ArrayList<>()).add(entry.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey())));
		Map<String, String> canonical = new LinkedHashMap<>();
		canonical.put("getCurrentCoordinator", "GET /api/me");
		canonical.put("setCurrentCoordinatorAvailability", "PUT /api/me/availability");
		canonical.put("listIncidentPresets", "GET /api/dashboard/incident-presets");
		canonical.put("createIncidentPreset", "POST /api/dashboard/incident-presets");
		canonical.put("getIncidentPreset", "GET /api/dashboard/incident-presets/{id}");
		canonical.put("updateIncidentPreset", "PUT /api/dashboard/incident-presets/{id}");
		canonical.put("deleteIncidentPreset", "DELETE /api/dashboard/incident-presets/{id}");
		canonical.put("listIncidents", "GET " + DASHBOARD_INCIDENTS);
		canonical.put("listItsmUsers", "GET /api/dashboard/users");
		canonical.put("updateIncidentCard", "PATCH " + DASHBOARD_CARD);
		canonical.put("getIncidentCard", "GET " + DASHBOARD_CARD);
		canonical.put("listIncidentChanges", "GET " + DASHBOARD_INCIDENTS + "/changes");
		canonical.put("getIncidentHistory", "GET " + DASHBOARD_CARD + "/history");
		canonical.put("getDeadlineWarningRule", "GET /api/dashboard/deadline-warning-rule");
		canonical.put("setDeadlineWarningRule", "PUT /api/dashboard/deadline-warning-rule");
		canonical.put("suggestSimilarIncidents", "GET " + DASHBOARD_SIMILAR);
		canonical.put("getIncidentPostmortemDraft", "GET /api/dashboard/incidents/{incidentId}/postmortem");
		canonical.put("monitoringSummary", "GET " + DASHBOARD_MONITORING);
		canonical.put("getIncidentRelationships", "GET " + DASHBOARD_RELATIONSHIPS);
		canonical.put("saveMeetingLink", "PUT /api/incidents/{id}/meeting-link");
		canonical.put("confirmIncidentLink", "POST " + DASHBOARD_CARD + "/confirmed-links");
		canonical.put("listSources", "GET " + DASHBOARD_SOURCES);
		canonical.put("pollEvents", "GET " + DASHBOARD_EVENTS);
		canonical.put("acknowledgeEvents", "POST " + DASHBOARD_ACK);
		String connections = "/api/dashboard/notification-connections";
		canonical.put("listNotificationConnections", "GET " + connections);
		canonical.put("createNotificationConnection", "POST " + connections);
		canonical.put("getNotificationConnection", "GET " + connections + "/{id}");
		canonical.put("updateNotificationConnection", "PUT " + connections + "/{id}");
		String notifications = "/api/dashboard/notifications";
		canonical.put("listNotifications", "GET " + notifications);
		canonical.put("markNotificationRead", "POST " + notifications + "/{id}/read");
		canonical.put("retryNotificationDelivery", "POST " + notifications + "/deliveries/{id}/retry");
		canonical.put("redirectCoordinationTask", "POST /api/dashboard/coordination-tasks/{taskId}/redirect");
		canonical.put("listCoordinationTaskAssignments", "GET /api/dashboard/coordination-tasks/{taskId}/assignments");
		canonical.forEach((operationId, route) -> {
			List<String> found = routes.getOrDefault(operationId, List.of());
			if (!found.equals(List.of(route))) {
				violations.add("dashboard-api: " + operationId + " must have exactly one route " + route
						+ ", found " + found);
			}
		});
		routes.keySet().stream().filter(operationId -> !canonical.containsKey(operationId))
				.forEach(operationId -> violations.add("dashboard-api: unexpected reading " + operationId));
		JsonNode list = operation(dashboard, "get", DASHBOARD_INCIDENTS);
		for (String name : List.of("group", "includeCompleted", "cursor", "limit")) {
			if (!hasParameter(list, "query", name)) {
				violations.add("dashboard-api: list must accept query parameter " + name);
			}
		}
		if (!hasParameter(operation(dashboard, "get", DASHBOARD_EVENTS), "query", "sinceEventSeq")) {
			violations.add("dashboard-api: polling must accept sinceEventSeq");
		}
		JsonNode ackRequest = operation(dashboard, "post", DASHBOARD_ACK).path("requestBody")
				.path("content").path("application/json").path("schema");
		JsonNode ack = ackRequest.has("$ref")
				? dashboard.at(ackRequest.get("$ref").stringValue().substring(1)) : ackRequest;
		if (!new HashSet<>(ack.path("properties").propertyNames()).equals(Set.of("eventIds"))
				|| !contains(ack.path("required"), "eventIds")) {
			violations.add("dashboard-api: ack must require exactly the eventIds set");
		}
		checkDashboardExamples();
		checkMonitoringExamples(dashboard);
	}

	private void checkMonitoringExamples(JsonNode dashboard) {
		JsonNode examples = operation(dashboard, "get", DASHBOARD_MONITORING)
				.path("responses").path("200").path("content").path("application/json").path("examples");
		JsonNode active = examples.path("active").path("value");
		JsonNode empty = examples.path("beforeFirstSuccess").path("value");
		if (active.isMissingNode() || empty.isMissingNode()) {
			violations.add("dashboard-api: monitoring summary must document active and empty examples");
			return;
		}
		if (active.path("timeline").isEmpty()) {
			violations.add("dashboard-api: monitoring example must carry actual timeline observations");
		}
		Instant previous = Instant.MIN;
		for (JsonNode point : active.path("timeline")) {
			Instant at = Instant.parse(point.path("at").stringValue());
			if (!at.isAfter(previous)) {
				violations.add("dashboard-api: monitoring timeline must be strictly ordered by at");
				break;
			}
			previous = at;
		}
		if (!empty.path("timeline").isEmpty() || !empty.path("updatedAt").isNull()) {
			violations.add("dashboard-api: absent monitoring history must stay empty without invented observations");
		}
	}

	private static boolean hasParameter(JsonNode operation, String location, String name) {
		for (JsonNode parameter : operation.path("parameters")) {
			if (location.equals(parameter.path("in").stringValue())
					&& name.equals(parameter.path("name").stringValue())) {
				return true;
			}
		}
		return false;
	}

	private void checkDashboardExamples() {
		JsonNode list = synthetic.get("list-default");
		JsonNode emptyGroup = synthetic.get("list-empty-group");
		JsonNode card = synthetic.get("card-without-deadline");
		JsonNode completed = synthetic.get("card-completed");
		JsonNode sources = synthetic.get("sources-stale");
		JsonNode events = synthetic.get("events-poll-two-events");
		JsonNode ackRequest = synthetic.get("events-ack-request");
		JsonNode ack = synthetic.get("events-ack-response");
		JsonNode heartbeat = synthetic.get("events-heartbeat");
		if (list == null || emptyGroup == null || card == null || completed == null || sources == null
				|| events == null || ackRequest == null || ack == null || heartbeat == null) {
			violations.add("dashboard-api: scenario fixtures are incomplete");
			return;
		}
		if (list.path("beforeFirstSuccess").booleanValue(true)
				|| !list.path("eventSeq").isNumber() || !list.path("revision").isNumber()) {
			violations.add("dashboard-api: the list example must publish revision and eventSeq");
		}
		Set<String> itemIds = new LinkedHashSet<>();
		for (JsonNode item : list.path("items")) {
			if (!INC.matcher(item.path("id").stringValue("")).matches()) {
				violations.add("dashboard-api: list item id must be a string INC, found " + item.path("id"));
			}
			if (!itemIds.add(item.path("id").stringValue(""))) {
				violations.add("dashboard-api: an INC in two groups must be one list item");
			}
		}
		JsonNode p4 = findItem(list.path("items"), "INC000021095532");
		List<String> cardGroups = groups(card);
		if (!cardGroups.containsAll(List.of("critical", "coordinator"))
				|| p4 != null && !(groups(p4).containsAll(List.of("critical", "coordinator"))
						&& cardGroups.containsAll(groups(p4)))) {
			violations.add("dashboard-api: the P4 example must list the same two groups in list and card");
		}
		if (!card.path("deadline").isNull() || p4 != null && !p4.path("deadline").isNull()) {
			violations.add("dashboard-api: the P4 example without deadline must have a null deadline in card and list");
		}
		if (completed.path("completionDate").isNull() || completed.path("solution").isNull()) {
			violations.add("dashboard-api: a completed INC must carry completionDate and solution");
		}
		boolean empty = !emptyGroup.path("groups").isEmpty() && emptyGroup.path("items").isEmpty();
		for (JsonNode group : emptyGroup.path("groups")) {
			empty &= group.path("incidents").isEmpty() && group.path("incidentCount").asInt(-1) == 0;
		}
		if (!empty) {
			violations.add("dashboard-api: the empty group example must be a valid empty group");
		}
		if (sources.path("sources").isEmpty() || !sources.path("sources").get(0).path("stale").booleanValue(false)
				|| !sources.path("sources").get(0).has("revision")
				|| !sources.path("sources").get(0).has("sourceVersion")) {
			violations.add("dashboard-api: the stale source example must separate revision and sourceVersion");
		}
		List<JsonNode> eventList = new ArrayList<>(events.path("events").values());
		boolean twoEventsOfOneIncident = eventList.size() == 2
				&& eventList.get(0).path("incident").path("id").stringValue("")
						.equals(eventList.get(1).path("incident").path("id").stringValue(""))
				&& !eventList.get(0).path("eventId").stringValue("")
						.equals(eventList.get(1).path("eventId").stringValue(""));
		if (!twoEventsOfOneIncident) {
			violations.add("dashboard-api: polling example must contain two distinct events of one INC");
		}
		Set<String> requested = strings(ackRequest.path("eventIds"));
		Set<String> known = new LinkedHashSet<>();
		eventList.forEach(event -> known.add(event.path("eventId").stringValue("")));
		if (!strings(ack.path("confirmedEventIds")).equals(requested) || !known.containsAll(requested)) {
			violations.add("dashboard-api: ack example must confirm exactly the requested, known events");
		}
		if (heartbeat.path("events").values().stream()
				.noneMatch(event -> "heartbeat".equals(event.path("type").stringValue()))) {
			violations.add("dashboard-api: heartbeat example must carry a heartbeat event");
		}
		checkUtc("card-after-first-success", card);
		checkUtc("card-completed", completed);
		checkUtc("sources-stale", sources);
		checkUtc("events-poll-two-events", events);
		checkUtc("events-heartbeat", heartbeat);
	}

	private static JsonNode findItem(JsonNode items, String id) {
		for (JsonNode item : items) {
			if (id.equals(item.path("id").stringValue(""))) {
				return item;
			}
		}
		return null;
	}

	private static List<String> groups(JsonNode incident) {
		List<String> groups = new ArrayList<>();
		incident.path("groupMemberships").forEach(membership -> groups.add(membership.path("group").stringValue("")));
		return groups;
	}

	private static Set<String> strings(JsonNode array) {
		Set<String> values = new LinkedHashSet<>();
		array.forEach(element -> values.add(element.stringValue("")));
		return values;
	}

	// --- shared model ----------------------------------------------------------------------

	private void checkSharedModel() {
		JsonNode ingest = documents.get("itsm-ingest");
		JsonNode dashboard = documents.get("dashboard-api");
		JsonNode incident = ingest.path("components").path("schemas").path("ItsmIncident");
		if (!acceptsOnlyStrings(incident.path("properties").path("id"), ingest)) {
			violations.add("itsm-ingest: ItsmIncident.id must be a string");
		}
		if (!incident.path("additionalProperties").booleanValue(false)) {
			violations.add("itsm-ingest: unknown incident fields must be preserved");
		}
		for (String field : List.of("deadline", "completionDate", "solution")) {
			if (!nullable(incident.path("properties").path(field))) {
				violations.add("itsm-ingest: ItsmIncident." + field + " must be nullable");
			}
		}
		JsonNode card = dashboard.path("components").path("schemas").path("IncidentCard");
		for (String field : List.of("deadline", "completionDate", "solution", "lastAssignmentToTeamDateTime")) {
			if (!nullable(card.path("properties").path(field))) {
				violations.add("dashboard-api: IncidentCard." + field + " must be nullable");
			}
		}
		if (!contains(card.path("required"), "revision") || !contains(card.path("required"), "eventSeq")) {
			violations.add("dashboard-api: IncidentCard must require revision and eventSeq");
		}
		if (!nullable(ingest.path("components").path("schemas").path("SnapshotStatus")
				.path("properties").path("revision"))) {
			violations.add("itsm-ingest: SnapshotStatus.revision must be nullable");
		}
		if (!nullable(dashboard.path("components").path("schemas").path("SourceStatus")
				.path("properties").path("sourceVersion"))) {
			violations.add("dashboard-api: SourceStatus.sourceVersion must be nullable");
		}
		checkUtc("snapshot-status-published", synthetic.get("snapshot-status-published"));
		checkUtc("snapshot-status-stale", synthetic.get("snapshot-status-stale"));
	}

	/**
	 * Every instant field (a name ending with {@code At}) in an example must be UTC.
	 */
	private void checkUtc(String name, JsonNode node) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isObject()) {
			node.properties().forEach(entry -> {
				if (entry.getKey().endsWith("At") && entry.getValue().isString()
						&& !UTC.matcher(entry.getValue().stringValue()).matches()) {
					violations.add("example " + name + " " + entry.getKey() + " must be UTC, found "
							+ entry.getValue());
				}
				checkUtc(name, entry.getValue());
			});
		}
		else if (node.isArray()) {
			node.values().forEach(value -> checkUtc(name, value));
		}
	}

	/**
	 * Follows a {@code $ref} and checks that the schema accepts nothing but strings.
	 */
	private static boolean acceptsOnlyStrings(JsonNode schema, JsonNode document) {
		JsonNode resolved = schema.has("$ref")
				? document.at(schema.get("$ref").stringValue().substring(1)) : schema;
		JsonNode type = resolved.path("type");
		return type.isString() && "string".equals(type.stringValue())
				|| type.isArray() && type.size() == 1 && "string".equals(type.get(0).stringValue());
	}

	private static boolean nullable(JsonNode schema) {
		return schema.path("type").isArray() && contains(schema.path("type"), "null")
				|| schema.path("anyOf").values().stream()
						.anyMatch(option -> "null".equals(option.path("type").stringValue("")));
	}

	private static boolean contains(JsonNode array, String value) {
		for (JsonNode element : array) {
			if (value.equals(element.stringValue(""))) {
				return true;
			}
		}
		return false;
	}

	// --- error schemas ---------------------------------------------------------------------

	private void checkErrorExamples(String id, JsonNode document) {
		operations(document).properties().forEach(path -> path.getValue().properties().stream()
				.filter(entry -> METHODS.contains(entry.getKey()))
				.forEach(entry -> entry.getValue().path("responses").properties().forEach(response -> {
					if (!Set.of("401", "404").contains(response.getKey())) {
						return;
					}
					String location = id + ": " + entry.getValue().path("operationId").stringValue() + " "
							+ response.getKey();
					JsonNode examples = response.getValue().path("content").path("application/json")
							.path("examples");
					if (!examples.isObject() || examples.isEmpty()) {
						violations.add(location + " must publish a named example with code and message");
					}
					examples.properties().forEach(example -> {
						JsonNode value = example.getValue().path("value");
						if (value.path("code").stringValue("").isBlank()
								|| value.path("message").stringValue("").isBlank()) {
							violations.add(location + " example " + example.getKey() + " needs code and message");
						}
					});
				})));
	}

	// --- helpers ---------------------------------------------------------------------------

	/**
	 * Operations of both canonical documents: the ingestion operations are webhooks (the
	 * documented sender-to-receiver event), the dashboard reads are ordinary paths.
	 */
	private static JsonNode operation(JsonNode document, String method, String path) {
		JsonNode operations = document.has("webhooks") ? document.get("webhooks") : document.get("paths");
		return operations == null ? tools.jackson.databind.node.MissingNode.getInstance()
				: operations.path(path).path(method);
	}

	private static Map<String, JsonNode> examples(
			JsonNode document,
			String method,
			String path,
			String part,
			String mediaType
	) {
		JsonNode media = "request".equals(part)
				? operation(document, method, path).path("requestBody").path("content").path(mediaType)
				: operation(document, method, path).path("responses").path(part).path("content").path(mediaType);
		Map<String, JsonNode> result = new LinkedHashMap<>();
		media.path("examples").properties()
				.forEach(example -> result.put(example.getKey(), example.getValue().path("value")));
		return result;
	}

	private static String message(Throwable throwable) {
		return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
	}

	static ClassLoader classLoader() {
		return Objects.requireNonNull(OpenApiContractValidator.class.getClassLoader());
	}
}
