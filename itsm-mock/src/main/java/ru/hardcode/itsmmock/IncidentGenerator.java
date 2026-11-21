package ru.hardcode.itsmmock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

final class IncidentGenerator {
    static final List<String> TEAMS = List.of(
            "КЦ ИТ Координаторы Массовых Инцидентов", "Дежурные платёжных сервисов",
            "Команда хранения данных", "Команда клиентских приложений");
    private static final List<String> PRIORITIES = List.of("Низкий", "Средний", "Высокий", "Наивысший");
    private static final List<String> PRODUCTS = List.of("Тестовый магазин", "Учебный кабинет", "Демо-доставка");
    private static final List<String> SERVICES = List.of("Оплата", "Авторизация", "Отслеживание");
    private static final List<String> SCENARIOS = List.of("Подтверждение заказа", "Вход", "Проверка статуса");
    private static final List<String> SYMPTOMS = List.of("Запросы завершаются с задержкой",
            "Часть запросов отклоняется", "Показатель успешности снизился");
    static final List<State> STATES = List.of(
            new State("Assigned", 0, "Назначен", false),
            new State("WorkInProgress", 2, "Выполняется", false),
            new State("Resolved", 7, "Завершен", true),
            new State("Closed", 10, "Закрыт", true),
            new State("Research", 20, "Исследование ситуации", true));

    private final long seed;
    private final int size;

    IncidentGenerator(long seed, int size) {
        this.seed = seed;
        this.size = size;
    }

    List<Map<String, Object>> next(List<Map<String, Object>> previous, long version, Instant now) {
        now = now.truncatedTo(ChronoUnit.MICROS);
        if (previous.isEmpty()) {
            var initial = new ArrayList<Map<String, Object>>(size);
            for (int index = 0; index < size; index++) {
                initial.add(create(index, now));
            }
            return initial;
        }
        // Leave a full unchanged observation every third emission.
        if (version % 3 == 0) {
            return previous;
        }
        var snapshot = new ArrayList<>(previous);
        var random = new SplittableRandom(seed ^ version);
        int index = random.nextInt(snapshot.size());
        var incident = new LinkedHashMap<>(snapshot.get(index));
        State current = STATES.stream().filter(s -> s.name().equals(incident.get("stateEnum")))
                .findFirst().orElseThrow();
        State next = STATES.get((STATES.indexOf(current) + 1) % STATES.size());
        changeState(incident, next, now);
        snapshot.set(index, incident);
        return snapshot;
    }

    private Map<String, Object> create(int index, Instant now) {
        var random = new SplittableRandom(seed + index);
        int product = random.nextInt(PRODUCTS.size());
        int priority = (3 + index) % PRIORITIES.size();
        int team = Math.floorMod(random.nextInt(TEAMS.size()) + index, TEAMS.size());
        Instant started = now.minus(5, ChronoUnit.MINUTES);
        String symptom = SYMPTOMS.get(random.nextInt(SYMPTOMS.size()));
        String suffix = suffix(index);
        var item = new LinkedHashMap<String, Object>();
        item.put("sysId", suffix);
        item.put("id", "INC" + suffix);
        // Four stable graph cases in the first slots: a parent, its two children and an
        // unrelated incident. parentId is the external id, never the sysId.
        if (index == 1 || index == 2) {
            item.put("parentId", "INC" + suffix(0));
        }
        item.put("stateReason", "Нет");
        item.put("stateReasonId", "1");
        item.put("submitDate", started.toString());
        item.put("deadline", index % 5 == 0 ? null : now.plus(90, ChronoUnit.MINUTES).toString());
        item.put("lastAssignmentToTeamDateTime", started.toString());
        item.put("header", SERVICES.get(product) + " · " + symptom);
        item.put("description", symptom + " в сценарии «" + SCENARIOS.get(product) + "».");
        item.put("shortDescription", symptom);
        item.put("assignedGroupName", TEAMS.get(team));
        item.put("assignedEmployee", "Дежурный " + (team + 1) + " (demo" + team + ")");
        item.put("assignedEmployeeLogin", "demo" + team);
        item.put("initiatorName", "Тестовый мониторинг");
        item.put("initiatorLogin", "monitor-demo");
        item.put("isEffect", false);
        item.put("isReason", false);
        item.put("isDouble", false);
        item.put("isMaster", false);
        item.put("incType", "МИ");
        item.put("serviceTypeId", 30);
        item.put("serviceType", "Технологический");
        item.put("priorityId", priority + 1);
        item.put("priority", PRIORITIES.get(priority));
        item.put("urgencyId", 1);
        item.put("urgency", "Низкая");
        item.put("impactId", 2);
        item.put("impact", "Среднее");
        item.put("waitingExitDate", "0001-01-01T00:00:00");
        item.put("isRootIncident", true);
        item.put("productName", PRODUCTS.get(product));
        item.put("productId", "700" + product);
        item.put("productMasterId", "BI_700" + product);
        item.put("productUrl", null);
        item.put("service", "[" + PRODUCTS.get(product) + "] " + SERVICES.get(product));
        item.put("serviceId", "800" + product);
        item.put("scenarioName", SCENARIOS.get(product));
        item.put("isNeedUp", index % 2 == 0 ? null : false);
        item.put("eliminationType", null);
        item.put("eliminationTypeId", null);
        item.put("receivingChannel", "Мониторинг");
        changeState(item, STATES.get(index % STATES.size()), now);
        return item;
    }

    static void changeState(Map<String, Object> incident, State state, Instant now) {
        incident.put("stateEnum", state.name());
        incident.put("stateId", state.id());
        incident.put("state", state.label());
        if (state.finished() && incident.get("completionDate") == null) {
            incident.put("completionDate", now.toString());
        } else if (!state.finished()) {
            incident.put("completionDate", null);
        }
        incident.put("solution", state.finished() ? "Восстановление подтверждено на стенде." : "");
    }

    private static String suffix(int index) {
        return String.format(java.util.Locale.ROOT, "%012d", 700_000_000L + index);
    }

    record State(String name, int id, String label, boolean finished) {}
}
