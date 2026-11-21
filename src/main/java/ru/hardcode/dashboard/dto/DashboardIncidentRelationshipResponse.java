package ru.hardcode.dashboard.dto;

import java.util.List;

import ru.hardcode.dashboard.entity.ItsmIncidentState;

/**
 * One-step incident graph around the focus incident. Only the focus, its direct parent and its
 * direct children are present. Existing incidents can lack an imported title/state/priority.
 */
public record DashboardIncidentRelationshipResponse(
		String focusId,
		List<Node> nodes,
		List<Edge> edges
) {

	public DashboardIncidentRelationshipResponse {
		nodes = List.copyOf(nodes);
		edges = List.copyOf(edges);
	}

	public record Node(String id, String title, ItsmIncidentState stateEnum, Integer priorityId) {
	}

	public record Edge(String parentId, String childId) {
	}
}
