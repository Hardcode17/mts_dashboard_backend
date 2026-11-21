package ru.hardcode.dashboard.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import ru.hardcode.dashboard.dto.ItsmIncidentRecord;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogHint;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogProduct;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogScenario;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogScope;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogService;
import ru.hardcode.dashboard.service.ItsmDomainMapping.ExternalAssignment;
import ru.hardcode.dashboard.service.ItsmDomainMapping.ExternalGroup;
import ru.hardcode.dashboard.service.ItsmDomainMapping.ExternalPerson;
import ru.hardcode.dashboard.service.ItsmDomainMapping.Incident;
import ru.hardcode.dashboard.service.ItsmDomainMapping.IncidentAttribute;

/**
 * Maps a validated {@link ItsmIncidentSnapshot} to the domain import (v5 §6.1 and §8).
 *
 * <p>Catalog parents come from the original text attributes: a product is a full product
 * name ({@code productName}), its services are {@code service} inside that product, and its
 * scenarios are {@code scenarioName} inside that service. The external {@code productId} and
 * {@code serviceId} are not parent keys: the original snapshot repeats one productId for
 * different products and one serviceId for services of different products, so such values are
 * recorded as observations under their parent and never merge two catalogs. People are taken
 * from login attributes only and are never treated as local application accounts.
 */
@Component
public class ItsmSnapshotMapper {

	static final String FIELD_SYS_ID = "sysId";
	static final String FIELD_PRODUCT_NAME = "productName";
	static final String FIELD_PRODUCT_ID = "productId";
	static final String FIELD_PRODUCT_MASTER_ID = "productMasterId";
	static final String FIELD_PRODUCT_URL = "productUrl";
	static final String FIELD_SERVICE = "service";
	static final String FIELD_SERVICE_ID = "serviceId";
	static final String FIELD_SCENARIO_NAME = "scenarioName";
	static final String FIELD_ASSIGNED_GROUP_NAME = "assignedGroupName";
	static final String FIELD_ASSIGNED_EMPLOYEE = "assignedEmployee";
	static final String FIELD_ASSIGNED_EMPLOYEE_LOGIN = "assignedEmployeeLogin";
	static final String FIELD_INITIATOR_NAME = "initiatorName";
	static final String FIELD_INITIATOR_LOGIN = "initiatorLogin";
	static final String FIELD_ID = "id";
	static final String FIELD_STATE_ENUM = "stateEnum";
	static final String FIELD_PRIORITY_ID = "priorityId";
	static final String FIELD_SUBMIT_DATE = "submitDate";

	/** Attributes owned by dedicated columns and therefore not repeated per incident. */
	private static final Set<String> COLUMN_BACKED_ATTRIBUTES = Set.of(
			FIELD_ID,
			FIELD_STATE_ENUM,
			FIELD_PRIORITY_ID,
			FIELD_SUBMIT_DATE,
			"deadline",
			"lastAssignmentToTeamDateTime",
			"completionDate",
			"solution",
			ItsmIncidentSnapshotParser.FIELD_WAITING_EXIT_DATE
	);

