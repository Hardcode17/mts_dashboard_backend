package ru.hardcode.dashboard.service;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;
import ru.hardcode.dashboard.exception.*;
import ru.hardcode.dashboard.repository.*;
import ru.hardcode.dashboard.dto.DashboardIncidentCardResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class DashboardIncidentService {
    private static final TypeReference<Map<String,Object>> ROW = new TypeReference<>() {};
    private static final String COORDINATORS = "КЦ ИТ Координаторы Массовых Инцидентов";
    private static final List<String> CARD_FIELDS = List.of("id","header","description","productName","productMasterId",
            "service","serviceType","assignedGroupName","assignedEmployeeLogin","assignedEmployee","stateEnum","stateReason",
            "priorityId","submitDate","lastAssignmentToTeamDateTime","deadline","completionDate","solution");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    private final ItsmApiClient itsm;
    private final ItsmBaselineRepository domain;
    private final ItsmIncidentSnapshotParser parser;
    private final ItsmSnapshotMapper mapper;
    private final String template;
    private final boolean demo;
    public DashboardIncidentService(JdbcTemplate db, ObjectMapper json, Clock clock, ItsmApiClient itsm,
            ItsmBaselineRepository domain, ItsmIncidentSnapshotParser parser, ItsmSnapshotMapper mapper,
            @Value("${dashboard.itsm.link-template:http://127.0.0.1:8082/itsm_incident/{incidentId}/page}") String template,
            @Value("${dashboard.itsm.link-demo:true}") boolean demo) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.itsm = itsm;
        this.domain = domain;
        this.parser = parser;
        this.mapper = mapper;
        String placeholder = "{incidentId}";
        URI sample = URI.create(template.replace(placeholder,"KANPLACEHOLDER"));
        if (template.indexOf(placeholder)<0 || template.indexOf(placeholder)!=template.lastIndexOf(placeholder)
                || !List.of("http","https").contains(sample.getScheme()) || sample.getHost()==null
                || sample.getRawAuthority().contains("KANPLACEHOLDER") || sample.getUserInfo()!=null)
            throw new IllegalArgumentException("Invalid ITSM incident link template");
        this.template=template; this.demo=demo;
    }
    private record Published(Map<String,Object> row, String source, long eventSeq, long revision) {}
    private Published published(String id) {
        if (!id.matches("INC[0-9]{6,18}")) throw new AppException(AppErrors.INVALID_INCIDENT_ID);
        return db.query("""
                SELECT i.source_data, i.source_id, p.event_seq, p.revision
                FROM itsm_incident i CROSS JOIN dashboard_publication p
                WHERE i.id=? AND i.present_in_source AND i.source_data IS NOT NULL
                """,(rs,n)->new Published(json.readValue(rs.getString(1),ROW),rs.getString(2),rs.getLong(3),rs.getLong(4)),id)
                .stream().findFirst().orElseThrow(() -> db.queryForObject("SELECT revision,event_seq FROM dashboard_publication WHERE singleton",
                        (rs,n)->new PublishedIncidentNotFound(rs.getLong(1),rs.getLong(2))));
    }
    public DashboardIncidentCardResponse card(String id) {
        var publication=published(id);
        var row=publication.row();
        var card=new LinkedHashMap<String,Object>();
        for (String key:CARD_FIELDS) card.put(key,row.get(key));
        boolean active = List.of("Assigned","WorkInProgress").contains(row.get("stateEnum"));
        card.put("groupMemberships",memberships(row));
        var deadline = active && row.get("deadline") instanceof String date ? Instant.parse(date) : null;
        Instant now=clock.instant();
        card.put("remainingSeconds",deadline==null ? null : Duration.between(now,deadline).getSeconds());
        card.put("overdue",deadline!=null && deadline.isBefore(now));
        card.put("itsmUrl",template.replace("{incidentId}",UriUtils.encodePathSegment(id,java.nio.charset.StandardCharsets.UTF_8)));
        card.put("itsmLinkDemo",demo);
        card.put("revision",publication.revision());
        card.put("eventSeq",publication.eventSeq());
        return json.convertValue(card,DashboardIncidentCardResponse.class);
    }

    static List<Map<String,String>> memberships(Map<String,Object> row) {
        boolean active=List.of("Assigned","WorkInProgress").contains(row.get("stateEnum"));
        var groups=new ArrayList<Map<String,String>>();
        if ((active || "Research".equals(row.get("stateEnum"))) && COORDINATORS.equals(row.get("assignedGroupName")))
            groups.add(Map.of("group","coordinator","matchedRule","assignedToCoordinators"));
        if (active && Integer.valueOf(4).equals(row.get("priorityId"))) groups.add(Map.of("group","critical","matchedRule","priorityId = 4"));
        if (active && Integer.valueOf(3).equals(row.get("priorityId"))) groups.add(Map.of("group","high","matchedRule","priorityId = 3"));
        if ("Research".equals(row.get("stateEnum"))) groups.add(Map.of("group","research","matchedRule","stateEnum = Research"));
        return groups;
    }

    private void validatePatch(Map<String,Object> changes) {
        if (changes == null || changes.isEmpty() || !Set.of("assignedEmployeeLogin","assignedEmployee","stateEnum","assignedGroupName","solution").containsAll(changes.keySet()))
            throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
        for (var entry : changes.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null && (key.equals("assignedEmployeeLogin") || key.equals("assignedEmployee"))) continue;
            if (!(value instanceof String text) || text.isBlank()) throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
            int limit = switch(key) { case "assignedEmployeeLogin" -> 128; case "assignedGroupName", "assignedEmployee" -> 300; default -> Integer.MAX_VALUE; };
            if (text.codePointCount(0,text.length()) > limit) throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
        }
        if (changes.containsKey("stateEnum") && !Set.of("Assigned","WorkInProgress","Research","Resolved","Closed").contains(changes.get("stateEnum")))
            throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
        if ((changes.containsKey("assignedEmployee") && !changes.containsKey("assignedEmployeeLogin"))
                || (changes.containsKey("solution") && !Set.of("Research","Resolved","Closed").contains(changes.getOrDefault("stateEnum",""))))
            throw new AppException(AppErrors.INCIDENT_PATCH_INVALID);
    }

    @Transactional
    public DashboardIncidentCardResponse patch(String id,Map<String,Object> changes,long actor) {
        validatePatch(changes);
        domain.lockInstallation();
        var before=published(id);
        Object sysId=before.row().get("sysId");
        if (!(sysId instanceof String externalId) || externalId.isBlank()) throw new AppException(AppErrors.ITSM_BAD_RESPONSE);
        var response=itsm.patch(externalId,changes);
        if (!id.equals(response.get("id")) || !sysId.equals(response.get("sysId"))) throw new AppException(AppErrors.ITSM_BAD_RESPONSE);
        ru.hardcode.dashboard.dto.ItsmIncidentSnapshot normalized;
        try {
            normalized=parser.parse(json.writeValueAsBytes(List.of(response)));
            ItsmSnapshotPublicationService.validateDomainFields(normalized);
        } catch (AppException invalidResponse) {
            throw new AppException(AppErrors.ITSM_BAD_RESPONSE,invalidResponse);
        }
        var row=normalized.records().getFirst().raw();
        String updated=json.writeValueAsString(row);
        Long revision=db.query("""
                INSERT INTO itsm_incident_change(source_id,incident_id,source_version,change_revision,
                    change_kind,before_data,after_data,coordinator_id)
                SELECT source_id,id,last_seen_version,nextval('itsm_snapshot_change_revision'),'UPDATED',source_data,?::jsonb,?
                FROM itsm_incident WHERE id=? AND source_data IS DISTINCT FROM ?::jsonb RETURNING change_revision
                """,(rs,n)->rs.getLong(1),updated,actor,id,updated).stream().findFirst().orElse(null);
        if (revision!=null) {
            domain.upsertDomain(mapper.map(normalized),before.source(),Set.of(id),Set.of());
            db.update("UPDATE itsm_incident SET source_data=?::jsonb,last_change_revision=? WHERE id=?",updated,revision,id);
            db.update("UPDATE itsm_snapshot_source SET change_revision=? WHERE source_id=?",revision,before.source());
            db.update("UPDATE dashboard_publication SET revision=?,event_seq=event_seq+1 WHERE singleton",revision);
            boolean handedOff = !COORDINATORS.equals(row.get("assignedGroupName")) ||
                    (changes.containsKey("assignedEmployeeLogin") && row.get("assignedEmployeeLogin")!=null);
            if (handedOff) db.update("UPDATE coordination_tasks SET status='completed',finished_at=now(),updated_at=now() WHERE incident_id=? AND status='open'",id);
        }
        return card(id);
    }
}
