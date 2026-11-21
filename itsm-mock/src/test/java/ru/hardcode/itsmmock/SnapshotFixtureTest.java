package ru.hardcode.itsmmock;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class SnapshotFixtureTest {
    @TempDir
    Path dir;

    @Test
    void addsCaseRowsWithoutOverwritingEditsOrChangingAnInFlightBatch() throws Exception {
        var mapper = JsonMapper.builder().build();
        var stateFile = dir.resolve("snapshot.json");
        var fixture = dir.resolve("fixture.json");
        Files.writeString(fixture, """
                [{"id":"INC001","stateEnum":"Assigned"},{"id":"INC002","stateEnum":"Assigned"}]
                """);
        Map<String, Object> edited = Map.of("id", "INC001", "stateEnum", "WorkInProgress");
        var pending = new SnapshotStore.Package(8, "existing-batch", mapper.writeValueAsBytes(List.of(edited)));
        var store = new SnapshotStore(stateFile, mapper);
        store.save(new SnapshotStore.State("stand-mock", 7, List.of(edited), pending, null, Set.of("INC001")));
        var settings = new MockSettings(
                URI.create("http://backend"), URI.create("http://keycloak"), "client", "secret", "stand-mock",
                53, 5, stateFile, fixture, Duration.ofSeconds(1), Duration.ZERO, 1);
        var transport = new BatchTransport(settings, mapper);

        var firstStart = new SnapshotSender(transport, settings, Clock.systemUTC(), mapper);
        var secondStart = new SnapshotSender(transport, settings, Clock.systemUTC(), mapper);

        assertThat(firstStart.rows()).containsExactly(edited, Map.of("id", "INC002", "stateEnum", "Assigned"));
        assertThat(secondStart.rows()).isEqualTo(firstStart.rows());
        var saved = store.read("stand-mock");
        assertThat(saved.deliveredVersion()).isEqualTo(7);
        assertThat(saved.manualStates()).containsExactly("INC001");
        assertThat(saved.pending().version()).isEqualTo(8);
        assertThat(saved.pending().batchId()).isEqualTo("existing-batch");
        assertThat(saved.pending().body()).isEqualTo(pending.body());
    }
}
