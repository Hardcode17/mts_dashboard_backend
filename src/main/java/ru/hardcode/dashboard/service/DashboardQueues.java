package ru.hardcode.dashboard.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.hardcode.dashboard.exception.AppErrors;
import ru.hardcode.dashboard.exception.AppException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class DashboardQueues {
    private record Group(String key,String title,String rule) {}
    private static final List<Group> GROUPS = List.of(
        new Group("critical","Критические","Активный инцидент с priorityId = 4"),
        new Group("high","Высокий приоритет","Активный инцидент с priorityId = 3"),
        new Group("coordinator","На координаторах","Назначен на координаторов"),
        new Group("research","Исследование ситуации","stateEnum = Research"));
    private static final TypeReference<Map<String,Object>> ROW = new TypeReference<>() {};
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public DashboardQueues(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }

    public record Page(long revision,long eventSeq,boolean beforeFirstSuccess,
            List<Map<String,Object>> groups,List<Map<String,Object>> items,String nextCursor) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(List<String> filter,boolean completed,String cursor,int limit,boolean export) {
        var keys=GROUPS.stream().map(Group::key).toList();
        for(String key:filter) if (!keys.contains(key)) throw new AppException(AppErrors.INVALID_GROUP,Map.of("group",key));
        if (limit < 1 || limit > 100) throw new AppException(AppErrors.INVALID_PAGE_SIZE);
        String after="";
        if (cursor!=null && !export) {
            try { after=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8); }
            catch(IllegalArgumentException e) { throw new AppException(AppErrors.INVALID_CURSOR); }
            if (!after.matches("INC[0-9]{6,18}")) throw new AppException(AppErrors.INVALID_CURSOR);
        }
        var version=db.queryForObject("SELECT revision,event_seq FROM dashboard_publication WHERE singleton",
                (rs,n)->new long[]{rs.getLong(1),rs.getLong(2)});
        if (version[0]==0) return new Page(0,0,true,List.of(),List.of(),null);
        var counts=new HashMap<String,Integer>();
        var byGroup=new HashMap<String,List<Map<String,Object>>>();
        var items=new ArrayList<Map<String,Object>>();
        final String previous=after;
        boolean[] more={false};
        db.query("""
                SELECT jsonb_build_object('id',id,'productName',source_data->'productName','submitDate',source_data->'submitDate',
                     'assignedEmployee',source_data->'assignedEmployee','assignedEmployeeLogin',source_data->'assignedEmployeeLogin',
                     'assignedGroupName',source_data->'assignedGroupName','stateEnum',state,'priorityId',priority_id,
                     'deadline',source_data->'deadline','completionDate',source_data->'completionDate')
                FROM itsm_incident WHERE present_in_source AND source_data IS NOT NULL ORDER BY id
                """,rs->{
            Map<String,Object> row=json.readValue(rs.getString(1),ROW);
            var memberships=DashboardIncidentService.memberships(row);
            row.put("groupMemberships",memberships);
            var matches=memberships.stream().map(m->m.get("group")).filter(k->filter.isEmpty() || filter.contains(k)).toList();
            if (matches.isEmpty() && (!completed || !filter.isEmpty())) return;
            for(var key:matches) counts.merge(key,1,Integer::sum);
            if (((String)row.get("id")).compareTo(previous)<=0) return;
            if (!export && items.size()==limit) { more[0]=true; return; }
            items.add(row);
            for(var key:matches) byGroup.computeIfAbsent(key,k->new ArrayList<>()).add(row);
        });
        var groups=new ArrayList<Map<String,Object>>();
        for(var group:GROUPS) {
            if (!filter.isEmpty() && !filter.contains(group.key())) continue;
            var details=new LinkedHashMap<String,Object>();
            details.put("key",group.key()); details.put("title",group.title()); details.put("rule",group.rule());
            details.put("active",!group.key().equals("research")); details.put("fixed",null);
            details.put("incidentCount",counts.getOrDefault(group.key(),0));
            details.put("incidents",byGroup.getOrDefault(group.key(),List.of())); groups.add(details);
        }
        String next=more[0] ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                items.getLast().get("id").toString().getBytes(StandardCharsets.UTF_8)) : null;
        return new Page(version[0],version[1],false,groups,items,next);
    }
    public String csv(Page page) {
        var output=new StringBuilder("incidentId,priorityId,stateEnum,deadline,groups\r\n");
        for(var row:page.items()) {
            var memberships=membershipsForCsv(row);
            for(var field:List.of("id","priorityId","stateEnum","deadline")) output.append(Objects.toString(row.get(field),"")).append(',');
            output.append(memberships).append("\r\n");
        }
        return output.toString();
    }
    private String membershipsForCsv(Map<String,Object> row) {
        return String.join(";", DashboardIncidentService.memberships(row).stream().map(m->m.get("group")).toList());
    }
}
