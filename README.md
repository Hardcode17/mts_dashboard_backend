# MCC — бэкенд дашборда координатора

Бэкенд принимает снимки инцидентов из ITSM, сохраняет их в PostgreSQL и публикует проверенную версию для дашборда. Координатор получает очередь, карточку инцидента, состояние сроков и свежесть данных. API также поддерживает изменение назначения, статуса и решения через ITSM.

## Быстрый старт

Понадобятся Docker с Compose v2 и OpenSSL. Из корня репозитория:

```sh
runtime_dir="${XDG_CONFIG_HOME:-$HOME/.config}/mcc-dashboard"
umask 077
mkdir -p "$runtime_dir"
export BACKEND_ENV_FILE="$runtime_dir/demo.env"
if [ ! -f "$BACKEND_ENV_FILE" ]; then
  printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)" > "$BACKEND_ENV_FILE"
fi

docker compose --env-file "$BACKEND_ENV_FILE" up -d --build
curl -fsS --retry 30 --retry-delay 2 --retry-connrefused http://127.0.0.1:8081/health
```

Успешный ответ `/health` имеет код HTTP 200. Compose поднимает PostgreSQL 17, а Flyway применяет общие и демонстрационные миграции. Для чтения инцидентов нужны Keycloak, допуск координатора и опубликованный снимок. Для быстрого знакомства с интерфейсом используйте локальное демо в репозитории фронтенда.

Остановить стенд с сохранением базы:

```sh
docker compose --env-file "$BACKEND_ENV_FILE" down
```

## Как проходят данные

```text
ITSM или itsm-mock → приём полного JSON-снимка → очередь в PostgreSQL
→ проверка и публикация → REST API дашборда
```

- **Приём снимка.** Отправитель использует машинный JWT и передаёт `Idempotency-Key`, `X-Source-Id` и `X-Source-Version`. Повтор той же отправки возвращает исходный ID пакета. Конфликт версии или ключа даёт HTTP 409.
- **Публикация.** HTTP 202 подтверждает сохранение пакета. Состояние обработки доступно отдельно по адресу из `Location`. Дашборд читает опубликованные данные и может проверить свежесть по `/api/snapshot/status`.
- **Работа с карточкой.** Изменение исполнителя, группы, статуса или решения бэкенд передаёт в ITSM. После успешного ответа сохраняет изменение локально.
- **Доступ координатора.** Для пользовательских маршрутов нужны JWT Keycloak и включённая запись пользователя с совпадающими `iss` и `sub`. Для приёма снимков действует отдельная машинная политика доступа.

| Маршрут | Назначение |
|---|---|
| `GET /health` | Проверка запуска без токена |
| `GET /api/me` | Профиль и проверка допуска координатора |
| `GET /api/dashboard/incidents` | Очереди, фильтрация, пагинация и экспорт CSV |
| `GET /api/dashboard/incidents/{incidentId}` | Карточка инцидента |
| `PATCH /api/dashboard/incidents/{incidentId}` | Изменение карточки через ITSM |
| `POST /api/integrations/itsm/batches` | Приём снимка с машинным JWT |

Полное описание параметров и ответов есть в [OpenAPI дашборда](docs/dashboard-api.openapi.yaml) и [OpenAPI импорта](docs/itsm-ingest.openapi.yaml).

## Локальный вход через Keycloak

[Отдельный стенд авторизации](compose.auth.yaml) запускает Keycloak на порту `8084` и API на порту `8091`. Для подготовки realm нужен JDK 21. Замените примеры паролей и секрета на свои локальные значения перед запуском.

```sh
export KEYCLOAK_ADMIN_PASSWORD='<local-admin-password>'
export AUTH_POSTGRES_PASSWORD='<local-database-password>'
export DASHBOARD_TEST_USERNAME='coordinator'
export DASHBOARD_TEST_EMAIL='coordinator@example.invalid'
export DASHBOARD_TEST_PASSWORD='<local-coordinator-password>'
export ITSM_SENDER_CLIENT_SECRET='<local-sender-secret>'

export AUTH_REALM_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/mcc-dashboard/dashboard-realm.json"
umask 077
mkdir -p "$(dirname "$AUTH_REALM_FILE")"
java scripts/RenderRealm.java src/keycloak/realm/dashboard-realm.json "$AUTH_REALM_FILE"
docker compose -f compose.auth.yaml up -d --build
curl -fsS --retry 30 --retry-delay 2 --retry-connrefused http://127.0.0.1:8091/health
```

