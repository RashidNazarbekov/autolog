package kg.autolog;

import kg.autolog.car.CarDraft;
import kg.autolog.car.FuelType;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/** Заготовки для тестов. Telegram id уникальны в пределах прогона. */
@Component
public class TestData {

    private static final AtomicLong TELEGRAM_IDS = new AtomicLong(1_000_000);

    private final DriverService drivers;

    public TestData(DriverService drivers) {
        this.drivers = drivers;
    }

    public Driver driver(String name) {
        return drivers.register(TELEGRAM_IDS.incrementAndGet(), name, null, null);
    }

    public static CarDraft diesel(String name) {
        return new CarDraft(name, FuelType.DIESEL, null, new BigDecimal("87"), null, new BigDecimal("9.5"), 150_000);
    }

    public static CarDraft electric(String name) {
        return new CarDraft(name, FuelType.ELECTRIC, null, null, new BigDecimal("40.3"), new BigDecimal("13"), 1_200);
    }
}
