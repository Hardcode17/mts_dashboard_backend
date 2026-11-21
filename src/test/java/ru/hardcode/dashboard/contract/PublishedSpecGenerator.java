package ru.hardcode.dashboard.contract;

import java.nio.file.Path;

/**
 * Writes the published view of the canonical documents to {@code build/openapi/published/} as
 * class-path resources, so {@link OpenApiContractValidatorMain} can validate the published
 * documents without Docker:
 * <pre>
 * java -cp &lt;test runtime classpath&gt; ru.hardcode.dashboard.contract.PublishedSpecGenerator docs build/test-resources
 * java -cp &lt;test runtime classpath&gt;:-Dkan20.published-root=build/test-resources/openapi/published \
 *     ru.hardcode.dashboard.contract.PublishedSpecValidator docs
 * </pre>
 */
public final class PublishedSpecGenerator {

	private PublishedSpecGenerator() {
	}

	public static void main(String[] args) {
		Path docs = Path.of(args.length > 0 ? args[0] : "docs");
		Path target = Path.of(args.length > 1 ? args[1] : "build/test-resources");
		PublishedSpecService.write(docs, target.resolve("openapi/published"));
		System.out.println("Published view written to " + target.resolve("openapi/published"));
	}
}
