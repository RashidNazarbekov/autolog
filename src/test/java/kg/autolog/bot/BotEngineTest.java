package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.car.CarState;
import kg.autolog.car.FuelType;
import kg.autolog.car.CarRepository;
import kg.autolog.household.HouseholdMemberRepository;
import kg.autolog.household.MemberRole;
import kg.autolog.driver.DriverRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозные сценарии бота на настоящей БД: как будто человек пишет и жмёт кнопки.
 * Ядро не транзакционное (как в проде), поэтому у каждого теста свои Telegram id.
 */
@TestPropertySource(properties = "autolog.telegram.bot-username=autolog_test_bot")
class BotEngineTest extends IntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(5_000_000);
    private static final Pattern CODE = Pattern.compile("<code>([A-Z0-9]{8})</code>");

    @Autowired
    BotEngine engine;

    @Autowired
    DriverRepository drivers;

    @Autowired
    HouseholdMemberRepository members;

    @Autowired
    CarRepository cars;

    long owner;
    long brother;

    @BeforeEach
    void ids() {
        owner = IDS.incrementAndGet();
        brother = IDS.incrementAndGet();
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

    static List<String> buttons(List<Reply> replies) {
        return replies.stream().flatMap(r -> r.allButtons().stream()).map(Reply.Button::data).toList();
    }

    void createHome(long user) {
        say(user, "/start");
        press(user, BotEngine.CREATE_HOME);
        say(user, "Дом Назарбековых");
    }

    @Test
    void newUserIsWelcomedWithTwoWaysIn() {
        var r = say(owner, "/start");

        assertThat(text(r)).contains("Привет, User" + owner);
        assertThat(buttons(r)).containsExactly(BotEngine.CREATE_HOME, BotEngine.JOIN_HOME);
        assertThat(drivers.findByTelegramId(owner)).isPresent();
    }

    @Test
    void ownerCreatesHomeAndAddsDieselCar() {
        say(owner, "/start");
        press(owner, BotEngine.CREATE_HOME);
        var created = say(owner, "  Дом Назарбековых ");
        assertThat(text(created)).contains("Дом Назарбековых", "владелец");
        assertThat(buttons(created)).contains(BotEngine.ADD_CAR, BotEngine.INVITE);

        press(owner, BotEngine.ADD_CAR);
        press(owner, BotEngine.CAR_TYPE + FuelType.DIESEL);
        assertThat(text(say(owner, "Прадо"))).contains("Объём бака");
        assertThat(text(say(owner, "87 л"))).contains("пробег");
        assertThat(text(say(owner, "150 000"))).contains("л на 100 км");
        var done = say(owner, "9,5");

        assertThat(text(done)).contains("Добавлена", "Прадо", "150 000 км", "свободна");
        var driverId = drivers.findByTelegramId(owner).orElseThrow().getId();
        var householdId = members.findFirstByDriverId(driverId).orElseThrow().getHouseholdId();
        var car = cars.findByHouseholdIdAndArchivedFalseOrderById(householdId).get(0);
        assertThat(car.getFuelType()).isEqualTo(FuelType.DIESEL);
        assertThat(car.getTankLiters()).isEqualByComparingTo("87");
        assertThat(car.getRatedConsumption()).isEqualByComparingTo("9.5");
        assertThat(car.getOdometerKm()).isEqualTo(150_000);
        assertThat(car.getState()).isEqualTo(CarState.FREE);
    }

    @Test
    void electricCarWithSkippedConsumption() {
        createHome(owner);
        press(owner, BotEngine.ADD_CAR);
        press(owner, BotEngine.CAR_TYPE + FuelType.ELECTRIC);
        assertThat(text(say(owner, "Эмка"))).contains("Ёмкость батареи");
        say(owner, "40,3");
        var ask = say(owner, "1200");
        assertThat(buttons(ask)).contains(BotEngine.SKIP);

        var done = press(owner, BotEngine.SKIP);
        assertThat(text(done)).contains("Добавлена", "Эмка");

        var list = press(owner, BotEngine.CARS);
        assertThat(text(list)).contains("батарея 40,3 кВт·ч").doesNotContain("расход");
    }

    @Test
    void wrongInputIsExplainedAndStepRepeats() {
        createHome(owner);
        press(owner, BotEngine.ADD_CAR);
        press(owner, BotEngine.CAR_TYPE + FuelType.DIESEL);
        say(owner, "Прадо");

        assertThat(text(say(owner, "много"))).contains("⚠️", "объём бака");
        assertThat(text(say(owner, "-5"))).contains("⚠️");
        assertThat(text(say(owner, "87"))).contains("пробег");
    }

    @Test
    void duplicateCarNameIsCaughtEarly() {
        createHome(owner);
        press(owner, BotEngine.ADD_CAR);
        press(owner, BotEngine.CAR_TYPE + FuelType.DIESEL);
        say(owner, "Прадо");
        say(owner, "87");
        say(owner, "150000");
        press(owner, BotEngine.SKIP);

        press(owner, BotEngine.ADD_CAR);
        press(owner, BotEngine.CAR_TYPE + FuelType.ELECTRIC);
        assertThat(text(say(owner, "прадо"))).contains("уже есть");
    }

    @Test
    void driverJoinsByInviteLink() {
        createHome(owner);
        var invite = press(owner, BotEngine.INVITE);
        assertThat(text(invite)).contains("https://t.me/autolog_test_bot?start=join_");
        var m = CODE.matcher(text(invite));
        assertThat(m.find()).isTrue();

        var joined = say(brother, "/start join_" + m.group(1));

        assertThat(text(joined)).contains("Вы в доме «Дом Назарбековых»");
        var brotherId = drivers.findByTelegramId(brother).orElseThrow().getId();
        assertThat(members.findFirstByDriverId(brotherId).orElseThrow().getRole()).isEqualTo(MemberRole.DRIVER);
        assertThat(text(press(owner, BotEngine.MEMBERS))).contains("User" + owner, "владелец", "User" + brother);
    }

    @Test
    void driverJoinsByTypingCodeAfterAMistake() {
        createHome(owner);
        var m = CODE.matcher(text(press(owner, BotEngine.INVITE)));
        assertThat(m.find()).isTrue();

        say(brother, "/start");
        press(brother, BotEngine.JOIN_HOME);
        assertThat(text(say(brother, "WRONG123"))).contains("⚠️", "Код не найден");
        assertThat(text(say(brother, m.group(1).toLowerCase()))).contains("Вы в доме");
    }

    @Test
    void driverCannotAddCarsOrInvite() {
        createHome(owner);
        var m = CODE.matcher(text(press(owner, BotEngine.INVITE)));
        assertThat(m.find()).isTrue();
        say(brother, "/start join_" + m.group(1));

        var menu = say(brother, "/menu");
        assertThat(buttons(menu)).doesNotContain(BotEngine.ADD_CAR, BotEngine.INVITE);
        assertThat(text(press(brother, BotEngine.ADD_CAR))).contains("только владелец");
    }

    @Test
    void cancelLeavesTheWizard() {
        createHome(owner);
        press(owner, BotEngine.ADD_CAR);
        press(owner, BotEngine.CAR_TYPE + FuelType.DIESEL);

        var cancelled = say(owner, "/cancel");
        assertThat(text(cancelled)).contains("Отменено");

        var after = say(owner, "Прадо");
        assertThat(text(after)).contains("Выберите действие");
    }
}
