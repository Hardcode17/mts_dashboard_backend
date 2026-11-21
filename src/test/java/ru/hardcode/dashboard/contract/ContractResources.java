package ru.hardcode.dashboard.contract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixture and published-resource paths that belong to one resource directory:
 * {@code openapi/} in the test class path, {@code openapi/published/} for derived documents.
 */
final class ContractResources {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final com.fasterxml.jackson.databind.ObjectMapper YAML_READER =
			new com.fasterxml.jackson.databind.ObjectMapper(new YAMLFactory());
	private static final JsonMapper JSON_WRITER = JsonMapper.builder().build();

	private ContractResources() {
	}

	static JsonNode readJson(InputStream input) throws IOException {
		return OBJECT_MAPPER.readTree(input);
	}

	static Path directory(String directory) {
		var resource = java.util.Objects.requireNonNull(
				ContractResources.class.getClassLoader().getResource("openapi/" + directory),
				"resource directory openapi/" + directory);
		try {
			return Path.of(resource.toURI());
		} catch (URISyntaxException exception) {
			throw new IllegalStateException("Invalid URI for OpenAPI resource directory " + directory, exception);
		}
	}

	static InputStream open(String resource) {
		return ContractResources.class.getClassLoader().getResourceAsStream(resource);
	}

	/**
	 * Reads a YAML document and converts it to the Jackson 3 tree used by the contract tools.
	 * Extension fields such as {@code x-fixture} are preserved.
	 */
	static JsonNode readYaml(Path path) {
		try {
			byte[] json = JSON_WRITER.writeValueAsBytes(YAML_READER.readTree(Files.readString(path, StandardCharsets.UTF_8)));
			return OBJECT_MAPPER.readTree(json);
		}
		catch (IOException exception) {
			throw new java.io.UncheckedIOException(exception);
		}
	}
}
