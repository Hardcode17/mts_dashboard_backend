package ru.hardcode.dashboard.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.SnapshotStatusResponse;
import ru.hardcode.dashboard.entity.ItsmBatchStatus;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository.LatestBatch;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository.SuccessfulBatch;

@Service
public class SnapshotStatusService {

	private final ItsmBatchSqlRepository sqlRepository;
	private final Duration staleAfter;

	public SnapshotStatusService(
			ItsmBatchSqlRepository sqlRepository,
			@Value("${itsm.intake.stale-after:PT90S}") Duration staleAfter
	) {
		if (staleAfter.isNegative() || staleAfter.isZero()) {
			throw new IllegalArgumentException("itsm.intake.stale-after must be positive");
		}
		this.sqlRepository = sqlRepository;
		this.staleAfter = staleAfter;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SnapshotStatusResponse status() {
		Optional<LatestBatch> latest = sqlRepository.findLatestBatch();
		Optional<SuccessfulBatch> success = sqlRepository.findLatestSuccessfulBatch();

		Instant lastReceivedAt = latest.map(LatestBatch::receivedAt).orElse(null);
		Instant lastSuccessAt = success.map(SuccessfulBatch::processedAt).orElse(null);
		Long revision = success.map(SuccessfulBatch::revision).orElse(null);
		boolean stale = latest.isEmpty()
				|| latest.get().status() != ItsmBatchStatus.SUCCEEDED
				|| lastSuccessAt == null
				|| lastReceivedAt.isBefore(Instant.now().minus(staleAfter));

		return new SnapshotStatusResponse(lastReceivedAt, lastSuccessAt, revision, stale);
	}
}
