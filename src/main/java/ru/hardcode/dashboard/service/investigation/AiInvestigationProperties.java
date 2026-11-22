package ru.hardcode.dashboard.service.investigation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded configuration of the AI investigation feature (KAN-40). The model call itself is
 * enabled by the {@code ai} Spring profile together with a model API key; without them the
 * launch is refused with an explicit status instead of a silent or fake result.
 *
 * @param modelApiKey the API key gate: blank keeps every launch refused with
 *                    {@code AI_MODEL_NOT_CONFIGURED}; normally the same variable the
 *                    Spring AI model client reads
 * @param modelTimeout hard deadline of one model call; a slower model fails the run with
 *                     {@code AI_MODEL_TIMEOUT}
 * @param workers      concurrent model-answer workers
 * @param queueCapacity buffered launches before new ones are refused
 */
@ConfigurationProperties(prefix = "ai.investigation")
public record AiInvestigationProperties(
		String modelApiKey,
		Duration modelTimeout,
		Integer workers,
		Integer queueCapacity
) {

	public AiInvestigationProperties {
		modelApiKey = modelApiKey == null ? "" : modelApiKey;
		modelTimeout = modelTimeout == null || modelTimeout.isNegative() || modelTimeout.isZero()
				? Duration.ofMinutes(1) : modelTimeout;
		workers = workers == null || workers < 1 ? 2 : workers;
		queueCapacity = queueCapacity == null || queueCapacity < 1 ? 100 : queueCapacity;
	}

	public boolean hasModelApiKey() {
		return !modelApiKey.isBlank();
	}
}
