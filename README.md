# autolog

Семейный журнал машин: поездки, заправки, зарядки электромобиля и расходы через Telegram-бота.

Несколько водителей пользуются несколькими машинами (дизель и электро). Каждый отмечает в Telegram начало и конец поездки, заправки и зарядки, а система считает пробег, расход, стоимость километра и строит отчёты по машинам и водителям.

Требования — [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md). Правила разработки — [CONTRIBUTING.md](CONTRIBUTING.md).

## Стек

Java 21 · Spring Boot 3 · PostgreSQL 16 + Flyway · Telegram Bot API · JUnit 5 + Testcontainers · Docker Compose · GitHub Actions

## Запуск локально

Нужны JDK 21 и Docker.

```bash
cp .env.example .env          # заполнить секреты
docker compose up -d db       # PostgreSQL на localhost:5432
./gradlew bootRun             # приложение на http://localhost:8080
```

Проверка: `curl localhost:8080/actuator/health` → `{"status":"UP"}`.

Тесты (поднимают PostgreSQL в Docker сами):

```bash
./gradlew test
```

Всё в контейнерах: `docker compose --profile app up -d --build`.

## Структура

```
src/main/java/kg/autolog      код приложения
src/main/resources/db/migration   миграции Flyway
docs/                         требования и заметки
.github/                      CI и шаблон PR
```
