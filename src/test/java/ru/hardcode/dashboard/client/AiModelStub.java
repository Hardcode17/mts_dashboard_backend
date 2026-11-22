package ru.hardcode.dashboard.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Controlled stand-in for the DeepSeek/OpenAI-compatible chat model of KAN-40 integration
 * tests. It answers only the fixed chat-completions route, records every received request
 * body so tests can prove what was and was not sent to the model, and lets each test script
 * the reply, a delay or a refusal. Real model behaviour is verified separately.
 */
public final class AiModelStub implements AutoCloseable {

	static final String COMPLETIONS_PATH = "/chat/completions";

	private final HttpServer server;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private final AtomicInteger answers = new AtomicInteger();
	private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
	private final AtomicReference<String> content = new AtomicReference<>(answer("Гипотеза не задана тестом.", "Шаг не задан тестом."));
	private final AtomicInteger status = new AtomicInteger(200);

	private AiModelStub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::dispatch);
		server.start();
	}

	public static AiModelStub start() {
		try {
			return new AiModelStub();
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot start the model stub", exception);
		}
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Requests the model client sent, in order, with their raw JSON bodies. */
	public List<String> requests() {
		return List.copyOf(requests);
	}

	/** Answers the model client completed (including refusals), so a late reply can be awaited. */
	public int answers() {
		return answers.get();
	}

	/** Scripts the reply as one valid investigation answer. */
	public void reply(String hypothesis, String nextStep) {
		content.set(answer(hypothesis, nextStep));
		status.set(200);
	}

	/** Scripts a raw assistant content; used to prove an invalid reply is rejected. */
	public void rawReply(String rawContent) {
		content.set(rawContent);
		status.set(200);
	}

	/** Delays every answer; the application is expected to enforce its own deadline. */
	public void delay(Duration answerDelay) {
		delay.set(answerDelay);
	}

	/** Every answer is an HTTP 400: a non-transient model error that fails the call at once. */
	public void refuse() {
		status.set(400);
	}

	public void reset() {
		requests.clear();
		answers.set(0);
		delay.set(Duration.ZERO);
		content.set(answer("Гипотеза не задана тестом.", "Шаг не задан тестом."));
		status.set(200);
	}

	/** Waits until the model client delivered the given number of requests. */
	public void awaitRequests(int count, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (requests.size() < count && System.nanoTime() < deadline) {
			sleep();
		}
		if (requests.size() < count) {
			throw new IllegalStateException("The model stub received " + requests.size()
					+ " requests instead of " + count);
		}
	}

	/** Waits until the stub finished the given number of response attempts. */
	public void awaitAnswers(int count, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (answers.get() < count && System.nanoTime() < deadline) {
			sleep();
		}
		if (answers.get() < count) {
			throw new IllegalStateException("The model stub completed " + answers.get()
					+ " response attempts instead of " + count);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void dispatch(HttpExchange exchange) throws IOException {
		try {
			if (!COMPLETIONS_PATH.equals(exchange.getRequestURI().getPath())
					|| !"POST".equals(exchange.getRequestMethod())) {
				send(exchange, 404, "{}");
				return;
			}
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(body);
			Duration answerDelay = delay.get();
			if (!answerDelay.isZero()) {
				sleep(answerDelay);
			}
			try {
				int statusCode = status.get();
				String payload = statusCode == 200
						? completion(content.get())
						: "{\"error\":{\"message\":\"stub refusal\"}}";
				send(exchange, statusCode, payload);
			} finally {
				// A timed-out caller can disconnect before the stub has sent its delayed reply.
				answers.incrementAndGet();
			}
		} catch (RuntimeException failure) {
			send(exchange, 500, "{}");
		}
	}

	private static String completion(String content) {
		return "{\"id\":\"chatcmpl-kan40\",\"object\":\"chat.completion\",\"created\":1732099200,"
				+ "\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
				+ "\"content\":" + json(content) + "},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20}}";
	}

	private static String answer(String hypothesis, String nextStep) {
		return "{\"hypothesis\":" + json(hypothesis) + ",\"nextStep\":" + json(nextStep) + "}";
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(body.getBytes(StandardCharsets.UTF_8));
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.size());
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes.toByteArray());
		}
	}

	private static void sleep(Duration duration) {
		try {
			TimeUnit.NANOSECONDS.sleep(duration.toNanos());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private static void sleep() {
		sleep(Duration.ofMillis(20));
	}

	private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

	private static String json(String value) {
		return JSON.writeValueAsString(value);
	}
}
