package kg.autolog.bot;

import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverRepository;
import kg.autolog.expense.ExpenseCategory;
import kg.autolog.expense.ExpenseService;
import kg.autolog.household.HouseholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/**
 * Прочий расход: «💳 Расход» → машина → категория → сумма → срок (для страховки, шин) →
 * кто нарушил (для штрафа) → комментарий → записано.
 */
@Component
@RequiredArgsConstructor
class ExpenseFlow {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final ExpenseService expenses;
    private final CarService cars;
    private final HouseholdService households;
    private final DriverRepository drivers;
    private final BotSessionStore sessions;
    private final BotScreens screens;

    List<Reply> start(Driver driver) {
        var list = cars.list(driver);
        if (list.isEmpty()) return List.of(Reply.of("Машин пока нет — расход записать не к чему."), screens.menu(driver));
        if (list.size() == 1) return carChosen(driver, list.get(0).getId());
        sessions.save(driver.getTelegramId(), BotState.EXPENSE_CAR, new LinkedHashMap<>());
        var rows = new ArrayList<List<Reply.Button>>();
        for (var c : list) rows.add(List.of(button(c.getName(), Buttons.EXPENSE_CAR + c.getId())));
        rows.add(List.of(button("Отмена", Buttons.CANCEL)));
        return List.of(Reply.of("💳 На какую машину расход?", rows));
    }

    List<Reply> carChosen(Driver driver, long carId) {
        var car = cars.require(driver, carId);
        var data = new LinkedHashMap<String, String>();
        data.put("carId", String.valueOf(car.getId()));
        sessions.save(driver.getTelegramId(), BotState.EXPENSE_CATEGORY, data);
        var rows = new ArrayList<List<Reply.Button>>();
        var row = new ArrayList<Reply.Button>();
        for (var c : expenses.categories(driver)) {
            row.add(button(c.title(), Buttons.EXPENSE_CATEGORY + c.getId()));
            if (row.size() == 2) {
                rows.add(List.copyOf(row));
                row.clear();
            }
        }
        if (!row.isEmpty()) rows.add(List.copyOf(row));
        if (households.requireMembership(driver).isOwner()) {
            rows.add(List.of(button("➕ Своя категория", Buttons.EXPENSE_NEW_CATEGORY)));
        }
        rows.add(List.of(button("Отмена", Buttons.CANCEL)));
        return List.of(Reply.of("💳 Расход на «" + esc(car.getName()) + "». Что это?", rows));
    }

    List<Reply> categoryChosen(Driver driver, long categoryId) {
        var session = sessions.get(driver.getTelegramId());
        if (session.get("carId") == null) return stale(driver);
        var category = expenses.requireCategory(driver, categoryId);
        var data = new LinkedHashMap<>(session.data());
        data.put("categoryId", String.valueOf(category.getId()));
        sessions.save(driver.getTelegramId(), BotState.EXPENSE_AMOUNT, data);
        return List.of(Reply.of(category.title() + ". Сколько заплатили, сом? Например: <i>1 500</i>", Buttons.cancel()));
    }

