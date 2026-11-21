package ru.hardcode.dashboard.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Group;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Membership;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Summary;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentSummary;

enum DashboardIncidentGroup {
	CRITICAL("critical", "Критические", "Активный инцидент с priorityId = 4", "priorityId = 4", true,
			inc -> !inc.state().isCompleted() && inc.priorityId() == 4),
	COORDINATOR("coordinator", "На координаторах", "Активный инцидент или Research, назначенный на координаторов",
			"assignedToCoordinators", true,
			inc -> (!inc.state().isCompleted() || inc.state() == ItsmIncidentState.Research)
					&& "КЦ ИТ Координаторы Массовых Инцидентов".equals(inc.assignedGroupName())),
	HIGH("high", "Высокий приоритет", "Активный инцидент с priorityId = 3", "priorityId = 3", true,
			inc -> !inc.state().isCompleted() && inc.priorityId() == 3),
	RESEARCH("research", "Исследование ситуации", "stateEnum = Research; карточка видна и с датой устранения",
			"stateEnum = Research", false, inc -> inc.state() == ItsmIncidentState.Research);

	private final String key;
	private final String title;
	private final String rule;
	private final String matchedRule;
	private final boolean active;
	private final Predicate<IncidentSummary> matches;

	DashboardIncidentGroup(String key, String title, String rule, String matchedRule, boolean active,
			Predicate<IncidentSummary> matches) {
		this.key = key;
		this.title = title;
		this.rule = rule;
		this.matchedRule = matchedRule;
		this.active = active;
		this.matches = matches;
	}

	static DashboardIncidentGroup fromKey(String key) {
		return Arrays.stream(values()).filter(group -> group.key.equals(key)).findFirst()
				.orElseThrow(() -> new AppException(AppErrors.INVALID_GROUP, Map.of("group", key)));
	}

	static List<Membership> memberships(IncidentSummary incident) {
		return Arrays.stream(values()).filter(group -> group.matches.test(incident))
				.sorted(Comparator.comparing(group -> group.key))
				.map(group -> new Membership(group.key, group.matchedRule)).toList();
	}

	boolean contains(Summary item) {
		return item.groupMemberships().stream().anyMatch(membership -> key.equals(membership.group()));
	}

	Group summarize(List<Summary> selected, List<Summary> page) {
		int count = (int) selected.stream().filter(this::contains).count();
		return new Group(key, title, rule, active, count, page.stream().filter(this::contains).toList(), null);
	}
}
