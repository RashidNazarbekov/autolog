-- Когда водителю последний раз напомнили о незакрытой поездке (или он ответил «ещё еду»)
ALTER TABLE trip ADD COLUMN reminded_at TIMESTAMPTZ;
-- Фоновая проверка ищет только открытые поездки
CREATE INDEX idx_trip_open_started ON trip (started_at) WHERE status = 'OPEN';
