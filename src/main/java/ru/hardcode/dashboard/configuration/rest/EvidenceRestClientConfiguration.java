package ru.hardcode.dashboard.configuration.rest;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.hardcode.dashboard.configuration.evidence.ConditionalOnEvidenceMapping;

/**
 * Bounded outbound HTTP client for the read-only stand evidence sources. Fixed timeouts
 * keep an unavailable stand source from stalling MCP tool calls.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnEvidenceMapping
public class EvidenceRestClientConfiguration {

	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	public static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

	@Bean
	public RestClient evidenceRestClient(RestClient.Builder builder) {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return builder.requestFactory(requestFactory).build();
	}
}
