package ru.hardcode.dashboard.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Administrator-side Keycloak REST calls for integration tests: configure the imported fixture
 * and pre-create accounts, as an administrator would in a realm. The application itself never
 * uses this client.
 */
public final class KeycloakAdminTestClient {

	private static final String MASTER_REALM = "master";
	private static final String ADMIN_CLI = "admin-cli";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpClient http = HttpClient.newHttpClient();
	private final String baseUrl;
	private final String adminUsername;
	private final String adminPassword;

	public KeycloakAdminTestClient(String issuer, String adminUsername, String adminPassword) {
		this.baseUrl = issuer.replaceAll("/realms/" + KeycloakTestContainer.REALM + "/?$", "");
		this.adminUsername = adminUsername;
		this.adminPassword = adminPassword;
	}

	public void resetPassword(String username, String password) {
		JsonNode user = findUser(username);
		send(HttpRequest.newBuilder(URI.create(userUri(user)))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(
						json(fields("type", "password", "value", password, "temporary", false)))), 204);
	}

	public void resetClientSecret(String clientId, String secret) {
		JsonNode client = findClient(clientId);
		send(HttpRequest.newBuilder(URI.create(clientUri(client)))
				.header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(json(fields("clientId", clientId, "secret", secret)))), 204);
	}

	public boolean userExists(String username) {
		return users("/users?exact=true&username=" + encode(username)).size() > 0;
	}

	public List<String> userGroups(String username) {
		JsonNode groups = send(HttpRequest.newBuilder(URI.create(userUri(findUser(username)) + "/groups")).GET(), 200);
		return groups.properties().stream().map(entry -> entry.getValue().get("path").asString()).toList();
	}

	/** Creates an enabled user without e-mail verification, as an administrator would. */
	public void createUser(String username, String email, String password, List<String> groupPaths) {
		String search = "/users?exact=true&username=" + encode(username);
		users(search).forEach(user -> deleteUser(user.get("id").asString()));
		Map<String, Object> user = fields("username", username, "email", email, "enabled", true,
				"emailVerified", true, "firstName", "Local", "lastName", "Test");
		user.put("groups", groupPaths);
		send(HttpRequest.newBuilder(URI.create(realmUri("/users"))).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(user))), 201);
		resetPassword(username, password);
	}

	private void deleteUser(String id) {
		send(HttpRequest.newBuilder(URI.create(realmUri("/users/" + id))).DELETE(), 204);
	}

	private JsonNode findUser(String username) {
		List<JsonNode> users = users("/users?exact=true&username=" + encode(username));
		if (users.size() != 1) {
			throw new IllegalStateException("Expected exactly one user " + username + " but found " + users.size());
		}
		return users.getFirst();
	}

	private JsonNode findClient(String clientId) {
		JsonNode clients = send(HttpRequest.newBuilder(URI.create(realmUri("/clients?clientId=" + encode(clientId)))).GET(), 200);
		if (clients.size() != 1) {
			throw new IllegalStateException("Expected exactly one client " + clientId + " but found " + clients.size());
		}
		return clients.get(0);
	}

	private List<JsonNode> users(String pathAndQuery) {
		JsonNode users = send(HttpRequest.newBuilder(URI.create(realmUri(pathAndQuery))).GET(), 200);
		return users.valueStream().toList();
	}

	private String userUri(JsonNode user) {
		return realmUri("/users/" + user.get("id").asString());
	}

	private String clientUri(JsonNode client) {
		return realmUri("/clients/" + client.get("id").asString());
	}

	private String realmUri(String path) {
		return baseUrl + "/admin/realms/" + KeycloakTestContainer.REALM + path;
	}

	private JsonNode send(HttpRequest.Builder request, int expectedStatus) {
		HttpResponse<String> response;
		try {
			response = http.send(request.header("Authorization", "Bearer " + adminToken()).build(),
					HttpResponse.BodyHandlers.ofString());
		} catch (Exception exception) {
			throw new IllegalStateException("Keycloak Admin API call failed", exception);
		}
		if (response.statusCode() != expectedStatus) {
			throw new IllegalStateException("Keycloak Admin API returned " + response.statusCode()
					+ " instead of " + expectedStatus + ": " + response.body());
		}
		return response.body() == null || response.body().isBlank()
				? MAPPER.createArrayNode()
				: MAPPER.readTree(response.body());
	}

	private String adminToken() {
		Map<String, String> form = Map.of("grant_type", "password", "client_id", ADMIN_CLI,
				"username", adminUsername, "password", adminPassword);
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/realms/" + MASTER_REALM
						+ "/protocol/openid-connect/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form(form)))
				.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IllegalStateException("Keycloak admin login failed: HTTP " + response.statusCode());
			}
			return MAPPER.readTree(response.body()).get("access_token").asString();
		} catch (Exception exception) {
			throw new IllegalStateException("Keycloak admin login failed", exception);
		}
	}

	private static String json(Map<?, ?> value) {
		return MAPPER.writeValueAsString(value);
	}

	private static Map<String, Object> fields(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static String form(Map<String, String> form) {
		return form.entrySet().stream()
				.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
				.collect(Collectors.joining("&"));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
