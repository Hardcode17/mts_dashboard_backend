package ru.hardcode.dashboard.service.investigation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.hardcode.dashboard.configuration.evidence.McpEvidenceDemoProperties;
import ru.hardcode.dashboard.dto.AiInvestigationRunResponse;
import ru.hardcode.dashboard.dto.AiInvestigationRunResponse.Fact;
import ru.hardcode.dashboard.dto.AiInvestigationRunResponse.UnavailableSource;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import ru.hardcode.dashboard.dto.McpEvidenceIncidentContextResponse;
import ru.hardcode.dashboard.dto.McpEvidenceObservation;
import ru.hardcode.dashboard.dto.McpEvidenceRef;
import ru.hardcode.dashboard.dto.McpEvidenceUnavailableSource;
import ru.hardcode.dashboard.entity.AiInvestigationFact;
import ru.hardcode.dashboard.entity.AiInvestigationRun;
import ru.hardcode.dashboard.entity.AiInvestigationRunStatus;
import ru.hardcode.dashboard.entity.AiInvestigationUnavailableSource;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import ru.hardcode.dashboard.repository.AiInvestigationRunRepository;
import ru.hardcode.dashboard.repository.DashboardIncidentRepository;
import ru.hardcode.dashboard.service.DashboardIncidentService;
import ru.hardcode.dashboard.service.evidence.McpEvidenceService;

/**
 * Async launch and read of an AI investigation of one incident (KAN-40). A launch captures
 * the published incident card, the evidence facts and the explicit gaps of one bounded
 * window, binds everything to the published {@code revision} and stores it as a run; only
 * the model answer is produced asynchronously. A newer revision marks an old run stale at
 * read time without touching its stored result, so the coordinator always knows which
 * state of the incident an answer describes.
 */
@Service
public class AiInvestigationService {

	private static final Logger log = LoggerFactory.getLogger(AiInvestigationService.class);

	private static final Pattern INCIDENT_ID = Pattern.compile("INC[0-9]{6,18}");

	private final DashboardIncidentService incidents;
	private final DashboardIncidentRepository dashboard;
	private final ObjectProvider<McpEvidenceService> evidence;
	private final ObjectProvider<McpEvidenceDemoProperties> evidenceMapping;
	private final AiInvestigationModel model;
	private final AiInvestigationRunRepository runs;
	private final Clock clock;
	private final ExecutorService investigations;
	private final TransactionTemplate transactionTemplate;

