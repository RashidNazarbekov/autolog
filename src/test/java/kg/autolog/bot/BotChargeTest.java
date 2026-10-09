package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.car.FuelType;
import kg.autolog.charge.ChargeLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Сквозные сценарии зарядки и цен в боте на настоящей БД. */
class BotChargeTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(9_000_000);

    @Autowired
    BotEngine engine;

    long owner;

    @BeforeEach
    void home() {
        owner = IDS.incrementAndGet();
        say("/start");
        press(Buttons.CREATE_HOME);
        say("Дом");
        press(Buttons.ADD_CAR);
        press(Buttons.CAR_TYPE + FuelType.ELECTRIC);
        say("Эмка");
        say("40,3");
        say("1200");
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
    void homeChargeFromStartToFinish() {
        assertThat(text(press(buttonData(say("/menu"), "🔌 Зарядка")))).contains("Где заряжаете «Эмка»");
        assertThat(text(press(Buttons.CHARGE_LOCATION + ChargeLocation.HOME))).contains("1 200 км");
        assertThat(text(press(Buttons.CHARGE_SAME_ODOMETER))).contains("Заряд сейчас");
        var started = say("20 %");
        assertThat(text(started)).contains("Зарядка началась", "дома", "Заряд 20 %");

        var menu = say("/menu");
        assertThat(text(menu)).contains("на зарядке с");
        assertThat(text(press(buttonData(menu, "Закончить зарядку")))).contains("В начале зарядки было 20 %");
        assertThat(text(say("10"))).contains("⚠️", "Меньше, чем в начале");
        var done = say("80");

        assertThat(text(done)).contains("Зарядка закончена", "20 → 80 %", "из сети 27,08 кВт·ч × 1,64 = <b>44,41 сом</b>");
        assertThat(text(done)).contains("Эмка</b> — 1 200 км · свободна");
    }

    @Test
    void stationChargeWithSkippedReceipt() {
        press(buttonData(say("/menu"), "🔌 Зарядка"));
        press(Buttons.CHARGE_LOCATION + ChargeLocation.DC80);
        press(Buttons.CHARGE_SAME_ODOMETER);
        var started = say("15");
        press(buttonData(started, "Закончить зарядку"));

        assertThat(text(say("80"))).contains("кВт·ч отдала станция");
        assertThat(text(press(Buttons.SKIP))).contains("14 сом/кВт·ч");
        var done = press(Buttons.SKIP);
        assertThat(text(done)).contains("Зарядка закончена", "станция 80 кВт", "× 14 =");
    }

    @Test
    void roadChargeDuringTripAndFinish() {
        press(buttonData(say("/menu"), "Поехать на «Эмка»"));
        press(Buttons.TRIP_SAME_ODOMETER);
        var started = say("30");

        assertThat(text(press(buttonData(started, "Подзарядка в пути")))).contains("где заряжались");
        assertThat(text(press(Buttons.CHARGE_LOCATION + ChargeLocation.DC120))).contains("Заряд до подзарядки");
        say("15");
        assertThat(text(say("10"))).contains("⚠️", "больше, чем до");
        assertThat(text(say("80"))).contains("кВт·ч отдала станция");
        say("30");
        var saved = say("480 сом");
        assertThat(text(saved)).contains("Подзарядка записана", "по счётчику станции 30 кВт·ч × 16 = <b>480 сом</b>", "Поездка продолжается");

        press(buttonData(saved, "Закончить поездку"));
        say("1300");
        var done = say("70");
        assertThat(text(done)).contains("Поездка закончена", "Проехали <b>100 км</b>", "сом</b> по средней цене зарядок");
    }

    @Test
    void tooHighChargeAtFinishSuggestsRoadCharge() {
        press(buttonData(say("/menu"), "Поехать на «Эмка»"));
        press(Buttons.TRIP_SAME_ODOMETER);
        var started = say("30");
        press(buttonData(started, "Закончить поездку"));
        say("1250");

        var reply = say("90");
        assertThat(text(reply)).contains("⚠️", "больше, чем на старте");
        assertThat(buttonData(reply, "Отметить подзарядку")).startsWith(Buttons.ROAD_CHARGE);
    }

    @Test
    void ownerEditsPrices() {
        var screen = press(buttonData(say("/menu"), "Цены"));
        assertThat(text(screen)).contains("Свет дома: <b>1,64</b>", "Станция 120 кВт: <b>16</b>");

        assertThat(text(press(buttonData(screen, "Свет дома")))).contains("Сейчас 1,64");
        assertThat(text(say("500"))).contains("⚠️");
        var saved = say("2,94");
        assertThat(text(saved)).contains("✅ Свет дома: 2,94", "Свет дома: <b>2,94</b>");
    }
}
