package kg.autolog.report;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.charge.ChargeLocation;
import kg.autolog.charge.ChargeService;
import kg.autolog.driver.Driver;
import kg.autolog.expense.ExpenseCategory;
import kg.autolog.expense.ExpenseService;
import kg.autolog.fuel.RefuelService;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import static kg.autolog.TestData.diesel;
import static kg.autolog.TestData.electric;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Отчёт на заданных данных:
 * <ul>
 *   <li>Прадо (дизель): заправка 40 л × 90 на 150 000; Айбек едет 400 км и в пути заправляет 40 л × 90 на 150 300
 *       → расход 40 л / 300 км = 13,3 л/100, топливо 13,3 × 90 / 100 = 11,97 сом/км.</li>
 *   <li>Эмка (электро): дома 20 → 80 % (44,41 сом за 24,18 кВт·ч → 1,84 сом/кВт·ч); Рашид едет 100 км, 80 → 65 %
 *       → 6,0 кВт·ч/100 км, 0,11 сом/км.</li>
 *   <li>Шины на Прадо 24 000 сом на 24 мес (1 000 в месяц), мойка Эмки 800 сом (Айбек).</li>
 * </ul>
 */
@Transactional
class ReportServiceTest extends IntegrationTest {

    @Autowired ReportService reports;
    @Autowired TripService trips;
    @Autowired RefuelService refuels;
    @Autowired ChargeService charges;
    @Autowired ExpenseService expenses;
    @Autowired CarService cars;
    @Autowired HouseholdService households;
    @Autowired TestData data;
    @Autowired Clock clock;

    Driver owner;
    Driver brother;
    Car prado;
    Car emka;
    ReportPeriod month;

    @BeforeEach
    void history() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        households.create(owner, "Дом");
        households.join(brother, households.createInvite(owner));
        prado = cars.add(owner, diesel("Прадо"));
        emka = cars.add(owner, electric("Эмка"));

        refuels.record(owner, prado.getId(), 150_000, d("40"), d("90"), null);
        var t1 = trips.start(brother, prado.getId(), 150_000, null, null).trip();
        refuels.record(brother, prado.getId(), 150_300, d("40"), d("90"), null);
        trips.finish(brother, t1.getId(), 150_400, null, null);

        var ch = charges.start(owner, emka.getId(), ChargeLocation.HOME, 1_200, 20).charge();
        charges.finish(owner, ch.getId(), 80, null, null);
        var t2 = trips.start(owner, emka.getId(), 1_200, 80, null).trip();
        trips.finish(owner, t2.getId(), 1_300, 65, null);

        expenses.record(owner, new ExpenseService.Draft(prado.getId(), category("Шины").getId(), d("24000"), null, null, null, null));
        expenses.record(brother, new ExpenseService.Draft(emka.getId(), category("Мойка и уход").getId(), d("800"), null, null, null, null));

        var m = YearMonth.now(clock);
        month = new ReportPeriod(m.atDay(1), m.atEndOfMonth(), "Месяц");
    }

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    ExpenseCategory category(String name) {
        return expenses.categories(owner).stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void dieselCar() {
        var r = reports.build(owner, month).cars().stream().filter(c -> c.car().getId().equals(prado.getId())).findFirst().orElseThrow();

        assertThat(r.km()).isEqualTo(400);
        assertThat(r.trips()).isEqualTo(1);
        assertThat(r.fuelLiters()).isEqualByComparingTo("80");
        assertThat(r.energyPaid()).isEqualByComparingTo("7200");
        assertThat(r.consumption()).isEqualByComparingTo("13.3");
        assertThat(r.consumptionMeasured()).isTrue();
        assertThat(r.energyPerKm()).isEqualByComparingTo("11.97");
        assertThat(r.energyCost()).isEqualByComparingTo("4788");
        assertThat(r.otherExpenses()).isEqualByComparingTo("1000");
        assertThat(r.expensesByCategory()).containsEntry("🛞 Шины", new BigDecimal("1000"));
        assertThat(r.totalCost()).isEqualByComparingTo("5788");
        assertThat(r.totalPerKm()).isEqualByComparingTo("14.47");
    }

    @Test
    void electricCar() {
        var r = reports.build(owner, month).cars().stream().filter(c -> c.car().getId().equals(emka.getId())).findFirst().orElseThrow();

        assertThat(r.km()).isEqualTo(100);
        assertThat(r.chargedKwh()).isEqualByComparingTo("27.08");
        assertThat(r.energyPaid()).isEqualByComparingTo("44");
        assertThat(r.consumption()).isEqualByComparingTo("6.0");
        assertThat(r.energyPerKm()).isEqualByComparingTo("0.11");
        assertThat(r.energyCost()).isEqualByComparingTo("11");
        assertThat(r.otherExpenses()).isEqualByComparingTo("800");
        assertThat(r.totalCost()).isEqualByComparingTo("811");
    }

    @Test
    void dieselVersusElectric() {
        var c = reports.build(owner, month).comparison();

        assertThat(c.dieselPerKm()).isEqualByComparingTo("11.97");
        assertThat(c.evPerKm()).isEqualByComparingTo("0.11");
        assertThat(c.evKm()).isEqualTo(100);
        assertThat(c.savings()).isEqualByComparingTo("1186"); // (11,97 − 0,11) × 100
    }

    @Test
    void drivers() {
        var report = reports.build(brother, month);
        var aibek = report.drivers().stream().filter(d -> d.driverId() == brother.getId()).findFirst().orElseThrow();
        var rashid = report.drivers().stream().filter(d -> d.driverId() == owner.getId()).findFirst().orElseThrow();

        assertThat(aibek.km()).isEqualTo(400);
        assertThat(aibek.kmByCar()).containsEntry("Прадо", 400);
        assertThat(aibek.tripCost()).isEqualByComparingTo("4788");
        assertThat(aibek.paid()).isEqualByComparingTo("4400"); // заправка в пути 3 600 + мойка 800

        assertThat(rashid.km()).isEqualTo(100);
        assertThat(rashid.paid()).isEqualByComparingTo("27644"); // заправка 3 600 + зарядка 44,41 + шины 24 000
        assertThat(report.drivers().get(0).driverId()).isEqualTo(brother.getId()); // по убыванию км
        assertThat(report.totalKm()).isEqualTo(500);
        assertThat(report.totalCost()).isEqualByComparingTo("6599");
    }

    @Test
    void emptyPeriodHasNoKilometres() {
        var past = new ReportPeriod(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 31), "past");
        var report = reports.build(owner, past);
        assertThat(report.totalKm()).isZero();
        assertThat(report.cars()).allSatisfy(c -> assertThat(c.otherExpenses()).isEqualByComparingTo("0"));
    }

    @Test
    void journalListsEverythingNewestFirst() {
        var journal = reports.journal(owner, month, 50);

        assertThat(journal).extracting(ReportService.JournalEntry::icon).contains("🏁", "⛽", "🔋", "💳");
        assertThat(journal).extracting(ReportService.JournalEntry::text)
                .anyMatch(t -> t.contains("Айбек · «Прадо» · 400 км"))
                .anyMatch(t -> t.contains("«Прадо» · 40 л · 3600 сом"));
        for (int i = 1; i < journal.size(); i++) {
            assertThat(journal.get(i - 1).at()).isAfterOrEqualTo(journal.get(i).at());
        }
    }
}
