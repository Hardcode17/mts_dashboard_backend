# mts_dashboard_backend

## Тестовый деплой на Raspberry Pi

Пуш в main запускает Deploy backend на runner организации Hardcode17 с метками
self-hosted, Linux, ARM64, pi-web. Второй runner не нужен.

Compose-проект mts-dashboard-backend запускает Java 21 backend и PostgreSQL 17.
Порт приложения на Pi: 8081. PostgreSQL не публикует порт наружу, данные сохраняются
в томе postgres-data. Имя БД и пользователь: dashboard.

Workflow автоматически создаёт /home/gha-runner/.config/mts-dashboard-backend/runtime.env
с правами 600 и случайным POSTGRES_PASSWORD. Существующий пароль и остальные
настройки сохраняются. Пароль не печатается и не хранится в Git. Заполнять
параметры подключения вручную не нужно.

Профиль demo отключает внешние AI-сервисы. Flyway создаёт deployment_sample с
тремя искусственными записями из db/demo. Повторные деплои сохраняют данные.
Workflow ждёт HTTP 200 от /health и проверяет количество записей через SQL.
Только GET /health открыт без авторизации, остальные пути требуют входа.

После успешного Actions проверьте из PowerShell:

```powershell
curl.exe --noproxy "*" -i http://100.77.2.49:8081/health
```

На Pi:

```bash
docker logs --tail 100 mts-dashboard-backend-backend-1
docker exec mts-dashboard-backend-postgres-1 psql -U dashboard -d dashboard -c 'TABLE deployment_sample;'
```

Не удаляйте том и не меняйте пароль в runtime.env без согласованной смены пароля
в PostgreSQL. Резервируйте том и runtime.env. Для перехода на рабочую БД используйте
отдельную базу и план миграции: demo-миграция записана в историю Flyway тестовой БД.

Repository Variables: BACKEND_PORT (по умолчанию 8081), BACKEND_ENV_FILE (путь выше).
При ручном использовании Compose задайте COMPOSE_PROJECT_NAME=mts-dashboard-backend,
BACKEND_ENV_FILE и используйте docker compose --env-file "$BACKEND_ENV_FILE" из корня.

Dockerfile выполняет bootJar без тестов. Локальные тесты: ./gradlew test или
./gradlew.bat test; нужны Java 21, доступ к зависимостям и Docker для Testcontainers.
При ошибке workflow выводит логи обоих сервисов. Автоматического отката нет.

## Тестовый источник инцидентов ITCS

`itsm-mock` отправляет полный синтетический снимок раз в 30 секунд. Включается
профилем Compose после подготовки приёмника [KAN-27](https://sokolenkonikita12ru.atlassian.net/browse/KAN-27)
и машинного клиента Keycloak с правом импорта.

Добавьте в локальное окружение `ITSM_MOCK_TOKEN_URL`, `ITSM_MOCK_CLIENT_ID` и
`ITSM_MOCK_CLIENT_SECRET`. URL токена должен быть доступен из контейнера.
Поднимите мок вместе с бэкендом:

```sh
docker compose --env-file "$BACKEND_ENV_FILE" --profile itsm-mock up -d --build
curl -fsS http://127.0.0.1:8082/api/mock/status
curl -sS -i -X POST http://127.0.0.1:8082/api/mock/emit
```

URL приёмника задаёт `ITSM_MOCK_TARGET_URL`. Настройки `ITSM_MOCK_SOURCE_ID`,
`ITSM_MOCK_SEED` и `ITSM_MOCK_BATCH_SIZE` задают источник, генерацию и размер
синтетического снимка (1–500). Интервал задаётся через `ITSM_MOCK_INTERVAL`,
например `PT2S`. Для ручной проверки можно задать `ITSM_MOCK_SCHEDULED=false`.
Для регрессии смонтируйте исходную фикстуру только на чтение и укажите путь
в контейнере в `ITSM_MOCK_FIXTURE_FILE`.

Каждый новый снимок получает новую версию и ключ. При сбое мок сохраняет пакет
в томе и продолжает с теми же заголовками и телом. При отказе вроде 401 или 422
периодическая отправка приостанавливается с причиной в status. После исправления
настроек повторите отправку вручную. Для каждого источника используйте один
экземпляр отправителя и отдельный том.

[Предлагаемый контракт KAN-53 для согласования с KAN-27](docs/itsm-ingest.openapi.yaml).

Код 422 означает, что уже сформированный снимок нужно разобрать вместе с
владельцем приёмника. Изменение файла фикстуры влияет на будущие версии. Для
повторной регрессии с исправленной фикстурой создайте отдельный тестовый источник
с новым идентификатором и чистым томом.

Проверки мока с Java 21:

```sh
sh ./gradlew :itsm-mock:test :itsm-mock:bootJar
```

`SnapshotHttpIT` проверяет реальные HTTP-запросы на локальном приёмнике.
`SnapshotContainerIT` повторяет проверку с контейнерным приёмником и требует
работающего Docker.
