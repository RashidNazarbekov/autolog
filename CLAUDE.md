# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Telegram bot for a household sharing cars: trips, fuel/EV charging, expenses. Java 21, Spring Boot 3, PostgreSQL 16 + Flyway, Telegram Bot API (long polling). Code comments and domain text (error messages, bot replies) are in Russian — match that when editing existing files.

## Commands

```bash
cp .env.example .env            # fill in secrets first
docker compose up -d db         # Postgres on localhost:5432
set -a; . ./.env; set +a        # load .env into shell env
mvn spring-boot:run             # app on http://localhost:8080
curl localhost:8080/actuator/health   # should return {"status":"UP"}

mvn test                        # full test suite (spins up Postgres via Testcontainers, needs Docker)
mvn test -Dtest=TripServiceTest                      # single test class
mvn test -Dtest=TripServiceTest#start_rejectsLowerOdometer   # single test method
mvn verify                      # what CI runs

docker compose --profile app up -d --build   # run everything (app + db) in containers
```

No separate lint command; standard `mvn compile`/`verify` surfaces compiler warnings. Empty `TELEGRAM_BOT_TOKEN` disables the bot but the rest of the app (actuator, DB) still starts — useful for running tests/app without a real bot token.

## Architecture

**Package-by-domain** under `src/main/java/kg/autolog/`: `household`, `driver`, `car`, `trip`, `fuel`, `charge`, `expense`, `report`, `bot`, `common`. Each domain package typically has an entity (JPA), a `*Repository` (Spring Data), and a `*Service` holding business rules and `@Transactional` boundaries. Services call into other domains' services/repositories directly — there's no separate controller layer for most domains since the only client is the Telegram bot.

**Multi-tenancy model**: a `Household` owns `Car`s; `Driver`s join a household via invite code (`HouseholdMember`, role = owner/member). Nearly every service method takes the acting `Driver` and checks membership/ownership via `HouseholdService` before acting — follow this pattern for any new domain action (e.g. `cars.require(driver, carId)` throws if the car isn't in the driver's household).

**Car state machine**: `Car.state` (`FREE` / `ON_TRIP` / `CHARGING`) with optimistic locking (`@Version`) — starting a trip or charge flips the car's state and `saveAndFlush`es immediately so a concurrent attempt on the same car fails fast with `AutologException.Conflict` (see `TripService.flush`). Preserve this flush-then-check pattern when adding new flows that claim a car.

**Domain errors**: `AutologException` is a sealed class with exactly four subtypes — `NotFound`, `Forbidden`, `Invalid`, `Conflict`. Messages are written for end users (shown verbatim in Telegram replies), not developers. Don't add new exception types; pick the closest of the four.

**Bot layer** (`bot/` package) is a state machine decoupled from the Telegram library:
- `BotEngine` is the single entry point (`handle(Incoming)`) — routes commands (`/menu`, `/report`, …), button callbacks, and free-text input.
- Multi-step input (e.g. "add a car": type → name → capacity → odometer → consumption) is modeled as a `BotState` enum step, persisted per-driver in `BotSessionStore`/`BotSessionRepository` (DB-backed, survives restarts) between messages.
- Each multi-step scenario lives in its own `*Flow` class (`TripFlow`, `RefuelFlow`, `ChargeFlow`, `PriceFlow`, `ExpenseFlow`, `ReportFlow`) — add new multi-step scenarios the same way rather than growing `BotEngine`.
- `BotEngine` has no dependency on the Telegram SDK, so its scenarios are tested directly (`BotTripTest`, `BotRefuelTest`, etc.) without mocking Telegram. `bot/telegram/TelegramBot` is the thin adapter that wires `BotEngine` to the actual long-polling client.
- Replies use a small HTML subset for Telegram; use `Format.esc` when interpolating user-provided strings into reply text to avoid breaking markup.

**Economy calculations**: `FuelEconomy` / `ChargeEconomy` derive consumption (L or kWh per 100km) and per-trip cost from the car's factory spec, falling back to historical refuels/charges when available — see `TripService.finish` for how trip-end economy is assembled per fuel type (diesel vs electric diverge significantly here).

**Config**: `AutologProperties` (`@ConfigurationProperties("autolog")`) binds `application.yml`'s `autolog.*` section — timezone, Telegram credentials, and trip-reminder timing (`autolog.reminders`: how long before nagging a driver about an open trip, repeat interval, quiet hours, poll interval). `TripReminderJob` polls on `reminders.checkEvery` and uses `TripService.takeDueReminders`.

**Migrations**: Flyway only, `src/main/resources/db/migration/V<N>__<name>.sql`. Hibernate `ddl-auto` is `validate` — schema changes must go through a new migration file; never edit an already-merged migration.

## Testing

- `IntegrationTest` is the shared base for integration tests: starts one `PostgreSQLContainer` (Testcontainers) statically, shared across all test classes in the run, wired in via `@DynamicPropertySource`.
- `TestData` provides shared fixture builders.
- Bot scenario tests (`src/test/java/kg/autolog/bot/`) drive `BotEngine` directly with synthetic `Incoming` messages/button presses and assert on the `Reply` list — this is the pattern to follow for testing new bot flows, no Telegram mocking needed.

## Git workflow (see CONTRIBUTING.md for full detail)

- Branch from `development`: `feature/<slug>`, `fix/<slug>`, `chore/<slug>`. Direct pushes to `main`/`development` are blocked.
- Commits: Conventional Commits, English, imperative (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `chore:`; optional scope `feat(bot): ...`).
- PRs target `development`, merged via squash. `development` → `main` PRs are tagged `vX.Y.Z` releases.
