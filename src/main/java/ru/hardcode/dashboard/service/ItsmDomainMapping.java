package ru.hardcode.dashboard.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.entity.ItsmIncidentState;

/**
 * One validated ITSM snapshot mapped to the domain catalog and to external people from the
 * original attributes. Catalog nodes are scoped by their parent, external people and the
 * active external assignment stay separate from local accounts, and the original auxiliary
 * attributes are kept per incident.
 *
 * @param records          incidents in original order
 * @param products         full-product nodes
 * @param services         services of products
 * @param scenarios        scenarios of services
 * @param catalogHints     observed external identities per catalog scope and parent
 * @param people           external people (initiators and active holders)
 * @param groups           external support groups
 * @param assignments      current active external assignment per incident
 * @param attributes       original auxiliary attributes per incident id
 * @param snapshot         the validated parsed snapshot
 */
public record ItsmDomainMapping(
		List<Incident> records,
		List<CatalogProduct> products,
		List<CatalogService> services,
		List<CatalogScenario> scenarios,
		List<CatalogHint> catalogHints,
		List<ExternalPerson> people,
		List<ExternalGroup> groups,
		List<ExternalAssignment> assignments,
		Map<String, List<IncidentAttribute>> attributes,
		ItsmIncidentSnapshot snapshot
) {

	public ItsmDomainMapping {
		records = List.copyOf(records);
		products = List.copyOf(products);
		services = List.copyOf(services);
		scenarios = List.copyOf(scenarios);
		catalogHints = List.copyOf(catalogHints);
		people = List.copyOf(people);
		groups = List.copyOf(groups);
		assignments = List.copyOf(assignments);
		attributes = Map.copyOf(attributes);
	}

	public int incidentCount() {
		return records.size();
	}

	/**
	 * Full-product catalog node. {@code productKey} is the original product name and the
	 * parent scope; {@code externalProductId} is the original productId of the observed row.
	 */
	public record CatalogProduct(
			String productKey,
			String externalProductId,
			String productMasterId,
			String productUrl
	) {
	}

	/** Service of one product; {@code parentProductKey} is the product name. */
	public record CatalogService(
			String parentProductKey,
			String serviceKey,
			String externalServiceId
	) {
	}

	/** Scenario of one service; {@code parentServiceKey} is unique inside its product. */
	public record CatalogScenario(
			String parentProductKey,
			String parentServiceKey,
			String scenarioKey
	) {
	}

	public enum CatalogScope {
		PRODUCT_ID,
		SERVICE_ID
	}

	/** An observed external identity under its parent scope. */
	public record CatalogHint(
			CatalogScope scope,
			String parentKey,
			String externalIdentity
	) {
	}

	public record ExternalPerson(
			String externalLogin,
			String displayName
	) {
	}

	public record ExternalGroup(
			String groupName
	) {
	}

	/**
	 * Active external assignment of one incident. {@code assignedAt} is the original
	 * last-assignment time (the submit time when the snapshot has none).
	 */
	public record ExternalAssignment(
			String incidentId,
			String personLogin,
			String groupName,
			Instant assignedAt
	) {
	}

	public record Incident(
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
			String productKey,
			String serviceKey,
			String scenarioKey,
			String assignedGroupName,
			String assignedPersonLogin,
			String initiatorLogin
	) {
	}

	public enum AttributeType {
		STRING,
		NUMBER,
		BOOLEAN,
		NULL
	}

	/**
	 * One original attribute value. Exactly one of {@code stringValue},
	 * {@code numberValue} and {@code booleanValue} is set, or none for {@link AttributeType#NULL}.
	 */
	public record IncidentAttribute(
			String name,
			AttributeType type,
			String stringValue,
			String numberValue,
			Boolean booleanValue
	) {
		public static IncidentAttribute ofString(String name, String value) {
			return new IncidentAttribute(name, AttributeType.STRING, value, null, null);
		}

		public static IncidentAttribute ofNumber(String name, String value) {
			return new IncidentAttribute(name, AttributeType.NUMBER, null, value, null);
		}

		public static IncidentAttribute ofBoolean(String name, boolean value) {
			return new IncidentAttribute(name, AttributeType.BOOLEAN, null, null, value);
		}

		public static IncidentAttribute ofNull(String name) {
			return new IncidentAttribute(name, AttributeType.NULL, null, null, null);
		}
	}
}
