package ru.hardcode.dashboard.service;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DeadlineWarningRuleResponse;
import ru.hardcode.dashboard.repository.DeadlineWarningRepository;
import ru.hardcode.dashboard.repository.ItsmBaselineRepository;

@Service
public class DeadlineWarningService {

	private final DeadlineWarningRepository rules;
	private final LocalIncidentEventWriter events;
	private final ItsmBaselineRepository installation;

	public DeadlineWarningService(DeadlineWarningRepository rules, LocalIncidentEventWriter events,
			ItsmBaselineRepository installation) {
		this.rules = rules;
		this.events = events;
		this.installation = installation;
	}

	@Transactional(readOnly = true)
	public DeadlineWarningRuleResponse rule() {
		return rules.rule();
	}

	@Transactional
	public DeadlineWarningRuleResponse setThreshold(int thresholdSeconds) {
		installation.lockInstallation();
		return rules.setThreshold(thresholdSeconds);
	}

	@Transactional
	public int checkDue(Instant now) {
		installation.lockInstallation();
		var rule = rules.rule();
		int count = 0;
		for (var candidate : rules.pending(now, rule)) {
			var event = events.append(candidate.incidentId(), "DEADLINE_APPROACHING", candidate.key(), now,
					Map.of("deadline", candidate.deadline().toString(), "ruleVersion", rule.version()));
			if (event.isPresent()) {
				count++;
			}
		}
		return count;
	}
}
