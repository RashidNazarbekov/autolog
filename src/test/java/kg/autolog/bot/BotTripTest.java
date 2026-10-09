package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.car.FuelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Сквозные сценарии поездок в боте на настоящей БД. */
class BotTripTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(7_000_000);
    private static final Pattern CODE = Pattern.compile("<code>([A-Z0-9]{8})</code>");

    @Autowired
    BotEngine engine;

    long owner;
    long brother;

    @BeforeEach
    void home() {
        owner = IDS.incrementAndGet();
        brother = IDS.incrementAndGet();
        say(owner, "/start");
        press(owner, Buttons.CREATE_HOME);
        say(owner, "Дом");
        addCar(owner, FuelType.DIESEL, "Прадо", "87", "150000", "9,5");
        addCar(owner, FuelType.ELECTRIC, "Эмка", "40,3", "1200", null);
        var m = CODE.matcher(text(press(owner, Buttons.INVITE)));
        assertThat(m.find()).isTrue();
        say(brother, "/start join_" + m.group(1));
    }

    void addCar(long user, FuelType type, String name, String capacity, String km, String consumption) {
        press(user, Buttons.ADD_CAR);
        press(user, Buttons.CAR_TYPE + type);
        say(user, name);
        say(user, capacity);
        say(user, km);
        if (consumption == null) press(user, Buttons.SKIP);
        else say(user, consumption);
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

    static List<Reply.Button> buttons(List<Reply> replies) {
        return replies.stream().flatMap(r -> r.allButtons().stream()).toList();
    }

    /** Данные кнопки, подпись которой содержит текст. */
    static String buttonData(List<Reply> replies, String labelPart) {
        return buttons(replies).stream().filter(b -> b.text().contains(labelPart)).findFirst()
                .orElseThrow(() -> new AssertionError("Нет кнопки «" + labelPart + "» в " + buttons(replies)))
                .data();
    }

    @Test
    void dieselTripFromMenuToSummary() {
        var menu = say(brother, "/menu");
        var start = press(brother, buttonData(menu, "Прадо"));
        assertThat(text(start)).contains("Последний известный пробег: <b>150 000 км</b>");

        assertThat(text(press(brother, Buttons.TRIP_SAME_ODOMETER))).contains("Запас хода");
        var started = press(brother, Buttons.SKIP);
        assertThat(text(started)).contains("Поехали", "150 000 км");

        var ownersMenu = say(owner, "/menu");
        assertThat(text(ownersMenu)).contains("в поездке у User" + brother);
        assertThat(buttons(ownersMenu)).noneMatch(b -> b.text().contains("Поехать на «Прадо»"));

        var finishPrompt = press(brother, buttonData(started, "Закончить"));
        assertThat(text(finishPrompt)).contains("На старте было <b>150 000 км</b>");
        assertThat(text(say(brother, "150 042"))).contains("Запас хода");
        var done = say(brother, "430");

        assertThat(text(done)).contains("Поездка закончена", "Проехали <b>42 км</b>", "≈ 4 л по заводскому расходу 9,5");
        assertThat(text(done)).contains("Прадо</b> — 150 042 км · свободна");
    }

    @Test
    void electricTripWithMileageGap() {
        var menu = say(owner, "/menu");
        press(owner, buttonData(menu, "Эмка"));
        assertThat(text(say(owner, "1250"))).contains("Заряд батареи");
        var started = say(owner, "85%");

        assertThat(text(started)).contains("Поехали", "заряд 85 %", "<b>50 км</b>, которые никто не отметил");
        var answer = press(owner, buttonData(started, "User" + brother));
        assertThat(text(answer)).contains("Записал: 50 км — User" + brother);

        var finishPrompt = press(owner, buttonData(say(owner, "/menu"), "Закончить"));
        assertThat(text(finishPrompt)).contains("1 250 км");
        assertThat(text(say(owner, "1290"))).contains("На старте было 85 %");
        assertThat(text(say(owner, "95"))).contains("⚠️", "больше, чем на старте");
        var done = say(owner, "70");

        assertThat(text(done)).contains("Проехали <b>40 км</b>", "Ушло 6 кВт·ч (85 → 70 %)", "15 кВт·ч на 100 км");
    }

    @Test
    void busyCarShowsWhoIsDriving() {
        var ownersMenu = say(owner, "/menu");
        var startPrado = buttonData(ownersMenu, "Прадо");
        press(owner, startPrado);
        press(owner, Buttons.TRIP_SAME_ODOMETER);
        press(owner, Buttons.SKIP);

        var reply = press(brother, startPrado);
        assertThat(text(reply)).contains("⚠️", "сейчас в поездке у: User" + owner);
    }

    @Test
    void wrongOdometerIsExplained() {
        press(owner, buttonData(say(owner, "/menu"), "Прадо"));

        assertThat(text(say(owner, "149 000"))).contains("⚠️", "меньше последнего известного");
        assertThat(text(say(owner, "200 000"))).contains("⚠️", "опечатку");
        assertThat(text(say(owner, "абв"))).contains("⚠️", "целое число");
        assertThat(text(say(owner, "150 010"))).contains("Запас хода");
    }

    @Test
    void gapCanBeLeftForLaterAndResolvedFromMenu() {
        press(owner, buttonData(say(owner, "/menu"), "Прадо"));
        say(owner, "150 020");
        var started = press(owner, Buttons.SKIP);
        var later = press(owner, buttonData(started, "Не знаю"));
        assertThat(text(later)).contains("оставлю эти километры неучтёнными", "Неучтённый пробег: 20 км");

        var question = press(brother, Buttons.GAPS);
        var resolved = press(brother, buttonData(question, "Я ("));
        assertThat(text(resolved)).contains("Записал: 20 км — User" + brother);
        assertThat(text(say(owner, "/menu"))).doesNotContain("Неучтённый пробег");
    }
}
