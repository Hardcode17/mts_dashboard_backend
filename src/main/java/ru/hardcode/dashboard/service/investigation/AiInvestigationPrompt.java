package ru.hardcode.dashboard.service.investigation;

import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import ru.hardcode.dashboard.dto.McpEvidenceIncidentContextResponse;
import ru.hardcode.dashboard.dto.McpEvidenceObservation;
import ru.hardcode.dashboard.dto.McpEvidenceRef;
import ru.hardcode.dashboard.dto.McpEvidenceUnavailableSource;

/**
 * The user message of one investigation: the incident card as published at launch, the
 * evidence facts of the bounded window and the explicit gaps. Every value is bounded and
 * single-line, so a long or hostile source record cannot bloat the model request.
 *
 * <p>A solution is included only for completed incidents. For an active incident the
 * solution never reaches the model, even when a corrupted record carries one.
 */
final class AiInvestigationPrompt {

	static final int MAX_FIELD_LENGTH = 500;
	static final int MAX_DESCRIPTION_LENGTH = 4_000;
	static final int MAX_SOLUTION_LENGTH = 2_000;

	private AiInvestigationPrompt() {
	}

	static String modelPrompt(DashboardIncidentCardResponse card, McpEvidenceIncidentContextResponse evidence) {
		StringBuilder prompt = new StringBuilder(2_048);
		prompt.append("Инцидент MCC для расследования.\n\nКарточка (ревизия публикации ")
				.append(card.revision()).append("):\n");
		line(prompt, "id", card.id());
		line(prompt, "заголовок", bounded(card.header(), MAX_FIELD_LENGTH));
		line(prompt, "описание", bounded(card.description(), MAX_DESCRIPTION_LENGTH));
		line(prompt, "продукт", bounded(card.productName(), MAX_FIELD_LENGTH));
		line(prompt, "сервис", bounded(card.service(), MAX_FIELD_LENGTH));
		line(prompt, "тип сервиса", bounded(card.serviceType(), MAX_FIELD_LENGTH));
		line(prompt, "группа исполнения", bounded(card.assignedGroupName(), MAX_FIELD_LENGTH));
		line(prompt, "ответственный", bounded(card.assignedEmployee(), MAX_FIELD_LENGTH));
		line(prompt, "состояние", card.stateEnum().name());
		line(prompt, "причина состояния", bounded(card.stateReason(), MAX_FIELD_LENGTH));
		line(prompt, "приоритет", String.valueOf(card.priorityId()));
		line(prompt, "зарегистрирован", String.valueOf(card.submitDate()));
		line(prompt, "срок", card.deadline() == null ? "не указан" : card.deadline().toString());
		if (card.completionDate() != null) {
			line(prompt, "устранён", card.completionDate().toString());
		}
		// Only a completed incident may reveal its solution to the model. An active
		// incident never sends one, even if a corrupted record carries it.
		if (card.stateEnum().isCompleted() && card.solution() != null && !card.solution().isBlank()) {
			line(prompt, "решение (инцидент завершён)", bounded(card.solution(), MAX_SOLUTION_LENGTH));
		}

		prompt.append("\nСвидетельства стендовых источников за окно ")
				.append(evidence.windowFrom()).append(" .. ").append(evidence.windowTo()).append(":\n");
		if (evidence.evidence().isEmpty()) {
			prompt.append("свидетельства отсутствуют\n");
		}
		for (McpEvidenceRef ref : evidence.evidence()) {
			prompt.append("- ").append(ref.source()).append(' ').append(ref.serviceKey())
					.append(" (evidenceId ").append(ref.evidenceId()).append(", наблюдено ")
					.append(ref.observedAt()).append("):\n");
			McpEvidenceObservation observation = ref.observation();
			if (observation.message() != null) {
				line(prompt, "сообщение", observation.message()
						+ (Boolean.TRUE.equals(observation.messageTruncated()) ? " (сообщение сокращено)" : ""));
			}
			if (observation.level() != null) {
				line(prompt, "уровень", observation.level());
			}
			if (observation.assignedCommand() != null) {
				line(prompt, "назначено на", observation.assignedCommand());
			}
			if (observation.recordCount() != null) {
				line(prompt, "записей в окне", String.valueOf(observation.recordCount()));
			}
			if (observation.value() != null) {
				line(prompt, "последнее значение", String.valueOf(observation.value()));
			}
			if (observation.serviceState() != null) {
				line(prompt, "состояние сервиса", observation.serviceState());
			}
		}

		if (!evidence.unavailable().isEmpty()) {
			prompt.append("\nНедоступные источники (пробелы в свидетельствах):\n");
			for (McpEvidenceUnavailableSource source : evidence.unavailable()) {
				prompt.append("- ").append(source.source()).append(' ').append(source.serviceKey())
						.append(": ").append(source.code()).append('\n');
			}
		}

		prompt.append("\nЗадача: по карточке и свидетельствам сформулируй гипотезу причины инцидента ")
				.append("и следующий шаг координатора. Не выдумывай факты, которых нет в свидетельствах; ")
				.append("недоступный источник — пробел, а не источник данных.\n");
		return prompt.toString();
	}

	private static void line(StringBuilder prompt, String name, String value) {
		if (value != null && !value.isBlank()) {
			prompt.append("- ").append(name).append(": ").append(singleLine(value)).append('\n');
		}
	}

	private static String singleLine(String value) {
		return value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ").trim();
	}

	private static String bounded(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String singleLine = singleLine(value);
		if (singleLine.length() <= maxLength) {
			return singleLine;
		}
		return singleLine.substring(0, maxLength) + "…(сокращено)";
	}
}
