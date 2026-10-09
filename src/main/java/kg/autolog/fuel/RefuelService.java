package kg.autolog.fuel;

import kg.autolog.car.Car;
import kg.autolog.car.CarRepository;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.MileageGap;
import kg.autolog.trip.MileageGapRepository;
import kg.autolog.trip.Trip;
import kg.autolog.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;

@Service
@RequiredArgsConstructor
public class RefuelService {

    static final BigDecimal MIN_PRICE = new BigDecimal("10");
    static final BigDecimal MAX_PRICE = new BigDecimal("1000");
    static final BigDecimal MAX_TOTAL = new BigDecimal("200000");
    static final BigDecimal MAX_LITERS_NO_TANK = new BigDecimal("300");

    private final RefuelRepository refuels;
    private final CarService cars;
    private final CarRepository carRepository;
    private final TripService trips;
    private final HouseholdService households;
    private final MileageGapRepository gaps;
    private final Clock clock;

    /**
     * @param refuel сохранённая заправка (все три значения заполнены)
     * @param trip   поездка, во время которой заправились, или {@code null}
     * @param gap    неучтённый пробег, если машина стояла, а одометр ушёл вперёд
     */
    public record Result(Refuel refuel, Car car, Trip trip, MileageGap gap) {
    }

    /**
     * Записать заправку. Из литров, цены за литр и суммы достаточно любых двух — третье посчитается.
     * Если указаны все три, они должны сходиться (с точностью до сома или 2 %).
     */
    @Transactional
    public Result record(Driver driver, long carId, int odometerKm,
                        BigDecimal liters, BigDecimal pricePerLiter, BigDecimal totalCost) {
        var car = cars.require(driver, carId);
        if (car.isElectric()) throw new AutologException.Invalid("Заправка — только для дизеля. Для электро будет зарядка");

        Trip trip = null;
        if (car.getState() == CarState.ON_TRIP) {
            trip = trips.openTripOf(car.getId()).orElseThrow();
            boolean mine = trip.getDriverId().equals(driver.getId());
            if (!mine && !households.requireMembership(driver).isOwner()) {
                throw new AutologException.Forbidden(trips.busyMessage(car) + ". Заправку в поездке отмечает тот, кто за рулём");
            }
            if (odometerKm < trip.getStartOdometerKm()) {
                throw new AutologException.Invalid("Пробег меньше, чем на старте поездки: " + trip.getStartOdometerKm() + " км");
            }
            if (odometerKm - trip.getStartOdometerKm() > TripService.MAX_TRIP_KM) {
                throw new AutologException.Invalid("Больше " + TripService.MAX_TRIP_KM + " км от старта поездки — похоже на опечатку");
            }
        } else {
            if (odometerKm < car.getOdometerKm()) {
                throw new AutologException.Invalid("Пробег меньше последнего известного: " + car.getOdometerKm() + " км");
            }
            if (odometerKm - car.getOdometerKm() > TripService.MAX_GAP_KM) {
                throw new AutologException.Invalid("Пробег больше последнего известного на "
                        + (odometerKm - car.getOdometerKm()) + " км — похоже на опечатку");
            }
        }

        var amounts = complete(liters, pricePerLiter, totalCost);
        var maxLiters = car.getTankLiters() == null ? MAX_LITERS_NO_TANK
                : car.getTankLiters().multiply(new BigDecimal("1.1")).setScale(0, RoundingMode.UP);
        if (amounts.liters().compareTo(maxLiters) > 0) {
            throw new AutologException.Invalid(amounts.liters().toPlainString() + " л — больше объёма бака ("
                    + car.getTankLiters().toPlainString() + " л). Проверьте литры");
        }

        var now = clock.instant();
        MileageGap gap = null;
        if (trip == null && odometerKm > car.getOdometerKm()) {
            // Машина стояла свободной, а одометр ушёл вперёд — кто-то ездил без отметки
            gap = gaps.save(new MileageGap(car.getId(), car.getOdometerKm(), odometerKm, now, null));
            car.setOdometerKm(odometerKm);
            carRepository.save(car);
        }
        var refuel = refuels.save(new Refuel(car.getId(), driver.getId(), trip == null ? null : trip.getId(), now,
                odometerKm, amounts.liters(), amounts.price(), amounts.total()));
        return new Result(refuel, car, trip, gap);
    }

    record Amounts(BigDecimal liters, BigDecimal price, BigDecimal total) {
    }

    /** Достраивает третье значение из двух и проверяет, что всё в разумных пределах. */
    static Amounts complete(BigDecimal liters, BigDecimal price, BigDecimal total) {
        int given = (liters != null ? 1 : 0) + (price != null ? 1 : 0) + (total != null ? 1 : 0);
        if (given < 2) throw new AutologException.Invalid("Нужны два значения из трёх: литры, цена за литр, сумма");
        positive(liters, "Литры");
        positive(price, "Цена за литр");
        positive(total, "Сумма");
        if (price != null && (price.compareTo(MIN_PRICE) < 0 || price.compareTo(MAX_PRICE) > 0)) {
            throw new AutologException.Invalid("Цена за литр — от " + MIN_PRICE + " до " + MAX_PRICE + " сом. Проверьте цену");
        }
        if (total != null && total.compareTo(MAX_TOTAL) > 0) {
            throw new AutologException.Invalid("Сумма больше " + MAX_TOTAL + " сом — проверьте сумму");
        }
        if (liters == null) {
            liters = total.divide(price, 2, RoundingMode.HALF_UP);
        } else if (price == null) {
            price = total.divide(liters, 2, RoundingMode.HALF_UP);
            if (price.compareTo(MIN_PRICE) < 0 || price.compareTo(MAX_PRICE) > 0) {
                throw new AutologException.Invalid("Получается " + price.toPlainString() + " сом за литр — проверьте литры и сумму");
            }
        } else if (total == null) {
            total = liters.multiply(price).setScale(2, RoundingMode.HALF_UP);
        } else {
            var expected = liters.multiply(price);
            var tolerance = total.multiply(new BigDecimal("0.02")).max(BigDecimal.ONE);
            if (expected.subtract(total).abs().compareTo(tolerance) > 0) {
                throw new AutologException.Invalid("Не сходится: " + liters.toPlainString() + " л × " + price.toPlainString()
                        + " = " + expected.setScale(0, RoundingMode.HALF_UP).toPlainString() + " сом, а не " + total.toPlainString());
            }
        }
        return new Amounts(liters.setScale(2, RoundingMode.HALF_UP), price.setScale(2, RoundingMode.HALF_UP),
                total.setScale(2, RoundingMode.HALF_UP));
    }

    private static void positive(BigDecimal v, String what) {
        if (v != null && v.signum() <= 0) throw new AutologException.Invalid(what + " — больше нуля");
    }
}
