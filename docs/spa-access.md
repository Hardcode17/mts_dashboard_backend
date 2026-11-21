# Подключение браузерного SPA к API — KAN-64

Фронтенд (`Hardcode17/mts_dashboard_front`, React + Vite) — отдельный origin и ходит к
бэкенду по HTTP с JWT. Здесь зафиксировано, как он подключается: origin, порт, auth flow,
CORS и env. Контракт чтений — [dashboard-api.openapi.yaml](dashboard-api.openapi.yaml),
допуск координатора — [coordinator-access.md](coordinator-access.md).

## Auth flow

1. Браузер открывает OIDC Authorization Code + PKCE (S256) через **публичный** клиент
   `dashboard-web` realm'а `dashboard` (realm-файл `src/keycloak/realm/dashboard-realm.json`).
   Секрет клиента не нужен и не существует; direct grants и регистрация выключены.
2. Access token содержит аудиторию `dashboard-api` (audience mapper клиента `dashboard-web`).
3. SPA отправляет токен в заголовке `Authorization: Bearer <token>` на `/api/**`.
4. Бэкенд (человеческая цепочка `HumanApiSecurityConfiguration`) проверяет `iss`, подпись по
   JWKS, `aud=dashboard-api` и срок, затем локальный допуск по `iss/sub` (KAN-22).
5. `GET /api/me` возвращает профиль и подтверждает допуск.

Значение `iss` должно быть одинаковым для браузера и бэкенда: браузер достигает Keycloak по
публичному URL, бэкенд может использовать внутренний `JWK_SET_URI`.

## CORS

CORS обслуживается **только** человеческой security-цепочкой (`/api/**`); машинная цепочка
`/api/integrations/itsm/**` не открывает CORS вообще — приём снимков ходит с серверов, а не
из браузера.

| Параметр | Значение |
|---|---|
| Origins | env `APP_CORS_ALLOWED_ORIGINS`, через запятую; по умолчанию `http://localhost:3000,http://localhost:5173` |
| Методы | `GET`, `POST`, `PATCH`, `PUT`, `DELETE`, `OPTIONS` |
| Заголовки запроса | `Authorization`, `Content-Type`, `If-None-Match`, `X-Request-Id` |
| Доступные браузеру заголовки ответа | `ETag`, `Location`, `X-Request-Id` |
| Preflight | `OPTIONS` без аутентификации, `Access-Control-Max-Age: 3600` |
| Credentials | не используются (Bearer без cookies) |

Поведение: preflight с разрешённого origin → `200` с CORS-заголовками; запрос без токена →
`401` (CORS-заголовки сохраняются, чтобы браузер мог прочитать ошибку); чужой origin →
`403` без `Access-Control-Allow-Origin`. Явно пустой `APP_CORS_ALLOWED_ORIGINS` отключает
CORS целиком (fail-closed). Настройки читает `app.cors.allowed-origins`
(`ru.hardcode.dashboard.configuration.CorsProperties`).

## Порты и origins

| Компонент | Dev-адрес |
|---|---|
| Vite dev-сервер фронта | `http://localhost:5173` |
| Локальный build фронта | `http://localhost:3000` |
| API на стенде авторизации (`compose.auth.yaml`) | `http://127.0.0.1:8091` |
| API демо-стенда (`compose.yaml`) | `http://<host>:${BACKEND_PORT:-8081}` |
| Keycloak | `http://localhost:8084/realms/dashboard` |

Оба dev-origin'а фронта уже входят в redirectUris/webOrigins клиента `dashboard-web` и в
CORS по умолчанию.

## Env бэкенда

| Переменная | Назначение |
|---|---|
| `APP_CORS_ALLOWED_ORIGINS` | CORS-origins через запятую; по умолчанию dev-origins (см. выше) |
| `APP_AUTH_HUMAN_ISSUER` | точный `iss`, одинаковый для браузера и бэкенда |
| `APP_AUTH_HUMAN_JWK_SET_URI` | JWKS-URL, доступный бэкенду (иначе `APP_AUTH_HUMAN_ISSUER_URI` для discovery) |
| `APP_AUTH_HUMAN_AUDIENCE` | `dashboard-api` |

Без этих переменных человеческая цепочка не активируется: `/api/**` остаётся на form login
+ Basic + session + CSRF, а `/api/me` отвергает токены (fail-closed).

## Где что включено

- **Стенд авторизации** `compose.auth.yaml`: человеческая цепочка и CORS включены
  (env в сервисе `auth-backend`), origins по умолчанию dev.
- **Прод-стенд** `compose.yaml` + workflow `deploy.yml`: `scripts/prepare-runtime.sh`
  рендерит env и записывает `APP_CORS_ALLOWED_ORIGINS=<STAND_FRONTEND_URL>,http://localhost:3000,http://localhost:5173`
  рядом с `APP_AUTH_HUMAN_*` — источник истины для origin'а фронта переменная
  `STAND_FRONTEND_URL` репозитория. На реальном стенде новые переменные применяются
  следующим деплоем через пайплайн; до этого включение проверено локально через
  `compose.auth.yaml` и Testcontainers (см. проверку ниже).

## Проверка

```sh
./gradlew :test --tests '*HumanApiCorsIT' --tests '*CorsPropertiesTest' --no-daemon
```

`HumanApiCorsIT` поднимает PostgreSQL 17 и Keycloak 26.4.7 (Testcontainers) и гоняет
реальную цепочку фильтров через MockMvc: preflight без аутентификации, `GET /api/dashboard/incidents`
с Origin + Bearer с CORS-заголовками и данными, `401` без токена, отказ чужому и выключенному
config'ом origin'у, отсутствие CORS на `/api/integrations/itsm/**` и независимость машинной
цепочки. Нужны JDK 21 и Docker.

Сквозная проверка из браузера: запустить стенд авторизации (`compose.auth.yaml`), выдать
допуск (`scripts/grant-coordinator.sql`) и войти фронтом с `localhost:5173` — запросы к
`GET /api/dashboard/incidents` и `GET /api/dashboard/incidents/{id}` должны отдавать данные.
