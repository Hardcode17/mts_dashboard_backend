package ru.hardcode.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.hardcode.dashboard.dto.ItsmBaseline;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineAttribute;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineAttributeType;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineAssignment;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineGroup;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineIncident;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselinePerson;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineProduct;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineScenario;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineService;
import ru.hardcode.dashboard.dto.ItsmBaseline.ItsmBaselineSnapshot;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.service.ItsmDomainMapping;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogProduct;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogScenario;
import ru.hardcode.dashboard.service.ItsmDomainMapping.CatalogService;
import ru.hardcode.dashboard.service.ItsmDomainMapping.ExternalAssignment;
import ru.hardcode.dashboard.service.ItsmDomainMapping.ExternalGroup;
import ru.hardcode.dashboard.service.ItsmDomainMapping.ExternalPerson;
import ru.hardcode.dashboard.service.ItsmDomainMapping.Incident;
import ru.hardcode.dashboard.service.ItsmDomainMapping.IncidentAttribute;

/**
 * PostgreSQL storage of the KAN-17 domain baseline and current projection. Imports run
 * under the shared publication lock in the caller's transaction.
 */
@Repository
public class ItsmBaselineRepository {

	/** Stable transaction lock key for replacing the ITSM baseline. */
	private static final long BASELINE_LOCK_KEY = 4_723_199_901_278L;

	private static final String SNAPSHOT_SQL = """
			SELECT batch_id, source_id, source_version, payload_sha256, incident_count, applied_at
			FROM itsm_snapshot
			WHERE id = 1
			""";

	/**
	 * Reads the whole baseline in one statement. Every table is a branch with its own columns;
	 * {@code UNION ALL} keeps all sections, and PostgreSQL evaluates them from one version of
	 * the database, so a reader never mixes two installed snapshots. Columns not relevant to a
	 * branch are NULL-typed casts.
	 */
	private static final String BASELINE_SQL = """
			WITH snap AS (
			    SELECT batch_id, source_id, source_version, incident_count, applied_at
			    FROM itsm_snapshot WHERE id = 1
			), inc AS (
			    SELECT id, sys_id, state, priority_id, submit_date, last_assignment_to_team_at, deadline,
			           completion_date, waiting_exit_date, solution, product_id, service_id, scenario_id,
			           assigned_group_id, assigned_person_id, initiator_person_id
			    FROM itsm_incident WHERE present_in_source
			), prod AS (
			    SELECT id, product_key, external_product_id, product_master_id, product_url
			    FROM itsm_catalog_product
			), svc AS (
			    SELECT id, product_id, service_key, external_service_id
			    FROM itsm_catalog_service
			), scen AS (
			    SELECT id, service_id, scenario_key
			    FROM itsm_catalog_scenario
			), person AS (
			    SELECT id, external_login, display_name
			    FROM itsm_external_person
			), grp AS (
			    SELECT id, group_name
			    FROM itsm_external_group
			), asg AS (
			    SELECT incident_id, person_id, group_id, assigned_at
			    FROM itsm_external_assignment WHERE incident_id IN (SELECT id FROM inc)
			), attr AS (
			    SELECT incident_id, attribute_name, value_type, string_value, number_value, boolean_value
			    FROM itsm_incident_attribute WHERE incident_id IN (SELECT id FROM inc)
			)
			SELECT 'SNAPSHOT' AS section,
			       NULL::TEXT AS incident_id, NULL::TEXT AS sys_id, NULL::BIGINT AS product_id,
			       NULL::BIGINT AS service_id, NULL::BIGINT AS scenario_id, NULL::BIGINT AS assigned_group_id,
			       NULL::BIGINT AS assigned_person_id, NULL::BIGINT AS initiator_person_id,
			       NULL::TEXT AS state, NULL::INTEGER AS priority_id, NULL::TIMESTAMP AS submit_date,
			       NULL::TIMESTAMP AS last_assignment_to_team_at, NULL::TIMESTAMP AS deadline,
			       NULL::TIMESTAMP AS completion_date, NULL::TIMESTAMP AS waiting_exit_date, NULL::TEXT AS solution,
			       NULL::TEXT AS text_key, NULL::TEXT AS text_value, NULL::TEXT AS text_extra,
			       NULL::TEXT AS text_last, NULL::TEXT AS attribute_type, NULL::NUMERIC AS number_value,
			       NULL::BOOLEAN AS boolean_value,
			       NULL::TIMESTAMP AS assigned_at, batch_id, source_id, source_version, incident_count, applied_at
			FROM snap
			UNION ALL
			SELECT 'INCIDENT', id, sys_id, product_id, service_id, scenario_id, assigned_group_id,
			       assigned_person_id, initiator_person_id, state, priority_id, submit_date,
			       last_assignment_to_team_at, deadline, completion_date, waiting_exit_date, solution,
			       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
			FROM inc
			UNION ALL
			SELECT 'PRODUCT', NULL, NULL, id, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL, product_key, external_product_id, product_master_id, product_url, NULL,
			       NULL, NULL, NULL, NULL, NULL, NULL, NULL
			FROM prod
			UNION ALL
			SELECT 'SERVICE', NULL, NULL, id, product_id, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL, service_key, external_service_id, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL, NULL, NULL
			FROM svc
			UNION ALL
			SELECT 'SCENARIO', NULL, NULL, id, service_id, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL, scenario_key, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL
			FROM scen
			UNION ALL
			SELECT 'PERSON', NULL, NULL, id, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, external_login, display_name, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL
			FROM person
			UNION ALL
			SELECT 'GROUP', NULL, NULL, id, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, group_name, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL
			FROM grp
			UNION ALL
			SELECT 'ASSIGNMENT', incident_id, NULL, NULL, NULL, NULL, group_id, person_id, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, assigned_at,
			       NULL, NULL, NULL, NULL, NULL
			FROM asg
			UNION ALL
			SELECT 'ATTRIBUTE', incident_id, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
			       NULL, NULL, NULL, NULL, attribute_name, string_value, NULL, NULL, value_type, number_value,
			       boolean_value, NULL, NULL, NULL, NULL, NULL, NULL
			FROM attr
			""";

