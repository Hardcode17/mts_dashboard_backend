import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders the Keycloak realm fixture for `--profile auth`: replaces the __NAME__ markers with
 * values from the environment. Keycloak imports a realm as-is and substitutes nothing inside it
 * (its own ${VAR} substitution cannot express the nested default of KEYCLOAK_PUBLIC_URL
 * either), so the file is rendered once, before the container starts. Run through
 * scripts/render-auth-config.sh, which validates the environment first.
 *
 * <p>Arguments: {@code <source> <target>}.
 */
public final class RenderRealm {

	// Fixture marker (imported realms are not templates) -> environment variable.
	private static final String[][] PLACEHOLDERS = {
			{"__DASHBOARD_TEST_USERNAME__", "DASHBOARD_TEST_USERNAME"},
			{"__DASHBOARD_TEST_EMAIL__", "DASHBOARD_TEST_EMAIL"},
			{"__DASHBOARD_TEST_PASSWORD__", "DASHBOARD_TEST_PASSWORD"},
			{"__ITSM_SENDER_CLIENT_SECRET__", "ITSM_SENDER_CLIENT_SECRET"}
	};

	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			fail("usage: RenderRealm <source> <target>");
		}
		String realm = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
		for (String[] placeholder : PLACEHOLDERS) {
			String value = System.getenv(placeholder[1]);
			if (value == null || value.isBlank()) {
				fail("missing environment variable " + placeholder[1]);
			}
			realm = realm.replace(placeholder[0], escape(value));
		}
		if (realm.contains("${") || realm.contains("__")) {
			fail("the realm still contains placeholders; Keycloak would not substitute them");
		}

		Files.writeString(Path.of(args[1]), realm, StandardCharsets.UTF_8);
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (ch == '"' || ch == '\\') {
				escaped.append('\\').append(ch);
			} else if (ch < 0x20) {
				escaped.append(String.format("\\u%04x", (int) ch));
			} else {
				escaped.append(ch);
			}
		}
		return escaped.toString();
	}

	private static void fail(String message) {
		System.err.println("RenderRealm: " + message);
		System.exit(1);
	}

	private RenderRealm() {
	}
}