	public AiInvestigationService(
			DashboardIncidentService incidents,
			DashboardIncidentRepository dashboard,
			ObjectProvider<McpEvidenceService> evidence,
			ObjectProvider<McpEvidenceDemoProperties> evidenceMapping,
			AiInvestigationModel model,
			AiInvestigationRunRepository runs,
			Clock clock,
			@Qualifier("aiInvestigationExecutor") ExecutorService investigations,
			PlatformTransactionManager transactions
	) {
		this.incidents = incidents;
		this.dashboard = dashboard;
		this.evidence = evidence;
		this.evidenceMapping = evidenceMapping;
		this.model = model;
		this.runs = runs;
		this.clock = clock;
		this.investigations = investigations;
		this.transactionTemplate = new TransactionTemplate(transactions);
		this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Launches one investigation of the incident. The card, the evidence facts and the gaps
	 * are captured at launch, so the run always describes one published revision; only the
	 * model answer is still pending when the launch returns.
	 */
	public AiInvestigationRunResponse launch(String incidentId) {
		requireIncidentId(incidentId);
		DashboardIncidentCardResponse card = incidents.card(incidentId);
		if (!model.isConfigured()) {
			throw new AppException(AppErrors.AI_MODEL_NOT_CONFIGURED);
		}
		McpEvidenceService evidenceService = requireEvidence();
		McpEvidenceDemoProperties mapping = evidenceMapping.getIfAvailable();
		Window window = launchWindow(mapping);
		McpEvidenceIncidentContextResponse context = evidenceService.incidentContext(
				mapping.scenarioId(), incidentId, mapping.scenarioRunId(),
				window.from().toString(), window.to().toString());
		UUID runId = UUID.randomUUID();
		AiInvestigationRun run = new AiInvestigationRun(runId, incidentId, card.revision(),
				AiInvestigationRunStatus.RUNNING, window.from(), window.to(),
				AiInvestigationPrompt.modelPrompt(card, context), null, null, null, null,
				clock.instant(), null);
		List<AiInvestigationFact> facts = facts(runId, context);
		List<AiInvestigationUnavailableSource> unavailable = unavailable(runId, context);
		transactionTemplate.executeWithoutResult(status -> runs.create(run, facts, unavailable));
		submit(runId);
		// The row is re-read after the submit: a queue refusal, or a model answer that won
		// the race against this response, is reported as stored, never as a stale snapshot.
		AiInvestigationRun stored = runs.find(runId).orElse(run);
		return response(stored, facts, unavailable,
				stored.revision() < dashboard.publication().revision());
	}

	/**
	 * Reads one run of the incident. The run stays readable in every status; {@code stale}
	 * compares the bound revision with the publication current at read time, so a run
	 * completed before a newer revision is marked as describing an older incident state.
	 */
	public AiInvestigationRunResponse run(String incidentId, UUID runId) {
		requireIncidentId(incidentId);
		AiInvestigationRun stored = runs.find(runId)
				.filter(found -> incidentId.equals(found.incidentId()))
				.orElseThrow(() -> new AppException(AppErrors.AI_RUN_NOT_FOUND,
						Map.of("runId", String.valueOf(runId), "incidentId", incidentId)));
		boolean stale = stored.revision() < dashboard.publication().revision();
		return response(stored, runs.facts(runId), runs.unavailable(runId), stale);
	}

	/**
	 * The model answer of one run. A run that is no longer running, or whose terminal
	 * status was already written, is left untouched; the guarded repository update makes
	 * every late or duplicate answer harmless.
	 */
	void process(UUID runId) {
		AiInvestigationRun stored = runs.find(runId).orElse(null);
		if (stored == null || stored.status() != AiInvestigationRunStatus.RUNNING) {
			return;
		}
		try {
			AiInvestigationModel.AiInvestigationReply reply = model.analyze(stored.modelPrompt());
			if (!runs.complete(runId, reply.hypothesis(), reply.nextStep(), clock.instant())) {
				log.debug("Investigation run {} was already terminal when the model answered", runId);
			}
		} catch (AppException failure) {
			fail(runId, failure);
		} catch (RuntimeException failure) {
			fail(runId, new AppException(AppErrors.AI_MODEL_UNAVAILABLE,
					Map.of("reason", "the model call failed (" + failure.getClass().getSimpleName() + ")"),
					failure));
		}
	}

	private void submit(UUID runId) {
		try {
			investigations.execute(() -> process(runId));
		} catch (RejectedExecutionException overflow) {
			fail(runId, new AppException(AppErrors.AI_MODEL_UNAVAILABLE,
					Map.of("reason", "the investigation queue is full")));
		}
	}

	private void fail(UUID runId, AppException failure) {
		if (!runs.fail(runId, failure.getAppError().getCode(), failure.getMessage(), clock.instant())) {
			log.debug("Investigation run {} was already terminal when it failed", runId);
		}
	}

	/**
	 * The evidence stack is required: an investigation never runs on invented data. Without
	 * the explicit demo mapping the launch is refused before anything is gathered.
	 */
	private McpEvidenceService requireEvidence() {
		if (evidence.getIfAvailable() == null || evidenceMapping.getIfAvailable() == null) {
			throw new AppException(AppErrors.AI_EVIDENCE_NOT_MAPPED);
		}
		return evidence.getObject();
	}

	/**
	 * The latest slice of the observation window the launch may ask for: at most one
	 * {@code max-window} long, ending at the current instant (or at the window end), never
	 * outside the mapped observation window. The evidence service validates the slice again.
	 */
	private Window launchWindow(McpEvidenceDemoProperties mapping) {
		Instant now = clock.instant();
		McpEvidenceDemoProperties.Window observation = mapping.observationWindow(now);
		if (observation == null || !now.isAfter(observation.from())) {
			throw new AppException(AppErrors.MCP_EVIDENCE_WINDOW_OUTSIDE_DEMO);
		}
		Instant to = now.isAfter(observation.to()) ? observation.to() : now;
		Instant from = to.minus(mapping.maxWindow());
		if (from.isBefore(observation.from())) {
			from = observation.from();
		}
		if (!from.isBefore(to)) {
			throw new AppException(AppErrors.MCP_EVIDENCE_WINDOW_OUTSIDE_DEMO);
		}
		return new Window(from, to);
	}

	private static void requireIncidentId(String incidentId) {
		if (incidentId == null || !INCIDENT_ID.matcher(incidentId).matches()) {
			throw new AppException(AppErrors.INVALID_INCIDENT_ID);
		}
	}

	private static List<AiInvestigationFact> facts(UUID runId, McpEvidenceIncidentContextResponse context) {
		List<AiInvestigationFact> rows = new ArrayList<>();
		int index = 0;
		for (McpEvidenceRef ref : context.evidence()) {
			McpEvidenceObservation observation = ref.observation();
			rows.add(new AiInvestigationFact(runId, index++, ref.evidenceId(), ref.source(), ref.serviceKey(),
					ref.observedAt(), observation.message(), observation.messageTruncated(),
					observation.level(), observation.assignedCommand(), observation.recordCount(),
					observation.value(), observation.serviceState()));
		}
		return rows;
	}

	private static List<AiInvestigationUnavailableSource> unavailable(
			UUID runId, McpEvidenceIncidentContextResponse context) {
		List<AiInvestigationUnavailableSource> rows = new ArrayList<>();
		int index = 0;
		for (McpEvidenceUnavailableSource source : context.unavailable()) {
			rows.add(new AiInvestigationUnavailableSource(runId, index++, source.source(),
					source.serviceKey(), source.code(), source.reason()));
		}
		return rows;
	}

	private static AiInvestigationRunResponse response(
			AiInvestigationRun run,
			List<AiInvestigationFact> facts,
			List<AiInvestigationUnavailableSource> unavailable,
			boolean stale
	) {
		return new AiInvestigationRunResponse(run.runId(), run.incidentId(), run.status().code(), run.revision(),
				stale, run.windowFrom(), run.windowTo(), run.hypothesis(), run.nextStep(),
				facts.stream().map(AiInvestigationService::fact).toList(),
				unavailable.stream().map(AiInvestigationService::gap).toList(),
				run.errorCode(), run.errorMessage(), run.createdAt(), run.finishedAt());
	}

	private static Fact fact(AiInvestigationFact fact) {
		return new Fact(fact.evidenceId(), fact.source().name(), fact.serviceKey(), fact.observedAt(),
				fact.message(), fact.messageTruncated(), fact.level(), fact.assignedCommand(),
				fact.recordCount(), fact.value(), fact.serviceState());
	}

	private static UnavailableSource gap(AiInvestigationUnavailableSource source) {
		return new UnavailableSource(source.source().name(), source.serviceKey(), source.code(), source.reason());
	}

	private record Window(Instant from, Instant to) {
	}
}
