-- Разрешить бензин и газ в дополнение к дизелю и электро.
-- Имена констрейнтов из V2 не фиксированы явно, поэтому находим их по определению.
DO $$
DECLARE
    con RECORD;
BEGIN
    FOR con IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'car'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%fuel_type%'
        LOOP
            EXECUTE format('ALTER TABLE car DROP CONSTRAINT %I', con.conname);
        END LOOP;
END $$;

ALTER TABLE car
    ADD CONSTRAINT car_fuel_type_check CHECK (fuel_type IN ('DIESEL', 'PETROL', 'GAS', 'ELECTRIC')),
    ADD CONSTRAINT car_fuel_tank_or_battery_check
        CHECK ((fuel_type != 'ELECTRIC' AND tank_liters IS NOT NULL) OR (fuel_type = 'ELECTRIC' AND battery_kwh IS NOT NULL));
