package ru.hardcode.dashboard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.hardcode.dashboard.service.NotificationDeliveryService;
import ru.hardcode.dashboard.service.SignificantEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KAN-35 delivery mechanics over Spring Boot with the real filter chain, MockMvc and
 * PostgreSQL 17. Outbound channel calls go to one controlled webhook receiver that records
 * every request and answers with a scripted outcome per address.
 */
@SpringBootTest(properties = "itsm.worker.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("work")
@Import(ItsmPostgresTestcontainersConfiguration.class)
class NotificationDeliveryIT {

	private static final String CONNECTIONS = "/api/dashboard/notification-connections";
	private static final String NOTIFICATIONS = "/api/dashboard/notifications";
	private static final String ISSUER = "https://kan35.issuer.test";
	private static final String INCIDENT = "INC000021095532";
	private static final RequestPostProcessor OWNER = coordinator("kan35-owner");
	private static final RequestPostProcessor OTHER = coordinator("kan35-other");

	/** Started before the application context so its URL can be injected as a property. */
	private static final ControlledWebhookReceiver WEBHOOK = ControlledWebhookReceiver.start();

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired NotificationDeliveryService service;
	@Autowired PostgreSQLContainer postgres;
	private long ownerId;
	private long otherId;

	@DynamicPropertySource
	static void webhookProperties(DynamicPropertyRegistry registry) {
		registry.add("notifications.webhook.url", WEBHOOK::url);
		registry.add("notifications.webhook.read-timeout", () -> "PT2S");
	}

	@AfterAll
	static void stopWebhook() {
		WEBHOOK.close();
	}

	@BeforeEach
	void seedUsers() {
		WEBHOOK.reset();
		jdbc.update("DELETE FROM notification");
		jdbc.update("DELETE FROM users WHERE identity_issuer = ?", ISSUER);
		ownerId = user("kan35-owner");
		otherId = user("kan35-other");
	}

	@Test
	void oneEventCreatesOneNotificationPerRecipientAndOneDeliveryPerAllowedConnection() throws Exception {
		long ownerEmail = connection(OWNER, "email", "owner@example.test");
		long ownerTelegram = connection(OWNER, "telegram", "owner-telegram", "TELEGRAM_DEMO_TOKEN");
		long otherEmail = connection(OTHER, "email", "other@example.test");
		long ownerSmsP3 = connection(OWNER, "sms", "owner-sms-p3", null, "[3]");

		service.deliver(new SignificantEvent("evt-0041", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " стал критическим и требует реакции смены.", null));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM notification WHERE recipient_user_id = ?
				""", Long.class, ownerId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Long.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM notification_delivery d
				JOIN notification n ON n.id = d.notification_id WHERE n.recipient_user_id = ?
				""", Long.class, ownerId)).isEqualTo(2);
		// The connection subscribed only to P3 is not an allowed connection of the P4 event.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery WHERE connection_id = ?",
				Long.class, ownerSmsP3)).isZero();
		assertThat(WEBHOOK.requestsTo("owner@example.test")).hasSize(1);
		assertThat(WEBHOOK.requestsTo("owner-telegram")).hasSize(1);
		assertThat(WEBHOOK.requestsTo("other@example.test")).hasSize(1);

		// A replay of the same eventKey creates and sends nothing new for any recipient.
		service.deliver(new SignificantEvent("evt-0041", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " стал критическим и требует реакции смены.", null));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Long.class)).isEqualTo(3);
		assertThat(WEBHOOK.totalRequests()).isEqualTo(3);

		var response = mvc.perform(get(NOTIFICATIONS).with(OWNER))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].incidentId").value(INCIDENT))
				.andExpect(jsonPath("$[0].priorityId").value(4))
				.andExpect(jsonPath("$[0].deliveries.length()").value(2))
				.andReturn().getResponse();
		assertThat(response.getContentAsString())
				.doesNotContain("owner@example.test", "owner-telegram", "TELEGRAM_DEMO_TOKEN");
	}

	@Test
	void failingChannelKeepsReasonWhileSuccessfulDeliveryIsNeverResent() throws Exception {
		long email = connection(OWNER, "email", "mixed@example.test");
		connection(OWNER, "telegram", "mixed-telegram", "TELEGRAM_DEMO_TOKEN");
		WEBHOOK.signal("mixed@example.test", "sent");
		WEBHOOK.reject("mixed-telegram", 503);

		service.deliver(new SignificantEvent("evt-0042", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));

		var failedId = deliveryId("telegram");
		var successfulId = deliveryId("email");
		mvc.perform(get(NOTIFICATIONS).with(OWNER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].deliveries[?(@.id==" + failedId + ")].state").value("failed"))
				.andExpect(jsonPath("$[0].deliveries[?(@.id==" + failedId + ")].failureReason").value("channel_rejected_503"))
				.andExpect(jsonPath("$[0].deliveries[?(@.id==" + successfulId + ")].state").value("sent"))
				.andExpect(jsonPath("$[0].deliveries[?(@.id==" + successfulId + ")].attempts").value(1));
		assertThat(mvc.perform(get(NOTIFICATIONS).with(OWNER)).andReturn().getResponse().getContentAsString())
				.doesNotContain("mixed@example.test", "mixed-telegram", "TELEGRAM_DEMO_TOKEN");

		// A retry of the successful delivery is a no-op: no second send to the channel.
		var retry = mvc.perform(post(NOTIFICATIONS + "/deliveries/{id}/retry", successfulId).with(OWNER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("sent"))
				.andExpect(jsonPath("$.attempts").value(1))
				.andReturn().getResponse();
		assertThat(retry.getContentAsString()).doesNotContain("TELEGRAM_DEMO_TOKEN", "mixed");
		assertThat(WEBHOOK.requestsTo("mixed@example.test")).hasSize(1);
		assertThat(WEBHOOK.requestsTo("mixed-telegram")).hasSize(1);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM notification_delivery WHERE state = 'sent'
				""", Long.class)).isEqualTo(1);
	}

	@Test
	void retryResendsOnlyTheFailedDelivery() throws Exception {
		connection(OWNER, "email", "recover@example.test");
		connection(OWNER, "telegram", "recover-telegram");
		WEBHOOK.signal("recover@example.test", "sent");
		WEBHOOK.reject("recover-telegram", 500);

		service.deliver(new SignificantEvent("evt-0043", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));
		long failedId = deliveryId("telegram");
		assertThat(WEBHOOK.requestsTo("recover-telegram")).hasSize(1);

		// The channel recovers; the failed delivery alone is re-sent.
		WEBHOOK.signal("recover-telegram", "delivered");
		mvc.perform(post(NOTIFICATIONS + "/deliveries/{id}/retry", failedId).with(OWNER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("delivered"))
				.andExpect(jsonPath("$.failureReason").doesNotExist())
				.andExpect(jsonPath("$.attempts").value(2));

		assertThat(WEBHOOK.requestsTo("recover-telegram")).hasSize(2);
		assertThat(WEBHOOK.requestsTo("recover@example.test")).hasSize(1);
		assertThat(jdbc.queryForObject("""
				SELECT sent_at IS NOT NULL AND delivered_at IS NOT NULL FROM notification_delivery
				WHERE id = ?
				""", Boolean.class, failedId)).isTrue();

		// The failed delivery is now successful, so another retry stays a no-op.
		mvc.perform(post(NOTIFICATIONS + "/deliveries/{id}/retry", failedId).with(OWNER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("delivered"))
				.andExpect(jsonPath("$.attempts").value(2));
		assertThat(WEBHOOK.requestsTo("recover-telegram")).hasSize(2);
	}

	@Test
	void concurrentRetriesDoNotDuplicateTheSuccessfulSend() throws Exception {
		connection(OWNER, "telegram", "concurrent-telegram");
		WEBHOOK.reject("concurrent-telegram", 500);
		service.deliver(new SignificantEvent("evt-0044", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));
		long failedId = deliveryId("telegram");
		WEBHOOK.signal("concurrent-telegram", "sent");

		int callers = 4;
		List<Future<Integer>> results;
		try (var executor = Executors.newFixedThreadPool(callers)) {
			var ready = new CountDownLatch(callers);
			var start = new CountDownLatch(1);
			results = new ArrayList<>();
			for (int index = 0; index < callers; index++) {
				results.add(executor.submit((Callable<Integer>) () -> {
					ready.countDown();
					start.await();
					var response = mvc.perform(post(NOTIFICATIONS + "/deliveries/{id}/retry", failedId)
							.with(OWNER)).andReturn();
					return response.getResponse().getStatus();
				}));
			}
			ready.await();
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(200);
			}
		}

		// One failed attempt plus exactly one retry send, whatever the interleaving was.
		assertThat(WEBHOOK.requestsTo("concurrent-telegram")).hasSize(2);
		var delivery = jdbc.queryForMap("""
				SELECT state, attempts FROM notification_delivery WHERE id = ?
				""", failedId);
		assertThat(delivery).isEqualTo(Map.of("state", "sent", "attempts", 2));
	}

	@Test
	void noReceiptLeavesStateUnknown() throws Exception {
		connection(OWNER, "email", "silent@example.test");
		WEBHOOK.noSignal("silent@example.test");
		service.deliver(new SignificantEvent("evt-0045", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));

		mvc.perform(get(NOTIFICATIONS).with(OWNER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].deliveries.length()").value(1))
				.andExpect(jsonPath("$[0].deliveries[0].state").value("unknown"))
				.andExpect(jsonPath("$[0].deliveries[0].attempts").value(1))
				.andExpect(jsonPath("$[0].deliveries[0].failureReason").doesNotExist());
		assertThat(jdbc.queryForObject("""
				SELECT sent_at IS NULL AND delivered_at IS NULL AND read_in_channel_at IS NULL
					AND read_in_app_at IS NULL AND last_attempt_at IS NOT NULL
				FROM notification_delivery
				""", Boolean.class)).isTrue();
	}

	@Test
	void appReadIsSeparateFromChannelRead() throws Exception {
		connection(OWNER, "email", "reading@example.test");
		connection(OWNER, "telegram", "reading-telegram");
		connection(OWNER, "sms", "reading-sms");
		WEBHOOK.signal("reading@example.test", "delivered");
		WEBHOOK.signal("reading-telegram", "read_in_channel");
		WEBHOOK.noSignal("reading-sms");
		service.deliver(new SignificantEvent("evt-0046", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));

		// The channel receipts arrive without any app read: nothing is marked read in the app.
		mvc.perform(get(NOTIFICATIONS).with(OWNER))
				.andExpect(jsonPath("$[0].readInAppAt").doesNotExist())
				.andExpect(jsonPath("$[0].deliveries[?(@.channel=='telegram')].state").value("read_in_channel"))
				.andExpect(jsonPath("$[0].deliveries[?(@.channel=='email')].state").value("delivered"))
				.andExpect(jsonPath("$[0].deliveries[?(@.channel=='sms')].state").value("unknown"));

		mvc.perform(post(NOTIFICATIONS + "/{id}/read", notificationId()).with(OWNER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.readInAppAt").isNotEmpty())
				.andExpect(jsonPath("$.deliveries[?(@.channel=='telegram')].state").value("read_in_channel"))
				.andExpect(jsonPath("$.deliveries[?(@.channel=='telegram')].readInChannelAt").isNotEmpty())
				.andExpect(jsonPath("$.deliveries[?(@.channel=='email')].state").value("read_in_app"))
				.andExpect(jsonPath("$.deliveries[?(@.channel=='sms')].state").value("read_in_app"));

		// A repeated confirmation keeps the first timestamp and changes nothing else.
		String first = mvc.perform(get(NOTIFICATIONS).with(OWNER)).andReturn().getResponse().getContentAsString();
		String second = mvc.perform(post(NOTIFICATIONS + "/{id}/read", notificationId()).with(OWNER))
				.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(second).get("readInAppAt"))
				.isEqualTo(json.readTree(first).get(0).get("readInAppAt"));
	}

	@Test
	void deliveryStateSurvivesBackendRestart() throws Exception {
		connection(OWNER, "email", "restart@example.test");
		connection(OWNER, "telegram", "restart-telegram", "TELEGRAM_DEMO_TOKEN");
		WEBHOOK.signal("restart@example.test", "delivered");
		WEBHOOK.reject("restart-telegram", 503);
		service.deliver(new SignificantEvent("evt-0047", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));
		mvc.perform(post(NOTIFICATIONS + "/{id}/read", notificationId()).with(OWNER))
				.andExpect(status().isOk());

		try (var app = restart()) {
			var restarted = mvc(app);
			restarted.perform(get(NOTIFICATIONS).with(OWNER))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(1))
					.andExpect(jsonPath("$[0].incidentId").value(INCIDENT))
					.andExpect(jsonPath("$[0].readInAppAt").isNotEmpty())
					.andExpect(jsonPath("$[0].deliveries[?(@.channel=='email')].state").value("read_in_app"))
					.andExpect(jsonPath("$[0].deliveries[?(@.channel=='email')].deliveredAt").isNotEmpty())
					.andExpect(jsonPath("$[0].deliveries[?(@.channel=='telegram')].state").value("failed"))
					.andExpect(jsonPath("$[0].deliveries[?(@.channel=='telegram')].failureReason").value("channel_rejected_503"))
					.andExpect(jsonPath("$[0].deliveries[?(@.channel=='telegram')].attempts").value(1));
			assertThat(restarted.perform(get(NOTIFICATIONS).with(OWNER)).andReturn().getResponse()
					.getContentAsString())
					.doesNotContain("restart@example.test", "restart-telegram", "TELEGRAM_DEMO_TOKEN");

			// After the restart the recipient can still retry only the failed delivery.
			WEBHOOK.signal("restart-telegram", "sent");
			long failedId = jdbc.queryForObject("""
					SELECT d.id FROM notification_delivery d
					JOIN notification n ON n.id = d.notification_id
					JOIN notification_connections c ON c.id = d.connection_id
					JOIN notification_types t ON t.id = c.notification_type_id
					WHERE n.recipient_user_id = ? AND t.code = 'telegram'
					""", Long.class, ownerId);
			restarted.perform(post(NOTIFICATIONS + "/deliveries/{id}/retry", failedId).with(OWNER))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.state").value("sent"))
					.andExpect(jsonPath("$.attempts").value(2));
			assertThat(WEBHOOK.requestsTo("restart-telegram")).hasSize(2);
		}
	}

	@Test
	void protectsOwnershipAndRequiresAuthentication() throws Exception {
		connection(OWNER, "email", "protected@example.test");
		WEBHOOK.signal("protected@example.test", "sent");
		service.deliver(new SignificantEvent("evt-0048", INCIDENT, 4, "P4 назначен на смену",
				INCIDENT + " требует реакции.", List.of(ownerId)));
		long delivery = deliveryId("email");

		mvc.perform(get(NOTIFICATIONS)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("HUMAN_AUTH_REQUIRED"))
				.andExpect(jsonPath("$.message").isString());
		mvc.perform(post(NOTIFICATIONS + "/{id}/read", 1).with(OTHER))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		mvc.perform(post(NOTIFICATIONS + "/deliveries/{id}/retry", delivery).with(OTHER))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOTIFICATION_DELIVERY_NOT_FOUND"));
		mvc.perform(get(NOTIFICATIONS).with(OTHER))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_delivery", Long.class)).isEqualTo(1);
	}

	private long user(String subject) {
		return jdbc.queryForObject("""
				INSERT INTO users(identity_issuer, identity_subject, login, display_name)
				VALUES (?, ?, ?, ?) RETURNING id
				""", Long.class, ISSUER, subject, subject, subject);
	}

	private long connection(RequestPostProcessor who, String channel, String address) throws Exception {
		return connection(who, channel, address, null, "[4]");
	}

	private long connection(RequestPostProcessor who, String channel, String address, String secretRef)
			throws Exception {
		return connection(who, channel, address, secretRef, "[4]");
	}

	private long connection(RequestPostProcessor who, String channel, String address, String secretRef,
			String priorities) throws Exception {
		String secret = secretRef == null ? "" : ",\"secretRef\":\"" + secretRef + "\"";
		String body = "{\"channel\":\"%s\",\"address\":\"%s\",\"active\":true,\"priorityIds\":%s%s}"
				.formatted(channel, address, priorities, secret);
		var response = mvc.perform(post(CONNECTIONS).with(who).contentType(MediaType.APPLICATION_JSON)
						.content(body)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.secretRef").doesNotExist()).andReturn().getResponse();
		return json.readTree(response.getContentAsString()).get("id").asLong();
	}

	private long notificationId() {
		return jdbc.queryForObject("""
				SELECT id FROM notification WHERE recipient_user_id = ? ORDER BY id DESC LIMIT 1
				""", Long.class, ownerId);
	}

	private long deliveryId(String channel) {
		return jdbc.queryForObject("""
				SELECT d.id FROM notification_delivery d
				JOIN notification n ON n.id = d.notification_id
				JOIN notification_connections c ON c.id = d.connection_id
				JOIN notification_types t ON t.id = c.notification_type_id
				WHERE n.recipient_user_id = ? AND t.code = ?
				""", Long.class, ownerId, channel);
	}

	private ConfigurableApplicationContext restart() {
		return new SpringApplicationBuilder(DashboardApplication.class).profiles("work").run(
				"--server.port=0",
				"--itsm.worker.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--notifications.webhook.url=" + WEBHOOK.url()
		);
	}

	private static MockMvc mvc(ConfigurableApplicationContext app) {
		return MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).apply(springSecurity()).build();
	}

	private static RequestPostProcessor coordinator(String subject) {
		return jwt().jwt(token -> token.issuer(ISSUER).subject(subject).claim("azp", "dashboard-web"));
	}

	/**
	 * Controlled stand-in for the channel APIs: records every delivery request and answers
	 * with the scripted outcome of the target address. Default answer is a plain send.
	 */
	private static final class ControlledWebhookReceiver implements AutoCloseable {

		record Received(String address, String title, String body) {
		}

		private final HttpServer server;
		private final Map<String, String> signals = new ConcurrentHashMap<>();
		private final Map<String, Integer> rejections = new ConcurrentHashMap<>();
		private final List<Received> requests = new CopyOnWriteArrayList<>();
		private final ObjectMapper json = new ObjectMapper();

		private ControlledWebhookReceiver(HttpServer server) {
			this.server = server;
		}

		static ControlledWebhookReceiver start() {
			try {
				var receiver = new ControlledWebhookReceiver(HttpServer.create(
						new InetSocketAddress("127.0.0.1", 0), 0));
				receiver.server.createContext("/deliver", exchange -> {
					String requestBody = new String(exchange.getRequestBody().readAllBytes());
					JsonNode request = receiver.json.readTree(requestBody);
					String address = request.path("address").stringValue("");
					receiver.requests.add(new Received(address, request.path("title").stringValue(""),
							request.path("body").stringValue("")));
					int status = receiver.rejections.getOrDefault(address, 200);
					String answer = status == 200
							? receiver.answer(request.path("address").stringValue(""))
							: "{\"signal\":\"sent\"}";
					byte[] body = answer.getBytes();
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					exchange.sendResponseHeaders(status, body.length);
					try (var output = exchange.getResponseBody()) {
						output.write(body);
					}
				});
				receiver.server.start();
				return receiver;
			}
			catch (IOException unavailable) {
				throw new IllegalStateException("cannot start the controlled webhook receiver", unavailable);
			}
		}

		private String answer(String address) {
			String signal = signals.get(address);
			if (signal == null) {
				return "{\"signal\":\"sent\"}";
			}
			return signal.isEmpty() ? "{}" : "{\"signal\":\"" + signal + "\"}";
		}

		String url() {
			return "http://127.0.0.1:" + server.getAddress().getPort() + "/deliver";
		}

		void signal(String address, String signal) {
			rejections.remove(address);
			signals.put(address, signal);
		}

		void noSignal(String address) {
			rejections.remove(address);
			signals.put(address, "");
		}

		void reject(String address, int status) {
			rejections.put(address, status);
		}

		void reset() {
			signals.clear();
			rejections.clear();
			requests.clear();
		}

		List<Received> requestsTo(String address) {
			return requests.stream().filter(request -> request.address().equals(address)).toList();
		}

		int totalRequests() {
			return requests.size();
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}
}
