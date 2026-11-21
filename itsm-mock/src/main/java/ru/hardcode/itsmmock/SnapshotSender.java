package ru.hardcode.itsmmock;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
final class SnapshotSender {
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {};

    private final BatchTransport transport;
    private final MockSettings settings;
    private final Clock clock;
    private final ObjectMapper json;
    private final SnapshotStore store;
    private final IncidentGenerator generator;
    private SnapshotStore.State state;
    private volatile Status currentStatus;

    SnapshotSender(BatchTransport transport, MockSettings settings, Clock clock, ObjectMapper json) throws IOException {
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.json = json;
        this.store = new SnapshotStore(settings.stateFile(), json);
        this.state = store.read(settings.sourceId());
        this.generator = new IncidentGenerator(settings.seed(), settings.batchSize());
        updateStatus(state.rejection());
    }

    synchronized Emission emit(boolean manual) {
        if (state.rejection() != null && !manual) {
            return new Emission(false, status());
        }
        String reason = null;
        boolean delivered = false;
        try {
            if (state.pending() == null) {
                createPending();
            }
            var pending = state.pending();
            for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
                var result = transport.send(pending);
                reason = result.reason();
                if (result.delivered()) {
                    persist(new SnapshotStore.State(state.sourceId(), pending.version(), state.rows(), null, null));
                    delivered = true;
                    break;
                }
                if (!result.retryable()) {
                    persist(new SnapshotStore.State(state.sourceId(), state.deliveredVersion(), state.rows(),
                            pending, reason));
                    break;
                }
                if (state.rejection() != null) {
                    persist(new SnapshotStore.State(state.sourceId(), state.deliveredVersion(), state.rows(),
                            pending, null));
                }
                if (attempt < settings.maxAttempts()) {
                    Thread.sleep(settings.retryDelay().multipliedBy(attempt));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "Delivery interrupted";
        } catch (IOException | tools.jackson.core.JacksonException e) {
            reason = "Snapshot storage or encoding failed: " + e.getClass().getSimpleName();
        }
        updateStatus(reason);
        return new Emission(delivered, status());
    }

    private void createPending() throws IOException {
        long version = Math.incrementExact(state.deliveredVersion());
        var rows = settings.fixtureFile() == null
                ? generator.next(state.rows(), version, clock.instant())
                : readFixture();
        var pending = new SnapshotStore.Package(version, UUID.randomUUID().toString(), json.writeValueAsBytes(rows));
        persist(new SnapshotStore.State(state.sourceId(), state.deliveredVersion(), rows, pending, null));
        updateStatus(null);
    }

    private List<Map<String, Object>> readFixture() throws IOException {
        var rows = json.readValue(Files.readAllBytes(settings.fixtureFile()), ROWS);
        if (rows == null || rows.stream().anyMatch(row -> row == null || !(row.get("id") instanceof String id)
                || id.isBlank())) {
            throw new IOException("Fixture must contain a JSON array of incident records with string IDs");
        }
        return rows;
    }

    private void persist(SnapshotStore.State next) throws IOException {
        store.save(next);
        state = next;
    }

    Status status() {
        return currentStatus;
    }

    /** The incidents of the latest snapshot (generated before it is delivered). */
    synchronized List<Map<String, Object>> rows() {
        return List.copyOf(state.rows());
    }

    private void updateStatus(String reason) {
        var pending = state.pending();
        currentStatus = new Status(state.sourceId(), state.deliveredVersion(),
                pending == null ? null : pending.version(),
                pending == null ? null : pending.batchId(), state.rejection() != null, reason);
    }

    record Emission(boolean delivered, Status status) {}

    record Status(String sourceId, long deliveredVersion, Long pendingVersion, String pendingBatchId,
                  boolean paused, String reason) {}
}
