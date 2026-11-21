package ru.hardcode.dashboard.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.hardcode.dashboard.dto.SnapshotStatusResponse;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository.LatestBatch;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository.SuccessfulBatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SnapshotStatusServiceTest {

	private static final Instant RECEIVED_AT = Instant.now().minusSeconds(2);
	private static final Instant SUCCESS_AT = RECEIVED_AT.plusSeconds(1);

	@Mock
	private ItsmBatchSqlRepository sqlRepository;

	private SnapshotStatusService service;

	@BeforeEach
	void setUp() {
		service = new SnapshotStatusService(sqlRepository, Duration.ofSeconds(90));
	}

	@Test
	void emptyStoreIsStaleWithNullSuccess() {
		given(sqlRepository.findLatestBatch()).willReturn(Optional.empty());
		given(sqlRepository.findLatestSuccessfulBatch()).willReturn(Optional.empty());

		SnapshotStatusResponse status = service.status();

		assertThat(status.lastReceivedAt()).isNull();
		assertThat(status.lastSuccessAt()).isNull();
		assertThat(status.revision()).isNull();
		assertThat(status.stale()).isTrue();
	}

	@Test
	void acceptedBatchWithoutWorkerSuccessIsStale() {
		given(sqlRepository.findLatestBatch())
				.willReturn(Optional.of(new LatestBatch(RECEIVED_AT, ItsmBatchStatus.RECEIVED)));
		given(sqlRepository.findLatestSuccessfulBatch()).willReturn(Optional.empty());

		SnapshotStatusResponse status = service.status();

		assertThat(status.lastReceivedAt()).isEqualTo(RECEIVED_AT);
		assertThat(status.lastSuccessAt()).isNull();
		assertThat(status.revision()).isNull();
		assertThat(status.stale()).isTrue();
	}

	@Test
	void latestAcceptedBatchSucceededIsNotStale() {
		given(sqlRepository.findLatestBatch())
				.willReturn(Optional.of(new LatestBatch(RECEIVED_AT, ItsmBatchStatus.SUCCEEDED)));
		given(sqlRepository.findLatestSuccessfulBatch())
				.willReturn(Optional.of(new SuccessfulBatch(SUCCESS_AT, 41L)));

		SnapshotStatusResponse status = service.status();

		assertThat(status.lastSuccessAt()).isEqualTo(SUCCESS_AT);
		assertThat(status.revision()).isEqualTo(41L);
		assertThat(status.stale()).isFalse();
	}

	@Test
	void newerAcceptedBatchAfterSuccessIsStaleAgain() {
		given(sqlRepository.findLatestBatch())
				.willReturn(Optional.of(new LatestBatch(RECEIVED_AT, ItsmBatchStatus.RECEIVED)));
		given(sqlRepository.findLatestSuccessfulBatch())
				.willReturn(Optional.of(new SuccessfulBatch(SUCCESS_AT, 41L)));

		SnapshotStatusResponse status = service.status();

		assertThat(status.lastSuccessAt()).isEqualTo(SUCCESS_AT);
		assertThat(status.revision()).isEqualTo(41L);
		assertThat(status.stale()).isTrue();
	}

	@Test
	void stoppedSourceIsStaleEvenAfterSuccess() {
		Instant old = Instant.now().minusSeconds(120);
		given(sqlRepository.findLatestBatch())
				.willReturn(Optional.of(new LatestBatch(old, ItsmBatchStatus.SUCCEEDED)));
		given(sqlRepository.findLatestSuccessfulBatch())
				.willReturn(Optional.of(new SuccessfulBatch(old, null)));

		SnapshotStatusResponse status = service.status();

		assertThat(status.stale()).isTrue();
		assertThat(status.lastSuccessAt()).isEqualTo(old);
		assertThat(status.revision()).isNull();
	}
}