	private final JdbcClient jdbcClient;

	public ItsmBaselineRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * Metadata of the installed snapshot.
	 */
	public Optional<ItsmBaselineSnapshot> findSnapshot() {
		return jdbcClient.sql(SNAPSHOT_SQL).query(this::mapSnapshot).optional();
	}

	/** Serializes first-snapshot installation until the caller commits. */
	public void lockInstallation() {
		jdbcClient.sql("SELECT pg_advisory_xact_lock(?)").param(BASELINE_LOCK_KEY).query().singleRow();
	}

	/** Installs the first baseline under the caller's installation lock. */
	public int install(
			ItsmDomainMapping mapping,
			UUID batchId,
			String sourceId,
			long sourceVersion,
			String payloadSha256,
			Instant appliedAt
	) {

		Set<String> incidentIds = mapping.records().stream().map(Incident::id)
				.collect(java.util.stream.Collectors.toSet());
		int incidents = upsertDomain(mapping, sourceId, incidentIds, Set.of());
		updateSnapshot(batchId, sourceId, sourceVersion, payloadSha256, appliedAt);
		return incidents;
	}

	/** Updates source-owned columns and current external assignments only, never local incident fields. */
	public int upsertDomain(ItsmDomainMapping mapping, String sourceId,
			Set<String> changedPresent, Set<String> missing) {
		deleteExternalAssignments(missing);
		if (changedPresent.isEmpty()) {
			return 0;
		}
		Map<String, Long> productIds = insertProducts(mapping.products());
		Map<ServiceRef, Long> serviceIds = insertServices(mapping.services(), productIds);
		Map<ScenarioRef, Long> scenarioIds = insertScenarios(mapping.scenarios(), serviceIds);
		Map<String, Long> personIds = insertPeople(mapping.people());
		Map<String, Long> groupIds = insertGroups(mapping.groups());
		insertHints(mapping.catalogHints());
		List<Incident> changed = mapping.records().stream().filter(incident -> changedPresent.contains(incident.id())).toList();
		int incidents = insertIncidents(changed, sourceId, productIds, serviceIds, scenarioIds, personIds, groupIds);
		deleteExternalAssignments(changedPresent);
		insertAssignments(mapping.assignments().stream().filter(a -> changedPresent.contains(a.incidentId())).toList(),
				personIds, groupIds);
		jdbcClient.sql("DELETE FROM itsm_incident_attribute WHERE incident_id IN (:ids)")
				.param("ids", changedPresent).update();
		insertAttributes(mapping.attributes(), changedPresent);
		return incidents;
	}

