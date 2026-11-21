package ru.hardcode.itsmmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"itsm.mock.batch-size=2", "itsm.mock.retry-delay=PT0S"})
@AutoConfigureMockMvc
@Import(ControlledScheduler.class)
class SnapshotHttpIT {
    private static final Path STATE = temporaryState();
    private static final ConcurrentLinkedQueue<Integer> CODES = new ConcurrentLinkedQueue<>();
    private static final List<Request> REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer RECEIVER = startReceiver();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskScheduler taskScheduler;
    @Autowired MockSettings settings;
    @Autowired BatchTransport transport;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String receiver = "http://127.0.0.1:" + RECEIVER.getAddress().getPort();
        registry.add("itsm.mock.target-url", () -> receiver + "/api/integrations/itsm/batches");
        registry.add("itsm.mock.token-url", () -> receiver + "/token");
        registry.add("itsm.mock.client-id", () -> "test-client");
        registry.add("itsm.mock.client-secret", () -> "test-secret");
        registry.add("itsm.mock.state-file", STATE::toString);
    }

    @AfterAll
    static void clean() throws IOException {
        RECEIVER.stop(0);
        Files.deleteIfExists(STATE);
        Files.deleteIfExists(STATE.getParent());
    }

    @Test
    void manualScheduledRetryRejectionAndRestart() throws Exception {
        CODES.addAll(List.of(429, 503, 202));
        mvc.perform(post("/api/mock/emit")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveredVersion").value(1));
        assertThat(REQUESTS).hasSize(3);
        Request first = REQUESTS.getFirst();
        assertThat(REQUESTS).containsOnly(first);
        assertThat(first.source()).isEqualTo("demo-itsm");
        assertThat(first.bearer()).isEqualTo("Bearer test-token");
        assertThat(first.contentType()).isEqualTo("application/json");
        assertThat(first.version()).isEqualTo("1");
        var initial = json.readTree(first.body());
        assertThat(initial.isArray()).isTrue();
        assertThat(initial.size()).isEqualTo(2);
        assertThat(initial.get(0).size()).isEqualTo(46);
        assertThat(initial.get(0).get("priorityId").asInt()).isEqualTo(4);
        assertThat(initial.get(0).get("deadline").isNull()).isTrue();
        assertThat(initial.get(0).get("stateReasonId").isString()).isTrue();

        // The stand incident contract is a fixed read back of the assigned group.
        String incidentId = initial.get(0).get("id").asString();
        mvc.perform(get("/api/mock/incidents/{id}", incidentId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId))
                .andExpect(jsonPath("$.assignedGroupName").value(initial.get(0).get("assignedGroupName").asString()))
                .andExpect(jsonPath("$.stateEnum").value(initial.get(0).get("stateEnum").asString()))
                .andExpect(jsonPath("$.lastAssignmentAt").value(initial.get(0).get("lastAssignmentToTeamDateTime").asString()))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.assignedEmployeeId").doesNotExist());
        mvc.perform(get("/api/mock/incidents/{id}", "INC-missing")).andExpect(status().isNotFound());
        assertThat(REQUESTS).hasSize(3);

        var scheduled = ControlledScheduler.callback(taskScheduler);
        scheduled.run();
        scheduled.run();
        Request second = REQUESTS.get(3);
        Request third = REQUESTS.get(4);
        assertThat(List.of(second.version(), third.version())).containsExactly("2", "3");
        assertThat(List.of(first.id(), second.id(), third.id())).doesNotHaveDuplicates();
        var modified = json.readTree(second.body());
        assertThat(modified.size()).isEqualTo(initial.size());
        assertThat(modified).isNotEqualTo(initial);
        assertThat(List.of(modified.get(0).get("id"), modified.get(1).get("id")))
                .containsExactly(initial.get(0).get("id"), initial.get(1).get("id"));
        assertThat(modified.get(0).equals(initial.get(0)) ^ modified.get(1).equals(initial.get(1))).isTrue();
        assertThat(third.body()).isEqualTo(second.body());

        CODES.add(401);
        mvc.perform(post("/api/mock/emit")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.reason").value("Ingest HTTP 401"));
        assertThat(REQUESTS).hasSize(6);
        Request rejected = REQUESTS.getLast();
        scheduled.run();
        var restarted = new SnapshotSender(transport, settings, java.time.Clock.systemUTC(), json);
        assertThat(restarted.emit(false).status().reason()).isEqualTo("Ingest HTTP 401");
        assertThat(REQUESTS).hasSize(6);

        CODES.add(422);
        assertThat(restarted.emit(true).status().reason()).isEqualTo("Ingest HTTP 422");
        assertThat(REQUESTS).hasSize(7);
        assertThat(REQUESTS.getLast()).isEqualTo(rejected);
        assertThat(restarted.emit(true).delivered()).isTrue();
        assertThat(REQUESTS).hasSize(8);
        assertThat(REQUESTS.getLast()).isEqualTo(rejected);
        assertThat(restarted.status().deliveredVersion()).isEqualTo(4);
        CODES.add(200);
        assertThat(restarted.emit(true).status().reason()).isEqualTo("Ingest HTTP 200");
        assertThat(restarted.status().deliveredVersion()).isEqualTo(4);
        assertThat(restarted.status().paused()).isTrue();
        mvc.perform(get("/api/mock/status")).andExpect(status().isOk());
    }

    @Test
    void expandedPoolsAndBoundaries() {
        Instant now = Instant.parse("2026-11-21T00:00:00.123456789Z");
        var one = new IncidentGenerator(53, 1).next(List.of(), 1, now);
        var maximum = new IncidentGenerator(53, 500).next(List.of(), 1, now);
        assertThat(one).hasSize(1);
        assertThat(one.getFirst().get("submitDate")).isEqualTo("2026-11-20T23:55:00.123456Z");
        assertThat(maximum).hasSize(500);
        assertThat(maximum.stream().map(row -> row.get("id"))).doesNotHaveDuplicates();
        assertThat(maximum.stream().map(row -> row.get("productName")).distinct().count()).isEqualTo(3);
        assertThat(maximum.stream().map(row -> row.get("assignedGroupName")).distinct().count()).isEqualTo(4);
        assertThat(maximum.stream().map(row -> row.get("priorityId")).distinct().count()).isEqualTo(4);
        assertThat(maximum.stream().map(row -> row.get("stateEnum")).distinct().count()).isEqualTo(5);
        assertThat(maximum).isEqualTo(new IncidentGenerator(53, 500).next(List.of(), 1, now));
        assertThat(maximum).isNotEqualTo(new IncidentGenerator(54, 500).next(List.of(), 1, now));
    }

    @Test
    void emitsFourStableGraphCases() {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        var rows = new IncidentGenerator(53, 4).next(List.of(), 1, now);

        assertThat(rows).hasSize(4);
        String parentId = (String) rows.get(0).get("id");
        assertThat(rows.get(0)).doesNotContainKey("parentId");
        assertThat(rows.get(1).get("parentId")).isEqualTo(parentId);
        assertThat(rows.get(2).get("parentId")).isEqualTo(parentId);
        assertThat(rows.get(3)).doesNotContainKey("parentId");
        assertThat(parentId).isNotEqualTo(rows.get(0).get("sysId"));
    }

    private static Path temporaryState() {
        try {
            return Files.createTempDirectory("itsm-mock-it-").resolve("state.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static HttpServer startReceiver() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                try (exchange) {
                    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    byte[] response = "{\"access_token\":\"test-token\"}".getBytes(StandardCharsets.UTF_8);
                    boolean valid = "POST".equals(exchange.getRequestMethod()) && form.equals(
                            "grant_type=client_credentials&client_id=test-client&client_secret=test-secret");
                    exchange.sendResponseHeaders(valid ? 200 : 401, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            server.createContext("/api/integrations/itsm/batches", exchange -> {
                try (exchange) {
                    if (!"POST".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }
                    var headers = exchange.getRequestHeaders();
                    REQUESTS.add(new Request(headers.getFirst("Idempotency-Key"), headers.getFirst("X-Source-Id"),
                            headers.getFirst("X-Source-Version"), headers.getFirst("Authorization"),
                            headers.getFirst("Content-Type"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    Integer code = CODES.poll();
                    exchange.sendResponseHeaders(code == null ? 202 : code, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Request(String id, String source, String version, String bearer, String contentType, String body) {}
}
