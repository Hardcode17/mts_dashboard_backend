package ru.hardcode.dashboard.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.hardcode.dashboard.dto.CreateIncidentNoteRequest;
import ru.hardcode.dashboard.dto.IncidentNoteResponse;
import ru.hardcode.dashboard.dto.IncidentNotesPage;
import ru.hardcode.dashboard.service.CoordinatorAccessService;
import ru.hardcode.dashboard.service.notes.IncidentNoteService;

/**
 * Shift notes in the chronology of one INC (KAN-43). The author is the authenticated local
 * coordinator; adding a note advances the shared {@code eventSeq} and never the published
 * snapshot revision. The history read pages by the same event sequence, so the next shift
 * sees every note with its author and observation time.
 */
@RestController
@RequestMapping("/api/dashboard")
public class IncidentNoteController {

	private final IncidentNoteService notes;
	private final CoordinatorAccessService access;

	public IncidentNoteController(IncidentNoteService notes, CoordinatorAccessService access) {
		this.notes = notes;
		this.access = access;
	}

	@PostMapping(path = "/incidents/{incidentId}/notes", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<IncidentNoteResponse> create(@PathVariable String incidentId,
			@RequestBody CreateIncidentNoteRequest request, Authentication auth) {
		// The author comes from the verified token; a claimed foreign author is rejected whole.
		var note = notes.create(incidentId, request, access.requireActiveUser(auth));
		return ResponseEntity.status(HttpStatus.CREATED).body(note);
	}

	/**
	 * The note journal of one INC. The cursor is the {@code lastEventSeq} of the previous page;
	 * parallel notes with one observation time get distinct positions in one cursor read.
	 */
	@GetMapping(path = "/incidents/{incidentId}/notes", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<IncidentNotesPage> history(@PathVariable String incidentId,
			@RequestParam(defaultValue = "0") long afterEventSeq, @RequestParam(defaultValue = "100") int limit) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(notes.history(incidentId, afterEventSeq, limit));
	}
}
