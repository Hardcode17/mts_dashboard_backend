package ru.hardcode.dashboard.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse;
import ru.hardcode.dashboard.dto.DashboardIncidentListResponse.Summary;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.exception.DashboardIncidentNotFoundException;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentRow;

@Service
public class DashboardIncidentService {

	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");
	private static final Comparator<IncidentRow> INCIDENT_ORDER =
			Comparator.comparing(DashboardIncidentService::updatedAt).reversed()
					.thenComparing(row -> row.summary().id());

	private final DashboardIncidentRepository repository;
	private final DashboardItsmLink itsmLink;
	private final Clock clock;

	public DashboardIncidentService(DashboardIncidentRepository repository, DashboardItsmLink itsmLink, Clock clock) {
		this.repository = repository;
		this.itsmLink = itsmLink;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DashboardIncidentCardResponse card(String incidentId) {
		if (!INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
		var published = repository.publication();
		if (published.eventSeq() == 0) {
			throw new DashboardIncidentNotFoundException(incidentId, 0, 0);
		}
		var card = repository.findCard(incidentId).orElseThrow(() ->
				new DashboardIncidentNotFoundException(incidentId, published.eventSeq(), published.revision()));
		var incident = card.summary();
		Duration remaining = !incident.state().isCompleted() && incident.deadline() != null
				? Duration.between(clock.instant(), incident.deadline()) : null;
		return new DashboardIncidentCardResponse(incident.id(), card.header(), card.description(), incident.productName(),
				card.productMasterId(), card.service(), card.serviceType(), incident.assignedGroupName(),
				card.assignedEmployee(), incident.state(), card.stateReason(), incident.priorityId(), incident.submitDate(),
				card.lastAssignmentToTeamDateTime(), incident.deadline(), incident.completionDate(), card.solution(),
				remaining == null ? null : remaining.getSeconds(), remaining != null && remaining.isNegative(),
				itsmLink.forIncident(incident.id()), itsmLink.demo(), DashboardIncidentGroup.memberships(incident),
				published.eventSeq(), published.revision());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ListPage list(List<String> groups, boolean includeCompleted, String cursor, int limit) {
		if (limit < 1 || limit > 100) {
			throw new AppException(AppErrors.INVALID_LIMIT);
		}
		var selection = selection(groups, includeCompleted);
		var publication = repository.publication();
		String variant = publication.eventSeq() + "-" + selection.key() + "-" + limit;
		int offset = cursor == null ? 0 : decodeOffset(cursor, variant, limit);
		if (publication.eventSeq() == 0) {
			if (cursor != null) {
				throw new AppException(AppErrors.INVALID_CURSOR);
			}
			return new ListPage(new DashboardIncidentListResponse(0, 0, true, List.of(), List.of(), null),
					variant + "-0");
		}

		var selected = selectItems(selection);
		if (cursor != null && offset >= selected.size()) {
			throw new AppException(AppErrors.INVALID_CURSOR);
		}
		int end = offset + Math.min(limit, selected.size() - offset);
		var items = selected.subList(offset, end);
		var sections = offset == 0 ? Arrays.stream(DashboardIncidentGroup.values())
				.map(group -> selection.includes(group) ? group.summarize(selected, items)
						: group.summarize(List.of(), List.of())).toList()
				: List.<DashboardIncidentListResponse.Group>of();
		var body = new DashboardIncidentListResponse(publication.eventSeq(), publication.revision(), false,
				sections, items, end < selected.size() ? encodeOffset(variant, end) : null);
		return new ListPage(body, variant + "-" + offset);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ListPage export(List<String> groups, boolean includeCompleted) {
		var selection = selection(groups, includeCompleted);
		var publication = repository.publication();
		boolean empty = publication.eventSeq() == 0;
		var body = new DashboardIncidentListResponse(publication.eventSeq(), publication.revision(), empty,
				List.of(), empty ? List.of() : selectItems(selection), null);
		return new ListPage(body, publication.eventSeq() + "-" + selection.key() + "-csv");
	}

	private List<Summary> selectItems(Selection selection) {
		return repository.list().stream()
				.filter(row -> selection.includeCompleted() || !row.summary().state().isCompleted()
						|| row.summary().state() == ItsmIncidentState.Research)
				.sorted(INCIDENT_ORDER)
				.map(DashboardIncidentService::toSummary)
				.filter(item -> selection.groups().isEmpty() || selection.groups().stream().anyMatch(g -> g.contains(item)))
				.toList();
	}

	private static Summary toSummary(IncidentRow row) {
		var inc = row.summary();
		return new Summary(inc.id(), inc.productName(), inc.assignedGroupName(), inc.state(), inc.priorityId(),
				inc.submitDate(), inc.deadline(), inc.completionDate(), DashboardIncidentGroup.memberships(inc));
	}

	private static Instant updatedAt(IncidentRow row) {
		if (row.sourceUpdatedAt() != null) {
			try {
				return Instant.parse(row.sourceUpdatedAt());
			} catch (DateTimeParseException ignored) {
				// updatedAt is not a required or validated field of the incoming snapshot.
			}
		}
		return row.changedAt() != null ? row.changedAt() : row.latestSourceDate();
	}

	private static Selection selection(List<String> groups, boolean includeCompleted) {
		var selected = EnumSet.noneOf(DashboardIncidentGroup.class);
		groups.forEach(key -> selected.add(DashboardIncidentGroup.fromKey(key)));
		return new Selection(selected, includeCompleted);
	}

	private static String encodeOffset(String variant, int offset) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("1:" + variant + ":" + offset).getBytes(StandardCharsets.US_ASCII));
	}

	private static int decodeOffset(String cursor, String variant, int limit) {
		try {
			if (cursor.length() > 96) {
				throw new IllegalArgumentException();
			}
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
			String prefix = "1:" + variant + ":";
			if (decoded.startsWith(prefix)) {
				int offset = Integer.parseInt(decoded.substring(prefix.length()));
				if (offset > 0 && offset % limit == 0 && encodeOffset(variant, offset).equals(cursor)) {
					return offset;
				}
			}
		} catch (IllegalArgumentException ignored) {
			// Malformed encoding or offset.
		}
		throw new AppException(AppErrors.INVALID_CURSOR);
	}

	private record Selection(EnumSet<DashboardIncidentGroup> groups, boolean includeCompleted) {
		boolean includes(DashboardIncidentGroup group) {
			return groups.isEmpty() || groups.contains(group);
		}

		String key() {
			int mask = groups.stream().mapToInt(group -> 1 << group.ordinal()).sum();
			return mask + "-" + (includeCompleted ? 1 : 0);
		}
	}

	public record ListPage(DashboardIncidentListResponse body, String etag) {
	}
}
