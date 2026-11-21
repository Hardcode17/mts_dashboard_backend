package ru.hardcode.dashboard.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes the canonical KAN-20 documents in the runtime format described by
 * {@link PublishedSpecPublisher}.
 *
 * <p>Two consumers share it: the {@code OpenApiContractIT} serves these bytes through the real
 * application chain (MockMvc), and a small command-line generator writes them as class-path
 * resources for {@code OpenApiContractValidatorMain}, which then reads them back over HTTP, as
 * it does for a running deployment. The published files are derived; they are never edited by
 * hand.
 */
final class PublishedSpecService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final Map<String, byte[]> resources = new LinkedHashMap<>();

	private PublishedSpecService() {
	}

	/**
	 * @param docs directory with the canonical documents
	 */
	static PublishedSpecService of(Path docs) {
		PublishedSpecService service = new PublishedSpecService();
		for (String id : OpenApiContractValidator.DOCUMENT_NAMES) {
			service.publish(id, OpenApiContractValidator.readCanonical(docs, id), docs);
		}
		return service;
	}

	/**
	 * Writes every published resource to {@code target}.
	 */
	static void write(Path docs, Path target) {
		of(docs).resources.forEach((name, body) -> {
			try {
				Path file = target.resolve(name);
				Files.createDirectories(file.getParent());
				Files.write(file, body);
			}
			catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		});
	}

	/**
	 * @return published resource name (below {@code openapi/published/}) to its bytes
	 */
	Map<String, byte[]> resources() {
		return resources;
	}

	byte[] resource(String name) {
		return resources.get(name);
	}

	private void publish(String id, JsonNode contract, Path docs) {
		String operationPath = contract.has("webhooks") ? "webhooks" : "paths";
		PublishedSpecPublisher publisher = PublishedSpecPublisher.of(id, contract, operationPath);
		put(id + ".api-docs.json", runtimeDocument(id, contract, operationPath));
		// The YAML document of the application keeps example values inline, as the contract does.
		put(id + ".api-docs.yaml.json", inlineDocument(contract, operationPath));
		put(PublishedSpecPublisher.FRAME_INDEX + ".json", publisher.frameIndex());
		put(PublishedSpecPublisher.SCHEMA_INDEX + ".json", publisher.schemaIndex());
		// Frame names contain the document id as a directory, as their hrefs do.
		// Frame documents live below FRAMES as name.json, like their hrefs.
		publisher.frames().forEach((name, frame) -> put(name.endsWith(".json") ? name : name + ".json", frame));
		publisher.schemas().forEach((name, schema) -> put(name + ".json", schema));
		// The canonical text of each document, as a deployment serves it from /openapi.
		try {
			resources.put("openapi." + id + ".openapi.yaml", Files.readAllBytes(docs.resolve(id + ".openapi.yaml")));
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	/**
	 * The runtime document keeps the contract's operations but publishes every example value as
	 * its own frame: {@code $frame} names the payload fixture, {@code $schema} the schema resource
	 * served next to the frame (this is how springdoc splits its three-document META-INF).
	 */
	/**
	 * The published YAML: the contract's operations with inline example values.
	 */
	private static ObjectNode inlineDocument(JsonNode contract, String operationPath) {
		ObjectNode document = OBJECT_MAPPER.createObjectNode();
		document.put("openapi", "3.1.0");
		document.set("info", contract.path("info"));
		document.putArray("servers").addObject().put("url", "/").put("description", "Dashboard");
		// Own copies: the runtime document rewrites examples in place.
		document.set(operationPath, contract.path(operationPath).deepCopy());
		document.set("components", contract.path("components").deepCopy());
		return document;
	}

	/**
	 * The runtime document publishes every example value as a frame pointer and keeps inline
	 * examples (those without a fixture) as they are.
	 */
	private static JsonNode runtimeDocument(String id, JsonNode contract, String operationPath) {
		ObjectNode document = inlineDocument(contract, operationPath);
		ObjectNode operations = (ObjectNode) document.get(operationPath);
		for (var path : contract.path(operationPath).properties()) {
			ObjectNode publishedPath = (ObjectNode) operations.get(path.getKey());
			for (var entry : path.getValue().properties()) {
				if (!entry.getValue().isObject()) {
					continue;
				}
				ObjectNode operation = (ObjectNode) publishedPath.get(entry.getKey()).deepCopy();
				JsonNode requestBody = operation.get("requestBody");
				if (requestBody != null && requestBody.has("content")) {
					((ObjectNode) requestBody).set("content", frames(id, requestBody.get("content")));
				}
				JsonNode responses = operation.get("responses");
				if (responses != null) {
					for (var response : responses.properties()) {
						JsonNode content = response.getValue().get("content");
						if (content != null) {
							((ObjectNode) response.getValue()).set("content", frames(id, content));
						}
					}
				}
				publishedPath.set(entry.getKey(), operation);
			}
		}
		return document;
	}

	private static ObjectNode frames(String id, JsonNode content) {
		ObjectNode published = OBJECT_MAPPER.createObjectNode();
		for (var media : content.properties()) {
			ObjectNode publishedMedia = published.putObject(media.getKey());
			if (media.getValue().has("schema")) {
				publishedMedia.set("schema", media.getValue().get("schema"));
			}
			ObjectNode examples = publishedMedia.putObject("examples");
			for (var example : media.getValue().path("examples").properties()) {
				String fixture = example.getValue().path(OpenApiContractValidator.FIXTURE).stringValue("");
				if (fixture.isEmpty()) {
					// Inline examples are published with their value.
					examples.set(example.getKey(), example.getValue());
					continue;
				}
				ObjectNode publishedExample = examples.putObject(example.getKey());
				if (example.getValue().has("summary")) {
					publishedExample.set("summary", example.getValue().get("summary"));
				}
				ObjectNode value = publishedExample.putObject("value");
				value.put(OpenApiContractValidator.FRAME_POINTER, id + "/" + fixture);
				value.put("$schema", PublishedSpecPublisher.SCHEMAS + fixture + ".json");
			}
		}
		return published;
	}

	private void put(String name, JsonNode json) {
		resources.put(name, json.toString().getBytes(StandardCharsets.UTF_8));
	}
}
