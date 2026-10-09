package kg.autolog.charge;

import kg.autolog.car.Car;
import kg.autolog.car.CarRepository;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.MileageGap;
import kg.autolog.trip.MileageGapRepository;
import kg.autolog.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChargeService {

    static final BigDecimal MAX_PAID = new BigDecimal("100000");

    private final ChargeRepository charges;
    private final ChargeEconomy economy;
    private final CarService cars;
    private final CarRepository carRepository;
    private final TripService trips;
    private final HouseholdService households;
    private final MileageGapRepository gaps;
    private final Clock clock;

    public record StartResult(Charge charge, Car car, MileageGap gap) {
    }

    public record FinishResult(Charge charge, Car car, Duration duration) {
    }

    public Optional<Charge> openChargeOf(long carId) {
        return charges.findFirstByCarIdAndStatus(carId, ChargeStatus.OPEN);
    }

    public Charge requireOpenCharge(Driver driver, long chargeId) {
        var charge = charges.findById(chargeId).filter(Charge::isOpen)
                .orElseThrow(() -> new AutologException.NotFound("Эта зарядка уже закончена"));
        cars.require(driver, charge.getCarId());
        return charge;
    }

    /** Поставить свободную машину на зарядку. Машина переходит в состояние «на зарядке». */
    @Transactional
    public StartResult start(Driver driver, long carId, ChargeLocation location, int odometerKm, int socPct) {
        var car = requireElectric(driver, carId);
        if (car.getState() != CarState.FREE) {
            throw new AutologException.Conflict(trips.busyMessage(car));
        }
        if (odometerKm < car.getOdometerKm()) {
            throw new AutologException.Invalid("Пробег меньше последнего известного: " + car.getOdometerKm() + " км");
        }
        if (odometerKm - car.getOdometerKm() > TripService.MAX_GAP_KM) {
            throw new AutologException.Invalid("Пробег больше последнего известного на "
                    + (odometerKm - car.getOdometerKm()) + " км — похоже на опечатку");
        }
        checkSoc(socPct);
        var now = clock.instant();
        int previousKm = car.getOdometerKm();
        car.setOdometerKm(odometerKm);
        car.setState(CarState.CHARGING);
        car.setCurrentDriverId(driver.getId());
        car.setStateSince(now);
        flush(car);
        Charge charge;
        try {
            charge = charges.saveAndFlush(new Charge(car.getId(), driver.getId(), null, location, now, odometerKm, socPct));
        } catch (DataIntegrityViolationException e) {
            throw new AutologException.Conflict("Машина уже на зарядке. Обновите меню");
        }
        MileageGap gap = null;
        if (odometerKm > previousKm) {
            gap = gaps.save(new MileageGap(car.getId(), previousKm, odometerKm, now, null));
        }
        return new StartResult(charge, car, gap);
    }

    /**
     * Снять с зарядки. Сделать это может любой участник дома — снимать с домашней розетки может не тот, кто ставил.
     *
     * @param measuredKwh кВт·ч со счётчика станции, если известны
     * @param paid        заплачено, сом; если пусто — кВт·ч × цена из настроек дома
     */
    @Transactional
    public FinishResult finish(Driver driver, long chargeId, int endSocPct, BigDecimal measuredKwh, BigDecimal paid) {
        var charge = requireOpenCharge(driver, chargeId);
        var car = cars.require(driver, charge.getCarId());
        checkSoc(endSocPct);
        if (endSocPct < charge.getStartSocPct()) {
            throw new AutologException.Invalid("Заряд меньше, чем в начале зарядки (" + charge.getStartSocPct() + " %)");
        }
        checkAmounts(car, measuredKwh, paid);
        fill(charge, car, endSocPct, measuredKwh, paid);
        car.setState(CarState.FREE);
        car.setCurrentDriverId(null);
        car.setStateSince(charge.getFinishedAt());
        flush(car);
        return new FinishResult(charge, car, Duration.between(charge.getStartedAt(), charge.getFinishedAt()));
    }

    /**
     * Подзарядка во время поездки — записывается одной отметкой: заряд до и после, кВт·ч и сумма по желанию.
     * Машина остаётся в поездке. Отмечает водитель поездки или владелец дома.
     */
    @Transactional
    public Charge roadCharge(Driver driver, long tripId, ChargeLocation location, int socBefore, int socAfter,
                             BigDecimal measuredKwh, BigDecimal paid) {
        var trip = trips.requireOpenTrip(driver, tripId);
        var car = requireElectric(driver, trip.getCarId());
        if (!trip.getDriverId().equals(driver.getId()) && !households.requireMembership(driver).isOwner()) {
            throw new AutologException.Forbidden("Подзарядку в поездке отмечает тот, кто за рулём");
        }
        checkSoc(socBefore);
        checkSoc(socAfter);
        if (socAfter <= socBefore) {
            throw new AutologException.Invalid("После зарядки заряд должен быть больше, чем до (" + socBefore + " %)");
        }
        checkAmounts(car, measuredKwh, paid);
        var charge = new Charge(car.getId(), driver.getId(), trip.getId(), location, clock.instant(), null, socBefore);
        fill(charge, car, socAfter, measuredKwh, paid);
        return charges.save(charge);
    }

    private void fill(Charge charge, Car car, int endSocPct, BigDecimal measuredKwh, BigDecimal paid) {
        var a = economy.amounts(car, charge.getLocation(), charge.getStartSocPct(), endSocPct, measuredKwh, paid);
        charge.setEndSocPct(endSocPct);
        charge.setBatteryKwh(a.batteryKwh());
        charge.setGridKwh(a.gridKwh());
        charge.setKwhMeasured(a.measured());
        charge.setPricePerKwh(a.pricePerKwh());
        charge.setTotalCost(a.totalCost());
        charge.setFinishedAt(clock.instant());
        charge.setStatus(ChargeStatus.FINISHED);
    }

    private Car requireElectric(Driver driver, long carId) {
        var car = cars.require(driver, carId);
        if (!car.isElectric()) throw new AutologException.Invalid("Зарядка — только для электро. Для дизеля есть заправка");
        return car;
    }

    private static void checkSoc(int soc) {
        if (soc < 0 || soc > 100) throw new AutologException.Invalid("Заряд — от 0 до 100 %");
    }

    private static void checkAmounts(Car car, BigDecimal measuredKwh, BigDecimal paid) {
        if (measuredKwh != null) {
            var max = car.getBatteryKwh().multiply(BigDecimal.valueOf(2));
            if (measuredKwh.signum() <= 0 || measuredKwh.compareTo(max) > 0) {
                throw new AutologException.Invalid("кВт·ч со станции — от 0 до " + max.toPlainString() + ". Проверьте число");
            }
        }
        if (paid != null && (paid.signum() < 0 || paid.compareTo(MAX_PAID) > 0)) {
            throw new AutologException.Invalid("Сумма — от 0 до " + MAX_PAID.toPlainString() + " сом");
        }
    }

    private void flush(Car car) {
        try {
            carRepository.saveAndFlush(car);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new AutologException.Conflict("«" + car.getName() + "» только что взял другой водитель. Обновите меню");
        }
    }
}
