package ru.hardcode.dashboard.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Browser emulation for the human login of a real Keycloak: Authorization Code with PKCE (S256)
 * through the public {@code dashboard-web} client. It never sends a client secret and never
 * creates accounts; only users pre-created in the realm can log in.
 */
public final class KeycloakPkceClient {

	private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]+)\"");
	private static final String CLIENT_ID = KeycloakTestContainer.HUMAN_CLIENT_ID;
	private static final String REDIRECT_URI = "http://localhost:3000/callback";

	private final HttpClient noRedirects = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private final String realmUrl;
	private String loginCookies;

	public KeycloakPkceClient(String issuer) {
		this.realmUrl = issuer.replaceAll("/+$", "") + "/protocol/openid-connect";
	}

	/** Runs the whole Authorization Code + PKCE login and returns the access token. */
	public String login(String username, String password) {
		Authorization authorization = authorize();
		HttpResponse<String> response = submitLogin(authorization.loginAction(), username, password);
		String location = response.headers().firstValue("Location").orElseThrow(
				() -> new IllegalStateException("Login did not redirect: HTTP " + response.statusCode()));
		String code = queryParameter(location, "code");
		if (code == null) {
			throw new IllegalStateException("Login was rejected; redirect without a code");
		}
		if (!authorization.state().equals(queryParameter(location, "state"))) {
			throw new IllegalStateException("Authorization response state does not match");
		}
		return token(verifierExchange(code, authorization.verifier()));
	}

	/**
	 * Opens the authorization endpoint with PKCE. The response is the login page; its form is
	 * the action the browser would post the credentials to.
	 */
	public Authorization authorize() {
		String verifier = randomToken(48);
		String state = randomToken(16);
		HttpResponse<String> page = authorizationPage("auth", state, verifier);
		if (page.statusCode() != 200) {
			throw new IllegalStateException("Unexpected authorization page: HTTP " + page.statusCode());
		}
		Matcher action = FORM_ACTION.matcher(page.body());
		if (!action.find()) {
			throw new IllegalStateException("Authorization page has no login form");
		}
		// This fixture uses a local development HTTP endpoint. Forward the session cookie as
		// received without the legacy JDK cookie parser rewriting or dropping it. It is only
		// sent to the same Keycloak origin that served the login page.
		loginCookies = page.headers().allValues("Set-Cookie").stream()
				.map(cookie -> cookie.split(";", 2)[0]).collect(Collectors.joining("; "));
		if (loginCookies.isBlank()) {
			throw new IllegalStateException("Authorization page did not set a session cookie");
		}
		String loginAction = page.uri().resolve(action.group(1).replace("&amp;", "&")).toString();
		return new Authorization(loginAction, verifier, state);
	}

	public record Authorization(String loginAction, String verifier, String state) {
	}

	/** Posts the credentials like the login form; without redirects the outcome is a 302. */
	public HttpResponse<String> submitLogin(String loginAction, String username, String password) {
		URI url = URI.create(loginAction);
		URI origin = URI.create(realmUrl);
		if (!origin.getScheme().equals(url.getScheme()) || !origin.getRawAuthority().equals(url.getRawAuthority())) {
			throw new IllegalArgumentException("Login form must post to the test Keycloak origin");
		}
		return send(noRedirects,
				HttpRequest.newBuilder(url)
						.header("Cookie", loginCookies)
						.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(HttpRequest.BodyPublishers.ofString(encodedForm(Map.of(
								"username", username,
								"password", password,
								"credentialId", ""))))
						.build());
	}

	public HttpResponse<String> verifierExchange(String code, String verifier) {
		return send(noRedirects, HttpRequest.newBuilder(URI.create(realmUrl + "/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(encodedForm(Map.of(
						"grant_type", "authorization_code",
						"client_id", CLIENT_ID,
						"code", code,
						"redirect_uri", REDIRECT_URI,
						"code_verifier", verifier))))
				.build());
	}

	/** Exchange of an authorization code without the PKCE verifier, as an attacker with only the code would. */
	public HttpResponse<String> exchangeWithoutVerifier(String code) {
		return send(noRedirects, HttpRequest.newBuilder(URI.create(realmUrl + "/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(encodedForm(Map.of(
						"grant_type", "authorization_code",
						"client_id", CLIENT_ID,
						"code", code,
						"redirect_uri", REDIRECT_URI))))
				.build());
	}

	/** Direct grants must stay disabled on the public client. */
	public HttpResponse<String> passwordGrant(String username, String password) {
		return send(noRedirects, HttpRequest.newBuilder(URI.create(realmUrl + "/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(encodedForm(Map.of(
						"grant_type", "password",
						"client_id", CLIENT_ID,
						"username", username,
						"password", password))))
				.build());
	}

	public HttpResponse<String> clientCredentials(String clientId, String clientSecret) {
		return send(noRedirects, HttpRequest.newBuilder(URI.create(realmUrl + "/token"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(encodedForm(Map.of(
						"grant_type", "client_credentials",
						"client_id", clientId,
						"client_secret", clientSecret))))
				.build());
	}

	/** An otherwise valid OIDC registration request is refused by the realm's configuration. */
	public HttpResponse<String> registrationPage() {
		return authorizationPage("registrations", randomToken(16), randomToken(48));
	}

	private HttpResponse<String> authorizationPage(String endpoint, String state, String verifier) {
		String url = realmUrl + "/" + endpoint + "?response_type=code"
				+ "&client_id=" + CLIENT_ID
				+ "&redirect_uri=" + encoded(REDIRECT_URI)
				+ "&scope=" + encoded("openid profile email")
				+ "&state=" + encoded(state)
				+ "&code_challenge=" + pkceChallenge(verifier)
				+ "&code_challenge_method=S256";
		return send(noRedirects, HttpRequest.newBuilder(URI.create(url)).GET().build());
	}

	public static String accessToken(HttpResponse<String> tokenResponse) {
		if (tokenResponse.statusCode() != 200) {
			throw new IllegalStateException("Keycloak did not issue a token: HTTP " + tokenResponse.statusCode());
		}
		return new ObjectMapper().readTree(tokenResponse.body()).get("access_token").asString();
	}

	public static JsonNode errorBody(HttpResponse<String> response) {
		return new ObjectMapper().readTree(response.body());
	}

	private static String token(HttpResponse<String> tokenResponse) {
		return accessToken(tokenResponse);
	}

	private HttpResponse<String> send(HttpClient client, HttpRequest request) {
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException exception) {
			throw new IllegalStateException("Keycloak is not reachable", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while calling Keycloak", exception);
		}
	}

	private static String encodedForm(Map<String, String> form) {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> entry : form.entrySet()) {
			if (!body.isEmpty()) {
				body.append('&');
			}
			body.append(encoded(entry.getKey())).append('=').append(encoded(entry.getValue()));
		}
		return body.toString();
	}

	private static String encoded(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String queryParameter(String url, String name) {
		String query = URI.create(url.isEmpty() ? "http://invalid" : url).getRawQuery();
		if (query == null) {
			return null;
		}
		Map<String, String> parameters = new LinkedHashMap<>();
		for (String pair : query.split("&")) {
			String[] parts = pair.split("=", 2);
			parameters.put(parts[0], parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
		}
		return parameters.get(name);
	}

	public static String randomToken(int bytes) {
		byte[] random = new byte[bytes];
		new SecureRandom().nextBytes(random);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
	}

	public static String pkceChallenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
