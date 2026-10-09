package kg.autolog.expense;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static kg.autolog.TestData.diesel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class ExpenseServiceTest extends IntegrationTest {

    @Autowired
    ExpenseService expenses;

    @Autowired
    CarService cars;

    @Autowired
    HouseholdService households;

    @Autowired
    TestData data;

    Driver owner;
    Driver brother;
    Car prado;

    @BeforeEach
    void home() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        households.create(owner, "Дом");
        households.join(brother, households.createInvite(owner));
        prado = cars.add(owner, diesel("Прадо"));
    }

    ExpenseCategory category(Driver who, String name) {
        return expenses.categories(who).stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }

    ExpenseService.Draft draft(String category, String amount, Integer spread, Long offender) {
        return new ExpenseService.Draft(prado.getId(), category(owner, category).getId(), new BigDecimal(amount),
                spread, null, offender, null);
    }

    @Test
    void newHouseholdGetsStandardCategories() {
        assertThat(expenses.categories(brother)).extracting(ExpenseCategory::getName).containsExactly(
                "ТО и ремонт", "Шины", "Мойка и уход", "Страховка", "Техосмотр и налог",
                "Парковка и дороги", "Штраф", "Покупки для машины", "Другое");
        assertThat(category(owner, "Шины").getDefaultSpreadMonths()).isEqualTo(24);
        assertThat(category(owner, "Штраф").isAsksOffender()).isTrue();
    }

    @Test
    void oneOffExpenseIsPaidByWhoRecordedIt() {
        var e = expenses.record(brother, draft("Мойка и уход", "800", null, null));

        assertThat(e.getDriverId()).isEqualTo(brother.getId());
        assertThat(e.getSpreadMonths()).isEqualTo(1);
        assertThat(e.getOdometerKm()).isEqualTo(150_000);
        assertThat(e.getSpentOn()).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Bishkek")));
    }

    @Test
    void tyresAreSpreadOverMonthsByDefault() {
        var e = expenses.record(owner, draft("Шины", "32000", null, null));

        assertThat(e.getSpreadMonths()).isEqualTo(24);
        assertThat(e.perMonth()).isEqualByComparingTo("1333.33");
        var first = YearMonth.from(e.getSpentOn());
        assertThat(e.shareIn(first)).isEqualByComparingTo("1333.33");
        assertThat(e.shareIn(first.plusMonths(23))).isEqualByComparingTo("1333.33");
        assertThat(e.shareIn(first.plusMonths(24))).isEqualByComparingTo("0");
        assertThat(e.shareIn(first.minusMonths(1))).isEqualByComparingTo("0");

        var oneOff = expenses.record(owner, draft("Шины", "1500", 1, null));
        assertThat(oneOff.getSpreadMonths()).isEqualTo(1);
    }

    @Test
    void fineRemembersTheOffender() {
        var e = expenses.record(owner, draft("Штраф", "1000", null, brother.getId()));
        assertThat(e.getOffenderDriverId()).isEqualTo(brother.getId());

        var stranger = data.driver("Сосед");
        assertThatThrownBy(() -> expenses.record(owner, draft("Штраф", "1000", null, stranger.getId())))
                .isInstanceOf(AutologException.NotFound.class);
    }

    @Test
    void invalidAmountsAndDatesAreRejected() {
        assertThatThrownBy(() -> expenses.record(owner, draft("Другое", "0", null, null)))
                .isInstanceOf(AutologException.Invalid.class);
        assertThatThrownBy(() -> expenses.record(owner, draft("Другое", "100", 61, null)))
                .isInstanceOf(AutologException.Invalid.class);
        var future = new ExpenseService.Draft(prado.getId(), category(owner, "Другое").getId(), new BigDecimal("100"),
                null, LocalDate.now().plusDays(3), null, null);
        assertThatThrownBy(() -> expenses.record(owner, future)).isInstanceOf(AutologException.Invalid.class);
    }

    @Test
    void ownerAddsOwnCategory() {
        var c = expenses.addCategory(owner, "  Автокресло ", 1);
        assertThat(c.getName()).isEqualTo("Автокресло");
        assertThat(expenses.categories(brother)).extracting(ExpenseCategory::getName).contains("Автокресло");

        assertThatThrownBy(() -> expenses.addCategory(owner, "автокресло", 1)).isInstanceOf(AutologException.Conflict.class);
        assertThatThrownBy(() -> expenses.addCategory(brother, "Коврики", 1)).isInstanceOf(AutologException.Forbidden.class);
    }

    @Test
    void categoriesOfAnotherHouseholdAreInvisible() {
        var neighbour = data.driver("Сосед");
        households.create(neighbour, "Соседи");
        var theirs = category(neighbour, "Мойка и уход");

        var d = new ExpenseService.Draft(prado.getId(), theirs.getId(), new BigDecimal("500"), null, null, null, null);
        assertThatThrownBy(() -> expenses.record(owner, d)).isInstanceOf(AutologException.NotFound.class);
    }

    @Test
    void recentShowsNewestFirst() {
        expenses.record(owner, draft("Мойка и уход", "800", null, null));
        expenses.record(owner, draft("ТО и ремонт", "6500", null, null));

        assertThat(expenses.recent(brother, 10)).extracting(e -> e.getAmount().intValue()).containsExactly(6500, 800);
    }
}
