package ru.hardcode.dashboard.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Channel adapter that hands every message to one controlled webhook receiver (KAN-35). The
 * receiver stands in for the real channel APIs of the stand: it answers synchronously with
 * the outcome signal the channel observed.
 *
 * <p>Request: {@code POST notifications.webhook.url} with
 * {@code {"channel", "address", "secretRef", "title", "body"}}. Response {@code 2xx} with
 * {@code {"signal": "sent" | "delivered" | "read_in_channel" | "unknown"}}: a missing or
 * unknown value means no usable signal, so the delivery stays {@code unknown}. Any non-2xx
 * answer, a missing webhook URL or a transport failure fails the attempt; the persisted
 * reason is only a short code with the numeric status, never the address, the token name or
 * the URL.
 */
@Component
public class WebhookNotificationChannelAdapter implements NotificationChannelAdapter {

	private final RestClient http;
	private final ObjectMapper json;
	private final String webhookUrl;

	public WebhookNotificationChannelAdapter(
			@Value("${notifications.webhook.url:}") String webhookUrl,
			@Value("${notifications.webhook.connect-timeout:PT2S}") Duration connectTimeout,
			@Value("${notifications.webhook.read-timeout:PT5S}") Duration readTimeout,
			ObjectMapper json
	) {
		this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
		this.json = json;
		var client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
		var requestFactory = new JdkClientHttpRequestFactory(client);
		requestFactory.setReadTimeout(readTimeout);
		this.http = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public Signal send(Destination destination, Message message) {
		if (webhookUrl.isBlank()) {
			throw new NotificationChannelFailure("channel_unconfigured");
		}
		String body;
		try {
			body = http.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
					.body(Map.of(
							"channel", destination.channel(),
							"address", destination.address() == null ? "" : destination.address(),
							"secretRef", destination.secretRef() == null ? "" : destination.secretRef(),
							"title", message.title(),
							"body", message.body()))
					.retrieve().body(String.class);
		}
		catch (RestClientResponseException rejected) {
			throw new NotificationChannelFailure("channel_rejected_" + rejected.getStatusCode().value(), rejected);
		}
		catch (ResourceAccessException unreachable) {
			throw new NotificationChannelFailure("channel_unavailable", unreachable);
		}
		catch (RuntimeException transportFailure) {
			throw new NotificationChannelFailure("channel_unavailable", transportFailure);
		}
		return signal(body);
	}

	private Signal signal(String body) {
		if (body == null || body.isBlank()) {
			return Signal.UNKNOWN;
		}
		try {
			JsonNode response = json.readTree(body);
			String signal = response.path("signal").asString(null);
			if (signal == null) {
				return Signal.UNKNOWN;
			}
			return switch (signal) {
				case "sent" -> Signal.SENT;
				case "delivered" -> Signal.DELIVERED;
				case "read_in_channel" -> Signal.READ_IN_CHANNEL;
				default -> Signal.UNKNOWN;
			};
		}
		catch (RuntimeException unparsable) {
			// The channel accepted the message but its answer carries no usable signal.
			return Signal.UNKNOWN;
		}
	}
}
