package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse;
import ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse.CoordinatorLoad;
import ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse.Counts;
import ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse.PriorityCount;
import ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse.TimelinePoint;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentRow;
import ru.hardcode.dashboard.repository.MonitoringTimelineRepository;
import ru.hardcode.dashboard.repository.MonitoringTimelineRepository.Observation;

@Service
public class DashboardMonitoringService {

	static final Duration HISTORY_WINDOW = Duration.ofHours(24);

	private final DashboardIncidentRepository incidents;
	private final MonitoringTimelineRepository timeline;
	private final Clock clock;

	public DashboardMonitoringService(DashboardIncidentRepository incidents,
			MonitoringTimelineRepository timeline, Clock clock) {
		this.incidents = incidents;
		this.timeline = timeline;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DashboardMonitoringSummaryResponse summary() {
		var rows = incidents.list();
		var current = current(rows);
		var publication = incidents.publication();
		Instant now = clock.instant();
		var history = timeline.between(now.minus(HISTORY_WINDOW), now).stream()
				.map(point -> new TimelinePoint(point.at(), point.critical(), point.high(), point.coordinators(),
						point.research()))
				.toList();
		return new DashboardMonitoringSummaryResponse(publication.updatedAt(), current.activeCount(),
				current.counts(), history, current.priorities(), current.coordinatorLoad());
	}

	/**
	 * Appends the observation in the caller's publication transaction. A failed publication rolls
	 * the point back with the published state; a restart keeps every committed point.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordPublishedSnapshot(UUID batchId, Instant observedAt) {
		var summaries = incidents.list().stream().map(IncidentRow::summary).toList();
		var counts = DashboardIncidentGroup.counts(summaries);
		timeline.append(batchId, new Observation(observedAt, counts.critical(), counts.high(),
				counts.coordinators(), counts.research()));
		timeline.deleteBefore(observedAt.minus(HISTORY_WINDOW));
	}

	private static Current current(List<IncidentRow> rows) {
		var summaries = rows.stream().map(IncidentRow::summary).toList();
		var active = rows.stream().filter(row -> !row.summary().state().isCompleted()).toList();
		var counts = DashboardIncidentGroup.counts(summaries);
		return new Current(active.size(), new Counts(counts.critical(), counts.high(), counts.coordinators(),
				counts.research()), priorities(active), coordinatorLoad(active));
	}

	private static List<PriorityCount> priorities(List<IncidentRow> active) {
		return active.stream()
				.collect(Collectors.groupingBy(row -> row.summary().priorityId(), TreeMap::new,
						Collectors.counting()))
				.entrySet().stream()
				.map(entry -> new PriorityCount(entry.getKey(), Math.toIntExact(entry.getValue())))
				.toList();
	}

	private static List<CoordinatorLoad> coordinatorLoad(List<IncidentRow> active) {
		Map<String, Long> load = active.stream()
				.filter(row -> DashboardIncidentGroup.COORDINATOR.matches(row.summary()))
				.filter(row -> row.assignedEmployee() != null && !row.assignedEmployee().isBlank())
				.collect(Collectors.groupingBy(row -> row.assignedEmployee().strip(), Collectors.counting()));
		return load.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
						.thenComparing(Map.Entry.comparingByKey()))
				.map(entry -> new CoordinatorLoad(entry.getKey(), Math.toIntExact(entry.getValue())))
				.toList();
	}

	private record Current(int activeCount, Counts counts, List<PriorityCount> priorities,
			List<CoordinatorLoad> coordinatorLoad) {
	}
}
