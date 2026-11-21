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

## Рабочая база и Flyway (KAN-19)

Для рабочего режима используйте отдельную БД и отдельный том. Профиль `work`
применяет миграции из `db/migration` и проверяет их контрольные суммы при старте.
Демонстрационная миграция `db/demo/V1` остаётся в истории старой БД. Оба профиля
запускаются с отключёнными внешними AI-сервисами.

1. Сохраните резервную копию демонстрационного тома и `runtime.env`. Для рабочей
   базы создайте отдельный env-файл вне репозитория с правами 600 и случайным
   `WORK_POSTGRES_PASSWORD`.
2. Запустите рабочий стек отдельно от демо. При необходимости задайте
   `WORK_BACKEND_PORT` в рабочем env-файле (по умолчанию 8083).

   ```sh
   docker compose -f compose.work.yaml --env-file "$WORK_ENV_FILE" up -d --build
   curl -fsS http://127.0.0.1:8083/health
   docker compose -f compose.work.yaml --env-file "$WORK_ENV_FILE" exec -T work-postgres \
     psql -U dashboard -d dashboard_work -c 'SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank;'
   ```

3. Проверьте, что в рабочей истории есть `V2__itsm_ingest_batch.sql`, и выполните
   обычный демо-smoke. Для пробного отключения остановите рабочий стек командой
   `docker compose -f compose.work.yaml --env-file "$WORK_ENV_FILE" stop`.
   Повторный запуск использует тот же рабочий том.

Интеграционная проверка с PostgreSQL 17 и MockMvc:
`sh ./gradlew :test --tests '*WorkDatabaseIT'`.

## Приём снимков ITSM (KAN-27)

Машинный приём полного снимка, идемпотентность, ограничение очереди и состояние
снимка описаны в [docs/itsm-ingest.md](docs/itsm-ingest.md). Там перечислены точные
переменные окружения, контракт заголовков KAN-53 и требования к Keycloak.
Для интеграционных тестов нужен Docker с PostgreSQL 17; для проверки авторизации
и формата Docker не требуется.

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

[Контракт приёма снимков KAN-27 / KAN-53](docs/itsm-ingest.openapi.yaml).

Ответ 202 подтверждает хранение, а не применение. Статус и диагностику можно
получить по `Location` из ответа приёмника. Изменение файла фикстуры влияет на будущие версии. Для
повторной регрессии с исправленной фикстурой создайте отдельный тестовый источник
с новым идентификатором и чистым томом.

Проверки мока с Java 21:

```sh
sh ./gradlew :itsm-mock:test :itsm-mock:bootJar
```

`SnapshotHttpIT` проверяет реальные HTTP-запросы на локальном приёмнике.
`SnapshotContainerIT` повторяет проверку с контейнерным приёмником и требует
работающего Docker.


### Локальный вход через Keycloak

`compose.auth.yaml` поднимает отдельный стенд. Укажите `AUTH_REALM_FILE` вне
репозитория и передайте через окружение `DASHBOARD_TEST_USERNAME`,
`DASHBOARD_TEST_EMAIL`, `DASHBOARD_TEST_PASSWORD` и `ITSM_SENDER_CLIENT_SECRET`.
Подготовьте realm на Java 21 и запустите стенд:

```sh
umask 077
java scripts/RenderRealm.java src/keycloak/realm/dashboard-realm.json "$AUTH_REALM_FILE"
docker compose -f compose.auth.yaml up -d --build
```

Для Compose также нужны `KEYCLOAK_ADMIN_PASSWORD` и `AUTH_POSTGRES_PASSWORD`.
При нестандартном порте Keycloak передайте `KEYCLOAK_PORT` в Compose и тот же
адрес через `KEYCLOAK_PUBLIC_URL` в скрипт получения токена.

```sh
token="$(bash scripts/fetch-human-token.sh)"
curl -fsS -H "Authorization: Bearer $token" http://localhost:8091/api/me
unset token
```

`subject` в ответе — значение `sub` из проверенного JWT. Для импорта используется
отдельный клиент `itsm-sender` и аудитория `itsm-ingest`. Проверка в Docker
выполняется командой `sh ./gradlew :test --tests '*HumanApiSecurityIT'`.
