-- Водитель — человек в Telegram
CREATE TABLE driver (
    id          BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT       NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    username    VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Дом: семья с общими машинами
CREATE TABLE household (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(64) NOT NULL,
    invite_code       VARCHAR(16) UNIQUE,
    invite_expires_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Участник дома и его роль
CREATE TABLE household_member (
    id           BIGSERIAL PRIMARY KEY,
    household_id BIGINT      NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    driver_id    BIGINT      NOT NULL REFERENCES driver (id) ON DELETE CASCADE,
    role         VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'DRIVER')),
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, driver_id)
);
CREATE INDEX idx_household_member_driver ON household_member (driver_id);

-- Машина дома. Для дизеля обязателен объём бака, для электро — ёмкость батареи.
CREATE TABLE car (
    id                BIGSERIAL PRIMARY KEY,
    household_id      BIGINT       NOT NULL REFERENCES household (id) ON DELETE CASCADE,
    name              VARCHAR(64)  NOT NULL,
    fuel_type         VARCHAR(16)  NOT NULL CHECK (fuel_type IN ('DIESEL', 'ELECTRIC')),
    plate             VARCHAR(16),
    tank_liters       NUMERIC(5, 1) CHECK (tank_liters > 0),
    battery_kwh       NUMERIC(5, 1) CHECK (battery_kwh > 0),
    -- Заводской расход: л/100 км для дизеля, кВт·ч/100 км для электро. Нужен, пока своих данных мало.
    rated_consumption NUMERIC(5, 2) CHECK (rated_consumption > 0),
    odometer_km       INTEGER      NOT NULL DEFAULT 0 CHECK (odometer_km >= 0),
    state             VARCHAR(16)  NOT NULL DEFAULT 'FREE' CHECK (state IN ('FREE', 'ON_TRIP', 'CHARGING')),
    current_driver_id BIGINT REFERENCES driver (id),
    state_since       TIMESTAMPTZ,
    archived          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CHECK ((fuel_type = 'DIESEL' AND tank_liters IS NOT NULL) OR (fuel_type = 'ELECTRIC' AND battery_kwh IS NOT NULL)),
    -- Свободная машина ни за кем не числится; в поездке или на зарядке — числится за водителем
    CHECK ((state = 'FREE') = (current_driver_id IS NULL))
);
CREATE INDEX idx_car_household ON car (household_id) WHERE NOT archived;
