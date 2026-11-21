package ru.hardcode.itsmmock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;

final class IncidentEdits {
    record Person(String sysId, String login, String displayName, String groupName) {}
    static class Failure extends RuntimeException {
        final HttpStatus status;
        Failure(HttpStatus status, String message) { super(message); this.status = status; }
    }
    static Map<String,Object> find(List<Map<String,Object>> rows, String id) {
        return rows.stream().filter(row -> id.equals(row.get("sysId")) || id.equals(row.get("id")))
                .findFirst().orElseThrow(() -> new Failure(HttpStatus.NOT_FOUND, "Инцидент не найден"));
    }
    static List<Person> users(List<Map<String,Object>> rows) {
        var people = new LinkedHashMap<String,Person>();
        for (int i = 0; i < IncidentGenerator.TEAMS.size(); i++) {
            var team = IncidentGenerator.TEAMS.get(i);
            put(people, "demo"+i, "Дежурный "+(i+1)+" (demo"+i+")", team);
            put(people, "engineer"+i, "Инженер "+(i+1), team);
        }
        for (var row : rows) put(people,row.get("assignedEmployeeLogin"),row.get("assignedEmployee"),row.get("assignedGroupName"));
        for (var row : rows) put(people,row.get("initiatorLogin"),row.get("initiatorName"),null);
        return List.copyOf(people.values());
    }
    private static void put(Map<String,Person> users,Object login,Object name,Object group) {
        if (login instanceof String key && !key.isBlank()) users.putIfAbsent(key,
                new Person(key,key,name == null ? key : name.toString(), (String) group));
    }
    private static final Set<String> STATUS = Set.of("stateEnum","stateId","state");
    private static final Set<String> EMPLOYEE = Set.of("assignedEmployeeLogin","userId","sysId");
    static boolean changesStatus(Map<String,Object> changes) { return !Collections.disjoint(STATUS,changes.keySet()); }
    private static Failure invalid() { return new Failure(HttpStatus.BAD_REQUEST,"Недопустимые поля изменения инцидента"); }
    static Map<String,Object> apply(Map<String,Object> original, Map<String,Object> changes, List<Person> people, Instant now) {
        if (changes.isEmpty()) throw invalid();
        var row = new LinkedHashMap<>(original);
        now = now.truncatedTo(ChronoUnit.MICROS);
        for (String field : changes.keySet()) {
            if (!(STATUS.contains(field) || EMPLOYEE.contains(field) || Set.of("assignedEmployee","solution","assignedGroupName").contains(field))) throw invalid();
        }
        if (changes.containsKey("assignedGroupName")) {
            if (!(changes.get("assignedGroupName") instanceof String group) || group.isBlank()) throw invalid();
            row.put("assignedGroupName",group.strip());
            if (!Objects.equals(original.get("assignedGroupName"),group.strip()))
                row.put("lastAssignmentToTeamDateTime",now.toString());
        }
        boolean assigned = false;
        for (String field : EMPLOYEE) {
            if (!changes.containsKey(field)) continue;
            Object login = changes.get(field);
            if (assigned && !Objects.equals(row.get("assignedEmployeeLogin"),login)) throw invalid();
            var person = login == null ? null : people.stream().filter(p -> p.login().equals(login)).findFirst().orElseThrow(IncidentEdits::invalid);
            row.put("assignedEmployeeLogin",login);
            row.put("assignedEmployee",person == null ? null : person.displayName());
            assigned = true;
        }
        if (changes.containsKey("assignedEmployee") && (!assigned || !Objects.equals(row.get("assignedEmployee"),changes.get("assignedEmployee")))) throw invalid();
        IncidentGenerator.State target = null;
        for (String field : STATUS) {
            if (!changes.containsKey(field)) continue;
            Object value = changes.get(field);
            var match = IncidentGenerator.STATES.stream().filter(s -> switch (field) {
                case "stateId" -> String.valueOf(s.id()).equals(String.valueOf(value));
                case "stateEnum" -> s.name().equals(value);
                default -> s.label().equals(value);
            }).findFirst().orElseThrow(IncidentEdits::invalid);
            if (target != null && target != match) throw invalid();
            target = match;
        }
        if (target != null) {
            if ("Closed".equals(original.get("stateEnum")) && !target.name().equals("Closed"))
                throw new Failure(HttpStatus.CONFLICT,"Закрытый инцидент нельзя вернуть в работу");
            if (!target.name().equals(original.get("stateEnum"))) IncidentGenerator.changeState(row,target,now);
        }
        if (changes.containsKey("solution")) {
            if (target == null || !target.finished() || !(changes.get("solution") instanceof String solution) || solution.isBlank()) throw invalid();
            row.put("solution",solution);
        }
        return row;
    }
}
