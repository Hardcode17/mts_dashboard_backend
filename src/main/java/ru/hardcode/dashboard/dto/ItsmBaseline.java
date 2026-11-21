package ru.hardcode.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ru.hardcode.dashboard.entity.ItsmIncidentState;

/**
 * Atomically published ITSM baseline: the current incidents, the parent-scoped catalog,
 * external people/groups/assignments and the original incident attributes.
 *
 * @param snapshot installed snapshot metadata, or {@code null} when no snapshot was applied
 * @param incidents current incidents in ITSM number order
 * @param products full-product catalog nodes
 * @param services services of products
 * @param scenarios scenarios of services
 * @param people external ITSM people; never application accounts
 * @param groups external ITSM support groups
 * @param assignments active external assignments by incident id
 * @param attributes original attributes by incident id
 */
public record ItsmBaseline(
		ItsmBaselineSnapshot snapshot,
		List<ItsmBaselineIncident> incidents,
		List<ItsmBaselineProduct> products,
		List<ItsmBaselineService> services,
		List<ItsmBaselineScenario> scenarios,
		List<ItsmBaselinePerson> people,
		List<ItsmBaselineGroup> groups,
		Map<String, ItsmBaselineAssignment> assignments,
		Map<String, List<ItsmBaselineAttribute>> attributes
) {

	public ItsmBaseline {
		incidents = List.copyOf(incidents);
		products = List.copyOf(products);
		services = List.copyOf(services);
		scenarios = List.copyOf(scenarios);
		people = List.copyOf(people);
		groups = List.copyOf(groups);
		assignments = Map.copyOf(assignments);
		attributes = Map.copyOf(attributes);
	}

	public record ItsmBaselineSnapshot(
			UUID batchId,
			String sourceId,
			long sourceVersion,
			Instant appliedAt,
			int incidentCount
	) {
	}

	public record ItsmBaselineProduct(
			long id,
			String productKey,
			String externalProductId,
			String productMasterId,
			String productUrl
	) {
	}

	public record ItsmBaselineService(
			long id,
			long productId,
			String serviceKey,
			String externalServiceId
	) {
	}

	public record ItsmBaselineScenario(
			long id,
			long serviceId,
			String scenarioKey
	) {
	}

	public record ItsmBaselinePerson(
			long id,
			String externalLogin,
			String displayName
	) {
	}

	public record ItsmBaselineGroup(
			long id,
			String groupName
	) {
	}

	/**
	 * One incident. The active external holder is resolved through {@link #assignments()} and
	 * is intentionally not a local user id.
	 */
	public record ItsmBaselineIncident(
			String id,
			String sysId,
			ItsmIncidentState state,
			int priorityId,
			Instant submitDate,
			Instant lastAssignmentToTeamAt,
			Instant deadline,
			Instant completionDate,
			Instant waitingExitDate,
			String solution,
			long productId,
			Long serviceId,
			Long scenarioId,
			Long assignedGroupId,
			Long assignedPersonId,
			Long initiatorPersonId
	) {
	}

	public record ItsmBaselineAssignment(
			String incidentId,
			long personId,
			long groupId,
			Instant assignedAt
	) {
	}

	public enum ItsmBaselineAttributeType {
		STRING,
		NUMBER,
		BOOLEAN,
		NULL
	}

	public record ItsmBaselineAttribute(
			String name,
			ItsmBaselineAttributeType type,
			String stringValue,
			BigDecimal numberValue,
			Boolean booleanValue
	) {
	}
}
