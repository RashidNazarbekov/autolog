package kg.autolog.trip;

import kg.autolog.car.Car;
import kg.autolog.car.CarRepository;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverRepository;
import kg.autolog.fuel.FuelEconomy;
import kg.autolog.household.HouseholdMemberRepository;
import kg.autolog.household.HouseholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TripService {

    /** Больше — почти наверняка опечатка в пробеге. */
    public static final int MAX_TRIP_KM = 2_000;
    public static final int MAX_GAP_KM = 5_000;
    public static final int MAX_RANGE_KM = 2_000;

    private final TripRepository trips;
    private final MileageGapRepository gaps;
    private final CarRepository carRepository;
    private final CarService cars;
    private final HouseholdService households;
    private final HouseholdMemberRepository members;
    private final DriverRepository drivers;
    private final FuelEconomy fuel;
    private final Clock clock;

    /** @param gap неучтённый пробег перед этой поездкой, если одометр ушёл вперёд */
    public record StartResult(Trip trip, Car car, MileageGap gap) {
    }

    /**
     * @param energyKwh       для электро: сколько кВт·ч ушло по падению заряда
     * @param estimatedLiters для дизеля: км × средний расход (по заправкам, иначе заводской)
     * @param estimatedCost   для дизеля: литры × средняя цена по заправкам; {@code null}, если заправок не было
     * @param litersPer100Km  расход, по которому считали литры
     * @param fromRefuels     расход посчитан по заправкам ({@code false} — заводской)
     */
    public record FinishResult(Trip trip, Car car, int distanceKm, Duration duration,
                               BigDecimal energyKwh, BigDecimal estimatedLiters, BigDecimal estimatedCost,
                               BigDecimal litersPer100Km, boolean fromRefuels) {

        /** кВт·ч на 100 км для электро, если есть пробег. */
        public BigDecimal consumptionPer100() {
            if (energyKwh == null || distanceKm <= 0) return null;
            return energyKwh.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(distanceKm), 1, RoundingMode.HALF_UP);
        }
    }

    /** Открытая поездка водителя, если есть. */
    public Optional<Trip> currentTrip(Driver driver) {
        return trips.findFirstByDriverIdAndStatus(driver.getId(), TripStatus.OPEN);
    }

    /** Открытая поездка машины, если есть. */
    public Optional<Trip> openTripOf(long carId) {
        return trips.findFirstByCarIdAndStatus(carId, TripStatus.OPEN);
    }

    /** Открытая поездка, которую водитель видит (его дом). */
    public Trip requireOpenTrip(Driver driver, long tripId) {
        var trip = trips.findById(tripId).filter(Trip::isOpen)
                .orElseThrow(() -> new AutologException.NotFound("Эта поездка уже закончена"));
        cars.require(driver, trip.getCarId());
        return trip;
    }

    @Transactional
    public StartResult start(Driver driver, long carId, int odometerKm, Integer socPct, Integer rangeKm) {
        var car = cars.require(driver, carId);
        if (car.getState() != CarState.FREE) {
            throw new AutologException.Conflict(busyMessage(car));
        }
        currentTrip(driver).ifPresent(t -> {
            throw new AutologException.Conflict("У вас уже идёт поездка на другой машине. Сначала закончите её");
        });
        if (odometerKm < car.getOdometerKm()) {
            throw new AutologException.Invalid("Пробег меньше последнего известного: " + car.getOdometerKm() + " км. Проверьте одометр");
        }
        if (odometerKm - car.getOdometerKm() > MAX_GAP_KM) {
            throw new AutologException.Invalid("Пробег больше последнего известного на "
                    + (odometerKm - car.getOdometerKm()) + " км — похоже на опечатку. Проверьте одометр");
        }
        socPct = checkSoc(car, socPct);
        rangeKm = checkRange(rangeKm);

        var now = clock.instant();
        int previousKm = car.getOdometerKm();
        // Сначала занимаем машину: если её одновременно взял другой водитель, здесь будет конфликт версий
        car.setOdometerKm(odometerKm);
        car.setState(CarState.ON_TRIP);
        car.setCurrentDriverId(driver.getId());
        car.setStateSince(now);
        flush(car);

        Trip trip;
        try {
            trip = trips.saveAndFlush(new Trip(car.getId(), driver.getId(), now, odometerKm, socPct, rangeKm));
        } catch (DataIntegrityViolationException e) {
            throw new AutologException.Conflict("У машины или у вас уже есть открытая поездка. Обновите меню");
        }
        MileageGap gap = null;
        if (odometerKm > previousKm) {
            gap = gaps.save(new MileageGap(car.getId(), previousKm, odometerKm, now, trip.getId()));
        }
        return new StartResult(trip, car, gap);
    }

    @Transactional
    public FinishResult finish(Driver driver, long tripId, int odometerKm, Integer socPct, Integer rangeKm) {
        var trip = requireOpenTrip(driver, tripId);
        var car = cars.require(driver, trip.getCarId());
        if (!trip.getDriverId().equals(driver.getId()) && !households.requireMembership(driver).isOwner()) {
            throw new AutologException.Forbidden("Закончить поездку может тот, кто её начал, или владелец дома");
        }
        if (odometerKm < trip.getStartOdometerKm()) {
            throw new AutologException.Invalid("Пробег меньше, чем на старте: " + trip.getStartOdometerKm() + " км");
        }
        if (odometerKm - trip.getStartOdometerKm() > MAX_TRIP_KM) {
            throw new AutologException.Invalid("Больше " + MAX_TRIP_KM + " км за поездку — похоже на опечатку. Проверьте одометр");
        }
        socPct = checkSoc(car, socPct);
        if (socPct != null && trip.getStartSocPct() != null && socPct > trip.getStartSocPct()) {
            throw new AutologException.Invalid("Заряд в конце (" + socPct + " %) больше, чем на старте ("
                    + trip.getStartSocPct() + " %). Подзарядку в пути можно будет отмечать в следующей версии");
        }
        rangeKm = checkRange(rangeKm);

        var now = clock.instant();
        trip.setEndOdometerKm(odometerKm);
        trip.setEndSocPct(socPct);
        trip.setEndRangeKm(rangeKm);
        trip.setFinishedAt(now);
        trip.setStatus(TripStatus.FINISHED);

        car.setOdometerKm(odometerKm);
        car.setState(CarState.FREE);
        car.setCurrentDriverId(null);
        car.setStateSince(now);
        flush(car);

        int km = trip.distanceKm();
        if (car.isElectric()) {
            BigDecimal energy = null;
            if (trip.getStartSocPct() != null && socPct != null) {
                energy = car.getBatteryKwh().multiply(BigDecimal.valueOf(trip.getStartSocPct() - socPct))
                        .divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
            }
            return new FinishResult(trip, car, km, trip.duration(), energy, null, null, null, false);
        }
        var f = fuel.tripFuel(car, km).orElse(null);
        return f == null
                ? new FinishResult(trip, car, km, trip.duration(), null, null, null, null, false)
                : new FinishResult(trip, car, km, trip.duration(), null, f.liters(), f.cost(),
                        f.basis().litersPer100Km(), f.basis().fromRefuels());
    }

    /** Неучтённые км, по которым ещё не выяснили, кто ездил. */
    public List<MileageGap> openGaps(Driver driver) {
        var carIds = cars.list(driver).stream().map(Car::getId).toList();
        if (carIds.isEmpty()) return List.of();
        return gaps.findByCarIdInAndResolvedAtIsNullOrderByDetectedAt(carIds);
    }

    /** Приписать неучтённые км водителю дома. Сделать это может любой участник дома. */
    @Transactional
    public MileageGap assignGap(Driver driver, long gapId, long assigneeDriverId) {
        var gap = gaps.findById(gapId).orElseThrow(() -> new AutologException.NotFound("Запись о неучтённом пробеге не найдена"));
        var car = cars.require(driver, gap.getCarId());
        members.findByHouseholdIdAndDriverId(car.getHouseholdId(), assigneeDriverId)
                .orElseThrow(() -> new AutologException.NotFound("Такого водителя нет в доме"));
        if (gap.isResolved()) {
            throw new AutologException.Conflict("Эти километры уже приписаны водителю");
        }
        gap.setDriverId(assigneeDriverId);
        gap.setResolvedAt(clock.instant());
        return gap;
    }

    /** «Прадо в поездке у Айбека с 09:40». */
    public String busyMessage(Car car) {
        var who = car.getCurrentDriverId() == null ? "другого водителя"
                : drivers.findById(car.getCurrentDriverId()).map(Driver::getName).orElse("другого водителя");
        return switch (car.getState()) {
            case ON_TRIP -> "«" + car.getName() + "» сейчас в поездке у: " + who;
            case CHARGING -> "«" + car.getName() + "» сейчас на зарядке";
            case FREE -> "«" + car.getName() + "» свободна";
        };
    }

    private Integer checkSoc(Car car, Integer socPct) {
        if (!car.isElectric()) return null;
        if (socPct == null) throw new AutologException.Invalid("Для электро укажите заряд батареи, %");
        if (socPct < 0 || socPct > 100) throw new AutologException.Invalid("Заряд — от 0 до 100 %");
        return socPct;
    }

    private static Integer checkRange(Integer rangeKm) {
        if (rangeKm == null) return null;
        if (rangeKm < 0 || rangeKm > MAX_RANGE_KM) {
            throw new AutologException.Invalid("Запас хода — от 0 до " + MAX_RANGE_KM + " км");
        }
        return rangeKm;
    }

    /** Сразу записываем машину: если её одновременно взял другой водитель, узнаем об этом здесь. */
    private void flush(Car car) {
        try {
            carRepository.saveAndFlush(car);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new AutologException.Conflict("«" + car.getName() + "» только что взял другой водитель. Обновите меню");
        }
    }
}