	/**
	 * @throws IllegalStateException when a validated record has no product name, so it cannot
	 *         be attached to the scoped catalog; the caller rolls the whole import back
	 */
	public ItsmDomainMapping map(ItsmIncidentSnapshot snapshot) {
		var incidents = new ArrayList<Incident>(snapshot.size());
		var products = new LinkedHashMap<String, CatalogProduct>();
		var services = new LinkedHashMap<ServiceKey, CatalogService>();
		var scenarios = new LinkedHashMap<ScenarioKey, CatalogScenario>();
		var hints = new LinkedHashSet<CatalogHint>();
		var people = new LinkedHashMap<String, ExternalPerson>();
		var groups = new LinkedHashSet<String>();
		var assignments = new ArrayList<ExternalAssignment>();
		var attributes = new LinkedHashMap<String, List<IncidentAttribute>>();

		for (ItsmIncidentRecord record : snapshot.records()) {
			Map<String, Object> raw = record.raw();
			String productName = requiredText(raw, FIELD_PRODUCT_NAME, record);
			String serviceName = text(raw, FIELD_SERVICE);
			String scenarioName = text(raw, FIELD_SCENARIO_NAME);
			boolean hasService = serviceName != null;
			boolean hasScenario = hasService && scenarioName != null;

			String productId = text(raw, FIELD_PRODUCT_ID);
			if (productId != null) {
				hints.add(new CatalogHint(CatalogScope.PRODUCT_ID, productName, productId));
			}
			products.putIfAbsent(productName, new CatalogProduct(
					productName,
					productId,
					text(raw, FIELD_PRODUCT_MASTER_ID),
					text(raw, FIELD_PRODUCT_URL)
			));
			if (hasService) {
				String serviceId = text(raw, FIELD_SERVICE_ID);
				if (serviceId != null) {
					hints.add(new CatalogHint(CatalogScope.SERVICE_ID, productName, serviceId));
				}
				services.putIfAbsent(new ServiceKey(productName, serviceName), new CatalogService(
						productName,
						serviceName,
						serviceId
				));
				if (hasScenario) {
					scenarios.putIfAbsent(new ScenarioKey(productName, serviceName, scenarioName),
							new CatalogScenario(productName, serviceName, scenarioName));
				}
			}

			String assignedLogin = login(raw, FIELD_ASSIGNED_EMPLOYEE_LOGIN);
			String assignedName = nameOf(raw, FIELD_ASSIGNED_EMPLOYEE);
			String initiatorLogin = login(raw, FIELD_INITIATOR_LOGIN);
			String initiatorName = nameOf(raw, FIELD_INITIATOR_NAME);
			putPerson(people, assignedLogin, assignedName);
			putPerson(people, initiatorLogin, initiatorName);
			String groupName = groupName(raw);
			if (groupName != null) {
				groups.add(groupName);
			}
			if (!record.state().isCompleted() && assignedLogin != null && groupName != null) {
				Instant assignedAt = record.lastAssignmentToTeamDateTime() != null
						? record.lastAssignmentToTeamDateTime()
						: record.submitDate();
				assignments.add(new ExternalAssignment(record.id(), assignedLogin, groupName, assignedAt));
			}

			incidents.add(new Incident(
					record.id(),
					text(raw, FIELD_SYS_ID),
					record.state(),
					record.priorityId(),
					record.submitDate(),
					record.lastAssignmentToTeamDateTime(),
					record.deadline(),
					record.completionDate(),
					record.technicalReferenceDate(),
					record.solution(),
					productName,
					hasService ? serviceName : null,
					hasScenario ? scenarioName : null,
					groupName,
					assignedLogin,
					initiatorLogin
			));
			attributes.put(record.id(), attributes(raw));
		}

		return new ItsmDomainMapping(
				incidents,
				List.copyOf(products.values()),
				List.copyOf(services.values()),
				List.copyOf(scenarios.values()),
				List.copyOf(hints),
				List.copyOf(people.values()),
				groups.stream().map(ExternalGroup::new).toList(),
				assignments,
				attributes,
				snapshot
		);
	}

	private static void putPerson(Map<String, ExternalPerson> people, String login, String displayName) {
		if (login == null) {
			return;
		}
		people.compute(login, (key, existing) ->
				existing != null && existing.displayName() != null
						? existing
						: new ExternalPerson(login, displayName)
		);
	}

	private static List<IncidentAttribute> attributes(Map<String, Object> raw) {
		var result = new ArrayList<IncidentAttribute>(raw.size());
		raw.forEach((name, value) -> {
			if (COLUMN_BACKED_ATTRIBUTES.contains(name)) {
				return;
			}
			result.add(attribute(name, value));
		});
		return List.copyOf(result);
	}

	/** Scalars keep their JSON type; nested objects and arrays are retained as their JSON text. */
	static IncidentAttribute attribute(String name, Object value) {
		return switch (value) {
			case null -> IncidentAttribute.ofNull(name);
			case String text -> IncidentAttribute.ofString(name, text);
			case Boolean flag -> IncidentAttribute.ofBoolean(name, flag);
			case BigDecimal decimal -> IncidentAttribute.ofNumber(name, decimal.toPlainString());
			case BigInteger integer -> IncidentAttribute.ofNumber(name, integer.toString());
			case Number number -> IncidentAttribute.ofNumber(name, new BigDecimal(number.toString()).toPlainString());
			default -> IncidentAttribute.ofString(name, value.toString());
		};
	}

	private static String requiredText(Map<String, Object> raw, String field, ItsmIncidentRecord record) {
		String value = text(raw, field);
		if (value == null) {
			throw new IllegalStateException(
					"ITSM incident " + record.id() + " has no " + field + " to attach to the catalog"
			);
		}
		return value;
	}

	/** A non-blank text value, or null for an absent, null, non-text or blank value. */
	private static String text(Map<String, Object> raw, String field) {
		if (raw.get(field) instanceof String value && !value.isBlank()) {
			return value;
		}
		return null;
	}

	private static String login(Map<String, Object> raw, String field) {
		String login = text(raw, field);
		return login == null ? null : login.strip();
	}

	/** Display text reduced to the person part: "Пятачок (piglet)" becomes "Пятачок". */
	private static String nameOf(Map<String, Object> raw, String field) {
		String name = text(raw, field);
		if (name == null) {
			return null;
		}
		int suffix = name.lastIndexOf(" (");
		String trimmed = suffix > 0 && name.endsWith(")") ? name.substring(0, suffix) : name;
		return trimmed.isBlank() ? null : trimmed.strip();
	}

	private static String groupName(Map<String, Object> raw) {
		String group = text(raw, FIELD_ASSIGNED_GROUP_NAME);
		return group == null ? null : group.strip();
	}

	private record ServiceKey(String productKey, String serviceKey) {
	}

	private record ScenarioKey(String productKey, String serviceKey, String scenarioKey) {
	}
}
