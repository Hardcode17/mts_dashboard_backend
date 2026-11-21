package ru.hardcode.dashboard.contract;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Source of the documents published by the running application. The integration test serves
 * them through MockMvc; the command-line validator reads the files a test wrote.
 */
interface PublishedHost {

	/**
	 * @param name resource name inside the published area, such as {@code itsm-ingest.api-docs.json}
	 * @return the body, or {@code null} when the resource is not published
	 */
	byte[] read(String name);

	/**
	 * In-memory variant: the resources the application publishes, by their URL-relative name.
	 */
	static PublishedHost of(java.util.Map<String, byte[]> resources) {
		return resources::get;
	}

	/**
	 * Local variant: an explicit resource map. Used with the files written by
	 * {@link PublishedSpecGenerator} when the CLI has no running server.
	 */
	static PublishedHost files(java.nio.file.Path root) {
		return new PublishedHost() {

			@Override
			public byte[] read(String name) {
				java.nio.file.Path file = root.resolve(name);
				try {
					return java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.readAllBytes(file) : null;
				}
				catch (java.io.IOException exception) {
					return null;
				}
			}
		};
	}

	/**
	 * Reads {@code name} over HTTP, which keeps URL encoding of fixture names in play. Used by
	 * the command-line validator only.
	 */
	static PublishedHost http(URI baseUri) {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		return new PublishedHost() {

			@Override
			public byte[] read(String name) {
				try {
					HttpResponse<byte[]> response = client.send(
							HttpRequest.newBuilder(baseUri.resolve(name)).GET().timeout(Duration.ofSeconds(30)).build(),
							HttpResponse.BodyHandlers.ofByteArray());
					return response.statusCode() == 200 ? response.body() : null;
				}
				catch (Exception exception) {
					return null;
				}
			}

		};
	}
}
