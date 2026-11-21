package ru.hardcode.itsmmock;

import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
final class IncidentApi {
    private final SnapshotSender sender;
    IncidentApi(SnapshotSender sender) { this.sender = sender; }

    @GetMapping("/user")
    List<IncidentEdits.Person> users() { return IncidentEdits.users(sender.rows()); }
    @GetMapping("/itsm_incident")
    List<Map<String,Object>> incidents() { return sender.rows(); }
    @GetMapping("/itsm_incident/{id}")
    Map<String,Object> incident(@PathVariable String id) { return IncidentEdits.find(sender.rows(),id); }
    @PatchMapping("/itsm_incident/{id}")
    Map<String,Object> patch(@PathVariable String id,@RequestBody Map<String,Object> changes) throws IOException {
        return sender.patch(id,changes);
    }
    @ExceptionHandler(IncidentEdits.Failure.class)
    ResponseEntity<?> failure(IncidentEdits.Failure ex) {
        return ResponseEntity.status(ex.status).body(Map.of("code",ex.status.name(),"message",ex.getMessage()));
    }
    @GetMapping(value="/itsm_incident/{id}/page",produces="text/html;charset=UTF-8")
    ResponseEntity<String> page(@PathVariable String id) {
        Map<String,Object> row;
        try { row = incident(id); }
        catch (IncidentEdits.Failure ex) { return ResponseEntity.status(ex.status).body(html("Инцидент не найден","")); }
        var body = new StringBuilder("<article><strong>"+value(row,"state")+"</strong> <strong>P"+value(row,"priorityId")+"</strong><h1>"+value(row,"header")+"</h1><p>"+value(row,"description")+"</p><p>"+value(row,"productName")+" · "+value(row,"service")+" · "+value(row,"scenarioName")+"</p></article>");
        section(body,row,"Атрибуты","Статус","state","Причина","stateReason","Приоритет","priority","Влияние","impact","Срочность","urgency","Тип сервиса","serviceType","Канал","receivingChannel");
        section(body,row,"Назначение","Группа","assignedGroupName","Ответственный","assignedEmployee","Дата назначения","lastAssignmentToTeamDateTime");
        section(body,row,"Сроки","Зарегистрирован","submitDate","Дедлайн","deadline","Завершён","completionDate","Решение","solution");
        return ResponseEntity.ok(html(value(row,"id"),body.toString()));
    }
    private static String value(Map<String,Object> row,String key) {
        Object v=row.get(key);
        if (v instanceof String date && Set.of("deadline","completionDate","submitDate","lastAssignmentToTeamDateTime").contains(key)) {
            if (date.startsWith("0001-01-01T00:00:00")) v = null;
            else {
                try { v = java.time.Instant.parse(date).toString(); }
                catch (java.time.format.DateTimeParseException noZone) {
                    try { v = java.time.LocalDateTime.parse(date.replace(' ','T')).toInstant(java.time.ZoneOffset.UTC).toString(); }
                    catch (java.time.format.DateTimeParseException invalid) { v = null; }
                }
            }
        }
        return v==null || v.toString().isBlank() ? (key.equals("deadline") ? "Срок не указан" : "—") : HtmlUtils.htmlEscape(v.toString());
    }
    private static void section(StringBuilder body,Map<String,Object> row,String title,String... fields) {
        body.append("<article><h2>").append(title).append("</h2><dl>");
        for (int i=0;i<fields.length;i+=2) {
            if (row.get("completionDate")==null && List.of("completionDate","solution").contains(fields[i+1])) continue;
            body.append("<dt>").append(fields[i]).append("</dt><dd>").append(value(row,fields[i+1])).append("</dd>");
        }
        body.append("</dl></article>");
    }
    private static String html(String title,String content) {
        return """
                <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s · Smart ITSM</title><style>
                body{margin:0;background:#f3f6fa;color:#1c3041;font:16px system-ui}header{background:#172b45;color:white;padding:1rem 5vw}
                main{max-width:860px;margin:auto;padding:1rem}article{background:white;padding:1.3rem 2rem;margin:1rem 0;border-radius:12px;box-shadow:0 2px 9px #18364b12}
                dl{display:grid;grid-template-columns:minmax(8em,1fr) 2fr;gap:.5rem}dd{margin:0;overflow-wrap:anywhere}dt{color:#536778}
                strong{background:#d9eefa;color:#15486a;border-radius:6px;padding:.25rem .6rem}
                </style><header>Smart ITSM · %s</header><main>%s</main></html>
                """.formatted(title,title,content);
    }
}