	private void deleteExternalAssignments(Set<String> ids) {
		if (!ids.isEmpty()) {
			jdbcClient.sql("DELETE FROM itsm_external_assignment WHERE incident_id IN (:ids)").param("ids", ids).update();
		}
	}

	public void updateSnapshot(UUID batchId, String sourceId, long sourceVersion, String payloadSha256, Instant appliedAt) {
		jdbcClient.sql("""
				INSERT INTO itsm_snapshot (id, batch_id, source_id, source_version, payload_sha256,
				                           incident_count, applied_at)
				VALUES (1, ?, ?, ?, ?, (SELECT count(*) FROM itsm_incident WHERE present_in_source), ?)
				ON CONFLICT (id) DO UPDATE SET batch_id = EXCLUDED.batch_id, source_id = EXCLUDED.source_id,
				    source_version = EXCLUDED.source_version, payload_sha256 = EXCLUDED.payload_sha256,
				    incident_count = EXCLUDED.incident_count, applied_at = EXCLUDED.applied_at
				""")
				.param(batchId)
				.param(sourceId)
				.param(sourceVersion)
				.param(payloadSha256)
				.param(Timestamp.from(appliedAt))
				.update();
	}

	/**
	 * Reads the installed baseline in one statement. The snapshot is {@code null} when no
	 * snapshot was installed yet.
	 */
	public ItsmBaseline read() {
		var reader = new BaselineReader();
		jdbcClient.sql(BASELINE_SQL).query(reader);
		return reader.toBaseline();
	}

	private Map<String, Long> insertProducts(List<CatalogProduct> products) {
		var ids = new LinkedHashMap<String, Long>(products.size());
		for (CatalogProduct product : products) {
			Long id = jdbcClient.sql("""
					INSERT INTO itsm_catalog_product (product_key, external_product_id, product_master_id, product_url)
					VALUES (?, ?, ?, ?)
					ON CONFLICT (product_key) DO UPDATE SET product_key = EXCLUDED.product_key
					RETURNING id
					""")
					.param(product.productKey())
					.param(product.externalProductId())
					.param(product.productMasterId())
					.param(product.productUrl())
					.query(Long.class)
					.single();
			ids.put(product.productKey(), id);
		}
		return ids;
	}

	private Map<ServiceRef, Long> insertServices(List<CatalogService> services, Map<String, Long> productIds) {
		var ids = new LinkedHashMap<ServiceRef, Long>(services.size());
		for (CatalogService service : services) {
			Long productId = requireParent(productIds, service.parentProductKey(), "product", service.serviceKey());
			Long id = jdbcClient.sql("""
					INSERT INTO itsm_catalog_service (product_id, service_key, external_service_id)
					VALUES (?, ?, ?)
					ON CONFLICT (product_id, service_key) DO UPDATE SET service_key = EXCLUDED.service_key
					RETURNING id
					""")
					.param(productId)
					.param(service.serviceKey())
					.param(service.externalServiceId())
					.query(Long.class)
					.single();
			ids.put(new ServiceRef(service.parentProductKey(), service.serviceKey()), id);
		}
		return ids;
	}

