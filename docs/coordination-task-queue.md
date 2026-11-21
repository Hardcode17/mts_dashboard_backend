# Coordination task queue (KAN-24)

`GET /api/dashboard/coordination-tasks` requires a valid human Bearer token and an active
local coordinator, using the existing dashboard security chain. It returns a JSON array,
ordered by creation time and task ID, e.g.

```json
[{"taskId":1,"incidentId":"INC0001","createdAt":"2026-11-21T12:00:00Z"}]
```

The configurable `coordination.external-group` is an exact, case-sensitive ITSM
`assignedGroupName`; it defaults to `КЦ ИТ Координаторы Массовых Инцидентов`.
An empty value disables creation. The first successfully published snapshot of each source
is baseline and creates no tasks, as for shift events. After baseline, a newly observed or
returned incident on that group, or a transition from another group, creates an open task.
A missing row, an unchanged assignment, or a rejected/skipped package does not.
One partial unique index prevents duplicate open tasks for an incident. Queue entry does not
write an assignment to ITSM or automatically assign a coordinator.
Before applying V12.1 to an existing database, resolve any pre-existing duplicate open tasks:
the migration deliberately fails rather than silently deleting or closing user data.

KAN-85 adds ownership; this shared queue only lists tasks without an owner.

The confirmed REST snapshot is processed in `ItsmSnapshotPublicationService.process`:
after `domain.upsertDomain`, it calls `CoordinationTaskService.observeEntries` in the same
transaction. The insertion method `CoordinationTaskRepository.insertOpen` is the shared
entry point for creating an open task.

# Подтверждённая передача (KAN-32)

Открытая задача закрывается только при успешно применённом полном REST-снимке того же источника,
если официальная группа изменилась с настроенной `coordination.external-group` на другую непустую
или изменился непустой официальный исполнитель. PATCH сам по себе задачу не закрывает:
официальное назначение сравнивается с отдельной проекцией предыдущего REST-наблюдения.
Отсутствие строки и изменения текста или статуса не считаются передачей.
В задаче сохраняются тип передачи, источник, версия, время наблюдения и закрытия, владелец
и время принятия. Следующая задача остаётся в общей очереди до ручного claim;
автоматического переназначения нет. Этот lifecycle не выполняет записи в ITSM.
