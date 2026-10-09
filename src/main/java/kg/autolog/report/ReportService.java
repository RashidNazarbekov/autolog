package kg.autolog.report;

import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.charge.Charge;
import kg.autolog.charge.ChargeEconomy;
import kg.autolog.charge.ChargeRepository;
import kg.autolog.charge.ChargeStatus;
import kg.autolog.driver.Driver;
import kg.autolog.expense.Expense;
import kg.autolog.expense.ExpenseCategory;
import kg.autolog.expense.ExpenseRepository;
import kg.autolog.expense.ExpenseService;
import kg.autolog.fuel.FuelEconomy;
import kg.autolog.fuel.Refuel;
import kg.autolog.fuel.RefuelRepository;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.MileageGap;
import kg.autolog.trip.MileageGapRepository;
import kg.autolog.trip.Trip;
import kg.autolog.trip.TripRepository;
import kg.autolog.trip.TripStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Отчёты за период: по машинам, по водителям, дизель против электро, журнал событий.
 *
 * <p>Стоимость энергии в отчёте — по расходу и средней цене (км × расход × цена), а не сумма заправок за период:
 * заправка раз в две недели иначе делала бы одну неделю дорогой, а другую бесплатной.
 * Сколько реально заплатили за топливо и зарядки — показывается отдельно.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CarService cars;
    private final HouseholdService households;
    private final ExpenseService expenseService;
    private final TripRepository trips;
    private final MileageGapRepository gaps;
    private final RefuelRepository refuels;
    private final ChargeRepository charges;
    private final ExpenseRepository expenses;
    private final FuelEconomy fuel;
    private final ChargeEconomy chargeEconomy;
    private final Clock clock;

    /**
     * @param km                 пробег за период: поездки + неучтённые км
     * @param untrackedKm        неучтённые км, которые никому не приписаны
     * @param fuelLiters         дизель: залито за период
     * @param chargedKwh         электро: из сети за период
     * @param energyPaid         заплачено за топливо или зарядки за период
     * @param consumption        л/100 км или кВт·ч/100 км
     * @param consumptionMeasured расход посчитан по вашим данным, а не заводской
     * @param energyPerKm        стоимость энергии на 1 км, сом (null — не знаем цену или расход)
     * @param energyCost         км × стоимость энергии на км
     * @param otherExpenses      прочие расходы за период с учётом распределения
     * @param totalCost          энергия + прочие расходы
     * @param totalPerKm         полная стоимость 1 км
     */
    public record CarReport(Car car, int km, int trips, int untrackedKm,
                            BigDecimal fuelLiters, BigDecimal chargedKwh, BigDecimal energyPaid,
                            BigDecimal consumption, boolean consumptionMeasured,
                            BigDecimal energyPerKm, BigDecimal energyCost,
                            BigDecimal otherExpenses, Map<String, BigDecimal> expensesByCategory,
                            BigDecimal totalCost, BigDecimal totalPerKm) {
    }

    /**
     * @param tripCost сколько стоила энергия на его поездках (км × стоимость км машины)
     * @param paid     сколько он заплатил сам: заправки, зарядки, прочие расходы
     */
    public record DriverReport(long driverId, String name, int km, int trips,
                               Map<String, Integer> kmByCar, BigDecimal tripCost, BigDecimal paid) {
    }

    /**
     * @param dieselPerKm стоимость энергии 1 км на дизеле
     * @param evPerKm     то же на электро
     * @param evKm        сколько проехали на электро за период
     * @param savings     сколько сэкономили: (дизель − электро) × км на электро
     */
    public record Comparison(BigDecimal dieselPerKm, BigDecimal evPerKm, int evKm, BigDecimal savings,
                             BigDecimal dieselTotalPerKm, BigDecimal evTotalPerKm) {
    }

    public record Report(ReportPeriod period, List<CarReport> cars, List<DriverReport> drivers,
                         Comparison comparison, int totalKm, BigDecimal totalCost) {
    }

    public record JournalEntry(Instant at, String icon, String text) {
    }

    @Transactional(readOnly = true)
    public Report build(Driver driver, ReportPeriod period) {
        ZoneId zone = clock.getZone();
        var carList = cars.list(driver);
        var carIds = carList.stream().map(Car::getId).toList();
        Instant from = period.start(zone);
        Instant to = period.end(zone);

        List<Trip> periodTrips = carIds.isEmpty() ? List.of()
                : trips.findByCarIdInAndStatusAndFinishedAtBetween(carIds, TripStatus.FINISHED, from, to);
        List<MileageGap> periodGaps = carIds.isEmpty() ? List.of() : gaps.findByCarIdInAndDetectedAtBetween(carIds, from, to);
        List<Refuel> periodRefuels = carIds.isEmpty() ? List.of() : refuels.findByCarIdInAndRefueledAtBetween(carIds, from, to);
        List<Charge> periodCharges = carIds.isEmpty() ? List.of()
                : charges.findByCarIdInAndStatusAndFinishedAtBetween(carIds, ChargeStatus.FINISHED, from, to);
        List<Expense> periodExpenses = carIds.isEmpty() ? List.of()
                : expenses.findByCarIdInAndSpentOnBetween(carIds, period.from().minusMonths(ExpenseService.MAX_SPREAD_MONTHS), period.to());
        var tripIds = periodTrips.stream().map(Trip::getId).toList();
        List<Charge> roadCharges = tripIds.isEmpty() ? List.of() : charges.findByTripIdInAndStatus(tripIds, ChargeStatus.FINISHED);
        Map<Long, ExpenseCategory> categories = expenseService.categories(driver).stream()
                .collect(Collectors.toMap(ExpenseCategory::getId, Function.identity()));

        var carReports = new ArrayList<CarReport>();
        for (var car : carList) {
            carReports.add(carReport(car, period,
                    filter(periodTrips, t -> t.getCarId().equals(car.getId())),
                    filter(periodGaps, g -> g.getCarId().equals(car.getId())),
                    filter(periodRefuels, r -> r.getCarId().equals(car.getId())),
                    filter(periodCharges, c -> c.getCarId().equals(car.getId())),
                    filter(periodExpenses, e -> e.getCarId().equals(car.getId())),
                    roadCharges, categories));
        }
        var perKmByCar = carReports.stream().filter(r -> r.energyPerKm() != null)
                .collect(Collectors.toMap(r -> r.car().getId(), CarReport::energyPerKm));
        var carNames = carList.stream().collect(Collectors.toMap(Car::getId, Car::getName));

        var driverReports = new ArrayList<DriverReport>();
        for (var m : households.members(driver)) {
            var mine = filter(periodTrips, t -> t.getDriverId() == m.driverId());
            var myGaps = filter(periodGaps, g -> g.getDriverId() != null && g.getDriverId() == m.driverId());
            var kmByCar = new LinkedHashMap<String, Integer>();
            var cost = BigDecimal.ZERO;
            for (var t : mine) {
                kmByCar.merge(carNames.get(t.getCarId()), t.distanceKm(), Integer::sum);
                cost = cost.add(perKmByCar.getOrDefault(t.getCarId(), BigDecimal.ZERO).multiply(BigDecimal.valueOf(t.distanceKm())));
            }
            for (var g : myGaps) {
                kmByCar.merge(carNames.get(g.getCarId()), g.km(), Integer::sum);
                cost = cost.add(perKmByCar.getOrDefault(g.getCarId(), BigDecimal.ZERO).multiply(BigDecimal.valueOf(g.km())));
            }
            var paid = sum(filter(periodRefuels, r -> r.getDriverId() == m.driverId()), Refuel::getTotalCost)
                    .add(sum(filter(periodCharges, c -> c.getDriverId() == m.driverId()), Charge::getTotalCost))
                    .add(sum(filter(periodExpenses, e -> e.getDriverId() == m.driverId() && inPeriod(e.getSpentOn(), period)), Expense::getAmount));
            int km = kmByCar.values().stream().mapToInt(Integer::intValue).sum();
            driverReports.add(new DriverReport(m.driverId(), m.name(), km, mine.size(), kmByCar,
                    cost.setScale(0, RoundingMode.HALF_UP), paid.setScale(0, RoundingMode.HALF_UP)));
        }
        driverReports.sort(Comparator.comparingInt(DriverReport::km).reversed());

        int totalKm = carReports.stream().mapToInt(CarReport::km).sum();
        var total = carReports.stream().map(CarReport::totalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(period, carReports, driverReports, comparison(carReports), totalKm, total);
    }

    private CarReport carReport(Car car, ReportPeriod period, List<Trip> carTrips, List<MileageGap> carGaps,
                                List<Refuel> carRefuels, List<Charge> carCharges, List<Expense> carExpenses,
                                List<Charge> roadCharges, Map<Long, ExpenseCategory> categories) {
        int tripKm = carTrips.stream().mapToInt(Trip::distanceKm).sum();
        int gapKm = carGaps.stream().mapToInt(MileageGap::km).sum();
        int untracked = carGaps.stream().filter(g -> g.getDriverId() == null).mapToInt(MileageGap::km).sum();
        int km = tripKm + gapKm;

        BigDecimal liters = null;
        BigDecimal kwh = null;
        BigDecimal paid;
        BigDecimal consumption = null;
        boolean measured = false;
        BigDecimal perKm = null;
        if (car.isElectric()) {
            kwh = sum(carCharges, Charge::getGridKwh);
            paid = sum(carCharges, Charge::getTotalCost);
            var energy = BigDecimal.ZERO;
            int socKm = 0;
            for (var t : carTrips) {
                if (t.getStartSocPct() == null || t.getEndSocPct() == null || t.distanceKm() <= 0) continue;
                int gain = roadCharges.stream().filter(c -> t.getId().equals(c.getTripId())).mapToInt(Charge::socGain).sum();
                energy = energy.add(car.getBatteryKwh().multiply(BigDecimal.valueOf(t.getStartSocPct() + gain - t.getEndSocPct()))
                        .divide(HUNDRED, 4, RoundingMode.HALF_UP));
                socKm += t.distanceKm();
            }
            if (socKm > 0 && energy.signum() > 0) {
                consumption = energy.multiply(HUNDRED).divide(BigDecimal.valueOf(socKm), 1, RoundingMode.HALF_UP);
                measured = true;
            } else {
                consumption = car.getRatedConsumption();
            }
            if (consumption != null) {
                perKm = consumption.multiply(chargeEconomy.pricePerBatteryKwh(car)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            }
        } else {
            liters = sum(carRefuels, Refuel::getLiters);
            paid = sum(carRefuels, Refuel::getTotalCost);
            var c = fuel.consumption(car).orElse(null);
            var price = fuel.averagePrice(car).orElse(null);
            if (c != null) {
                consumption = c.litersPer100Km();
                measured = c.fromRefuels();
                if (price != null) perKm = consumption.multiply(price).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            }
        }
        var energyCost = perKm == null ? paid : perKm.multiply(BigDecimal.valueOf(km)).setScale(0, RoundingMode.HALF_UP);

        var byCategory = new LinkedHashMap<String, BigDecimal>();
        var other = BigDecimal.ZERO;
        for (var e : carExpenses) {
            var share = amortized(e, period);
            if (share.signum() == 0) continue;
            other = other.add(share);
            var cat = categories.get(e.getCategoryId());
            byCategory.merge(cat == null ? "💳 Другое" : cat.title(), share, BigDecimal::add);
        }
        byCategory.replaceAll((k, v) -> v.setScale(0, RoundingMode.HALF_UP));
        other = other.setScale(0, RoundingMode.HALF_UP);
        var total = energyCost.add(other);
        var totalPerKm = km > 0 ? total.divide(BigDecimal.valueOf(km), 2, RoundingMode.HALF_UP) : null;
        return new CarReport(car, km, carTrips.size(), untracked, liters, kwh, paid.setScale(0, RoundingMode.HALF_UP),
                consumption, measured, perKm, energyCost, other, byCategory, total, totalPerKm);
    }

    private static Comparison comparison(List<CarReport> reports) {
        var diesel = reports.stream().filter(r -> !r.car().isElectric()).toList();
        var ev = reports.stream().filter(r -> r.car().isElectric()).toList();
        if (diesel.isEmpty() || ev.isEmpty()) return null;
        var dieselPerKm = weightedPerKm(diesel, CarReport::energyPerKm);
        var evPerKm = weightedPerKm(ev, CarReport::energyPerKm);
        if (dieselPerKm == null || evPerKm == null) return null;
        int evKm = ev.stream().mapToInt(CarReport::km).sum();
        var savings = dieselPerKm.subtract(evPerKm).multiply(BigDecimal.valueOf(evKm)).setScale(0, RoundingMode.HALF_UP);
        return new Comparison(dieselPerKm, evPerKm, evKm, savings,
                weightedPerKm(diesel, CarReport::totalPerKm), weightedPerKm(ev, CarReport::totalPerKm));
    }

    /** Средняя по машинам стоимость км, взвешенная по пробегу; без пробега — простая средняя. */
    private static BigDecimal weightedPerKm(List<CarReport> list, Function<CarReport, BigDecimal> perKm) {
        var known = list.stream().filter(r -> perKm.apply(r) != null).toList();
        if (known.isEmpty()) return null;
        int km = known.stream().mapToInt(CarReport::km).sum();
        if (km == 0) {
            return known.stream().map(perKm).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(known.size()), 2, RoundingMode.HALF_UP);
        }
        return known.stream().map(r -> perKm.apply(r).multiply(BigDecimal.valueOf(r.km())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(km), 2, RoundingMode.HALF_UP);
    }

    /**
     * Часть расхода, приходящаяся на период. Разовый — целиком, если дата в периоде.
     * Распределённый — по дням: доля месяца = сумма в месяц × дней месяца в периоде / дней в месяце.
     */
    static BigDecimal amortized(Expense e, ReportPeriod period) {
        if (e.getSpreadMonths() <= 1) return inPeriod(e.getSpentOn(), period) ? e.getAmount() : BigDecimal.ZERO;
        var first = YearMonth.from(e.getSpentOn());
        var last = first.plusMonths(e.getSpreadMonths() - 1L);
        var perMonth = e.getAmount().divide(BigDecimal.valueOf(e.getSpreadMonths()), 6, RoundingMode.HALF_UP);
        var total = BigDecimal.ZERO;
        for (var m = YearMonth.from(period.from()); !m.isAfter(YearMonth.from(period.to())); m = m.plusMonths(1)) {
            if (m.isBefore(first) || m.isAfter(last)) continue;
            LocalDate start = max(m.atDay(1), period.from());
            LocalDate end = min(m.atEndOfMonth(), period.to());
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            total = total.add(perMonth.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(m.lengthOfMonth()), 6, RoundingMode.HALF_UP));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------- Журнал ----------

    @Transactional(readOnly = true)
    public List<JournalEntry> journal(Driver driver, ReportPeriod period, int limit) {
        ZoneId zone = clock.getZone();
        var carList = cars.list(driver);
        if (carList.isEmpty()) return List.of();
        var carIds = carList.stream().map(Car::getId).toList();
        var names = carList.stream().collect(Collectors.toMap(Car::getId, Car::getName));
        var people = households.members(driver).stream()
                .collect(Collectors.toMap(HouseholdService.MemberView::driverId, HouseholdService.MemberView::name));
        Instant from = period.start(zone);
        Instant to = period.end(zone);
        var list = new ArrayList<JournalEntry>();

        for (var t : trips.findByCarIdInAndStatusAndFinishedAtBetween(carIds, TripStatus.FINISHED, from, to)) {
            list.add(new JournalEntry(t.getFinishedAt(), "🏁", who(people, t.getDriverId()) + " · «" + names.get(t.getCarId())
                    + "» · " + t.distanceKm() + " км"));
        }
        for (var t : trips.findByCarIdInAndStatus(carIds, TripStatus.OPEN)) {
            list.add(new JournalEntry(t.getStartedAt(), "▶", who(people, t.getDriverId()) + " · «" + names.get(t.getCarId())
                    + "» · в поездке"));
        }
        for (var r : refuels.findByCarIdInAndRefueledAtBetween(carIds, from, to)) {
            list.add(new JournalEntry(r.getRefueledAt(), "⛽", who(people, r.getDriverId()) + " · «" + names.get(r.getCarId())
                    + "» · " + plain(r.getLiters()) + " л · " + plain(r.getTotalCost()) + " сом"));
        }
        for (var c : charges.findByCarIdInAndStatusAndFinishedAtBetween(carIds, ChargeStatus.FINISHED, from, to)) {
            list.add(new JournalEntry(c.getFinishedAt(), "🔋", who(people, c.getDriverId()) + " · «" + names.get(c.getCarId())
                    + "» · " + c.getStartSocPct() + "→" + c.getEndSocPct() + " % · " + plain(c.getTotalCost()) + " сом"));
        }
        var categories = expenseService.categories(driver).stream()
                .collect(Collectors.toMap(ExpenseCategory::getId, ExpenseCategory::title));
        for (var e : expenses.findByCarIdInAndSpentOnBetween(carIds, period.from(), period.to())) {
            list.add(new JournalEntry(e.getCreatedAt(), "💳", who(people, e.getDriverId()) + " · «" + names.get(e.getCarId())
                    + "» · " + categories.getOrDefault(e.getCategoryId(), "Расход") + " · " + plain(e.getAmount()) + " сом"));
        }
        for (var g : gaps.findByCarIdInAndDetectedAtBetween(carIds, from, to)) {
            list.add(new JournalEntry(g.getDetectedAt(), "⚠️", "«" + names.get(g.getCarId()) + "» · " + g.km() + " км без отметки"
                    + (g.getDriverId() == null ? " · не разобрано" : " · " + who(people, g.getDriverId()))));
        }
        list.sort(Comparator.comparing(JournalEntry::at).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    // ---------- Мелочи ----------

    private static String who(Map<Long, String> people, Long id) {
        return id == null ? "—" : people.getOrDefault(id, "бывший участник");
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static boolean inPeriod(LocalDate d, ReportPeriod p) {
        return !d.isBefore(p.from()) && !d.isAfter(p.to());
    }

    private static <T> List<T> filter(List<T> list, java.util.function.Predicate<T> p) {
        return list.stream().filter(p).toList();
    }

    private static <T> BigDecimal sum(List<T> list, Function<T, BigDecimal> f) {
        return list.stream().map(f).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