	private Map<ScenarioRef, Long> insertScenarios(List<CatalogScenario> scenarios, Map<ServiceRef, Long> serviceIds) {
		var ids = new LinkedHashMap<ScenarioRef, Long>(scenarios.size());
		for (CatalogScenario scenario : scenarios) {
			ServiceRef parent = new ServiceRef(scenario.parentProductKey(), scenario.parentServiceKey());
			Long serviceId = requireParent(serviceIds, parent, "service", scenario.scenarioKey());
			Long id = jdbcClient.sql("""
					INSERT INTO itsm_catalog_scenario (service_id, scenario_key)
					VALUES (?, ?)
					ON CONFLICT (service_id, scenario_key) DO UPDATE SET scenario_key = EXCLUDED.scenario_key
					RETURNING id
					""")
					.param(serviceId)
					.param(scenario.scenarioKey())
					.query(Long.class)
					.single();
			ids.put(new ScenarioRef(parent, scenario.scenarioKey()), id);
		}
		return ids;
	}

	private static <K> Long requireParent(Map<K, Long> ids, K parent, String scope, String childKey) {
		Long id = ids.get(parent);
		if (id == null) {
			throw new IllegalStateException(
					"ITSM " + scope + " parent of " + childKey + " was not inserted; catalog parents are inconsistent"
			);
		}
		return id;
	}

	private Map<String, Long> insertPeople(List<ExternalPerson> people) {
		var ids = new LinkedHashMap<String, Long>(people.size());
		for (ExternalPerson person : people) {
			Long id = jdbcClient.sql("""
					INSERT INTO itsm_external_person (external_login, display_name)
					VALUES (?, ?)
					ON CONFLICT (external_login) DO UPDATE SET external_login = EXCLUDED.external_login
					RETURNING id
					""")
					.param(person.externalLogin())
					.param(person.displayName())
					.query(Long.class)
					.single();
			ids.put(person.externalLogin(), id);
		}
		return ids;
	}

	private Map<String, Long> insertGroups(List<ExternalGroup> groups) {
		var ids = new LinkedHashMap<String, Long>(groups.size());
		for (ExternalGroup group : groups) {
			Long id = jdbcClient.sql("""
					INSERT INTO itsm_external_group (group_name)
					VALUES (?)
					ON CONFLICT (group_name) DO UPDATE SET group_name = EXCLUDED.group_name
					RETURNING id
					""")
					.param(group.groupName())
					.query(Long.class)
					.single();
			ids.put(group.groupName(), id);
		}
		return ids;
	}

	private void insertHints(List<ItsmDomainMapping.CatalogHint> hints) {
		for (ItsmDomainMapping.CatalogHint hint : hints) {
			jdbcClient.sql("""
					INSERT INTO itsm_ingest_catalog_hint (identity_scope, parent_key, external_identity)
					VALUES (?, ?, ?)
					ON CONFLICT (identity_scope, parent_key, external_identity) DO NOTHING
					""")
					.param(hint.scope().name())
					.param(hint.parentKey())
					.param(hint.externalIdentity())
					.update();
		}
	}

