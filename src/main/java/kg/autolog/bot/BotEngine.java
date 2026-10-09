package kg.autolog.bot;

import kg.autolog.car.Car;
import kg.autolog.car.CarDraft;
import kg.autolog.car.CarService;
import kg.autolog.car.FuelType;
import kg.autolog.common.AutologException;
import kg.autolog.common.AutologProperties;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverService;
import kg.autolog.household.HouseholdService;
import kg.autolog.household.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/**
 * Ядро бота: превращает сообщение или нажатие кнопки в ответы.
 * Не знает про библиотеку Telegram, поэтому все сценарии проверяются тестами.
 *
 * <p>Метод {@link #handle} сам не транзакционный: каждое обращение к сервисам — своя транзакция,
 * поэтому ошибка ввода не откатывает уже сохранённое и не ломает следующий шаг.
 */
@Service
@RequiredArgsConstructor
public class BotEngine {

    // Данные кнопок
    static final String MENU = "menu";
    static final String CREATE_HOME = "home:create";
    static final String JOIN_HOME = "home:join";
    static final String CARS = "cars";
    static final String MEMBERS = "members";
    static final String INVITE = "invite";
    static final String ADD_CAR = "car:add";
    static final String CAR_TYPE = "car:type:";
    static final String SKIP = "skip";
    static final String CANCEL = "cancel";

    static final String JOIN_PREFIX = "join_";

    private final DriverService drivers;
    private final HouseholdService households;
    private final CarService cars;
    private final BotSessionStore sessions;
    private final AutologProperties props;

    public List<Reply> handle(Incoming in) {
        var driver = drivers.register(in.userId(), in.firstName(), in.lastName(), in.username());
        try {
            if (in.isButton()) return onButton(driver, in.callbackData());
            var text = in.text() == null ? "" : in.text().trim();
            if (text.startsWith("/")) return onCommand(driver, text);
            return onText(driver, text);
        } catch (AutologException e) {
            return List.of(Reply.of("⚠️ " + esc(e.getMessage())));
        }
    }

    // ---------- Команды ----------

    private List<Reply> onCommand(Driver driver, String text) {
        var parts = text.split("\\s+", 2);
        var command = parts[0].substring(1).toLowerCase().replaceAll("@.*$", "");
        var arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (command) {
            case "start" -> start(driver, arg);
            case "menu", "home" -> {
                sessions.clear(driver.getTelegramId());
                yield List.of(menu(driver));
            }
            case "cars" -> List.of(carList(driver));
            case "invite" -> List.of(invite(driver));
            case "cancel" -> cancel(driver);
            case "help" -> List.of(help());
            default -> List.of(Reply.of("Не знаю такую команду. Список — /help"), menu(driver));
        };
    }

    private List<Reply> start(Driver driver, String arg) {
        sessions.clear(driver.getTelegramId());
        if (arg.startsWith(JOIN_PREFIX)) {
            return join(driver, arg.substring(JOIN_PREFIX.length()));
        }
        if (households.membershipOf(driver).isPresent()) {
            return List.of(menu(driver));
        }
        return List.of(Reply.of(
                "Привет, " + esc(driver.getName()) + "! 👋\n\n"
                        + "Я веду журнал машин вашей семьи: кто на какой машине ездил, "
                        + "сколько ушло топлива или заряда и во что обходится километр.\n\n"
                        + "Для начала создайте дом и добавьте машины — или вступите в дом по коду от владельца.",
                List.of(List.of(button("🏠 Создать дом", CREATE_HOME)),
                        List.of(button("🔑 У меня есть код", JOIN_HOME)))));
    }

    private List<Reply> cancel(Driver driver) {
        sessions.clear(driver.getTelegramId());
        return households.membershipOf(driver).isPresent()
                ? List.of(Reply.of("Отменено."), menu(driver))
                : start(driver, "");
    }

    private Reply help() {
        return Reply.of("""
                <b>Команды</b>
                /menu — главное меню: машины и действия
                /cars — машины дома
                /invite — пригласить водителя (для владельца)
                /cancel — отменить текущий ввод
                /help — эта справка

                Числа можно писать как удобно: «40,3», «87 л», «150 000».""");
    }

