package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.car.FuelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Отчёты и журнал в боте. Подробные цифры проверяет ReportServiceTest. */
class BotReportTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(11_000_000);

    @Autowired
    BotEngine engine;

    long owner;

    @BeforeEach
    void home() {
        owner = IDS.incrementAndGet();
        say("/start");
        press(Buttons.CREATE_HOME);
        say("Дом");
        addCar(FuelType.DIESEL, "Прадо", "87", "150000", "9,5");
        addCar(FuelType.ELECTRIC, "Эмка", "40,3", "1200", "13");
        // Поездка на Прадо
        press(buttonData(say("/menu"), "Поехать на «Прадо»"));
        press(Buttons.TRIP_SAME_ODOMETER);
        press(Buttons.SKIP);
        press(buttonData(say("/menu"), "Закончить"));
        say("150 120");
        press(Buttons.SKIP);
    }

    void addCar(FuelType type, String name, String capacity, String km, String consumption) {
        press(Buttons.ADD_CAR);
        press(Buttons.CAR_TYPE + type);
        say(name);
        say(capacity);
        say(km);
        say(consumption);
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
    void monthlyReportFromMenu() {
        var periods = press(buttonData(say("/menu"), "Отчёты"));
        assertThat(text(periods)).contains("За какой период");

        var report = press(buttonData(periods, "Этот месяц"));
        assertThat(text(report)).contains("📊 <b>Отчёт</b>", "⛽ <b>Прадо</b> — 120 км, поездок: 1",
                "Расход 9,5 л/100 км (заводской)", "🔌 <b>Эмка</b> — 0 км", "👥 <b>Водители</b>",
                "User" + owner + " — 120 км", "Всего: 120 км");
        assertThat(buttonData(report, "• Этот месяц")).isEqualTo(Buttons.REPORT + "month");
    }

    @Test
    void journalShowsTheTrip() {
        var report = press(Buttons.REPORT + "week");
        var journal = press(buttonData(report, "Журнал"));
        assertThat(text(journal)).contains("📒 <b>Журнал</b>", "🏁 User" + owner + " · «Прадо» · 120 км");
        assertThat(buttonData(journal, "Отчёт за этот период")).isEqualTo(Buttons.REPORT + "week");
    }

    @Test
    void customPeriodByText() {
        assertThat(text(press(Buttons.REPORT_CUSTOM))).contains("Напишите период");
        assertThat(text(say("вчера"))).contains("⚠️");
        var report = say("01.01.2020–31.01.2020");
        assertThat(text(report)).contains("01.01.2020–31.01.2020", "Всего: 0 км");
        assertThat(buttonData(report, "Журнал")).isEqualTo(Buttons.JOURNAL + "d:20200101-20200131");
    }
}
