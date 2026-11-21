# Локальный допуск координатора — KAN-22

Keycloak аутентифицирует человека, таблица `users` определяет локальный допуск.
Пароли и внешние сотрудники ITSM сюда не попадают. Вход не создаёт и не обновляет
профиль автоматически. `is_active` не является доступностью смены KAN-30.

## Контракт

`GET /api/me` возвращает `{"id":1,"sub":"subject-from-keycloak","login":"coordinator","displayName":"Координатор"}`.

После проверки JWT в KAN-16 `CoordinatorAuthorizationManager` защищает `/api/**`:
проверяет аутентифицированный `JwtAuthenticationToken`, пользовательский `azp`,
точную пару строк `iss/sub` и `users.is_active`. Результат не кешируется между запросами.
Совпадение логина не предоставляет доступ, изменение `preferred_username` не меняет
локальный ID и сохранённый профиль. Машинные токены не создают локальных пользователей.

Неизвестный, отключённый или машинный пользователь получает 403:
`{"code":"COORDINATOR_ACCESS_DENIED","message":"Coordinator access is not granted"}`.
Отсутствующий/невалидный JWT получает 401 от цепочки аутентификации.
`GET /health` открыт; `/api/integrations/itsm/**` обслуживает отдельная машинная цепочка.

`COORDINATOR_USER_CLIENT_IDS` — список пользовательских client ID через запятую,
по умолчанию `dashboard-web`. Это должны быть клиенты с выключенными service accounts;
не включайте клиента ITCS. Проверка подписи, issuer, audience и срока выполняется KAN-16.

## Интеграция с KAN-16

Штатная цепочка HumanApiSecurityConfiguration проверяет Bearer JWT через Keycloak и передаёт авторизацию CoordinatorAuthorizationManager. Ответ /api/me дополнен локальными id, sub, login и displayName.

## Выдача допуска на стенде

Администратор заранее создаёт человека в Keycloak и получает точные issuer и `sub`
из доверенной конфигурации IdP. После Flyway на рабочей БД выполнить:

```sh
psql "$DATABASE_URL" -v issuer='https://idp.example/realms/dashboard' \
  -v subject='subject-from-keycloak' -v login='coordinator' \
  -v display_name='Координатор' -f scripts/grant-coordinator.sql
```

Пароль БД задавать через окружение/pgpass, не коммитить. Повтор обновляет только
указанную пару issuer/subject. API создания учётных записей нет. Отзыв допуска:

```sql
UPDATE users SET is_active = false, updated_at = now() WHERE id = 1;
```

Следующий запрос со старым токеном отклоняется. Уже авторизованный запрос не отменяется.
Миграция `V6__coordinator_users.sql` добавлена после V5, существующие миграции и demo V1
не меняются. Таблица соответствует разделу 4.1 документации БД v5.

## Проверка

```sh
./gradlew --no-daemon test --tests '*CoordinatorAccessIT' --tests '*ItsmBatchIntakeIT' --tests '*WorkDatabaseIT'
```

Нужны Java 21 и Docker. Тесты KAN-22 поднимают PostgreSQL 17 и Keycloak 26.2.5,
проверяют реальные подписанные токены, отзыв допуска, неизвестного пользователя,
коллизии login/subject/issuer, машинного клиента и отсутствие лишних записей.
Password grant и тестовые пароли существуют только в изолированном тестовом realm.
