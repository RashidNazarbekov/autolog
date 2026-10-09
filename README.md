# autolog

Семейный журнал машин: поездки, заправки, зарядки электромобиля и расходы через Telegram-бота.

Несколько водителей пользуются несколькими машинами (дизель и электро). Каждый отмечает в Telegram начало и конец поездки, заправки и зарядки, а система считает пробег, расход, стоимость километра и строит отчёты по машинам и водителям.

Требования — [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md). Правила разработки — [CONTRIBUTING.md](CONTRIBUTING.md).

## Стек

Java 21 · Spring Boot 3 · Maven · PostgreSQL 16 + Flyway · Telegram Bot API · JUnit 5 + Testcontainers · Docker Compose · GitHub Actions

## Запуск локально

Нужны JDK 21, Maven 3.9+ и Docker.

```bash
cp .env.example .env          # заполнить секреты
docker compose up -d db       # PostgreSQL на localhost:5432
set -a; . ./.env; set +a      # переменные из .env в окружение
mvn spring-boot:run           # приложение на http://localhost:8080
```

Проверка: `curl localhost:8080/actuator/health` → `{"status":"UP"}`.

### Telegram-бот

1. В Telegram откройте @BotFather → `/newbot`, задайте имя и username бота.
2. Впишите в `.env` токен (`TELEGRAM_BOT_TOKEN`) и username без @ (`TELEGRAM_BOT_USERNAME`).
3. Запустите приложение, как выше. В логе появится строка «Telegram-бот подключён».
4. Напишите боту `/start`: создайте дом, добавьте машины, пригласите водителей кнопкой «Пригласить» — бот даст ссылку и код.

Бот работает через long polling: домен и HTTPS не нужны, но отвечает он, пока приложение запущено. Запускайте один экземпляр с токеном — два одновременно Telegram не допускает.

Команды: `/menu`, `/cars`, `/invite`, `/cancel`, `/help`.

Тесты (поднимают PostgreSQL в Docker сами):

```bash
mvn test
```

Всё в контейнерах: `docker compose --profile app up -d --build`.

## Структура

```
src/main/java/kg/autolog      код приложения
src/main/resources/db/migration   миграции Flyway
docs/                         требования и заметки
.github/                      CI и шаблон PR
```
