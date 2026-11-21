package ru.hardcode.dashboard.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.ItsmBatchMetadataResponse;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.ItsmBatchSqlRepository;

@Service
public class ItsmBatchQueryService {

	private final ItsmBatchSqlRepository sqlRepository;

	public ItsmBatchQueryService(ItsmBatchSqlRepository sqlRepository) {
		this.sqlRepository = sqlRepository;
	}

	@Transactional(readOnly = true)
	public ItsmBatchMetadataResponse getMetadata(UUID batchId) {
		return sqlRepository.findMetadata(batchId)
				.map(metadata -> new ItsmBatchMetadataResponse(
						metadata.batchId(),
						metadata.sourceId(),
						metadata.sourceVersion(),
						metadata.receivedAt(),
						metadata.status(),
						metadata.diagnostic()
				))
				.orElseThrow(() -> new AppException(
						AppErrors.ITSM_BATCH_NOT_FOUND,
						Map.of("batchId", batchId)
				));
	}
}
