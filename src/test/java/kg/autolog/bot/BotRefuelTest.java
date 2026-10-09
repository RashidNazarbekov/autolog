package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.car.FuelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Сквозные сценарии заправки в боте на настоящей БД. */
class BotRefuelTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(8_000_000);

    @Autowired
    BotEngine engine;

    long owner;

    @BeforeEach
    void home() {
        owner = IDS.incrementAndGet();
        say(owner, "/start");
        press(owner, Buttons.CREATE_HOME);
        say(owner, "Дом");
        addCar(FuelType.DIESEL, "Прадо", "87", "150000", "9,5");
        addCar(FuelType.ELECTRIC, "Эмка", "40,3", "1200", null);
    }

    void addCar(FuelType type, String name, String capacity, String km, String consumption) {
        press(owner, Buttons.ADD_CAR);
        press(owner, Buttons.CAR_TYPE + type);
        say(owner, name);
        say(owner, capacity);
        say(owner, km);
        if (consumption == null) press(owner, Buttons.SKIP);
        else say(owner, consumption);
    }

    List<Reply> say(long user, String text) {
        return engine.handle(Incoming.text(user, "User" + user, null, null, text));
    }

    List<Reply> press(long user, String data) {
        return engine.handle(Incoming.button(user, "User" + user, null, null, data));
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
    void refuelFromMenuThenDuringTripWithTotalAndLastPrice() {
        // Заправка у дома: пробег не менялся, литры и цена
        var prompt = press(owner, buttonData(say(owner, "/menu"), "Заправка"));
        assertThat(text(prompt)).contains("Заправка «Прадо»", "150 000 км");
        assertThat(text(press(owner, Buttons.FUEL_SAME_ODOMETER))).contains("Сколько литров");
        assertThat(text(say(owner, "45 л"))).contains("Цена за литр");
        var first = say(owner, "87,9");
        assertThat(text(first)).contains("Заправка записана", "45 л × 87,9 сом", "Пробег 150 000 км");

        // Поездка с заправкой в пути: знаем только сумму, цена «как в прошлый раз»
        press(owner, buttonData(say(owner, "/menu"), "Поехать на «Прадо»"));
        press(owner, Buttons.TRIP_SAME_ODOMETER);
        var started = press(owner, Buttons.SKIP);
        press(owner, buttonData(started, "Заправка в пути"));
        assertThat(text(say(owner, "150 300"))).contains("Сколько литров");
        assertThat(text(press(owner, Buttons.FUEL_BY_TOTAL))).contains("Сколько заплатили");
        var askPrice = say(owner, "3 516 сом");
        assertThat(text(askPrice)).contains("Литры посчитаю сам");
        var second = press(owner, buttonData(askPrice, "как в прошлый раз"));
        assertThat(text(second)).contains("40 л × 87,9 сом", "Средний расход по заправкам: 13,3 л/100 км");

        // Финиш: стоимость поездки по реальному расходу и цене
        press(owner, buttonData(second, "Закончить"));
        say(owner, "150 500");
        var done = press(owner, Buttons.SKIP);
        assertThat(text(done)).contains("Проехали <b>500 км</b>", "по вашему среднему расходу 13,3 л/100 км", "сом</b>");
    }

    @Test
    void wrongAmountsAreExplained() {
        press(owner, buttonData(say(owner, "/menu"), "Заправка"));
        press(owner, Buttons.FUEL_SAME_ODOMETER);

        assertThat(text(say(owner, "200"))).contains("⚠️", "Больше объёма бака");
        say(owner, "40");
        assertThat(text(say(owner, "5"))).contains("⚠️", "от 10 до 1000");
        assertThat(text(say(owner, "88"))).contains("Заправка записана");
    }

    @Test
    void electricCarHasNoRefuelButton() {
        var menu = say(owner, "/menu");
        var all = menu.stream().flatMap(r -> r.allButtons().stream()).toList();
        assertThat(all).filteredOn(b -> b.text().contains("Заправка")).hasSize(1);

        var emkaId = buttonData(menu, "Поехать на «Эмка»").substring(Buttons.TRIP_START.length());
        assertThat(text(press(owner, Buttons.FUEL_CAR + emkaId))).contains("электромобиль");
    }
}
