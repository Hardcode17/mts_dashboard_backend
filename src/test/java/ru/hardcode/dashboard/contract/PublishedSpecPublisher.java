package ru.hardcode.dashboard.contract;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the runtime view of a canonical contract in the form published by
 * {@code springdoc-openapi-starter-webmvc-api} (three documents in its META-INF: the API
 * document, an {@code ApiExample} resource per example and an {@code ApiSchema} resource per
 * response):
 * <ul>
 *     <li>every example with a payload fixture becomes a frame document named
 *     {@code id/fixture}: {@code $frame} = {@code id/fixture}, {@code $schema} =
 *     {@code /schema/fixture.json};</li>
 *     <li>the frames index lists them, which is what a published deployment is checked
 *     against;</li>
 *     <li>every response schema is published as a JSON Schema resource.</li>
 * </ul>
 * The contract stays the only source of values: the publisher only splits it.
 */
final class PublishedSpecPublisher {

	/** Published resource names, as the application serves them. */
	static final String API_DOCS = "/api-docs";
	static final String DASHBOARD_API_DOCS = "/api-docs/dashboard";
	static final String YAML_DOCUMENT = "/api-docs.yaml";
	static final String FRAME_RESOURCE = "/api-docs/frame/{document}/{frame}";
	static final String SCHEMA_RESOURCE = "/api-docs/schema/{schema}";
	static final String INGESTION_SPECIFICATION = "/openapi/itsm-ingest.openapi.yaml";
	static final String DASHBOARD_SPECIFICATION = "/openapi/dashboard-api.openapi.yaml";
	static final String VALIDATION = "/openapi/validate";
	static final String RUNTIME = "itsm-ingest.api-docs.json";
	static final String DASHBOARD_RUNTIME = "dashboard-api.api-docs.json";
	static final String YAML = "itsm-ingest.api-docs.yaml.json";
	static final String SPECIFICATION = "openapi.itsm-ingest.openapi.yaml";
	static final String DASHBOARD_SPECIFICATION_POINTER = "openapi.dashboard-api.openapi.yaml";
	static final String FRAME_INDEX = "frames-index";
	static final String SCHEMA_INDEX = "schemas-index";

	/** URL prefix of published frames, inside a running application. */
	static final String FRAMES = "/api-docs/frame/";
	/** URL prefix of published schema resources, inside a running application. */
	static final String SCHEMAS = "/api-docs/schema/";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Set<String> METHODS =
			Set.of("get", "post", "put", "patch", "delete", "options", "head", "trace");

	/** Indices cannot be built in the constructor; they are filled after collection. */
	private final java.util.List<String> frameNames = new java.util.ArrayList<>();
	private final Map<String, ObjectNode> frames = new LinkedHashMap<>();
	private final Map<String, ObjectNode> schemas = new LinkedHashMap<>();

	/**
	 * @param id document id, which prefixes every frame name
	 * @param contract canonical OpenAPI document
	 * @param operationPath {@code paths} or {@code webhooks}
	 */
	static PublishedSpecPublisher of(String id, JsonNode contract, String operationPath) {
		PublishedSpecPublisher publisher = new PublishedSpecPublisher();
		for (var path : contract.path(operationPath).properties()) {
			for (var entry : path.getValue().properties()) {
				if (!METHODS.contains(entry.getKey())) {
					continue;
				}
				String operation = normalized(entry.getKey() + "_" + path.getKey());
				JsonNode op = entry.getValue();
				publisher.split(id, op.path("requestBody").path("content"));
				for (var response : op.path("responses").properties()) {
					JsonNode content = response.getValue().path("content");
					publisher.split(id, content);
					content.properties().forEach(media -> {
						JsonNode schema = media.getValue().path("schema");
						if (schema.isObject() && !schema.isEmpty()) {
							ObjectNode resource = publisher.schemas.computeIfAbsent(id, key -> {
								ObjectNode document = OBJECT_MAPPER.createObjectNode();
								document.put("openapi", "3.1.0");
								return document;
							});
							resource.putObject("components").putObject("schemas")
									.set(operation + "_" + response.getKey() + "_"
											+ normalized(media.getKey()), schema);
						}
					});
				}
			}
		}
		return publisher;
	}

	private void split(String id, JsonNode content) {
		content.properties().forEach(media -> media.getValue().path("examples").properties().forEach(example -> {
			String fixture = example.getValue().path(OpenApiContractValidator.FIXTURE).stringValue("");
			if (!OpenApiContractValidator.FIXTURE_NAME.matcher(fixture).matches()) {
				// Inline examples (such as the empty snapshot) are published in place.
				return;
			}
			ObjectNode frame = OBJECT_MAPPER.createObjectNode();
			frame.put(OpenApiContractValidator.FRAME_POINTER, id + "/" + fixture);
			frame.put("$schema", SCHEMAS + fixture + ".json");
			frames.put(id + "/" + fixture, frame);
			frameNames.add(id + "/" + fixture);
		}));
	}

	Map<String, ObjectNode> frames() {
		return frames;
	}

	Map<String, ObjectNode> schemas() {
		return schemas;
	}

	ObjectNode frameIndex() {
		return index("Frames", "Example payloads referenced by the " + OpenApiContractValidator.FRAME_POINTER
				+ " property", FRAMES, frameNames);
	}

	ObjectNode schemaIndex() {
		return index("Schemas", "JSON Schemas referenced by the $schema property", SCHEMAS, schemas.keySet());
	}

	private static String normalized(String value) {
		return value.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "").toUpperCase(Locale.ROOT);
	}

	private static ObjectNode index(String title, String description, String base, java.util.Collection<String> names) {
		ObjectNode index = OBJECT_MAPPER.createObjectNode();
		index.put("openapi", "3.1.0");
		ObjectNode info = index.putObject("info");
		info.put("title", title);
		info.put("version", "1.0.0");
		info.put("description", description);
		index.putArray("servers").addObject().put("url", base).put("description", title);
		ObjectNode linkset = index.putArray("linksets").addObject();
		linkset.put("anchor", base);
		ArrayNode items = linkset.putArray("item");
		for (String name : names) {
			// An empty published area has no documents and therefore no index files.
			if (!name.isEmpty()) {
				items.addObject().put("href", name + ".json").put("name", name);
			}
		}
		return index;
	}
}
