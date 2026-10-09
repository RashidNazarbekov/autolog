-- Базовая миграция: фиксирует, что схемой управляет Flyway.
-- Таблицы предметной области (дом, машины, водители, события) появятся в следующих миграциях.
CREATE TABLE app_info (
    key   VARCHAR(64) PRIMARY KEY,
    value VARCHAR(256) NOT NULL
);

INSERT INTO app_info (key, value) VALUES ('schema_baseline', '2026-10-09');
