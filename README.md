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
