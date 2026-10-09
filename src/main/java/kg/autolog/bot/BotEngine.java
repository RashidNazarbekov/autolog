package kg.autolog.bot;

import kg.autolog.car.CarDraft;
import kg.autolog.car.CarService;
import kg.autolog.car.FuelType;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverService;
import kg.autolog.household.HouseholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/**
 * Ядро бота: превращает сообщение или нажатие кнопки в ответы.
 * Не знает про библиотеку Telegram, поэтому все сценарии проверяются тестами.
 * Экраны — в {@link BotScreens}, сценарий поездки — в {@link TripFlow}.
 *
 * <p>Метод {@link #handle} сам не транзакционный: каждое обращение к сервисам — своя транзакция,
 * поэтому ошибка ввода не откатывает уже сохранённое и не ломает следующий шаг.
 */
@Service
@RequiredArgsConstructor
public class BotEngine {

    static final String JOIN_PREFIX = "join_";

    private final DriverService drivers;
    private final HouseholdService households;
    private final CarService cars;
    private final BotSessionStore sessions;
    private final BotScreens screens;
    private final TripFlow tripFlow;

    public List<Reply> handle(Incoming in) {
        var driver = drivers.register(in.userId(), in.firstName(), in.lastName(), in.username());
        try {
            if (in.isButton()) return onButton(driver, in.callbackData());
            var text = in.text() == null ? "" : in.text().trim();
            if (text.startsWith("/")) return onCommand(driver, text);
            return onText(driver, text);
        } catch (AutologException e) {
            return List.of(Reply.of("⚠️ " + esc(e.getMessage()), Buttons.menu()));
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
                yield List.of(screens.menu(driver));
            }
            case "cars" -> List.of(screens.carList(driver));
            case "invite" -> List.of(screens.invite(driver));
            case "cancel" -> cancel(driver);
            case "help" -> List.of(help());
            default -> List.of(Reply.of("Не знаю такую команду. Список — /help"), screens.menu(driver));
        };
    }

    private List<Reply> start(Driver driver, String arg) {
        sessions.clear(driver.getTelegramId());
        if (arg.startsWith(JOIN_PREFIX)) {
            return join(driver, arg.substring(JOIN_PREFIX.length()));
        }
        return List.of(screens.menu(driver));
    }

    private List<Reply> cancel(Driver driver) {
        sessions.clear(driver.getTelegramId());
        return households.membershipOf(driver).isPresent()
                ? List.of(Reply.of("Отменено."), screens.menu(driver))
                : List.of(screens.welcome(driver));
    }

    private Reply help() {
        return Reply.of("""
                <b>Как пользоваться</b>
                Перед поездкой: /menu → «▶ Поехать на …» → пробег.
                Вернулись: /menu → «🏁 Закончить поездку» → пробег.
                Для электро бот спросит ещё заряд батареи в %, для дизеля — запас хода (можно пропустить).

                <b>Команды</b>
                /menu — машины и действия
                /cars — машины дома
                /invite — пригласить водителя (для владельца)
                /cancel — отменить текущий ввод
                /help — эта справка

                Числа можно писать как удобно: «40,3», «87 л», «150 000».""");
    }

    // ---------- Кнопки ----------

    private List<Reply> onButton(Driver driver, String data) {
        var id = driver.getTelegramId();
        if (data.startsWith(Buttons.CAR_TYPE)) return carTypeChosen(driver, data.substring(Buttons.CAR_TYPE.length()));
        if (data.startsWith(Buttons.TRIP_START)) return tripFlow.startPressed(driver, parseId(data, Buttons.TRIP_START));
        if (data.startsWith(Buttons.TRIP_FINISH)) return tripFlow.finishPressed(driver, parseId(data, Buttons.TRIP_FINISH));
        if (data.startsWith(Buttons.GAP)) return tripFlow.gapAnswer(driver, data.substring(Buttons.GAP.length()));
        return switch (data) {
            case Buttons.MENU -> {
                sessions.clear(id);
                yield List.of(screens.menu(driver));
            }
            case Buttons.CREATE_HOME -> {
                if (households.membershipOf(driver).isPresent()) {
                    yield List.of(Reply.of("Вы уже состоите в доме."), screens.menu(driver));
                }
                sessions.save(id, BotState.AWAIT_HOUSEHOLD_NAME, new LinkedHashMap<>());
                yield List.of(Reply.of("Как назовём дом? Например: <i>Дом Назарбековых</i>", Buttons.cancel()));
            }
            case Buttons.JOIN_HOME -> {
                sessions.save(id, BotState.AWAIT_INVITE_CODE, new LinkedHashMap<>());
                yield List.of(Reply.of("Пришлите код приглашения от владельца дома — 8 букв и цифр.", Buttons.cancel()));
            }
            case Buttons.ADD_CAR -> {
                households.requireOwner(driver);
                sessions.save(id, BotState.CAR_AWAIT_TYPE, new LinkedHashMap<>());
                yield List.of(Reply.of("Какая машина?", List.of(
                        List.of(button("⛽ Дизель", Buttons.CAR_TYPE + FuelType.DIESEL),
                                button("🔌 Электро", Buttons.CAR_TYPE + FuelType.ELECTRIC)),
                        List.of(button("Отмена", Buttons.CANCEL)))));
            }
            case Buttons.TRIP_SAME_ODOMETER -> tripFlow.sameOdometer(driver);
            case Buttons.GAPS -> tripFlow.gaps(driver);
            case Buttons.SKIP -> skip(driver);
            case Buttons.CARS -> List.of(screens.carList(driver));
            case Buttons.MEMBERS -> List.of(screens.memberList(driver));
            case Buttons.INVITE -> List.of(screens.invite(driver));
            case Buttons.CANCEL -> cancel(driver);
            default -> stale(driver);
        };
    }

    // ---------- Текст по шагам сценария ----------

    private List<Reply> onText(Driver driver, String text) {
        var session = sessions.get(driver.getTelegramId());
        return switch (session.state()) {
            case NONE, CAR_AWAIT_TYPE -> List.of(Reply.of("Выберите действие кнопкой или командой — /help"), screens.menu(driver));
            case AWAIT_HOUSEHOLD_NAME -> createHousehold(driver, text);
            case AWAIT_INVITE_CODE -> join(driver, text);
            case CAR_AWAIT_NAME -> carName(driver, session, text);
            case CAR_AWAIT_CAPACITY -> carCapacity(driver, session, text);
            case CAR_AWAIT_ODOMETER -> carOdometer(driver, session, text);
            case CAR_AWAIT_CONSUMPTION -> carConsumption(driver, session, text);
            case TRIP_START_ODOMETER -> tripFlow.startOdometer(driver, session, Format.integer(text));
            case TRIP_START_SOC -> tripFlow.startSoc(driver, session, text);
            case TRIP_START_RANGE -> tripFlow.startRange(driver, session, text);
            case TRIP_END_ODOMETER -> tripFlow.endOdometer(driver, session, Format.integer(text));
            case TRIP_END_SOC -> tripFlow.endSoc(driver, session, text);
            case TRIP_END_RANGE -> tripFlow.endRange(driver, session, text);
        };
    }

    private List<Reply> createHousehold(Driver driver, String name) {
        var home = households.create(driver, name);
        sessions.clear(driver.getTelegramId());
        return List.of(Reply.of(
                "🏠 Дом «" + esc(home.getName()) + "» создан, вы — владелец.\n\nТеперь добавьте машины и пригласите остальных водителей.",
                List.of(List.of(button("➕ Добавить машину", Buttons.ADD_CAR)),
                        List.of(button("👥 Пригласить водителя", Buttons.INVITE)),
                        List.of(button("Меню", Buttons.MENU)))));
    }

    private List<Reply> join(Driver driver, String code) {
        var home = households.join(driver, code);
        sessions.clear(driver.getTelegramId());
        return List.of(Reply.of("✅ Вы в доме «" + esc(home.getName()) + "»."), screens.menu(driver));
    }

    private List<Reply> skip(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() == BotState.CAR_AWAIT_CONSUMPTION) return saveCar(driver, session.data(), null);
        if (TripFlow.isRangeStep(session.state())) return tripFlow.skipRange(driver, session);
        return stale(driver);
    }

    private List<Reply> stale(Driver driver) {
        return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
    }

    // ---------- Мастер добавления машины ----------

    private List<Reply> carTypeChosen(Driver driver, String type) {
        households.requireOwner(driver);
        FuelType fuel;
        try {
            fuel = FuelType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return stale(driver);
        }
        var data = new LinkedHashMap<String, String>();
        data.put("type", fuel.name());
        sessions.save(driver.getTelegramId(), BotState.CAR_AWAIT_NAME, data);
        return List.of(Reply.of("Как называете машину дома? Например: <i>"
                + (fuel == FuelType.DIESEL ? "Прадо" : "Эмка") + "</i>", Buttons.cancel()));
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
                : "Объём бака, л? Например: <i>87</i>", Buttons.cancel()));
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
        return List.of(Reply.of("Текущий пробег по одометру, км? Например: <i>150 000</i>", Buttons.cancel()));
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
                Buttons.skipOrCancel()));
    }

    private List<Reply> carConsumption(Driver driver, BotSessionStore.Session session, String text) {
        var value = Format.decimal(text);
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(60)) > 0) {
            return retry("Нужно число от 1 до 60, например <i>9,5</i> — или нажмите «Пропустить»");
        }
        return saveCar(driver, session.data(), value);
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
        return List.of(Reply.of("✅ Добавлена: " + screens.carLine(car),
                List.of(List.of(button("➕ Ещё машина", Buttons.ADD_CAR)),
                        List.of(button("👥 Пригласить водителя", Buttons.INVITE)),
                        List.of(button("Меню", Buttons.MENU)))));
    }

    // ---------- Мелочи ----------

    private static long parseId(String data, String prefix) {
        try {
            return Long.parseLong(data.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new AutologException.NotFound("Эта кнопка устарела");
        }
    }

    private static boolean isElectric(Map<String, String> data) {
        return FuelType.ELECTRIC.name().equals(data.get("type"));
    }

    private static List<Reply> retry(String text) {
        return List.of(Reply.of("⚠️ " + text, Buttons.cancel()));
    }
}