    List<Reply> amount(Driver driver, BotSessionStore.Session session, String text) {
        var amount = Format.decimal(text);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("10000000")) > 0) {
            return retry("Нужна сумма в сомах, например <i>1 500</i>");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("amount", amount.toPlainString());
        var category = category(driver, data);
        if (category.getDefaultSpreadMonths() > 1) return askSpread(driver, data, category, amount);
        data.put("spread", "1");
        return afterSpread(driver, data, category);
    }

    private List<Reply> askSpread(Driver driver, Map<String, String> data, ExpenseCategory category, BigDecimal amount) {
        sessions.save(driver.getTelegramId(), BotState.EXPENSE_SPREAD, data);
        int months = category.getDefaultSpreadMonths();
        var rows = new ArrayList<List<Reply.Button>>();
        rows.add(List.of(button("На " + months + " мес — по " + Format.number(perMonth(amount, months)) + " сом",
                Buttons.EXPENSE_SPREAD + months)));
        for (int m : new int[]{6, 12, 24, 36}) {
            if (m != months) rows.add(List.of(button("На " + m + " мес", Buttons.EXPENSE_SPREAD + m)));
        }
        rows.add(List.of(button("Разово, в этом месяце", Buttons.EXPENSE_SPREAD + 1)));
        rows.add(List.of(button("Отмена", Buttons.CANCEL)));
        return List.of(Reply.of("На какой срок распределить? Страховка обычно на год, шины — на несколько сезонов: "
                + "так месяц покупки не выглядит «диким» в отчётах. Можно написать число месяцев.", rows));
    }

    /** Срок выбран кнопкой. */
    List<Reply> spreadChosen(Driver driver, String value) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.EXPENSE_SPREAD) return stale(driver);
        return spread(driver, session, value);
    }

    /** Срок введён числом или выбран кнопкой. */
    List<Reply> spread(Driver driver, BotSessionStore.Session session, String text) {
        var months = Format.integer(text);
        if (months == null || months < 1 || months > ExpenseService.MAX_SPREAD_MONTHS) {
            return retry("Нужно число месяцев от 1 до " + ExpenseService.MAX_SPREAD_MONTHS + ", например <i>12</i>");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("spread", months.toString());
        return afterSpread(driver, data, category(driver, data));
    }

    private List<Reply> afterSpread(Driver driver, Map<String, String> data, ExpenseCategory category) {
        if (category.isAsksOffender()) {
            sessions.save(driver.getTelegramId(), BotState.EXPENSE_OFFENDER, data);
            var rows = new ArrayList<List<Reply.Button>>();
            for (var m : households.members(driver)) {
                var label = m.driverId() == driver.getId() ? "Я (" + m.name() + ")" : m.name();
                rows.add(List.of(button(label, Buttons.EXPENSE_OFFENDER + m.driverId())));
            }
            rows.add(List.of(button("Не знаю", Buttons.EXPENSE_OFFENDER + 0)));
            return List.of(Reply.of("Кто был за рулём, когда выписали штраф?", rows));
        }
        return askNote(driver, data);
    }

    List<Reply> offenderChosen(Driver driver, String value) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.EXPENSE_OFFENDER) return stale(driver);
        var data = new LinkedHashMap<>(session.data());
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException e) {
            return stale(driver);
        }
        if (id != 0) data.put("offender", String.valueOf(id));
        return askNote(driver, data);
    }

    private List<Reply> askNote(Driver driver, Map<String, String> data) {
        sessions.save(driver.getTelegramId(), BotState.EXPENSE_NOTE, data);
        return List.of(Reply.of("Комментарий? Например: <i>замена масла, 150 000 км</i>. Можно пропустить.", Buttons.skipOrCancel()));
    }

    List<Reply> note(Driver driver, BotSessionStore.Session session, String text) {
        var data = new LinkedHashMap<>(session.data());
        data.put("note", text);
        return save(driver, data);
    }

    List<Reply> skipNote(Driver driver, BotSessionStore.Session session) {
        return save(driver, new LinkedHashMap<>(session.data()));
    }

    private List<Reply> save(Driver driver, Map<String, String> data) {
        sessions.clear(driver.getTelegramId());
        var e = expenses.record(driver, new ExpenseService.Draft(
                Long.parseLong(data.get("carId")),
                Long.parseLong(data.get("categoryId")),
                new BigDecimal(data.get("amount")),
                Integer.valueOf(data.get("spread")),
                null,
                data.get("offender") == null ? null : Long.valueOf(data.get("offender")),
                data.get("note")));
        var car = cars.require(driver, e.getCarId());
        var category = expenses.requireCategory(driver, e.getCategoryId());
        var sb = new StringBuilder("💳 <b>Записано</b> — «").append(esc(car.getName())).append("»\n")
                .append(category.title()).append(": <b>").append(Format.number(e.getAmount())).append(" сом</b>");
        if (e.getSpreadMonths() > 1) {
            sb.append(", на ").append(e.getSpreadMonths()).append(" мес (по ").append(Format.number(e.perMonth())).append(" сом)");
        }
        if (e.getOffenderDriverId() != null) {
            drivers.findById(e.getOffenderDriverId()).ifPresent(d -> sb.append("\nНарушил: ").append(esc(d.getName())));
        }
        if (e.getNote() != null) sb.append("\n").append(esc(e.getNote()));
        return List.of(Reply.of(sb.toString(), List.of(
                List.of(button("💳 Ещё расход", Buttons.EXPENSE)),
                List.of(button("Меню", Buttons.MENU)))));
    }

    // ---------- Своя категория ----------

    List<Reply> newCategory(Driver driver) {
        households.requireOwner(driver);
        var session = sessions.get(driver.getTelegramId());
        sessions.save(driver.getTelegramId(), BotState.CATEGORY_NAME, new LinkedHashMap<>(session.data()));
        return List.of(Reply.of("Название новой категории? Например: <i>Автокресло</i>", Buttons.cancel()));
    }

    List<Reply> categoryName(Driver driver, BotSessionStore.Session session, String text) {
        var category = expenses.addCategory(driver, text, 1);
        var carId = session.get("carId");
        if (carId != null) {
            var data = new LinkedHashMap<>(session.data());
            data.put("categoryId", String.valueOf(category.getId()));
            sessions.save(driver.getTelegramId(), BotState.EXPENSE_AMOUNT, data);
            return List.of(Reply.of("✅ Категория «" + esc(category.getName()) + "» добавлена.\n\nСколько заплатили, сом?", Buttons.cancel()));
        }
        sessions.clear(driver.getTelegramId());
        return List.of(Reply.of("✅ Категория «" + esc(category.getName()) + "» добавлена."), screens.menu(driver));
    }

    // ---------- Список ----------

    Reply recent(Driver driver) {
        var list = expenses.recent(driver, 10);
        if (list.isEmpty()) {
            return Reply.of("Расходов пока нет.", List.of(List.of(button("💳 Записать расход", Buttons.EXPENSE)), List.of(button("Меню", Buttons.MENU))));
        }
        Map<Long, Car> carsById = cars.list(driver).stream().collect(Collectors.toMap(Car::getId, Function.identity()));
        Map<Long, ExpenseCategory> cats = expenses.categories(driver).stream().collect(Collectors.toMap(ExpenseCategory::getId, Function.identity()));
        var payerIds = list.stream().map(e -> e.getDriverId()).distinct().toList();
        Map<Long, String> names = drivers.findAllById(payerIds).stream().collect(Collectors.toMap(Driver::getId, Driver::getName));
        var sb = new StringBuilder("💳 <b>Последние расходы</b>\n\n");
        for (var e : list) {
            var car = carsById.get(e.getCarId());
            var cat = cats.get(e.getCategoryId());
            sb.append(DAY.format(e.getSpentOn())).append(" · ")
                    .append(car == null ? "—" : esc(car.getName())).append(" · ")
                    .append(cat == null ? "💳" : cat.title()).append(" — <b>")
                    .append(Format.number(e.getAmount())).append(" сом</b>");
            if (e.getSpreadMonths() > 1) sb.append(" (на ").append(e.getSpreadMonths()).append(" мес)");
            sb.append(" · ").append(esc(names.getOrDefault(e.getDriverId(), "—"))).append('\n');
        }
        return Reply.of(sb.toString().trim(), List.of(List.of(button("💳 Записать расход", Buttons.EXPENSE)), List.of(button("Меню", Buttons.MENU))));
    }

    // ---------- Мелочи ----------

    private ExpenseCategory category(Driver driver, Map<String, String> data) {
        return expenses.requireCategory(driver, Long.parseLong(data.get("categoryId")));
    }

    private static BigDecimal perMonth(BigDecimal amount, int months) {
        return amount.divide(BigDecimal.valueOf(months), 0, java.math.RoundingMode.HALF_UP);
    }

    private List<Reply> stale(Driver driver) {
        return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
    }

    private static List<Reply> retry(String text) {
        return List.of(Reply.of("⚠️ " + text, Buttons.cancel()));
    }
}
