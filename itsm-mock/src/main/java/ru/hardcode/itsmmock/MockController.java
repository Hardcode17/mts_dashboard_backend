package ru.hardcode.itsmmock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mock")
final class MockController {
    private final SnapshotSender sender;
    private final IncidentDirectory directory;

    MockController(SnapshotSender sender, IncidentDirectory directory) {
        this.sender = sender;
        this.directory = directory;
    }

    /**
     * Fixed read of one stand incident, including its assigned group. Read-only: it never
     * changes the stand snapshot.
     */
    @GetMapping("/incidents/{incidentId}")
    ResponseEntity<IncidentDirectory.IncidentView> incident(@PathVariable String incidentId) {
        return directory.find(incidentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/emit")
    ResponseEntity<SnapshotSender.Status> emit() {
        var emission = sender.emit(true);
        return ResponseEntity.status(emission.delivered() ? 200 : 502).body(emission.status());
    }

    @GetMapping("/status")
    SnapshotSender.Status status() {
        return sender.status();
    }
}