    // ---------- Кнопки ----------

    private List<Reply> onButton(Driver driver, String data) {
        var id = driver.getTelegramId();
        if (data.startsWith(CAR_TYPE)) {
            return carTypeChosen(driver, data.substring(CAR_TYPE.length()));
        }
        return switch (data) {
            case MENU -> {
                sessions.clear(id);
                yield List.of(menu(driver));
            }
            case CREATE_HOME -> {
                if (households.membershipOf(driver).isPresent()) {
                    yield List.of(Reply.of("Вы уже состоите в доме."), menu(driver));
                }
                sessions.save(id, BotState.AWAIT_HOUSEHOLD_NAME, new LinkedHashMap<>());
                yield List.of(Reply.of("Как назовём дом? Например: <i>Дом Назарбековых</i>", cancelButton()));
            }
            case JOIN_HOME -> {
                sessions.save(id, BotState.AWAIT_INVITE_CODE, new LinkedHashMap<>());
                yield List.of(Reply.of("Пришлите код приглашения от владельца дома — 8 букв и цифр.", cancelButton()));
            }
            case ADD_CAR -> {
                households.requireOwner(driver);
                sessions.save(id, BotState.CAR_AWAIT_TYPE, new LinkedHashMap<>());
                yield List.of(Reply.of("Какая машина?", List.of(
                        List.of(button("⛽ Дизель", CAR_TYPE + FuelType.DIESEL),
                                button("🔌 Электро", CAR_TYPE + FuelType.ELECTRIC)),
                        List.of(button("Отмена", CANCEL)))));
            }
            case SKIP -> skip(driver);
            case CARS -> List.of(carList(driver));
            case MEMBERS -> List.of(memberList(driver));
            case INVITE -> List.of(invite(driver));
            case CANCEL -> cancel(driver);
            default -> List.of(Reply.of("Эта кнопка устарела."), menu(driver));
        };
    }

    // ---------- Текст по шагам сценария ----------

    private List<Reply> onText(Driver driver, String text) {
        var session = sessions.get(driver.getTelegramId());
        return switch (session.state()) {
            case NONE, CAR_AWAIT_TYPE -> List.of(Reply.of("Выберите действие кнопкой или командой — /help"), homeOrWelcome(driver));
            case AWAIT_HOUSEHOLD_NAME -> createHousehold(driver, text);
            case AWAIT_INVITE_CODE -> join(driver, text);
            case CAR_AWAIT_NAME -> carName(driver, session, text);
            case CAR_AWAIT_CAPACITY -> carCapacity(driver, session, text);
            case CAR_AWAIT_ODOMETER -> carOdometer(driver, session, text);
            case CAR_AWAIT_CONSUMPTION -> carConsumption(driver, session, text);
        };
    }

    private List<Reply> createHousehold(Driver driver, String name) {
        var home = households.create(driver, name);
        sessions.clear(driver.getTelegramId());
        return List.of(Reply.of(
                "🏠 Дом «" + esc(home.getName()) + "» создан, вы — владелец.\n\nТеперь добавьте машины и пригласите остальных водителей.",
                List.of(List.of(button("➕ Добавить машину", ADD_CAR)),
                        List.of(button("👥 Пригласить водителя", INVITE)),
                        List.of(button("Меню", MENU)))));
    }

    private List<Reply> join(Driver driver, String code) {
        var home = households.join(driver, code);
        sessions.clear(driver.getTelegramId());
        return List.of(Reply.of("✅ Вы в доме «" + esc(home.getName()) + "»."), menu(driver));
    }

    // ---------- Мастер добавления машины ----------

    private List<Reply> carTypeChosen(Driver driver, String type) {
        households.requireOwner(driver);
        FuelType fuel;
        try {
            fuel = FuelType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return List.of(Reply.of("Эта кнопка устарела."), menu(driver));
        }
        var data = new LinkedHashMap<String, String>();
        data.put("type", fuel.name());
        sessions.save(driver.getTelegramId(), BotState.CAR_AWAIT_NAME, data);
        return List.of(Reply.of("Как называете машину дома? Например: <i>"
                + (fuel == FuelType.DIESEL ? "Прадо" : "Эмка") + "</i>", cancelButton()));
    }

