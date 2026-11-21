package ru.hardcode.itsmmock;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class SnapshotStore {
    private final Path file;
    private final ObjectMapper json;

    SnapshotStore(Path file, ObjectMapper json) {
        this.file = file.toAbsolutePath();
        this.json = json;
    }

    State read(String sourceId) throws IOException {
        if (Files.notExists(file)) {
            return new State(sourceId, 0, List.of(), null, null);
        }
        State state = json.readValue(Files.readAllBytes(file), State.class);
        if (!sourceId.equals(state.sourceId())) {
            throw new IllegalStateException("Stored state belongs to a different source");
        }
        return state;
    }

    void save(State state) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".snapshot-", ".tmp");
        try {
            Files.write(temporary, json.writeValueAsBytes(state));
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record State(String sourceId, long deliveredVersion, List<Map<String, Object>> rows, Package pending,
                 String rejection) {}

    record Package(long version, String batchId, byte[] body) {}
}
