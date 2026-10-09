package kg.autolog.bot;

import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.charge.Charge;
import kg.autolog.charge.ChargeLocation;
import kg.autolog.charge.ChargeService;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/**
 * Зарядка электро в боте.
 * <ul>
 *   <li>Отдельная: «Зарядка» → где → пробег → % → машина «на зарядке» … «Закончить зарядку» → % → (станция: кВт·ч, сумма) → итог.</li>
 *   <li>В пути: «Подзарядка» → где → % до → % после → (станция: кВт·ч, сумма) → записано, поездка продолжается.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ChargeFlow {

    private final ChargeService charges;
    private final TripService trips;
    private final CarService cars;
    private final HouseholdService households;
    private final BotSessionStore sessions;
    private final BotScreens screens;

    // ---------- Отдельная зарядка: начало ----------

    List<Reply> pressed(Driver driver, long carId) {
        var car = cars.require(driver, carId);
        if (!car.isElectric()) {
            return List.of(Reply.of("⚠️ «" + esc(car.getName()) + "» — дизель, для него есть заправка."), screens.menu(driver));
        }
        if (car.getState() != CarState.FREE) {
            return List.of(Reply.of("⚠️ " + esc(trips.busyMessage(car))), screens.menu(driver));
        }
        var data = new LinkedHashMap<String, String>();
        data.put("carId", String.valueOf(carId));
        sessions.save(driver.getTelegramId(), BotState.CHARGE_LOCATION, data);
        return List.of(Reply.of("🔌 Где заряжаете «" + esc(car.getName()) + "»?", locationButtons(driver)));
    }

    /** Нажата кнопка «где» — и для отдельной зарядки, и для подзарядки в пути. */
    List<Reply> location(Driver driver, String value) {
        var session = sessions.get(driver.getTelegramId());
        ChargeLocation location;
        try {
            location = ChargeLocation.valueOf(value);
        } catch (IllegalArgumentException e) {
            return stale(driver);
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("location", location.name());
        if (session.state() == BotState.CHARGE_LOCATION) {
            var car = car(driver, data);
            sessions.save(driver.getTelegramId(), BotState.CHARGE_START_ODOMETER, data);
            var km = Format.km(car.getOdometerKm());
            return List.of(Reply.of("Сколько на одометре? Последний известный пробег: <b>" + km + " км</b>",
                    List.of(List.of(button("Не менялся: " + km + " км", Buttons.CHARGE_SAME_ODOMETER)),
                            List.of(button("Отмена", Buttons.CANCEL)))));
        }
        if (session.state() == BotState.ROAD_CHARGE_LOCATION) {
            sessions.save(driver.getTelegramId(), BotState.ROAD_CHARGE_SOC_BEFORE, data);
            return List.of(Reply.of("Заряд до подзарядки, %? Например: <i>15</i>", Buttons.cancel()));
        }
        return stale(driver);
    }

    List<Reply> sameOdometer(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.CHARGE_START_ODOMETER) return stale(driver);
        return odometer(driver, session, car(driver, session.data()).getOdometerKm());
    }

    List<Reply> odometer(Driver driver, BotSessionStore.Session session, Integer km) {
        var car = car(driver, session.data());
        if (km == null) return retry("Нужно целое число километров, например <i>" + Format.km(car.getOdometerKm()) + "</i>");
        if (km < car.getOdometerKm()) {
            return retry("Пробег меньше последнего известного (" + Format.km(car.getOdometerKm()) + " км). Проверьте одометр.");
        }
        if (km - car.getOdometerKm() > TripService.MAX_GAP_KM) {
            return retry("Это на " + Format.km(km - car.getOdometerKm()) + " км больше — похоже на опечатку. Проверьте одометр.");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("odometer", km.toString());
        sessions.save(driver.getTelegramId(), BotState.CHARGE_START_SOC, data);
        return List.of(Reply.of("Заряд сейчас, %? Например: <i>20</i>", Buttons.cancel()));
    }

    List<Reply> startSoc(Driver driver, BotSessionStore.Session session, String text) {
        var soc = TripFlow.percent(text);
        if (soc == null) return retry("Нужен заряд в процентах — целое число от 0 до 100, например <i>20</i>");
        var data = session.data();
        sessions.clear(driver.getTelegramId());
        var r = charges.start(driver, Long.parseLong(data.get("carId")), ChargeLocation.valueOf(data.get("location")),
                Integer.parseInt(data.get("odometer")), soc);
        var c = r.charge();
        var replies = new ArrayList<Reply>();
        replies.add(Reply.of("🔌 <b>Зарядка началась</b> — «" + esc(r.car().getName()) + "», " + c.getLocation().title()
                        + "\nЗаряд " + c.getStartSocPct() + " %, с " + screens.when(c.getStartedAt())
                        + "\n\nКогда снимете с зарядки — нажмите «Закончить зарядку».",
                List.of(List.of(button("🔋 Закончить зарядку", Buttons.CHARGE_FINISH + c.getId())),
                        List.of(button("Меню", Buttons.MENU)))));
        if (r.gap() != null) replies.add(screens.gapQuestion(driver, r.gap()));
        return replies;
    }

    // ---------- Отдельная зарядка: конец ----------

    List<Reply> finishPressed(Driver driver, long chargeId) {
        var charge = charges.requireOpenCharge(driver, chargeId);
        var data = new LinkedHashMap<String, String>();
        data.put("chargeId", String.valueOf(chargeId));
        sessions.save(driver.getTelegramId(), BotState.CHARGE_END_SOC, data);
        return List.of(Reply.of("🔋 Сколько % сейчас? В начале зарядки было " + charge.getStartSocPct() + " %", Buttons.cancel()));
    }

    List<Reply> endSoc(Driver driver, BotSessionStore.Session session, String text) {
        var charge = charges.requireOpenCharge(driver, Long.parseLong(session.get("chargeId")));
        var soc = TripFlow.percent(text);
        if (soc == null) return retry("Нужен заряд в процентах — целое число от 0 до 100, например <i>80</i>");
        if (soc < charge.getStartSocPct()) {
            return retry("Меньше, чем в начале зарядки (" + charge.getStartSocPct() + " %). Сколько % сейчас?");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("endSoc", soc.toString());
        data.put("location", charge.getLocation().name());
        if (charge.getLocation() == ChargeLocation.HOME) return finish(driver, data);
        sessions.save(driver.getTelegramId(), BotState.CHARGE_END_KWH, data);
        return List.of(askKwh());
    }

    // ---------- Подзарядка в пути ----------

    List<Reply> roadPressed(Driver driver, long tripId) {
        var trip = trips.requireOpenTrip(driver, tripId);
        var car = cars.require(driver, trip.getCarId());
        if (!car.isElectric()) return stale(driver);
        var data = new LinkedHashMap<String, String>();
        data.put("tripId", String.valueOf(tripId));
        data.put("carId", String.valueOf(car.getId()));
        sessions.save(driver.getTelegramId(), BotState.ROAD_CHARGE_LOCATION, data);
        return List.of(Reply.of("🔌 Подзарядка в пути — где заряжались?", locationButtons(driver)));
    }

    List<Reply> roadSocBefore(Driver driver, BotSessionStore.Session session, String text) {
        var soc = TripFlow.percent(text);
        if (soc == null) return retry("Нужен заряд в процентах — целое число от 0 до 100, например <i>15</i>");
        var data = new LinkedHashMap<>(session.data());
        data.put("socBefore", soc.toString());
        sessions.save(driver.getTelegramId(), BotState.ROAD_CHARGE_SOC_AFTER, data);
        return List.of(Reply.of("Заряд после подзарядки, %? Например: <i>80</i>", Buttons.cancel()));
    }

    List<Reply> roadSocAfter(Driver driver, BotSessionStore.Session session, String text) {
        var soc = TripFlow.percent(text);
        int before = Integer.parseInt(session.get("socBefore"));
        if (soc == null) return retry("Нужен заряд в процентах — целое число от 0 до 100, например <i>80</i>");
        if (soc <= before) return retry("После подзарядки заряд должен быть больше, чем до (" + before + " %).");
        var data = new LinkedHashMap<>(session.data());
        data.put("endSoc", soc.toString());
        if (ChargeLocation.valueOf(data.get("location")) == ChargeLocation.HOME) return saveRoad(driver, data);
        sessions.save(driver.getTelegramId(), BotState.ROAD_CHARGE_KWH, data);
        return List.of(askKwh());
    }

    // ---------- Общие шаги: кВт·ч и сумма (станции) ----------

    List<Reply> kwh(Driver driver, BotSessionStore.Session session, String text) {
        var kwh = Format.decimal(text);
        if (kwh == null || kwh.signum() <= 0 || kwh.compareTo(BigDecimal.valueOf(500)) > 0) {
            return retry("Нужно число кВт·ч, например <i>32,5</i> — или нажмите «Пропустить»");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("kwh", kwh.toPlainString());
        return askPaid(driver, session.state() == BotState.CHARGE_END_KWH ? BotState.CHARGE_END_PAID : BotState.ROAD_CHARGE_PAID, data);
    }

    List<Reply> paid(Driver driver, BotSessionStore.Session session, String text) {
        var paid = Format.decimal(text);
        if (paid == null || paid.signum() < 0 || paid.compareTo(new BigDecimal("100000")) > 0) {
            return retry("Нужна сумма в сомах, например <i>450</i> — или нажмите «Пропустить»");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("paid", paid.toPlainString());
        return session.state() == BotState.CHARGE_END_PAID ? finish(driver, data) : saveRoad(driver, data);
    }

    /** «Пропустить» на шагах кВт·ч и суммы. */
    List<Reply> skip(Driver driver, BotSessionStore.Session session) {
        var data = new LinkedHashMap<>(session.data());
        return switch (session.state()) {
            case CHARGE_END_KWH -> askPaid(driver, BotState.CHARGE_END_PAID, data);
            case ROAD_CHARGE_KWH -> askPaid(driver, BotState.ROAD_CHARGE_PAID, data);
            case CHARGE_END_PAID -> finish(driver, data);
            case ROAD_CHARGE_PAID -> saveRoad(driver, data);
            default -> stale(driver);
        };
    }

    static boolean isSkippable(BotState state) {
        return state == BotState.CHARGE_END_KWH || state == BotState.CHARGE_END_PAID
                || state == BotState.ROAD_CHARGE_KWH || state == BotState.ROAD_CHARGE_PAID;
    }

    private List<Reply> askPaid(Driver driver, BotState next, Map<String, String> data) {
        sessions.save(driver.getTelegramId(), next, data);
        var location = ChargeLocation.valueOf(data.get("location"));
        var price = location.price(households.requireHousehold(driver));
        return List.of(Reply.of("Сколько заплатили, сом? Можно пропустить — посчитаю по цене: "
                + Format.number(price) + " сом/кВт·ч (" + location.title() + ")", Buttons.skipOrCancel()));
    }

    private Reply askKwh() {
        return Reply.of("Сколько кВт·ч отдала станция? Видно на экране станции или в приложении.\n"
                + "Можно пропустить — посчитаю по % заряда.", Buttons.skipOrCancel());
    }

    private List<Reply> finish(Driver driver, Map<String, String> data) {
        sessions.clear(driver.getTelegramId());
        var r = charges.finish(driver, Long.parseLong(data.get("chargeId")), Integer.parseInt(data.get("endSoc")),
                decimal(data.get("kwh")), decimal(data.get("paid")));
        var c = r.charge();
        var sb = new StringBuilder("🔋 <b>Зарядка закончена</b> — «").append(esc(r.car().getName())).append("», ")
                .append(c.getLocation().title()).append("\n\n")
                .append(c.getStartSocPct()).append(" → ").append(c.getEndSocPct()).append(" % за ")
                .append(TripFlow.duration(r.duration())).append('\n');
        summary(sb, c, r.car());
        return List.of(Reply.of(sb.toString()), screens.menu(driver));
    }

    private List<Reply> saveRoad(Driver driver, Map<String, String> data) {
        sessions.clear(driver.getTelegramId());
        long tripId = Long.parseLong(data.get("tripId"));
        var c = charges.roadCharge(driver, tripId, ChargeLocation.valueOf(data.get("location")),
                Integer.parseInt(data.get("socBefore")), Integer.parseInt(data.get("endSoc")),
                decimal(data.get("kwh")), decimal(data.get("paid")));
        var car = car(driver, data);
        var sb = new StringBuilder("🔌 <b>Подзарядка записана</b> — ").append(c.getLocation().title()).append("\n\n")
                .append(c.getStartSocPct()).append(" → ").append(c.getEndSocPct()).append(" %\n");
        summary(sb, c, car);
        sb.append("\n\nПоездка продолжается.");
        return List.of(Reply.of(sb.toString(), List.of(
                List.of(button("🏁 Закончить поездку", Buttons.TRIP_FINISH + tripId)),
                List.of(button("Меню", Buttons.MENU)))));
    }

    /** «В батарею 24,2 кВт·ч · из сети 27,1 кВт·ч × 1,64 = 44 сом». */
    private static void summary(StringBuilder sb, Charge c, Car car) {
        sb.append("В батарею ").append(Format.number(c.getBatteryKwh())).append(" кВт·ч · ")
                .append(c.isKwhMeasured() ? "по счётчику станции " : "из сети ").append(Format.number(c.getGridKwh()))
                .append(" кВт·ч × ").append(Format.number(c.getPricePerKwh())).append(" = <b>")
                .append(Format.number(c.getTotalCost())).append(" сом</b>");
    }

    // ---------- Мелочи ----------

    private List<List<Reply.Button>> locationButtons(Driver driver) {
        return List.of(
                List.of(button("🏠 Дома", Buttons.CHARGE_LOCATION + ChargeLocation.HOME)),
                List.of(button("⚡ 40 кВт", Buttons.CHARGE_LOCATION + ChargeLocation.DC40),
                        button("⚡ 80 кВт", Buttons.CHARGE_LOCATION + ChargeLocation.DC80),
                        button("⚡ 120 кВт", Buttons.CHARGE_LOCATION + ChargeLocation.DC120)),
                List.of(button("Отмена", Buttons.CANCEL)));
    }

    private Car car(Driver driver, Map<String, String> data) {
        return cars.require(driver, Long.parseLong(data.get("carId")));
    }

    private List<Reply> stale(Driver driver) {
        return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
    }

    private static BigDecimal decimal(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static List<Reply> retry(String text) {
        return List.of(Reply.of("⚠️ " + text, Buttons.cancel()));
    }

    /** Можно ли поставить на зарядку из меню: электро и свободна. */
    static boolean canCharge(Car car) {
        return car.isElectric() && car.getState() == CarState.FREE;
    }
}
