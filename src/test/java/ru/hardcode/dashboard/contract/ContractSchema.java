package ru.hardcode.dashboard.contract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tools.jackson.databind.JsonNode;

/**
 * JSON Schema 2020-12 validation for the KAN-20 contracts, limited to the keywords they use:
 * {@code $ref}, {@code type}, {@code enum}, {@code const}, {@code required}, {@code properties},
 * {@code additionalProperties}, {@code items}, {@code anyOf}, {@code minLength},
 * {@code maxLength}, {@code pattern}, {@code minItems}, {@code maxItems}, {@code uniqueItems},
 * {@code minimum}, {@code maximum}, {@code minProperties} and {@code format}.
 *
 * <p>Every error reports an absolute JSON-pointer location of the instance and the location of
 * the schema inside the canonical document, which no generic validator of the test class path
 * offered reliably. Differences from full JSON Schema 2020-12 (only inside KAN-20 contracts):
 * {@code and}/{@code or}/{@code not} interpretation in ordinary keywords follows the type of the
 * instance, and {@code format} only asserts {@code date-time} and {@code uri}.
 */
final class ContractSchema {

	static final String DOCUMENT = "https://contract.kan20.test/document";

	private final JsonNode root;
	private final List<String> errors = new ArrayList<>();

	private ContractSchema(JsonNode root) {
		this.root = root;
	}

	/**
	 * @param document whole canonical OpenAPI document; {@code $ref} values resolve against it
	 * @param schema the schema to validate against, located by {@code schemaPointer}
	 * @param schemaPointer JSON pointer of the schema inside the document (for messages)
	 */
	static List<String> errors(JsonNode document, JsonNode schema, String schemaPointer, JsonNode instance) {
		ContractSchema validator = new ContractSchema(document);
		validator.evaluate(schema, schemaPointer, instance, "");
		return validator.errors;
	}

	/**
	 * @throws IllegalStateException at the first violation
	 */
	static void assertValid(JsonNode document, JsonNode schema, String schemaPointer, JsonNode instance,
			String location) {
		List<String> errors = errors(document, schema, schemaPointer, instance);
		if (!errors.isEmpty()) {
			throw new IllegalStateException(location + " does not match its schema: " + errors.get(0));
		}
	}

	private void evaluate(JsonNode schema, String schemaPointer, JsonNode instance, String instancePointer) {
		if (!schema.isObject()) {
			return;
		}
		if (schema.has("$ref")) {
			String ref = schema.get("$ref").stringValue();
			JsonNode target = root.at(ref.substring(1));
			evaluate(target, ref.substring(1), instance, instancePointer);
		}
		if (schema.has("anyOf")) {
			boolean matched = false;
			List<String> firstFailure = List.of();
			for (JsonNode option : schema.get("anyOf")) {
				ContractSchema candidate = new ContractSchema(root);
				candidate.evaluate(option, schemaPointer, instance, instancePointer);
				if (candidate.errors.isEmpty()) {
					matched = true;
					break;
				}
				if (firstFailure.isEmpty()) {
					firstFailure = candidate.errors;
				}
			}
			if (!matched) {
				errors.add(instancePointer + ": matches no allowed variant (anyOf); first: " + firstFailure.get(0));
			}
			return;
		}
		if (schema.has("const") && !schema.get("const").equals(instance)) {
			error(instancePointer, schemaPointer, "must equal " + schema.get("const"));
		}
		if (schema.has("enum") && !contains(schema.get("enum"), instance)) {
			error(instancePointer, schemaPointer, "must be one of " + schema.get("enum"));
		}
		if (schema.has("type") && !hasType(schema.get("type"), instance)) {
			error(instancePointer, schemaPointer, "must be " + schema.get("type") + ", found " + typeName(instance));
			return;
		}
		if (schema.has("pattern") && instance.isString()
				&& !pattern(schema.get("pattern").stringValue()).matcher(instance.stringValue()).find()) {
			error(instancePointer, schemaPointer, "must match " + schema.get("pattern"));
		}
		if (schema.has("minLength") && instance.isString()
				&& instance.stringValue().length() < schema.get("minLength").intValue()) {
			error(instancePointer, schemaPointer, "must have at least " + schema.get("minLength") + " characters");
		}
		if (schema.has("maxLength") && instance.isString()
				&& instance.stringValue().length() > schema.get("maxLength").intValue()) {
			error(instancePointer, schemaPointer, "must have at most " + schema.get("maxLength") + " characters");
		}
		if (schema.has("format") && instance.isString() && !hasFormat(schema.get("format").stringValue(), instance)) {
			error(instancePointer, schemaPointer, "must be a valid " + schema.get("format"));
		}
		if (schema.has("minimum") && instance.isNumber()
				&& new BigDecimal(instance.decimalValue().toString())
						.compareTo(new BigDecimal(schema.get("minimum").toString())) < 0) {
			error(instancePointer, schemaPointer, "must be >= " + schema.get("minimum"));
		}
		if (schema.has("maximum") && instance.isNumber()
				&& new BigDecimal(instance.decimalValue().toString())
						.compareTo(new BigDecimal(schema.get("maximum").toString())) > 0) {
			error(instancePointer, schemaPointer, "must be <= " + schema.get("maximum"));
		}
		if (schema.has("required") && instance.isObject()) {
			for (JsonNode required : schema.get("required")) {
				if (!instance.has(required.stringValue())) {
					error(instancePointer, schemaPointer, "must have required property " + required.stringValue());
				}
			}
		}
		if (instance.isObject()) {
			JsonNode properties = schema.path("properties");
			Set<String> known = new LinkedHashSet<>();
			properties.properties().forEach(property -> known.add(property.getKey()));
			known.forEach(name -> {
				if (instance.has(name) && properties.has(name)) {
					evaluate(properties.get(name), schemaPointer + "/properties/" + name, instance.get(name),
							instancePointer + "/" + pointer(name));
				}
			});
			JsonNode additional = schema.path("additionalProperties");
			if (additional.isBoolean() && !additional.booleanValue()) {
				instance.propertyNames().stream().filter(name -> !known.contains(name))
						.forEach(name -> error(instancePointer + "/" + pointer(name), schemaPointer,
								"is not defined and additional properties are not allowed"));
			}
			else if (additional.isObject()) {
				instance.propertyNames().stream().filter(name -> !known.contains(name))
						.forEach(name -> evaluate(additional, schemaPointer + "/additionalProperties",
								instance.get(name), instancePointer + "/" + pointer(name)));
			}
			if (schema.has("minProperties") && instance.size() < schema.get("minProperties").intValue()) {
				error(instancePointer, schemaPointer, "must have at least " + schema.get("minProperties") + " properties");
			}
		}
		if (instance.isArray()) {
			if (schema.has("minItems") && instance.size() < schema.get("minItems").intValue()) {
				error(instancePointer, schemaPointer, "must have at least " + schema.get("minItems") + " items");
			}
			if (schema.has("maxItems") && instance.size() > schema.get("maxItems").intValue()) {
				error(instancePointer, schemaPointer, "must have at most " + schema.get("maxItems") + " items");
			}
			if (schema.path("uniqueItems").booleanValue(false)) {
				Set<JsonNode> seen = new LinkedHashSet<>();
				for (JsonNode item : instance) {
					if (!seen.add(item)) {
						error(instancePointer, schemaPointer, "must not contain duplicate items");
					}
				}
			}
			JsonNode items = schema.path("items");
			if (items.isObject()) {
				for (int index = 0; index < instance.size(); index++) {
					evaluate(items, schemaPointer + "/items", instance.get(index), instancePointer + "/" + index);
				}
			}
		}
	}

