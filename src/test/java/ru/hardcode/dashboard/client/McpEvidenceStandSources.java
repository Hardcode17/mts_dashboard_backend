package ru.hardcode.dashboard.client;

import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Controlled stand ITSM and log sources for KAN-39 integration tests.
 *
 * <p>Both sources answer only the fixed read requests the evidence tools are allowed to make
 * and record every received request, so tests can prove that no other request is sent. The
 * ITSM source serves the existing incident contract ({@code GET /api/mock/incidents/{id}}) with
 * the assigned group; the log source returns records with their explicit incident and
 * scenario-run ownership. Every fixture change is applied by the test through the bounded
 * helpers below, never through the application.
 */
public final class McpEvidenceStandSources implements AutoCloseable {

	static final String INCIDENT_PREFIX = "/api/mock/incidents/";
	static final String SHARED_SERVICE_ID = "st-checkout";

	private static final List<McpEvidenceStandSources> RUNNING = new CopyOnWriteArrayList<>();

	private final HttpServer server;
	private final Map<String, Incident> incidents = new ConcurrentHashMap<>();
	private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private final AtomicInteger logStatus = new AtomicInteger(200);

	private McpEvidenceStandSources() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/", this::dispatch);
		server.start();
		RUNNING.add(this);
	}

	public static McpEvidenceStandSources start() throws IOException {
		McpEvidenceStandSources sources = new McpEvidenceStandSources();
		sources.startRefusal();
		return sources;
	}

	/** Refuses every request: the source is down, not an empty answer. */
	public void startRefusal() {
		logStatus.set(503);
	}

	public void stopRefusal() {
		logStatus.set(200);
	}

	public void restartAll() {
		for (McpEvidenceStandSources sources : RUNNING) {
			sources.reset();
		}
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public void reset() {
		incidents.clear();
		logs.clear();
		requests.clear();
		logStatus.set(200);
	}

	public List<String> requests() {
		return List.copyOf(requests);
	}

	/** Requests addressed to the log source of this test. */
	public List<String> logRequests() {
		return requests.stream().filter(request -> request.startsWith("GET /api/logs?")).toList();
	}

	/**
	 * Puts one incident into the stand contract: the assigned group and timestamp move
	 * together with the state and priority, so a change of the assignment is visible. Both
	 * incidents of the demo share the external service identity, as they do on the stand.
	 */
	public void putIncident(
			String id,
			String stateEnum,
			String priority,
			String assignedGroupName,
			Instant lastAssignmentAt
	) {
		String serviceId = SHARED_SERVICE_ID;
		incidents.put(id, new Incident(id, stateEnum, stateEnum, priority, serviceId, "Оплата",
				assignedGroupName, lastAssignmentAt.toString()));
	}

	public void removeIncident(String id) {
		incidents.remove(id);
	}

	public void addLog(
			String id,
			Instant timestamp,
			String message,
			String level,
			String incidentId,
			String scenarioRunId
	) {
		logs.removeIf(record -> record.id().equals(id));
		logs.add(new LogRecord(id, timestamp.toString(), message, level, incidentId, scenarioRunId));
	}

	public void updateLogMessage(String id, String message) {
		logs.replaceAll(record -> record.id().equals(id)
				? new LogRecord(record.id(), record.timestamp(), message, record.level(), record.incidentId(),
						record.scenarioRunId())
				: record);
	}

	public void removeLog(String id) {
		logs.removeIf(record -> record.id().equals(id));
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void dispatch(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if (path.startsWith(INCIDENT_PREFIX)) {
			incident(exchange, path);
			return;
		}
		if (path.equals("/api/logs")) {
			logs(exchange);
			return;
		}
		requests.add(exchange.getRequestMethod() + " " + path);
		send(exchange, 404, "{}");
	}

	private void incident(HttpExchange exchange, String path) throws IOException {
		requests.add("GET " + path);
		if (!"GET".equals(exchange.getRequestMethod())) {
			send(exchange, 405, "{}");
			return;
		}
		String id = URLDecoder.decode(path.substring(INCIDENT_PREFIX.length()), StandardCharsets.UTF_8);
		Incident incident = incidents.get(id);
		if (incident == null) {
			send(exchange, 404, "{}");
			return;
		}
		send(exchange, 200, incidentJson(incident));
	}

	private void logs(HttpExchange exchange) throws IOException {
		requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
				+ "?" + exchange.getRequestURI().getRawQuery());
		if (logStatus.get() != 200) {
			send(exchange, logStatus.get(), "{\"error\":\"unavailable\"}");
			return;
		}
		Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
		Instant from = parseOrNull(query.get("from"));
		Instant to = parseOrNull(query.get("to"));
		boolean limitOne = query.containsKey("limit") && "1".equals(query.get("limit"));
		if (!"GET".equals(exchange.getRequestMethod())
				|| query.get("customerId") == null
				|| from == null || to == null || from.isAfter(to) || !limitOne) {
			send(exchange, 400, "{}");
			return;
		}
		List<LogRecord> selected = new ArrayList<>(logs);
		selected.removeIf(record -> Instant.parse(record.timestamp()).isBefore(from)
				|| Instant.parse(record.timestamp()).isAfter(to));
		selected.sort(Comparator.comparing((LogRecord record) -> Instant.parse(record.timestamp())).reversed());
		StringBuilder body = new StringBuilder("[");
		int limit = 5;
		for (int index = 0; index < Math.min(limit, selected.size()); index++) {
			if (index > 0) {
				body.append(',');
			}
			LogRecord record = selected.get(index);
			body.append("{\"id\":").append(json(record.id()))
					.append(",\"timestamp\":").append(json(record.timestamp()))
					.append(",\"level\":").append(json(record.level()))
					.append(",\"message\":").append(json(record.message()))
					.append(",\"incidentId\":").append(json(record.incidentId()))
					.append(",\"scenarioRunId\":").append(json(record.scenarioRunId()))
					.append('}');
		}
		send(exchange, 200, body.append(']').toString());
	}

	private static String incidentJson(Incident incident) {
		return "{\"id\":" + json(incident.id())
				+ ",\"stateEnum\":" + json(incident.stateEnum())
				+ ",\"state\":" + json(incident.state())
				+ ",\"priority\":" + json(incident.priority())
				+ ",\"serviceId\":" + json(incident.serviceId())
				+ ",\"service\":" + json(incident.serviceName())
				+ ",\"assignedGroupName\":" + json(incident.assignedGroupName())
				+ ",\"lastAssignmentAt\":" + json(incident.lastAssignmentAt())
				+ "}";
	}

	private static Instant parseOrNull(String value) {
		try {
			return value == null ? null : Instant.parse(value);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static Map<String, String> query(String rawQuery) {
		Map<String, String> values = new LinkedHashMap<>();
		if (rawQuery == null) {
			return values;
		}
		for (String pair : rawQuery.split("&")) {
			int separator = pair.indexOf('=');
			String name = separator < 0 ? pair : pair.substring(0, separator);
			String value = separator < 0 ? "" : pair.substring(separator + 1);
			values.put(
					URLDecoder.decode(name, StandardCharsets.UTF_8),
					URLDecoder.decode(value, StandardCharsets.UTF_8)
			);
		}
		return values;
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(body.getBytes(StandardCharsets.UTF_8));
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.size());
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes.toByteArray());
		}
	}

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	private static String json(String value) {
		return JSON.writeValueAsString(value);
	}

	record Incident(
			String id,
			String stateEnum,
			String state,
			String priority,
			String serviceId,
			String serviceName,
			String assignedGroupName,
			String lastAssignmentAt
	) {
	}

	record LogRecord(
			String id,
			String timestamp,
			String message,
			String level,
			String incidentId,
			String scenarioRunId
	) {
	}
}
