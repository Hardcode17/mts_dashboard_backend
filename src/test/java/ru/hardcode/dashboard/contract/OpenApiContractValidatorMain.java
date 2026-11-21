package ru.hardcode.dashboard.contract;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line contract check without Docker:
 * {@code java -cp <test runtime classpath> ru.hardcode.dashboard.contract.OpenApiContractValidatorMain docs [published-base-uri]}.
 *
 * <p>Without a published source only the canonical documents and fixtures are checked. With one
 * (an HTTP base URI of a running application or mock server, a local directory written by
 * {@link PublishedSpecGenerator}, or {@code -Dkan20.published-root}) the published documents are
 * checked as well, exactly as the integration test checks the real responses.
 */
public final class OpenApiContractValidatorMain {

	private OpenApiContractValidatorMain() {
	}

	public static void main(String[] args) {
		Path docs = Path.of(args.length > 0 ? args[0] : "docs");
		PublishedHost published = publishedHost(args);
		List<String> violations = OpenApiContractValidator.check(docs, published);
		violations.forEach(violation -> System.out.println("VIOLATION " + violation));
		System.out.println(violations.isEmpty()
				? "OK: KAN-20 contract, fixtures and published documents are valid"
				: "FAILED: " + violations.size() + " violation(s)");
		System.exit(violations.isEmpty() ? 0 : 1);
	}

	/**
	 * Second argument: an HTTP base URI of published documents, or (when
	 * {@code kan20.published-root} is not set) a local directory written by
	 * {@link PublishedSpecGenerator}.
	 */
	private static PublishedHost publishedHost(String[] args) {
		String root = System.getProperty("kan20.published-root", "");
		if (!root.isBlank()) {
			return PublishedHost.files(Path.of(root));
		}
		if (args.length < 2 || args[1].isBlank()) {
			return null;
		}
		if (args[1].startsWith("http")) {
			return PublishedHost.http(URI.create(args[1]));
		}
		return PublishedHost.files(Path.of(args[1]));
	}
}
