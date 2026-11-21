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
An open task survives subsequent changes, including leaving the external group; one partial
unique index prevents duplicate open tasks for an incident. No assignment is written to ITSM,
and no coordinator or timer is assigned. Task completion and claiming are outside this scope.
Before applying V12.1 to an existing database, resolve any pre-existing duplicate open tasks:
the migration deliberately fails rather than silently deleting or closing user data.

KAN-85 adds ownership; this shared queue only lists tasks without an owner.

The confirmed REST snapshot is processed in `ItsmSnapshotPublicationService.process`:
after `domain.upsertDomain`, it calls `CoordinationTaskService.observeEntries` in the same
transaction. The insertion method `CoordinationTaskRepository.insertOpen` is the shared
extension point for a future official transfer (KAN-32). The existing
`DashboardIncidentService.patch` has its own task-completion behaviour, which KAN-32 must
reconcile with its official transfer rules.