Для входа через интерфейс используйте realm `http://localhost:8084/realms/dashboard` и API `http://127.0.0.1:8091`. Чтобы выдать допуск, скопируйте ID тестового пользователя из Keycloak и подставьте его в `subject`. Понадобится запущенный стенд авторизации.

```sh
docker compose -f compose.auth.yaml exec -T auth-postgres \
  psql -U dashboard -d dashboard_auth \
  -v issuer='http://localhost:8084/realms/dashboard' \
  -v subject='<ID пользователя из Keycloak>' \
  -v login="$DASHBOARD_TEST_USERNAME" -v display_name='Координатор' \
  < scripts/grant-coordinator.sql

export KEYCLOAK_PUBLIC_URL='http://localhost:8084'
export DASHBOARD_REDIRECT_URI='http://localhost:3000/auth/callback'
token="$(bash scripts/fetch-human-token.sh)"
curl -fsS -H "Authorization: Bearer $token" http://127.0.0.1:8091/api/me
unset token
```

Подробности о допуске и отзыве доступа есть в [инструкции координатора](docs/coordinator-access.md).

### Загрузка тестового снимка

Из корня репозитория отправьте тестовую выгрузку через локальный стенд авторизации. Понадобится Python 3 для разбора ответа Keycloak.

```sh
machine_token="$(curl -fsS -X POST \
  http://localhost:8084/realms/dashboard/protocol/openid-connect/token \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=itsm-sender' \
  --data-urlencode "client_secret=$ITSM_SENDER_CLIENT_SECRET" \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["access_token"])')"

curl -fsS -i -X POST http://127.0.0.1:8091/api/integrations/itsm/batches \
  -H "Authorization: Bearer $machine_token" \
  -H 'Idempotency-Key: e65d1756-3bcf-4cc7-b018-0e857dcad54e' \
  -H 'X-Source-Id: local-readme' \
  -H 'X-Source-Version: 1' \
  -H 'Content-Type: application/json' \
  --data-binary @src/test/resources/itsm/character_problems.json
```

После HTTP 202 проверьте состояние по адресу из заголовка `Location`:

```sh
curl -fsS -H "Authorization: Bearer $machine_token" \
  http://127.0.0.1:8091/api/integrations/itsm/batches/e65d1756-3bcf-4cc7-b018-0e857dcad54e
```

После `SUCCEEDED` можно подключить фронтенд к API и выполнить `unset machine_token`. Для точного повтора отправки оставьте заголовки и файл без изменений.

## Источники и окружения

| Что нужно | Где настроить |
|---|---|
| Синтетическая отправка снимков | Профиль `itsm-mock` в [compose.yaml](compose.yaml). Для отправки задайте `ITSM_MOCK_TOKEN_URL`, `ITSM_MOCK_CLIENT_ID` и `ITSM_MOCK_CLIENT_SECRET`. Адрес Keycloak должен быть доступен из контейнера. |
| Живая связь с ITSM | `DASHBOARD_ITSM_BASE_URL` для серверных вызовов и `DASHBOARD_ITSM_LINK_TEMPLATE` для ссылки из браузера. |
| Простая проверка запуска | Профиль `demo` по умолчанию в [compose.yaml](compose.yaml). |
| Отдельная рабочая база | Профиль `work` из [compose.work.yaml](compose.work.yaml) с отдельным env-файлом, `WORK_POSTGRES_PASSWORD` и томом. |

Мок по умолчанию отправляет синтетический снимок раз в 30 секунд. Включить его можно флагом `--profile itsm-mock` в команде запуска Compose. Основной бэкенд должен принимать токены настроенного машинного клиента. Параметры авторизации, лимиты и повторы описаны в [документации по импорту ITSM](docs/itsm-ingest.md).

## Сборка и тесты

Бэкенд использует Java 21, Spring Boot, PostgreSQL, Flyway и Keycloak. Из корня репозитория:

```sh
sh ./gradlew :bootJar :itsm-mock:bootJar
sh ./gradlew test
```

Для локальной сборки нужен JDK 21, для полного прогона тестов с Testcontainers также нужен Docker. На Windows используйте `gradlew.bat`.

## Репозитории проекта

| Репозиторий | Роль |
|---|---|
| [mts_dashboard_front](https://github.com/Hardcode17/mts_dashboard_front) | Интерфейс координатора и локальный демостенд |
| [mts_dashboard_backend](https://github.com/Hardcode17/mts_dashboard_backend) | API, доступ и приём снимков ITSM |
| [mts_dashboard_data](https://github.com/Hardcode17/mts_dashboard_data) | Материалы кейса, требования и контракты |
