package ru.hardcode.dashboard.exception;

import org.springframework.http.HttpStatus;

public enum AppErrors implements AppError {
	COORDINATOR_AUTH_REQUIRED("COORDINATOR_AUTH_REQUIRED", HttpStatus.UNAUTHORIZED, "A valid user bearer token is required"),
	COORDINATOR_GROUP_REQUIRED("COORDINATOR_GROUP_REQUIRED", HttpStatus.FORBIDDEN,
			"Active coordinator group membership is required"),
	COORDINATOR_ACCESS_DENIED("COORDINATOR_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Coordinator access is not granted"),
	COORDINATION_REDIRECT_INVALID("COORDINATION_REDIRECT_INVALID", HttpStatus.BAD_REQUEST, "Invalid coordination task redirect"),
	COORDINATION_REDIRECT_FORBIDDEN("COORDINATION_REDIRECT_FORBIDDEN", HttpStatus.FORBIDDEN, "Coordination task is not available to this coordinator"),
	COORDINATION_ASSIGNMENT_CHANGED("COORDINATION_ASSIGNMENT_CHANGED", HttpStatus.CONFLICT, "Coordination assignment has changed"),
	COORDINATION_TASK_NOT_OPEN("COORDINATION_TASK_NOT_OPEN", HttpStatus.NOT_FOUND, "Open coordination task not found"),
	COORDINATION_TASK_TAKEN("COORDINATION_TASK_TAKEN", HttpStatus.CONFLICT, "Coordination task is already taken"),
	COORDINATION_WINDOW_UNAVAILABLE("COORDINATION_WINDOW_UNAVAILABLE", HttpStatus.CONFLICT, "Fullscreen acceptance window is not available"),
	COORDINATION_WINDOW_EXPIRED("COORDINATION_WINDOW_EXPIRED", HttpStatus.CONFLICT, "Fullscreen acceptance window has expired"),
	RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "Resource with id ${id} not found"),
	REQUEST_BODY_TOO_LARGE("REQUEST_BODY_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "Request body exceeds ${maxBytes} bytes"),
	INVALID_REQUEST_BODY("INVALID_REQUEST_BODY", HttpStatus.BAD_REQUEST, "Request body is malformed or missing"),
	UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported"),
	ITSM_INVALID_IDEMPOTENCY_KEY("ITSM_INVALID_IDEMPOTENCY_KEY", HttpStatus.BAD_REQUEST, "Idempotency-Key must be a UUID"),
	ITSM_INVALID_SOURCE_ID("ITSM_INVALID_SOURCE_ID", HttpStatus.BAD_REQUEST, "X-Source-Id must be 1-128 visible ASCII characters"),
	ITSM_INVALID_SOURCE_VERSION("ITSM_INVALID_SOURCE_VERSION", HttpStatus.BAD_REQUEST, "X-Source-Version must be a positive 64-bit integer"),
	ITSM_INVALID_PAYLOAD("ITSM_INVALID_PAYLOAD", HttpStatus.BAD_REQUEST, "Request body must be a single JSON array"),
	ITSM_INVALID_RECORD("ITSM_INVALID_RECORD", HttpStatus.BAD_REQUEST,
			"ITSM record in snapshot at index ${index} has invalid field ${field}: ${reason}"),
	ITSM_DUPLICATE_RECORD("ITSM_DUPLICATE_RECORD", HttpStatus.BAD_REQUEST,
			"ITSM snapshot contains duplicate id; record at index ${index} repeats index ${firstIndex}"),
	ITSM_UNSUPPORTED_SNAPSHOT_VALUE("ITSM_UNSUPPORTED_SNAPSHOT_VALUE", HttpStatus.BAD_REQUEST,
			"ITSM snapshot contains a string or number that cannot be stored"),
	ITSM_INVALID_HIERARCHY("ITSM_INVALID_HIERARCHY", HttpStatus.BAD_REQUEST,
			"ITSM snapshot incident hierarchy is invalid: ${reason}"),
	ITSM_INCIDENT_SOURCE_CONFLICT("ITSM_INCIDENT_SOURCE_CONFLICT", HttpStatus.CONFLICT,
			"ITSM snapshot contains an incident owned by another source"),
	ITSM_IDEMPOTENCY_CONFLICT("ITSM_IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT, "Idempotency-Key was already used with different request metadata or payload"),
	ITSM_SOURCE_VERSION_CONFLICT("ITSM_SOURCE_VERSION_CONFLICT", HttpStatus.CONFLICT, "Source version was already accepted with a different idempotency key"),
	ITSM_INTAKE_QUEUE_FULL("ITSM_INTAKE_QUEUE_FULL", HttpStatus.TOO_MANY_REQUESTS, "ITSM intake queue is full (${max} outstanding batches)"),
	ITSM_BATCH_NOT_FOUND("ITSM_BATCH_NOT_FOUND", HttpStatus.NOT_FOUND, "ITSM batch ${batchId} not found"),
	ITSM_BATCH_NOT_RETRYABLE("ITSM_BATCH_NOT_RETRYABLE", HttpStatus.CONFLICT,
			"Only an unscheduled technical ITSM failure can be retried"),
	ITSM_SOURCE_INITIALIZING("ITSM_SOURCE_INITIALIZING", HttpStatus.TOO_MANY_REQUESTS,
			"ITSM source is publishing its first snapshot; retry delivery"),
	ITSM_MACHINE_AUTH_REQUIRED("ITSM_MACHINE_AUTH_REQUIRED", HttpStatus.UNAUTHORIZED, "Machine bearer token is required"),
	ITSM_MACHINE_ACCESS_DENIED("ITSM_MACHINE_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Machine client identity or itsm import permission is missing"),
	HUMAN_AUTH_REQUIRED("HUMAN_AUTH_REQUIRED", HttpStatus.UNAUTHORIZED, "Human access token is required"),
	AUTH_REQUIRED("AUTH_REQUIRED", HttpStatus.UNAUTHORIZED, "Authentication is required"),
	INVALID_INCIDENT_PRESET("INVALID_INCIDENT_PRESET", HttpStatus.BAD_REQUEST, "Invalid incident preset"),
	INCIDENT_PRESET_NOT_FOUND("INCIDENT_PRESET_NOT_FOUND", HttpStatus.NOT_FOUND, "Incident preset was not found"),
	INCIDENT_PRESET_NAME_EXISTS("INCIDENT_PRESET_NAME_EXISTS", HttpStatus.CONFLICT,
			"An incident preset with this name already exists"),
	INVALID_GROUP("INVALID_GROUP", HttpStatus.BAD_REQUEST, "Unknown dashboard group ${group}"),
	INVALID_INCIDENT_FILTER("INVALID_INCIDENT_FILTER", HttpStatus.BAD_REQUEST,
			"Invalid incident filter ${field}: ${value}"),
	INVALID_CURSOR("INVALID_CURSOR", HttpStatus.BAD_REQUEST, "cursor is not a valid list cursor"),
	// Shared by the KAN-28 incident change feed (afterEventSeq) and the KAN-25 shift queue
	// (sinceEventSeq): the eventSeq cursor must be a non-negative 64-bit integer.
	INVALID_EVENT_SEQ("INVALID_EVENT_SEQ", HttpStatus.BAD_REQUEST,
			"afterEventSeq/sinceEventSeq must be a non-negative 64-bit integer"),
	INVALID_DEADLINE_WARNING_RULE("INVALID_DEADLINE_WARNING_RULE", HttpStatus.BAD_REQUEST,
			"thresholdSeconds must be a positive 32-bit integer"),
	INVALID_LIMIT("INVALID_LIMIT", HttpStatus.BAD_REQUEST, "limit must be between 1 and 100"),
	INVALID_EVENT_LIMIT("INVALID_EVENT_LIMIT", HttpStatus.BAD_REQUEST, "limit must be between 1 and 500"),
	ACK_EVENTS_INVALID("ACK_EVENTS_INVALID", HttpStatus.BAD_REQUEST,
			"eventIds must contain 1-1000 unique identifiers like evt-0001"),
	ACK_EVENT_UNKNOWN("ACK_EVENT_UNKNOWN", HttpStatus.BAD_REQUEST, "Unknown eventId ${eventId}"),
	ACK_EVENT_NOT_ALARM("ACK_EVENT_NOT_ALARM", HttpStatus.BAD_REQUEST,
			"Event ${eventId} does not require acknowledgement"),
	INVALID_INCIDENT_ID("INVALID_INCIDENT_ID", HttpStatus.BAD_REQUEST,
			"incidentId must be an INC identifier such as INC000021095531"),
	INCIDENT_NOT_FOUND("INCIDENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Incident${incident} not found"),
	POSTMORTEM_DRAFT_NOT_FOUND("POSTMORTEM_DRAFT_NOT_FOUND", HttpStatus.NOT_FOUND,
			"No postmortem draft for incident ${incidentId}"),
	INVALID_MEETING_LINK("INVALID_MEETING_LINK", HttpStatus.BAD_REQUEST,
			"meetingUrl must be an absolute HTTP or HTTPS URL without embedded credentials"),
	INVALID_INCIDENT_LINK("INVALID_INCIDENT_LINK", HttpStatus.BAD_REQUEST,
			"An incident link needs two different incidents and a supported kind"),
	INCIDENT_PATCH_INVALID("INCIDENT_PATCH_INVALID", HttpStatus.BAD_REQUEST, "Invalid incident update"),
	INCIDENT_CONFLICT("INCIDENT_CONFLICT", HttpStatus.CONFLICT, "Incident update conflicts with the current state"),
	NOTIFICATION_CONNECTION_NOT_FOUND("NOTIFICATION_CONNECTION_NOT_FOUND", HttpStatus.NOT_FOUND,
			"Notification connection not found"),
	INVALID_NOTIFICATION_CONNECTION("INVALID_NOTIFICATION_CONNECTION", HttpStatus.BAD_REQUEST,
			"Invalid notification connection settings"),
	NOTIFICATION_NOT_FOUND("NOTIFICATION_NOT_FOUND", HttpStatus.NOT_FOUND,
			"Notification not found"),
	NOTIFICATION_DELIVERY_NOT_FOUND("NOTIFICATION_DELIVERY_NOT_FOUND", HttpStatus.NOT_FOUND,
			"Notification delivery not found"),
	ITSM_BAD_RESPONSE("ITSM_BAD_RESPONSE", HttpStatus.BAD_GATEWAY, "Invalid response from ITSM"),
	ITSM_OFFLINE("ITSM_OFFLINE", HttpStatus.SERVICE_UNAVAILABLE, "ITSM is unavailable"),
	MCP_EVIDENCE_ACCESS_DENIED("MCP_EVIDENCE_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Caller is not allowed to read MCP evidence"),
	MCP_DASHBOARD_ACCESS_DENIED("MCP_DASHBOARD_ACCESS_DENIED", HttpStatus.FORBIDDEN,
			"Caller is not allowed to read the MCP dashboard"),
	MCP_EVIDENCE_DEMO_SCENARIO_UNKNOWN("MCP_EVIDENCE_DEMO_SCENARIO_UNKNOWN", HttpStatus.NOT_FOUND, "MCP evidence demo scenario ${scenarioId} is not a mapped demo scenario"),
	MCP_EVIDENCE_INCIDENT_NOT_MAPPED("MCP_EVIDENCE_INCIDENT_NOT_MAPPED", HttpStatus.NOT_FOUND, "Incident ${incidentId} has no explicit mapping to a scenario run"),
	MCP_EVIDENCE_SCENARIO_RUN_UNKNOWN("MCP_EVIDENCE_SCENARIO_RUN_UNKNOWN", HttpStatus.NOT_FOUND, "Scenario run is not a part of the requested demo mapping"),
	MCP_EVIDENCE_SERVICE_UNKNOWN("MCP_EVIDENCE_SERVICE_UNKNOWN", HttpStatus.NOT_FOUND, "serviceKey is not one of the services explicitly bound to the scenario run"),
	MCP_EVIDENCE_WINDOW_INVALID("MCP_EVIDENCE_WINDOW_INVALID", HttpStatus.BAD_REQUEST, "Evidence time window ${from} .. ${to} is invalid or exceeds the ${maxHours}-hour limit"),
	MCP_EVIDENCE_WINDOW_OUTSIDE_DEMO("MCP_EVIDENCE_WINDOW_OUTSIDE_DEMO", HttpStatus.BAD_REQUEST, "Evidence time window is outside the allowed demo observation window"),
	MCP_EVIDENCE_SOURCE_UNAVAILABLE("MCP_EVIDENCE_SOURCE_UNAVAILABLE", HttpStatus.BAD_GATEWAY, "Evidence source ${source} is unavailable: ${reason}"),
	MCP_EVIDENCE_SOURCE_REJECTED("MCP_EVIDENCE_SOURCE_REJECTED", HttpStatus.BAD_GATEWAY, "Evidence source ${source} rejected the fixed read request"),
	MCP_EVIDENCE_NOT_ATTRIBUTED("MCP_EVIDENCE_NOT_ATTRIBUTED", HttpStatus.BAD_GATEWAY,
			"Evidence source ${source} returned records that are not attributable to incident ${incidentId} of scenario run ${scenarioRunId}"),
	MCP_EVIDENCE_OUT_OF_WINDOW("MCP_EVIDENCE_OUT_OF_WINDOW", HttpStatus.BAD_GATEWAY,
			"Evidence source ${source} returned a record outside the requested evidence window");

	private final String code;
	private final HttpStatus httpStatus;
	private final String message;

	AppErrors(String code, HttpStatus httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	@Override
	public String getMessage() {
		return message;
	}
}
