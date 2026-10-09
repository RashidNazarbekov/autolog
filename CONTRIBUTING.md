# Как ведётся разработка

## Ветки

```
main          ← стабильные версии, только через PR из development
 └ development ← текущая разработка, только через PR из веток фич
    ├ feature/<коротко>   новая функциональность
    ├ fix/<коротко>       исправление ошибки
    └ chore/<коротко>     сборка, CI, зависимости, документация
```

- Каждая задача — своя ветка **от свежего `development`**:
  ```bash
  git switch development && git pull
  git switch -c feature/trip-start-end
  ```
- Имя ветки — латиницей, через дефис, по сути задачи: `feature/diesel-refuel`, `fix/odometer-gap`.
- Прямой push в `main` и `development` запрещён (включается в Settings → Branches → Branch protection).

## Коммиты

[Conventional Commits](https://www.conventionalcommits.org/), на английском, в повелительном наклонении:

| Тип | Когда |
|---|---|
| `feat:` | новая возможность |
| `fix:` | исправление ошибки |
| `test:` | только тесты |
| `refactor:` | переделка без изменения поведения |
| `docs:` | документация |
| `chore:` | сборка, CI, зависимости |

Примеры: `feat: start and finish a trip from the bot`, `fix: reject odometer lower than previous`.
Область можно указать в скобках: `feat(bot): ...`, `feat(reports): ...`.

## Pull request

1. PR открывается в `development`, название — как у коммита: `feat: diesel refuel`.
2. Описание по шаблону: что сделано, как проверить, чек-лист.
3. CI (сборка + тесты) должен быть зелёным.
4. Вливает владелец репозитория после просмотра. Способ — **Squash and merge**: одна фича = один коммит в `development`.
5. После влития ветка фичи удаляется.

## Релизы

Когда в `development` набирается рабочая версия — PR `development` → `main`, на `main` ставится тег `vX.Y.Z`.

## Секреты

Токены и пароли — только в `.env` (не коммитится) и в GitHub Secrets. В `.env.example` — только имена переменных.

## База данных

Схема меняется только новыми миграциями Flyway `src/main/resources/db/migration/V<N>__<что>.sql`. Уже влитые миграции не редактируются.
