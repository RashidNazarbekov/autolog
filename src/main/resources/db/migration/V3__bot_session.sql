-- Состояние диалога с ботом для каждого пользователя Telegram:
-- на каком шаге сценария он сейчас и что уже ввёл.
CREATE TABLE bot_session (
    telegram_id BIGINT PRIMARY KEY,
    state       VARCHAR(32) NOT NULL,
    payload     TEXT        NOT NULL DEFAULT '{}',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
