package ru.hardcode.dashboard.service.investigation;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.service.investigation.AiInvestigationModel.AiInvestigationReply;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real Spring AI model call of an investigation. It exists in every deployment, but a
 * launch is refused with {@code AI_MODEL_NOT_CONFIGURED} until the model profile provides a
 * chat model and its API key. The call runs on the dedicated model-call executor with a hard
 * deadline, so a slow model fails the run explicitly instead of blocking a launch worker.
 */
@Component
public class SpringAiInvestigationModel implements AiInvestigationModel {

	static final int MAX_HYPOTHESIS_LENGTH = 2_000;
	static final int MAX_NEXT_STEP_LENGTH = 2_000;

	private static final String SYSTEM_PROMPT = """
			Ты — ИИ-ассистент координатора массовых инцидентов MCC. По карточке инцидента и \
			свидетельствам стендовых источников ты формируешь гипотезу причины и следующий шаг \
			расследования.

			Правила ответа:
			- опирайся только на приведённые свидетельства и данные карточки; не выдумывай факты;
			- недоступный источник — это пробел: упомяни пробел в гипотезе, но не приписывай ему данные;
			- hypothesis — краткая гипотеза причины инцидента, до 2000 символов;
			- nextStep — конкретный следующий шаг координатора, до 2000 символов;
			- ответь строго одним JSON-объектом без markdown-разметки:
			  {"hypothesis": "...", "nextStep": "..."}""";

	private final ObjectProvider<ChatModel> chatModel;
	private final AiInvestigationProperties properties;
	private final ObjectMapper json;
	private final ExecutorService modelCalls;

	public SpringAiInvestigationModel(
			ObjectProvider<ChatModel> chatModel,
			AiInvestigationProperties properties,
			ObjectMapper json,
			@Qualifier("aiModelCallExecutor") ExecutorService modelCalls
	) {
		this.chatModel = chatModel;
		this.properties = properties;
		this.json = json;
		this.modelCalls = modelCalls;
	}

	@Override
	public boolean isConfigured() {
		return properties.hasModelApiKey() && chatModel.getIfAvailable() != null;
	}

	@Override
	public AiInvestigationReply analyze(String prompt) {
		ChatModel model = chatModel.getIfAvailable();
		if (model == null || !properties.hasModelApiKey()) {
			throw new AppException(AppErrors.AI_MODEL_NOT_CONFIGURED);
		}
		Future<String> reply = modelCalls.submit(() -> ChatClient.create(model)
				.prompt()
				.system(SYSTEM_PROMPT)
				.user(prompt)
				.call()
				.content());
		try {
			return parseReply(reply.get(properties.modelTimeout().toMillis(), TimeUnit.MILLISECONDS));
		} catch (TimeoutException exception) {
			throw new AppException(
					AppErrors.AI_MODEL_TIMEOUT,
					Map.of("seconds", properties.modelTimeout().toSeconds())
			);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AppException(AppErrors.AI_MODEL_UNAVAILABLE, Map.of("reason", "the call was interrupted"));
		} catch (ExecutionException exception) {
			// The cause stays internal; the public reason names only the failure class.
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			throw new AppException(AppErrors.AI_MODEL_UNAVAILABLE, Map.of("reason", reasonOf(cause)), cause);
		} finally {
			reply.cancel(true);
		}
	}

	/**
	 * Accepts exactly one JSON object with two bounded strings. Code fences are tolerated
	 * because real models add them despite the instructions; anything else, and any
	 * over-long or blank field, is an explicit invalid reply.
	 */
	AiInvestigationReply parseReply(String content) {
		String text = withoutFences(content);
		if (text == null || text.isBlank()) {
			throw new AppException(AppErrors.AI_MODEL_INVALID_REPLY);
		}
		JsonNode reply;
		try {
			reply = json.readTree(text);
		} catch (RuntimeException exception) {
			throw new AppException(AppErrors.AI_MODEL_INVALID_REPLY, exception);
		}
		if (!reply.isObject()) {
			throw new AppException(AppErrors.AI_MODEL_INVALID_REPLY);
		}
		return new AiInvestigationReply(
				bounded(text(reply.path("hypothesis")), MAX_HYPOTHESIS_LENGTH),
				bounded(text(reply.path("nextStep")), MAX_NEXT_STEP_LENGTH)
		);
	}

	/** A fenced ```json block is unwrapped; the object itself must still be strict JSON. */
	private static String withoutFences(String content) {
		if (content == null) {
			return null;
		}
		String text = content.trim();
		if (!text.startsWith("```")) {
			return text;
		}
		int firstLineEnd = text.indexOf('\n');
		if (firstLineEnd < 0) {
			return null;
		}
		text = text.substring(firstLineEnd + 1).trim();
		if (text.endsWith("```")) {
			text = text.substring(0, text.length() - 3).trim();
		}
		return text;
	}

	private static String text(JsonNode node) {
		return node.isString() ? node.stringValue() : null;
	}

	private static String bounded(String value, int maxLength) {
		if (value == null) {
			throw new AppException(AppErrors.AI_MODEL_INVALID_REPLY);
		}
		String singleLine = value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ").trim();
		if (singleLine.isBlank() || singleLine.length() > maxLength) {
			throw new AppException(AppErrors.AI_MODEL_INVALID_REPLY);
		}
		return singleLine;
	}

	private static String reasonOf(Throwable cause) {
		return "the model call failed (" + cause.getClass().getSimpleName() + ")";
	}
}
