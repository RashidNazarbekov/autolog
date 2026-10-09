package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.car.FuelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Сквозные сценарии прочих расходов в боте на настоящей БД. */
class BotExpenseTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(10_000_000);

    @Autowired
    BotEngine engine;

    long owner;

    @BeforeEach
    void home() {
        owner = IDS.incrementAndGet();
        say("/start");
        press(Buttons.CREATE_HOME);
        say("Дом");
        addCar(FuelType.DIESEL, "Прадо", "87", "150000");
        addCar(FuelType.ELECTRIC, "Эмка", "40,3", "1200");
    }

    void addCar(FuelType type, String name, String capacity, String km) {
        press(Buttons.ADD_CAR);
        press(Buttons.CAR_TYPE + type);
        say(name);
        say(capacity);
        say(km);
        press(Buttons.SKIP);
    }

    List<Reply> say(String text) {
        return engine.handle(Incoming.text(owner, "User" + owner, null, null, text));
    }

    List<Reply> press(String data) {
        return engine.handle(Incoming.button(owner, "User" + owner, null, null, data));
    }

    static String text(List<Reply> replies) {
        return String.join("\n---\n", replies.stream().map(Reply::text).toList());
    }

    static String buttonData(List<Reply> replies, String labelPart) {
        var all = replies.stream().flatMap(r -> r.allButtons().stream()).toList();
        return all.stream().filter(b -> b.text().contains(labelPart)).findFirst()
                .orElseThrow(() -> new AssertionError("Нет кнопки «" + labelPart + "» в " + all))
                .data();
    }

    @Test
    void tyresSpreadOverTwoYears() {
        var cars = press(buttonData(say("/menu"), "💳 Расход"));
        assertThat(text(cars)).contains("На какую машину");
        var categories = press(buttonData(cars, "Прадо"));
        assertThat(text(categories)).contains("Расход на «Прадо»");

        assertThat(text(press(buttonData(categories, "Шины")))).contains("Сколько заплатили");
        var spread = say("32 000");
        assertThat(text(spread)).contains("На какой срок");
        assertThat(text(press(buttonData(spread, "На 24 мес — по 1 333 сом")))).contains("Комментарий");
        var saved = say("зимние, Michelin");

        assertThat(text(saved)).contains("Записано", "«Прадо»", "🛞 Шины: <b>32 000 сом</b>",
                "на 24 мес (по 1 333,33 сом)", "зимние, Michelin");

        var list = say("/expenses");
        assertThat(text(list)).contains("Прадо", "🛞 Шины — <b>32 000 сом</b> (на 24 мес)", "User" + owner);
    }

    @Test
    void fineAsksWhoWasDriving() {
        var categories = press(buttonData(press(Buttons.EXPENSE), "Эмка"));
        press(buttonData(categories, "Штраф"));
        var who = say("1000");
        assertThat(text(who)).contains("Кто был за рулём");
        press(buttonData(who, "Я ("));
        var saved = press(Buttons.SKIP);

        assertThat(text(saved)).contains("🚨 Штраф: <b>1 000 сом</b>", "Нарушил: User" + owner);
    }

    @Test
    void ownCategoryOnTheFly() {
        var categories = press(buttonData(press(Buttons.EXPENSE), "Эмка"));
        assertThat(text(press(buttonData(categories, "Своя категория")))).contains("Название новой категории");
        assertThat(text(say("Автокресло"))).contains("Категория «Автокресло» добавлена", "Сколько заплатили");
        say("4 500");
        var saved = press(Buttons.SKIP);
        assertThat(text(saved)).contains("Автокресло: <b>4 500 сом</b>");

        var again = press(buttonData(press(Buttons.EXPENSE), "Прадо"));
        assertThat(buttonData(again, "Автокресло")).startsWith(Buttons.EXPENSE_CATEGORY);
    }

    @Test
    void wrongAmountIsExplained() {
        var categories = press(buttonData(press(Buttons.EXPENSE), "Прадо"));
        press(buttonData(categories, "Мойка"));
        assertThat(text(say("бесплатно"))).contains("⚠️", "сумма в сомах");
        assertThat(text(say("800"))).contains("Комментарий");
    }
}