	private int insertIncidents(
			List<Incident> incidents,
			String sourceId,
			Map<String, Long> productIds,
			Map<ServiceRef, Long> serviceIds,
			Map<ScenarioRef, Long> scenarioIds,
			Map<String, Long> personIds,
			Map<String, Long> groupIds
	) {
		for (Incident incident : incidents) {
			Long productId = requireParent(productIds, incident.productKey(), "product", incident.id());
			jdbcClient.sql("""
					INSERT INTO itsm_incident (
					    id, sys_id, state, priority_id, submit_date, last_assignment_to_team_at, deadline,
					    completion_date, waiting_exit_date, solution, product_id, service_id, scenario_id,
					    assigned_group_id, assigned_person_id, initiator_person_id, source_id
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (id) DO UPDATE SET sys_id = EXCLUDED.sys_id, state = EXCLUDED.state,
					    priority_id = EXCLUDED.priority_id, submit_date = EXCLUDED.submit_date,
					    last_assignment_to_team_at = EXCLUDED.last_assignment_to_team_at, deadline = EXCLUDED.deadline,
					    completion_date = EXCLUDED.completion_date, waiting_exit_date = EXCLUDED.waiting_exit_date,
					    solution = EXCLUDED.solution, product_id = EXCLUDED.product_id, service_id = EXCLUDED.service_id,
					    scenario_id = EXCLUDED.scenario_id, assigned_group_id = EXCLUDED.assigned_group_id,
					    assigned_person_id = EXCLUDED.assigned_person_id, initiator_person_id = EXCLUDED.initiator_person_id,
					    source_id = EXCLUDED.source_id
					""")
					.param(incident.id())
					.param(incident.sysId())
					.param(incident.state().name())
					.param(incident.priorityId())
					.param(timestamp(incident.submitDate()))
					.param(timestamp(incident.lastAssignmentToTeamAt()))
					.param(timestamp(incident.deadline()))
					.param(timestamp(incident.completionDate()))
					.param(timestamp(incident.waitingExitDate()))
					.param(incident.solution())
					.param(productId)
					.param(serviceId(serviceIds, incident))
					.param(scenarioId(scenarioIds, incident))
					.param(optionalId(groupIds, incident.assignedGroupName()))
					.param(optionalId(personIds, incident.assignedPersonLogin()))
					.param(optionalId(personIds, incident.initiatorLogin()))
					.param(sourceId)
					.update();
		}
		return incidents.size();
	}

	private void insertAssignments(
			List<ExternalAssignment> assignments,
			Map<String, Long> personIds,
			Map<String, Long> groupIds
	) {
		for (ExternalAssignment assignment : assignments) {
			Long personId = requireParent(personIds, assignment.personLogin(), "person", assignment.incidentId());
			Long groupId = requireParent(groupIds, assignment.groupName(), "group", assignment.incidentId());
			jdbcClient.sql("""
					INSERT INTO itsm_external_assignment (incident_id, person_id, group_id, assigned_at)
					VALUES (?, ?, ?, ?)
					""")
					.param(assignment.incidentId())
					.param(personId)
					.param(groupId)
					.param(timestamp(assignment.assignedAt()))
					.update();
		}
	}

	private void insertAttributes(Map<String, List<IncidentAttribute>> attributes, Set<String> ids) {
		attributes.forEach((incidentId, values) -> {
			if (!ids.contains(incidentId)) {
				return;
			}
			for (IncidentAttribute attribute : values) {
				jdbcClient.sql("""
						INSERT INTO itsm_incident_attribute (
						    incident_id, attribute_name, value_type, string_value, number_value, boolean_value
						) VALUES (?, ?, ?, ?, ?, ?)
						""")
						.param(incidentId)
						.param(attribute.name())
						.param(attribute.type().name())
						.param(attribute.stringValue())
						.param(attribute.numberValue() == null ? null : new java.math.BigDecimal(attribute.numberValue()))
						.param(attribute.booleanValue())
						.update();
			}
		});
	}

	private static Long serviceId(Map<ServiceRef, Long> serviceIds, Incident incident) {
		return incident.serviceKey() == null
				? null
				: serviceIds.get(new ServiceRef(incident.productKey(), incident.serviceKey()));
	}

	private static Long scenarioId(Map<ScenarioRef, Long> scenarioIds, Incident incident) {
		return incident.scenarioKey() == null || incident.serviceKey() == null
				? null
				: scenarioIds.get(new ScenarioRef(
						new ServiceRef(incident.productKey(), incident.serviceKey()),
						incident.scenarioKey()
				));
	}

