package ru.hardcode.dashboard.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.dto.ItsmBaseline;
import ru.hardcode.dashboard.dto.ItsmIncidentSnapshot;
import ru.hardcode.dashboard.repository.ItsmBaselineRepository;

/**
 * Atomic baseline of validated ITSM snapshots (KAN-17) for the KAN-55 processor.
 *
 * <p>{@link #publish} always joins the caller's transaction: KAN-55 calls it inside the
 * worker claim transaction together with {@code markSucceeded}. Any error rolls the whole
 * snapshot back, so readers never see half a baseline.
 *
 * <p>After the first successful installation, this service returns the existing baseline.
 * KAN-55 owns all subsequent changes and revision rules.
 */
@Service
public class ItsmBaselineService {

	private static final Logger log = LoggerFactory.getLogger(ItsmBaselineService.class);

	private final ItsmIncidentSnapshotParser parser;
	private final ItsmSnapshotMapper mapper;
	private final ItsmBaselineRepository repository;
	private final Clock clock;

	public ItsmBaselineService(
			ItsmIncidentSnapshotParser parser,
			ItsmSnapshotMapper mapper,
			ItsmBaselineRepository repository,
			Clock clock
	) {
		this.parser = parser;
		this.mapper = mapper;
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * Installs the validated snapshot atomically in the caller's transaction.
	 *
	 * @param batchId       accepted batch that delivered the snapshot
	 * @param payload       exact stored payload bytes of the batch
	 * @param sourceId      ITSM source of the batch
	 * @param sourceVersion monotonic snapshot version of the source
	 * @return result with the visible revision and whether the baseline was installed
	 * @throws RuntimeException when the record cannot be attached to the catalog; the caller's
	 *         transaction must roll back and the previous baseline stays intact
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Publication publish(UUID batchId, byte[] payload, String sourceId, long sourceVersion) {
		if (batchId == null || payload == null || sourceId == null || sourceId.isBlank()) {
			throw new IllegalArgumentException("batchId, payload and sourceId are required");
		}
		ItsmIncidentSnapshot snapshot = parser.parse(payload);
		ItsmDomainMapping mapping = mapper.map(snapshot);
		String payloadSha256 = ItsmBatchIntakeService.sha256(payload);

		repository.lockInstallation();
		Optional<ItsmBaseline.ItsmBaselineSnapshot> installed = repository.findSnapshot();
		if (installed.isPresent()) {
			ItsmBaseline.ItsmBaselineSnapshot current = installed.get();
			log.debug("ITSM snapshot {} of {} does not change the installed baseline", sourceVersion, sourceId);
			return new Publication(current.sourceId(), current.sourceVersion(), current.sourceVersion(), false);
		}

		int incidents = repository.install(mapping, batchId, sourceId, sourceVersion, payloadSha256, Instant.now(clock));
		log.info("ITSM snapshot {} installed atomically: {} incidents", sourceVersion, incidents);
		return new Publication(sourceId, sourceVersion, sourceVersion, true);
	}

	/**
	 * Reads the installed baseline in one read-only transaction.
	 */
	@Transactional(readOnly = true)
	public ItsmBaseline readBaseline() {
		return repository.read();
	}

	/**
	 * Reads the installed snapshot metadata in one read-only transaction.
	 */
	@Transactional(readOnly = true)
	public Optional<ItsmBaseline.ItsmBaselineSnapshot> findSnapshot() {
		return repository.findSnapshot();
	}

	public record Publication(String sourceId, long sourceVersion, long revision, boolean installed) {
	}
}
