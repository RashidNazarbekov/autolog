-- Цены на электричество — настройки дома. Значения по умолчанию — Бишкек, октябрь 2026:
-- свет дома 1,64 сом/кВт·ч (до 700 кВт·ч в месяц), станции — примерно, владелец поправит под свои.
ALTER TABLE household
    ADD COLUMN home_kwh_price NUMERIC(8, 2) NOT NULL DEFAULT 1.64 CHECK (home_kwh_price > 0),
    ADD COLUMN home_loss_pct  NUMERIC(4, 1) NOT NULL DEFAULT 12 CHECK (home_loss_pct BETWEEN 0 AND 50),
    ADD COLUMN dc40_kwh_price  NUMERIC(8, 2) NOT NULL DEFAULT 12 CHECK (dc40_kwh_price > 0),
    ADD COLUMN dc80_kwh_price  NUMERIC(8, 2) NOT NULL DEFAULT 14 CHECK (dc80_kwh_price > 0),
    ADD COLUMN dc120_kwh_price NUMERIC(8, 2) NOT NULL DEFAULT 16 CHECK (dc120_kwh_price > 0);

-- Зарядка электро. Дома или на станции; отдельная (машина «на зарядке») или подзарядка в пути (trip_id).
CREATE TABLE charge (
    id                BIGSERIAL PRIMARY KEY,
    car_id            BIGINT        NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    driver_id         BIGINT        NOT NULL REFERENCES driver (id),
    trip_id           BIGINT REFERENCES trip (id) ON DELETE SET NULL,
    location          VARCHAR(16)   NOT NULL CHECK (location IN ('HOME', 'DC40', 'DC80', 'DC120')),
    status            VARCHAR(16)   NOT NULL CHECK (status IN ('OPEN', 'FINISHED')),
    started_at        TIMESTAMPTZ   NOT NULL,
    start_odometer_km INTEGER CHECK (start_odometer_km >= 0),
    start_soc_pct     INTEGER       NOT NULL CHECK (start_soc_pct BETWEEN 0 AND 100),
    finished_at       TIMESTAMPTZ,
    end_soc_pct       INTEGER CHECK (end_soc_pct BETWEEN 0 AND 100),
    -- Сколько попало в батарею (по % заряда) и сколько взято из сети или отдала станция
    battery_kwh       NUMERIC(6, 2),
    grid_kwh          NUMERIC(6, 2),
    -- true — кВт·ч со счётчика станции, false — посчитаны по % с учётом потерь
    kwh_measured      BOOLEAN       NOT NULL DEFAULT FALSE,
    price_per_kwh     NUMERIC(8, 2),
    total_cost        NUMERIC(10, 2),
    CHECK ((status = 'OPEN') = (finished_at IS NULL)),
    CHECK (status = 'OPEN' OR (end_soc_pct IS NOT NULL AND grid_kwh IS NOT NULL AND total_cost IS NOT NULL)),
    CHECK (end_soc_pct IS NULL OR end_soc_pct >= start_soc_pct)
);
CREATE INDEX idx_charge_car_started ON charge (car_id, started_at DESC);
CREATE INDEX idx_charge_trip ON charge (trip_id) WHERE trip_id IS NOT NULL;
CREATE UNIQUE INDEX uq_charge_open_per_car ON charge (car_id) WHERE status = 'OPEN';
