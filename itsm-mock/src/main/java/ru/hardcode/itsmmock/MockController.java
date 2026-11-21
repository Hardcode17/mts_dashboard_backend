package ru.hardcode.itsmmock;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mock")
final class MockController {
    private final SnapshotSender sender;

    MockController(SnapshotSender sender) {
        this.sender = sender;
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
