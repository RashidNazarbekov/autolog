package kg.autolog.expense;

import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdMemberRepository;
import kg.autolog.household.HouseholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    public static final int MAX_SPREAD_MONTHS = 60;

    /** Стандартный набор категорий нового дома (тот же, что в миграции V7). */
    record Default(String name, String emoji, int spread, boolean offender, int sort) {
    }

    static final List<Default> DEFAULTS = List.of(
            new Default("ТО и ремонт", "🔧", 1, false, 10),
            new Default("Шины", "🛞", 24, false, 20),
            new Default("Мойка и уход", "🧽", 1, false, 30),
            new Default("Страховка", "📄", 12, false, 40),
            new Default("Техосмотр и налог", "🧾", 12, false, 50),
            new Default("Парковка и дороги", "🅿️", 1, false, 60),
            new Default("Штраф", "🚨", 1, true, 70),
            new Default("Покупки для машины", "🛒", 1, false, 80),
            new Default("Другое", "💳", 1, false, 90));

    private final ExpenseRepository expenses;
    private final ExpenseCategoryRepository categories;
    private final CarService cars;
    private final HouseholdService households;
    private final HouseholdMemberRepository members;
    private final Clock clock;

    /** Что ввёл человек. {@code spreadMonths} и {@code spentOn} — по умолчанию из категории и «сегодня». */
    public record Draft(long carId, long categoryId, BigDecimal amount, Integer spreadMonths,
                        LocalDate spentOn, Long offenderDriverId, String note) {
    }

    @EventListener
    @Transactional
    public void onHouseholdCreated(HouseholdService.HouseholdCreated event) {
        for (var d : DEFAULTS) {
            categories.save(new ExpenseCategory(event.householdId(), d.name(), d.emoji(), d.spread(), d.offender(), d.sort()));
        }
    }

    public List<ExpenseCategory> categories(Driver driver) {
        var householdId = households.requireMembership(driver).getHouseholdId();
        return categories.findByHouseholdIdAndArchivedFalseOrderBySortOrderAscIdAsc(householdId);
    }

    public ExpenseCategory requireCategory(Driver driver, long categoryId) {
        var householdId = households.requireMembership(driver).getHouseholdId();
        return categories.findByIdAndHouseholdId(categoryId, householdId)
                .filter(c -> !c.isArchived())
                .orElseThrow(() -> new AutologException.NotFound("Категория не найдена"));
    }

    /** Своя категория. Добавляет владелец дома. */
    @Transactional
    public ExpenseCategory addCategory(Driver owner, String rawName, int defaultSpreadMonths) {
        var householdId = households.requireOwner(owner).getHouseholdId();
        var name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 48) throw new AutologException.Invalid("Название категории — от 1 до 48 символов");
        checkSpread(defaultSpreadMonths);
        if (categories.existsByHouseholdIdAndArchivedFalseAndNameIgnoreCase(householdId, name)) {
            throw new AutologException.Conflict("Категория «" + name + "» уже есть");
        }
        return categories.save(new ExpenseCategory(householdId, name, "💳", defaultSpreadMonths, false, 95));
    }

    /** Записать расход. Записывает любой участник дома — тот, кто платил. */
    @Transactional
    public Expense record(Driver driver, Draft d) {
        Car car = cars.require(driver, d.carId());
        var category = requireCategory(driver, d.categoryId());
        if (d.amount() == null || d.amount().signum() <= 0 || d.amount().compareTo(MAX_AMOUNT) > 0) {
            throw new AutologException.Invalid("Сумма — больше 0 и не больше " + MAX_AMOUNT.toPlainString() + " сом");
        }
        int spread = d.spreadMonths() != null ? d.spreadMonths() : category.getDefaultSpreadMonths();
        checkSpread(spread);
        var today = LocalDate.now(clock);
        var spentOn = d.spentOn() != null ? d.spentOn() : today;
        if (spentOn.isAfter(today)) throw new AutologException.Invalid("Дата расхода в будущем");
        if (d.offenderDriverId() != null) {
            members.findByHouseholdIdAndDriverId(car.getHouseholdId(), d.offenderDriverId())
                    .orElseThrow(() -> new AutologException.NotFound("Такого водителя нет в доме"));
        }
        var note = d.note() == null ? null : d.note().trim();
        if (note != null && note.length() > 200) note = note.substring(0, 200);
        if (note != null && note.isEmpty()) note = null;
        var expense = new Expense(car.getId(), category.getId(), driver.getId(),
                d.amount().setScale(2, RoundingMode.HALF_UP), spentOn, spread, d.offenderDriverId(), note, clock.instant());
        expense.setOdometerKm(car.getOdometerKm());
        return expenses.save(expense);
    }

    /** Последние расходы по машинам дома. */
    public List<Expense> recent(Driver driver, int limit) {
        var carIds = cars.list(driver).stream().map(Car::getId).toList();
        if (carIds.isEmpty()) return List.of();
        return expenses.findByCarIdInOrderBySpentOnDescIdDesc(carIds, Limit.of(limit));
    }

    private static void checkSpread(int months) {
        if (months < 1 || months > MAX_SPREAD_MONTHS) {
            throw new AutologException.Invalid("Срок — от 1 до " + MAX_SPREAD_MONTHS + " месяцев");
        }
    }
}
