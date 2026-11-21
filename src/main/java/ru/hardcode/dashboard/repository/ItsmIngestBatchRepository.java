package ru.hardcode.dashboard.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.hardcode.dashboard.entity.ItsmIngestBatch;

public interface ItsmIngestBatchRepository extends JpaRepository<ItsmIngestBatch, UUID> {

	Optional<ItsmIngestBatch> findBySourceIdAndSourceVersion(String sourceId, long sourceVersion);
}
