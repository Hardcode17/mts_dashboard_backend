package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hardcode.dashboard.entity.ItsmIncidentState;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentRow;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.IncidentSummary;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository.Publication;
import ru.hardcode.dashboard.repository.MonitoringTimelineRepository;
import ru.hardcode.dashboard.repository.MonitoringTimelineRepository.Observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DashboardMonitoringServiceTest {

	private static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
	private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

	@Mock
	private DashboardIncidentRepository incidents;

	@Mock
	private MonitoringTimelineRepository timeline;

	private DashboardMonitoringService service;

	@BeforeEach
	void setUp() {
		service = new DashboardMonitoringService(incidents, timeline, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void derivesCurrentCountersFromTheDashboardClassificationAndReturnsTheBoundedTimeline() {
		given(incidents.list()).willReturn(rows());
		given(incidents.publication()).willReturn(new Publication(7, 42, Instant.parse("2026-11-21T11:59:00Z")));
		given(timeline.between(NOW.minus(DashboardMonitoringService.HISTORY_WINDOW), NOW))
				.willReturn(List.of(new Observation(Instant.parse("2026-11-21T11:30:00Z"), 2, 1, 3, 1)));

		var summary = service.summary();

		assertThat(summary.updatedAt()).isEqualTo(Instant.parse("2026-11-21T11:59:00Z"));
		assertThat(summary.activeCount()).isEqualTo(4);
		assertThat(summary.counts().critical()).isEqualTo(2);
		assertThat(summary.counts().high()).isEqualTo(1);
		assertThat(summary.counts().coordinators()).isEqualTo(3);
		assertThat(summary.counts().research()).isEqualTo(1);
		assertThat(summary.priorities()).extracting(point -> point.priorityId()).containsExactly(2, 3, 4);
		assertThat(summary.priorities()).extracting(point -> point.count()).containsExactly(1, 1, 2);
		assertThat(summary.coordinatorLoad()).extracting(point -> point.name()).containsExactly("Alice (alice)");
		assertThat(summary.coordinatorLoad()).extracting(point -> point.count()).containsExactly(2);
		assertThat(summary.timeline()).containsExactly(
				new ru.hardcode.dashboard.dto.DashboardMonitoringSummaryResponse.TimelinePoint(
						Instant.parse("2026-11-21T11:30:00Z"), 2, 1, 3, 1));
	}

	@Test
	void appendsTheSameClassificationInThePublicationTransaction() {
		given(incidents.list()).willReturn(rows());

		service.recordPublishedSnapshot(UUID.fromString("0b8f1a8e-3c1f-4a7d-9e8b-2b7f6a5c4d10"), NOW);

		var observation = ArgumentCaptor.forClass(Observation.class);
		verify(timeline).append(eq(UUID.fromString("0b8f1a8e-3c1f-4a7d-9e8b-2b7f6a5c4d10")), observation.capture());
		assertThat(observation.getValue()).isEqualTo(new Observation(NOW, 2, 1, 3, 1));
		verify(timeline).deleteBefore(NOW.minus(DashboardMonitoringService.HISTORY_WINDOW));
	}

	private static List<IncidentRow> rows() {
		return List.of(
				row("INC000001", ItsmIncidentState.Assigned, 4, COORDINATORS, "Alice (alice)"),
				row("INC000002", ItsmIncidentState.WorkInProgress, 4, COORDINATORS, "Alice (alice)"),
				row("INC000003", ItsmIncidentState.WorkInProgress, 3, "Other", "Bob (bob)"),
				row("INC000004", ItsmIncidentState.Research, 3, COORDINATORS, "Bob (bob)"),
				row("INC000005", ItsmIncidentState.Resolved, 4, COORDINATORS, "Alice (alice)"),
				row("INC000006", ItsmIncidentState.Assigned, 2, "Other", null));
	}

	private static IncidentRow row(String id, ItsmIncidentState state, int priority, String group,
			String assignedEmployee) {
		return new IncidentRow(new IncidentSummary(id, "Test product", group, state, priority,
				Instant.parse("2026-11-15T09:00:00Z"), null, null), null, null, null, null, null,
				assignedEmployee);
	}
}
