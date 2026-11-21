package ru.hardcode.dashboard.service.investigation;

/**
 * The model call of one investigation. Implementations own exactly one contract: a bounded
 * prompt in, a bounded hypothesis and next step out. Availability is reported explicitly, so
 * a deployment without a model refuses the launch instead of inventing an answer.
 */
public interface AiInvestigationModel {

	/** True only when a real model client and its API key are configured. */
	boolean isConfigured();

	/**
	 * Asks the model about one investigation prompt.
	 *
	 * @throws ru.hardcode.dashboard.exception.AppException with {@code AI_MODEL_TIMEOUT},
	 *         {@code AI_MODEL_UNAVAILABLE} or {@code AI_MODEL_INVALID_REPLY} when the model
	 *         does not deliver a valid bounded reply in time
	 */
	AiInvestigationReply analyze(String prompt);

	/** The validated model answer of one investigation. */
	record AiInvestigationReply(String hypothesis, String nextStep) {
	}
}