	private static Long optionalId(Map<String, Long> ids, String key) {
		return key == null ? null : ids.get(key);
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static Long longOrNull(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private ItsmBaselineSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
		return new ItsmBaselineSnapshot(
				rs.getObject("batch_id", UUID.class),
				rs.getString("source_id"),
				rs.getLong("source_version"),
				instant(rs, "applied_at"),
				rs.getInt("incident_count")
		);
	}

	/** Accumulates the section rows of {@link #BASELINE_SQL}. */
	private static final class BaselineReader implements RowCallbackHandler {

		private ItsmBaselineSnapshot snapshot;
		private final List<ItsmBaselineIncident> incidents = new ArrayList<>();
		private final List<ItsmBaselineProduct> products = new ArrayList<>();
		private final List<ItsmBaselineService> services = new ArrayList<>();
		private final List<ItsmBaselineScenario> scenarios = new ArrayList<>();
		private final List<ItsmBaselinePerson> people = new ArrayList<>();
		private final List<ItsmBaselineGroup> groups = new ArrayList<>();
		private final Map<String, ItsmBaselineAssignment> assignments = new LinkedHashMap<>();
		private final Map<String, List<ItsmBaselineAttribute>> attributes = new LinkedHashMap<>();

		@Override
		public void processRow(ResultSet rs) throws SQLException {
			switch (rs.getString("section")) {
				case "SNAPSHOT" -> {
					if (snapshot == null) {
						snapshot = new ItsmBaselineSnapshot(
								rs.getObject("batch_id", UUID.class),
								rs.getString("source_id"),
								rs.getLong("source_version"),
								instant(rs, "applied_at"),
								rs.getInt("incident_count")
						);
					}
				}
				case "INCIDENT" -> incidents.add(new ItsmBaselineIncident(
						rs.getString("incident_id"),
						rs.getString("sys_id"),
						ItsmIncidentState.valueOf(rs.getString("state")),
						rs.getInt("priority_id"),
						instant(rs, "submit_date"),
						instant(rs, "last_assignment_to_team_at"),
						instant(rs, "deadline"),
						instant(rs, "completion_date"),
						instant(rs, "waiting_exit_date"),
						rs.getString("solution"),
						rs.getLong("product_id"),
						longOrNull(rs, "service_id"),
						longOrNull(rs, "scenario_id"),
						longOrNull(rs, "assigned_group_id"),
						longOrNull(rs, "assigned_person_id"),
						longOrNull(rs, "initiator_person_id")
				));
				case "PRODUCT" -> products.add(new ItsmBaselineProduct(
						rs.getLong("product_id"),
						rs.getString("text_key"),
						rs.getString("text_value"),
						rs.getString("text_extra"),
						rs.getString("text_last")
				));
				case "SERVICE" -> services.add(new ItsmBaselineService(
						rs.getLong("product_id"),
						rs.getLong("service_id"),
						rs.getString("text_key"),
						rs.getString("text_value")
				));
				case "SCENARIO" -> scenarios.add(new ItsmBaselineScenario(
						rs.getLong("product_id"),
						rs.getLong("service_id"),
						rs.getString("text_key")
				));
				case "PERSON" -> people.add(new ItsmBaselinePerson(
						rs.getLong("product_id"),
						rs.getString("text_key"),
						rs.getString("text_value")
				));
				case "GROUP" -> groups.add(new ItsmBaselineGroup(
						rs.getLong("product_id"),
						rs.getString("text_key")
				));
				case "ASSIGNMENT" -> {
					var assignment = new ItsmBaselineAssignment(
							rs.getString("incident_id"),
							rs.getLong("assigned_person_id"),
							rs.getLong("assigned_group_id"),
							instant(rs, "assigned_at")
					);
					assignments.put(assignment.incidentId(), assignment);
				}
				case "ATTRIBUTE" -> attributes
						.computeIfAbsent(rs.getString("incident_id"), key -> new ArrayList<>())
						.add(new ItsmBaselineAttribute(
								rs.getString("text_key"),
								ItsmBaselineAttributeType.valueOf(rs.getString("attribute_type")),
								rs.getString("text_value"),
								rs.getBigDecimal("number_value"),
								rs.getObject("boolean_value", Boolean.class)
						));
				default -> {
					// The query is the only producer of these sections.
				}
			}
		}

		ItsmBaseline toBaseline() {
			return new ItsmBaseline(
					snapshot, incidents, products, services, scenarios, people, groups, assignments, attributes
			);
		}
	}

	private record ServiceRef(String parentProductKey, String serviceKey) {
	}

	private record ScenarioRef(ServiceRef parentService, String scenarioKey) {
	}
}
