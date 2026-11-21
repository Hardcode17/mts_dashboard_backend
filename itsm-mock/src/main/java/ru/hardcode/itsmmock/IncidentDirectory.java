package ru.hardcode.itsmmock;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Read-only view of the stand incidents the mock owns. It is the last generated or delivered
 * snapshot, so the assigned group of an incident can be read back through the stand contract.
 */
@Component
final class IncidentDirectory {
    private final SnapshotSender sender;

    IncidentDirectory(SnapshotSender sender) {
        this.sender = sender;
    }

    /** The fixed incident record with {@code id}, or empty when the stand does not know it. */
    Optional<IncidentView> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (Map<String, Object> row : sender.rows()) {
            if (id.equals(row.get("id"))) {
                return Optional.of(new IncidentView(id, text(row, "stateEnum"),
                        text(row, "priority"), text(row, "assignedGroupName"),
                        text(row, "lastAssignmentToTeamDateTime")));
            }
        }
        return Optional.empty();
    }

    private static String text(Map<String, Object> row, String key) {
        return row.get(key) instanceof String value ? value : null;
    }

    record IncidentView(String id, String stateEnum, String priority, String assignedGroupName,
                        String lastAssignmentAt) {
    }
}
