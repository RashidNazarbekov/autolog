-- Поездка: пара событий «начал» и «закончил». Пока открыта — машина числится за водителем.
CREATE TABLE trip (
    id                BIGSERIAL PRIMARY KEY,
    car_id            BIGINT      NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    driver_id         BIGINT      NOT NULL REFERENCES driver (id),
    status            VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'FINISHED')),
    started_at        TIMESTAMPTZ NOT NULL,
    start_odometer_km INTEGER     NOT NULL CHECK (start_odometer_km >= 0),
    start_soc_pct     INTEGER CHECK (start_soc_pct BETWEEN 0 AND 100),
    start_range_km    INTEGER CHECK (start_range_km >= 0),
    finished_at       TIMESTAMPTZ,
    end_odometer_km   INTEGER CHECK (end_odometer_km >= start_odometer_km),
    end_soc_pct       INTEGER CHECK (end_soc_pct BETWEEN 0 AND 100),
    end_range_km      INTEGER CHECK (end_range_km >= 0),
    CHECK ((status = 'OPEN') = (finished_at IS NULL)),
    CHECK (status = 'OPEN' OR end_odometer_km IS NOT NULL)
);
CREATE INDEX idx_trip_car_started ON trip (car_id, started_at DESC);
CREATE INDEX idx_trip_driver_started ON trip (driver_id, started_at DESC);
-- У машины не больше одной открытой поездки
CREATE UNIQUE INDEX uq_trip_open_per_car ON trip (car_id) WHERE status = 'OPEN';
-- У водителя не больше одной открытой поездки
CREATE UNIQUE INDEX uq_trip_open_per_driver ON trip (driver_id) WHERE status = 'OPEN';

-- Неучтённый пробег: на старте одометр больше, чем было известно. Кто проехал эти км — уточняют водители.
CREATE TABLE mileage_gap (
    id             BIGSERIAL PRIMARY KEY,
    car_id         BIGINT      NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    from_km        INTEGER     NOT NULL,
    to_km          INTEGER     NOT NULL CHECK (to_km > from_km),
    detected_at    TIMESTAMPTZ NOT NULL,
    before_trip_id BIGINT REFERENCES trip (id) ON DELETE SET NULL,
    driver_id      BIGINT REFERENCES driver (id),
    resolved_at    TIMESTAMPTZ,
    CHECK ((driver_id IS NULL) = (resolved_at IS NULL))
);
CREATE INDEX idx_mileage_gap_open ON mileage_gap (car_id) WHERE resolved_at IS NULL;
