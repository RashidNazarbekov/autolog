package kg.autolog.car;

import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CarService {

    private static final BigDecimal MAX_TANK_LITERS = new BigDecimal("300");
    private static final BigDecimal MAX_BATTERY_KWH = new BigDecimal("250");
    private static final BigDecimal MAX_CONSUMPTION = new BigDecimal("60");
    private static final int MAX_ODOMETER_KM = 3_000_000;

    private final CarRepository cars;
    private final HouseholdService households;
    private final Clock clock;

    /** Машины дома водителя (без архивных). */
    public List<Car> list(Driver driver) {
        return cars.findByHouseholdIdAndArchivedFalseOrderById(households.requireMembership(driver).getHouseholdId());
    }

    /** Машина дома водителя; чужие и архивные — как несуществующие. */
    public Car require(Driver driver, long carId) {
        var householdId = households.requireMembership(driver).getHouseholdId();
        return cars.findByIdAndHouseholdIdAndArchivedFalse(carId, householdId)
                .orElseThrow(() -> new AutologException.NotFound("Машина не найдена"));
    }

    @Transactional
    public Car add(Driver owner, CarDraft d) {
        var householdId = households.requireOwner(owner).getHouseholdId();
        if (d.fuelType() == null) throw new AutologException.Invalid("Укажите тип машины: дизель или электро");
        var name = cleanName(d.name());
        if (cars.existsByHouseholdIdAndArchivedFalseAndNameIgnoreCase(householdId, name)) {
            throw new AutologException.Conflict("Машина «" + name + "» уже есть в доме");
        }
        var car = new Car(householdId, name, d.fuelType(), clock.instant());
        apply(car, d);
        requireCapacity(car);
        return cars.save(car);
    }

    @Transactional
    public Car update(Driver owner, long carId, CarDraft d) {
        households.requireOwner(owner);
        var car = require(owner, carId);
        if (d.fuelType() != null && d.fuelType() != car.getFuelType()) {
            throw new AutologException.Invalid("Тип машины после создания не меняется");
        }
        if (d.name() != null) {
            var name = cleanName(d.name());
            if (!name.equalsIgnoreCase(car.getName())
                    && cars.existsByHouseholdIdAndArchivedFalseAndNameIgnoreCase(car.getHouseholdId(), name)) {
                throw new AutologException.Conflict("Машина «" + name + "» уже есть в доме");
            }
            car.setName(name);
        }
        apply(car, d);
        return car;
    }

    /** Убрать машину из списка. История событий по ней остаётся. */
    @Transactional
    public void archive(Driver owner, long carId) {
        households.requireOwner(owner);
        var car = require(owner, carId);
        if (car.getState() != CarState.FREE) {
            throw new AutologException.Conflict("Машина сейчас " + describe(car.getState()) + ". Архивировать можно только свободную");
        }
        car.setArchived(true);
    }

    /** Поля, общие для создания и изменения; имя и тип обрабатываются отдельно. */
    private void apply(Car car, CarDraft d) {
        if (d.plate() != null) {
            var plate = d.plate().trim().toUpperCase().replaceAll("\\s+", " ");
            if (plate.length() > 16) throw new AutologException.Invalid("Госномер — не длиннее 16 символов");
            car.setPlate(plate.isEmpty() ? null : plate);
        }
        if (d.tankLiters() != null) {
            if (car.isElectric()) throw new AutologException.Invalid("Объём бака указывается только для дизеля");
            car.setTankLiters(positive(d.tankLiters(), MAX_TANK_LITERS, "Объём бака, л"));
        }
        if (d.batteryKwh() != null) {
            if (!car.isElectric()) throw new AutologException.Invalid("Ёмкость батареи указывается только для электро");
            car.setBatteryKwh(positive(d.batteryKwh(), MAX_BATTERY_KWH, "Ёмкость батареи, кВт·ч"));
        }
        if (d.ratedConsumption() != null) {
            car.setRatedConsumption(positive(d.ratedConsumption(), MAX_CONSUMPTION,
                    car.isElectric() ? "Расход, кВт·ч/100 км" : "Расход, л/100 км"));
        }
        if (d.odometerKm() != null) {
            int km = d.odometerKm();
            if (km < 0 || km > MAX_ODOMETER_KM) throw new AutologException.Invalid("Пробег — от 0 до " + MAX_ODOMETER_KM + " км");
            if (car.getId() != null && km < car.getOdometerKm()) {
                throw new AutologException.Invalid("Пробег не может уменьшиться: сейчас " + car.getOdometerKm() + " км");
            }
            car.setOdometerKm(km);
        }
    }

    private static void requireCapacity(Car car) {
        if (car.isElectric() && car.getBatteryKwh() == null) {
            throw new AutologException.Invalid("Для электро укажите ёмкость батареи, кВт·ч");
        }
        if (!car.isElectric() && car.getTankLiters() == null) {
            throw new AutologException.Invalid("Для дизеля укажите объём бака, л");
        }
    }

    private static BigDecimal positive(BigDecimal v, BigDecimal max, String what) {
        if (v.signum() <= 0 || v.compareTo(max) > 0) {
            throw new AutologException.Invalid(what + ": нужно число больше 0 и не больше " + max.toPlainString());
        }
        return v;
    }

    static String cleanName(String name) {
        var n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new AutologException.Invalid("Укажите название машины");
        if (n.length() > 64) throw new AutologException.Invalid("Название машины — не длиннее 64 символов");
        return n;
    }

    static String describe(CarState state) {
        return switch (state) {
            case FREE -> "свободна";
            case ON_TRIP -> "в поездке";
            case CHARGING -> "на зарядке";
        };
    }
}
