-- Категории прочих расходов — свои у каждого дома: стандартный набор + добавленные владельцем.
CREATE TABLE expense_category (
    id                   BIGSERIAL PRIMARY KEY,
    household_id         BIGINT      NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    name                 VARCHAR(48) NOT NULL,
    emoji                VARCHAR(8)  NOT NULL DEFAULT '💳',
    -- На сколько месяцев обычно распределять: 1 — разово, 12 — страховка, 24 — шины
    default_spread_months INTEGER    NOT NULL DEFAULT 1 CHECK (default_spread_months BETWEEN 1 AND 60),
    -- Спросить, кто нарушил (штрафы)
    asks_offender        BOOLEAN     NOT NULL DEFAULT FALSE,
    sort_order           INTEGER     NOT NULL DEFAULT 100,
    archived             BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_expense_category_name ON expense_category (household_id, lower(name)) WHERE NOT archived;

-- Прочий расход на машину: мойка, ТО, шины, страховка, штрафы и т. п.
CREATE TABLE expense (
    id                 BIGSERIAL PRIMARY KEY,
    car_id             BIGINT         NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    category_id        BIGINT         NOT NULL REFERENCES expense_category (id),
    -- Кто платил
    driver_id          BIGINT         NOT NULL REFERENCES driver (id),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    spent_on           DATE           NOT NULL,
    -- 1 — разовый; больше — распределяется на столько месяцев начиная с spent_on
    spread_months      INTEGER        NOT NULL DEFAULT 1 CHECK (spread_months BETWEEN 1 AND 60),
    -- Для штрафов: кто нарушил
    offender_driver_id BIGINT REFERENCES driver (id),
    odometer_km        INTEGER CHECK (odometer_km >= 0),
    note               VARCHAR(200),
    created_at         TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_expense_car_spent ON expense (car_id, spent_on DESC);
CREATE INDEX idx_expense_driver ON expense (driver_id, spent_on DESC);

-- Стандартный набор для уже созданных домов (новые получают его при создании)
INSERT INTO expense_category (household_id, name, emoji, default_spread_months, asks_offender, sort_order)
SELECT h.id, c.name, c.emoji, c.spread, c.offender, c.sort
FROM household h
CROSS JOIN (VALUES
    ('ТО и ремонт',         '🔧', 1,  FALSE, 10),
    ('Шины',                '🛞', 24, FALSE, 20),
    ('Мойка и уход',        '🧽', 1,  FALSE, 30),
    ('Страховка',           '📄', 12, FALSE, 40),
    ('Техосмотр и налог',   '🧾', 12, FALSE, 50),
    ('Парковка и дороги',   '🅿️', 1,  FALSE, 60),
    ('Штраф',               '🚨', 1,  TRUE,  70),
    ('Покупки для машины',  '🛒', 1,  FALSE, 80),
    ('Другое',              '💳', 1,  FALSE, 90)
) AS c(name, emoji, spread, offender, sort);
