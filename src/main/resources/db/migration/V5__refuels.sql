-- Заправка дизеля. Литры, цена и сумма хранятся все три: недостающее значение считается при вводе.
CREATE TABLE refuel (
    id              BIGSERIAL PRIMARY KEY,
    car_id          BIGINT         NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    driver_id       BIGINT         NOT NULL REFERENCES driver (id),
    -- Если заправлялись посреди поездки
    trip_id         BIGINT REFERENCES trip (id) ON DELETE SET NULL,
    refueled_at     TIMESTAMPTZ    NOT NULL,
    odometer_km     INTEGER        NOT NULL CHECK (odometer_km >= 0),
    liters          NUMERIC(6, 2)  NOT NULL CHECK (liters > 0),
    price_per_liter NUMERIC(8, 2)  NOT NULL CHECK (price_per_liter > 0),
    total_cost      NUMERIC(10, 2) NOT NULL CHECK (total_cost > 0)
);
CREATE INDEX idx_refuel_car_odometer ON refuel (car_id, odometer_km DESC, refueled_at DESC);
CREATE INDEX idx_refuel_driver ON refuel (driver_id, refueled_at DESC);
