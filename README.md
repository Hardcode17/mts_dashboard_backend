# mts_dashboard_backend

## Автодеплой на Raspberry Pi

Пуш в `main` запускает `.github/workflows/deploy.yml` на runner организации
`Hardcode17` с метками `self-hosted`, `Linux`, `ARM64`, `pi-web`.
Используется существующий runner `raspberrypi-front`; устанавливать второй не нужно.
В настройках runner group репозиторий `mts_dashboard_backend` должен иметь доступ к runner.

Образ Java 21 собирается из `Dockerfile` на самой Pi. Compose-проект:
`mts-dashboard-backend`, сервис: `backend`, порт: `8081` на Pi → `8080` в контейнере.
Фронтенд остаётся отдельным Compose-проектом на порту `8080`.
Тесты при сборке образа не запускаются; это workflow деплоя.

### Перед первым пушем

1. Перенесите исходники приложения из `dev` в `main` вместе с файлами деплоя.
   В корне должны быть `gradlew`, каталог `gradle`, `build.gradle.kts` и `src`.
2. На Pi создайте файл настроек вне рабочего каталога Actions:

   ```bash
   sudo install -d -o gha-runner -g gha-runner -m 700 /home/gha-runner/.config/mts-dashboard-backend
   sudo -u gha-runner touch /home/gha-runner/.config/mts-dashboard-backend/runtime.env
   sudo chmod 600 /home/gha-runner/.config/mts-dashboard-backend/runtime.env
   sudo -u gha-runner nano /home/gha-runner/.config/mts-dashboard-backend/runtime.env
   ```

   Запишите реальные переменные окружения Spring Boot в формате `KEY=value`:
   `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
   `SPRING_DATASOURCE_PASSWORD` и настройки используемых AI/внешних сервисов.
   Этот Compose запускает только приложение; БД и внешние сервисы должны быть
   настроены отдельно. `localhost` внутри контейнера означает сам контейнер.
   Пароли и ключи не добавляйте в Git.
3. Убедитесь, что runner организации доступен этому репозиторию, и отправьте
   изменения в `main`. Следите за запуском **Deploy backend** во вкладке Actions.

Необязательные GitHub Actions Repository Variables:

| Переменная | По умолчанию |
| --- | --- |
| `BACKEND_PORT` | `8081` |
| `BACKEND_ENV_FILE` | `/home/gha-runner/.config/mts-dashboard-backend/runtime.env` |

После успешного деплоя адрес: `http://100.77.2.49:8081/health`.
При стандартной Spring Security потребуется авторизация. Проверка контейнера
принимает HTTP 200 или 401 как подтверждение запуска HTTP-сервера; она не
проверяет работоспособность БД или внешних API. Правила безопасности приложения
не изменяются.

Деплой ждёт готовности до 240 секунд. При ошибке выводятся статус и последние
100 строк логов. Автоматического отката нет. Для повторного запуска используйте
**Run workflow** на ветке `main`; ручной `git pull` на Pi не требуется.
