package ru.hardcode.itsmmock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "itsm.mock.scheduled", havingValue = "true", matchIfMissing = true)
final class SnapshotSchedule {
    private final SnapshotSender sender;

    SnapshotSchedule(SnapshotSender sender) {
        this.sender = sender;
    }

    @Scheduled(fixedRateString = "${itsm.mock.interval}", initialDelayString = "${itsm.mock.interval}")
    void publish() {
        sender.emit(false);
    }
}