    private List<Reply> carName(Driver driver, BotSessionStore.Session session, String text) {
        var name = text.replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || name.length() > 64) {
            return retry("Название — от 1 до 64 символов. Как называете машину?");
        }
        boolean taken = cars.list(driver).stream().anyMatch(c -> c.getName().equalsIgnoreCase(name));
        if (taken) return retry("Машина «" + esc(name) + "» уже есть в доме. Придумайте другое название.");
        var data = new LinkedHashMap<>(session.data());
        data.put("name", name);
        sessions.save(driver.getTelegramId(), BotState.CAR_AWAIT_CAPACITY, data);
        return List.of(Reply.of(isElectric(data)
                ? "Ёмкость батареи, кВт·ч? Например: <i>40,3</i>"
                : "Объём бака, л? Например: <i>87</i>", cancelButton()));
    }

    private List<Reply> carCapacity(Driver driver, BotSessionStore.Session session, String text) {
        var value = Format.decimal(text);
        var max = isElectric(session.data()) ? 250 : 300;
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            return retry(isElectric(session.data())
                    ? "Нужно число от 1 до 250 — ёмкость батареи в кВт·ч, например <i>40,3</i>"
                    : "Нужно число от 1 до 300 — объём бака в литрах, например <i>87</i>");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("capacity", value.toPlainString());
        sessions.save(driver.getTelegramId(), BotState.CAR_AWAIT_ODOMETER, data);
        return List.of(Reply.of("Текущий пробег по одометру, км? Например: <i>150 000</i>", cancelButton()));
    }

    private List<Reply> carOdometer(Driver driver, BotSessionStore.Session session, String text) {
        var km = Format.integer(text);
        if (km == null || km < 0 || km > 3_000_000) {
            return retry("Нужно целое число километров, например <i>150 000</i>");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("odometer", km.toString());
        sessions.save(driver.getTelegramId(), BotState.CAR_AWAIT_CONSUMPTION, data);
        return List.of(Reply.of(isElectric(data)
                        ? "Заводской расход, кВт·ч на 100 км? Например: <i>13</i>\nНе знаете — нажмите «Пропустить», посчитаю по вашим поездкам."
                        : "Заводской расход, л на 100 км? Например: <i>9,5</i>\nНе знаете — нажмите «Пропустить», посчитаю по вашим заправкам.",
                List.of(List.of(button("Пропустить", SKIP)), List.of(button("Отмена", CANCEL)))));
    }

    private List<Reply> carConsumption(Driver driver, BotSessionStore.Session session, String text) {
        var value = Format.decimal(text);
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(60)) > 0) {
            return retry("Нужно число от 1 до 60, например <i>9,5</i> — или нажмите «Пропустить»");
        }
        return saveCar(driver, session.data(), value);
    }

    private List<Reply> skip(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.CAR_AWAIT_CONSUMPTION) {
            return List.of(Reply.of("Эта кнопка устарела."), menu(driver));
        }
        return saveCar(driver, session.data(), null);
    }

    private List<Reply> saveCar(Driver driver, Map<String, String> data, BigDecimal consumption) {
        var electric = isElectric(data);
        var capacity = new BigDecimal(data.get("capacity"));
        var draft = new CarDraft(
                data.get("name"),
                electric ? FuelType.ELECTRIC : FuelType.DIESEL,
                null,
                electric ? null : capacity,
                electric ? capacity : null,
                consumption,
                Integer.valueOf(data.get("odometer")));
        sessions.clear(driver.getTelegramId());
        var car = cars.add(driver, draft);
        return List.of(Reply.of("✅ Добавлена: " + carLine(car),
                List.of(List.of(button("➕ Ещё машина", ADD_CAR)),
                        List.of(button("👥 Пригласить водителя", INVITE)),
                        List.of(button("Меню", MENU)))));
    }

    // ---------- Экраны ----------

    /** Главное меню для участника дома, приветствие — для остальных. */
    private Reply homeOrWelcome(Driver driver) {
        return households.membershipOf(driver).isPresent() ? menu(driver) : start(driver, "").get(0);
    }

    Reply menu(Driver driver) {
        var member = households.membershipOf(driver).orElse(null);
        if (member == null) return start(driver, "").get(0);
        var home = households.requireHousehold(driver);
        var list = cars.list(driver);
        var sb = new StringBuilder("🏠 <b>").append(esc(home.getName())).append("</b>\n\n");
        if (list.isEmpty()) {
            sb.append(member.isOwner() ? "Машин пока нет — добавьте первую." : "Машин пока нет — их добавит владелец дома.");
        } else {
            list.forEach(c -> sb.append(carLine(c)).append('\n'));
        }
        var rows = new ArrayList<List<Reply.Button>>();
        rows.add(List.of(button("🚗 Машины", CARS), button("👥 Водители", MEMBERS)));
        if (member.isOwner()) {
            rows.add(List.of(button("➕ Машина", ADD_CAR), button("🔗 Пригласить", INVITE)));
        }
        return Reply.of(sb.toString().trim(), rows);
    }

    private Reply carList(Driver driver) {
        var list = cars.list(driver);
        if (list.isEmpty()) return Reply.of("Машин пока нет.", menuButton());
        var sb = new StringBuilder("<b>Машины</b>\n\n");
        for (var c : list) {
            sb.append(carLine(c)).append('\n');
            sb.append(c.isElectric()
                    ? "   батарея " + Format.number(c.getBatteryKwh()) + " кВт·ч"
                    : "   бак " + Format.number(c.getTankLiters()) + " л");
            if (c.getRatedConsumption() != null) {
                sb.append(" · расход ").append(Format.number(c.getRatedConsumption()))
                        .append(c.isElectric() ? " кВт·ч/100 км" : " л/100 км");
            }
            sb.append("\n\n");
        }
        return Reply.of(sb.toString().trim(), menuButton());
    }

    private Reply memberList(Driver driver) {
        var sb = new StringBuilder("<b>Водители</b>\n\n");
        for (var m : households.members(driver)) {
            sb.append(m.role() == MemberRole.OWNER ? "👑 " : "• ").append(esc(m.name()));
            if (m.username() != null) sb.append(" (@").append(esc(m.username())).append(')');
            if (m.role() == MemberRole.OWNER) sb.append(" — владелец");
            sb.append('\n');
        }
        return Reply.of(sb.toString().trim(), menuButton());
    }

    private Reply invite(Driver driver) {
        var code = households.createInvite(driver);
        var username = props.telegram().botUsername();
        var sb = new StringBuilder("🔗 <b>Приглашение в дом</b>\n\n");
        if (username != null && !username.isBlank()) {
            sb.append("Перешлите эту ссылку водителю — по ней он сразу попадёт в дом:\n")
                    .append("https://t.me/").append(esc(username)).append("?start=").append(JOIN_PREFIX).append(code)
                    .append("\n\nИли пусть нажмёт в боте «У меня есть код» и введёт: <code>").append(code).append("</code>");
        } else {
            sb.append("Пусть водитель откроет бота, нажмёт «У меня есть код» и введёт:\n<code>").append(code).append("</code>");
        }
        sb.append("\n\nКод действует ").append(props.inviteTtl().toHours()).append(" ч. Новый код отменяет прежний.");
        return Reply.of(sb.toString(), menuButton());
    }

    static String carLine(Car c) {
        var icon = c.isElectric() ? "🔌" : "⛽";
        var state = switch (c.getState()) {
            case FREE -> "свободна";
            case ON_TRIP -> "в поездке";
            case CHARGING -> "на зарядке";
        };
        return icon + " <b>" + esc(c.getName()) + "</b> — " + Format.km(c.getOdometerKm()) + " км · " + state;
    }

    // ---------- Мелочи ----------

    private static boolean isElectric(Map<String, String> data) {
        return FuelType.ELECTRIC.name().equals(data.get("type"));
    }

    private static List<Reply> retry(String text) {
        return List.of(Reply.of("⚠️ " + text, cancelButton()));
    }

    private static List<List<Reply.Button>> cancelButton() {
        return List.of(List.of(button("Отмена", CANCEL)));
    }

    private static List<List<Reply.Button>> menuButton() {
        return List.of(List.of(button("Меню", MENU)));
    }
}