	private void error(String instancePointer, String schemaPointer, String message) {
		errors.add((instancePointer.isEmpty() ? "/" : instancePointer) + ": " + message + " (" + schemaPointer + ")");
	}

	private static boolean hasType(JsonNode type, JsonNode instance) {
		if (type.isString()) {
			return hasTypeName(type.stringValue(), instance);
		}
		for (JsonNode candidate : type) {
			if (hasTypeName(candidate.stringValue(), instance)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasTypeName(String type, JsonNode instance) {
		return switch (type) {
			case "null" -> instance.isNull();
			case "boolean" -> instance.isBoolean();
			case "object" -> instance.isObject();
			case "array" -> instance.isArray();
			case "string" -> instance.isString();
			case "integer" -> instance.isIntegralNumber();
			case "number" -> instance.isNumber();
			default -> true;
		};
	}

	private static boolean contains(JsonNode allowed, JsonNode instance) {
		for (JsonNode candidate : allowed) {
			if (candidate.equals(instance)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasFormat(String format, JsonNode instance) {
		String value = instance.stringValue();
		return switch (format) {
			case "date-time" -> ContractSchema.isDateTime(value);
			case "uri" -> value.contains(":") && !value.contains(" ");
			default -> true;
		};
	}

	/**
	 * RFC 3339 date-time: a calendar date, a time and an explicit UTC offset.
	 */
	static boolean isDateTime(String value) {
		try {
			java.time.OffsetDateTime.parse(value);
			return true;
		}
		catch (java.time.format.DateTimeParseException exception) {
			return false;
		}
	}

	private static String typeName(JsonNode instance) {
		if (instance.isNull()) {
			return "null";
		}
		if (instance.isBoolean()) {
			return "boolean";
		}
		if (instance.isObject()) {
			return "object";
		}
		if (instance.isArray()) {
			return "array";
		}
		if (instance.isIntegralNumber()) {
			return "integer";
		}
		if (instance.isNumber()) {
			return "number";
		}
		return "string";
	}

	private static Pattern pattern(String regex) {
		try {
			return Pattern.compile(regex);
		}
		catch (PatternSyntaxException exception) {
			throw new IllegalStateException("invalid schema pattern " + regex, exception);
		}
	}

	private static String pointer(String name) {
		return name.replace("~", "~0").replace("/", "~1");
	}
}
