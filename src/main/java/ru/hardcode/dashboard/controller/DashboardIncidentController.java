package ru.hardcode.dashboard.controller;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.hardcode.dashboard.service.*;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardIncidentController {
    private final DashboardIncidentService incidents;
    private final ItsmApiClient itsm;
    private final CoordinatorAccessService access;
    private final DashboardQueues queues;
    public DashboardIncidentController(DashboardIncidentService incidents, ItsmApiClient itsm, CoordinatorAccessService access, DashboardQueues queues) {
        this.incidents=incidents; this.itsm=itsm; this.access=access; this.queues=queues;
    }
    @GetMapping("/incidents")
    public org.springframework.http.ResponseEntity<?> list(
            @RequestParam(defaultValue="") List<String> group, @RequestParam(defaultValue="false") boolean includeCompleted,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="50") int limit,
            @RequestHeader(value="Accept",defaultValue="application/json") String accept) {
        boolean csv = accept.startsWith("text/csv");
        var page = queues.list(group,includeCompleted,cursor,limit,csv);
        var response=org.springframework.http.ResponseEntity.ok().eTag(Long.toString(page.eventSeq()));
        return csv ? response.contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8")).body(queues.csv(page))
                : response.body(page);
    }
    @GetMapping("/users")
    public List<Map<String,Object>> users() { return itsm.users(); }
    @GetMapping("/incidents/{incidentId}")
    public DashboardIncidentCardResponse card(@PathVariable String incidentId) { return incidents.card(incidentId); }
    @PatchMapping("/incidents/{incidentId}")
    public DashboardIncidentCardResponse patch(@PathVariable String incidentId, @RequestBody Map<String,Object> changes, Authentication auth) {
        return incidents.patch(incidentId,changes,access.requireActiveUser(auth).id());
    }
}
