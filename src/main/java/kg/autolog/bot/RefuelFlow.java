package kg.autolog.bot;

import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.driver.Driver;
import kg.autolog.fuel.FuelEconomy;
import kg.autolog.fuel.RefuelService;
import kg.autolog.trip.Trip;
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
 * Сценарий заправки дизеля: «Заправка» → пробег → литры → цена за литр (или сумма) → итог.
 * Можно начать с суммы: «Знаю только сумму» → сумма → цена → литры посчитаются.
 */
@Component
@RequiredArgsConstructor
class RefuelFlow {

    private static final BigDecimal MAX_INPUT = new BigDecimal("1000000");

    private final RefuelService refuels;
    private final FuelEconomy economy;
    private final TripService trips;
    private final CarService cars;
    private final BotSessionStore sessions;
    private final BotScreens screens;

    List<Reply> pressed(Driver driver, long carId) {
        var car = cars.require(driver, carId);
        if (car.isElectric()) {
            return List.of(Reply.of("⚠️ «" + esc(car.getName()) + "» — электромобиль, заправка только для дизеля."), screens.menu(driver));
        }
        Trip trip = trips.openTripOf(car.getId()).orElse(null);
        if (trip != null && !trip.getDriverId().equals(driver.getId())) {
            return List.of(Reply.of("⚠️ " + esc(trips.busyMessage(car)) + ". Заправку в поездке отмечает тот, кто за рулём."),
                    screens.menu(driver));
        }
        var data = new LinkedHashMap<String, String>();
        data.put("carId", String.valueOf(carId));
        sessions.save(driver.getTelegramId(), BotState.REFUEL_ODOMETER, data);
        var text = new StringBuilder("⛽ Заправка «").append(esc(car.getName())).append("».\n\nСколько на одометре? ");
        var rows = new ArrayList<List<Reply.Button>>();
        if (trip != null) {
            text.append("На старте поездки было <b>").append(Format.km(trip.getStartOdometerKm())).append(" км</b>");
        } else {
            var km = Format.km(car.getOdometerKm());
            text.append("Последний известный пробег: <b>").append(km).append(" км</b>");
            rows.add(List.of(button("Не менялся: " + km + " км", Buttons.FUEL_SAME_ODOMETER)));
        }
        rows.add(List.of(button("Отмена", Buttons.CANCEL)));
        return List.of(Reply.of(text.toString(), rows));
    }

    List<Reply> sameOdometer(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.REFUEL_ODOMETER) return stale(driver);
        var car = car(driver, session);
        return odometer(driver, session, car.getOdometerKm());
    }

    List<Reply> odometer(Driver driver, BotSessionStore.Session session, Integer km) {
        var car = car(driver, session);
        var trip = trips.openTripOf(car.getId()).orElse(null);
        int min = trip != null ? trip.getStartOdometerKm() : car.getOdometerKm();
        if (km == null) return retry("Нужно целое число километров, например <i>" + Format.km(min) + "</i>");
        if (km < min) {
            return retry((trip != null ? "Пробег меньше, чем на старте поездки (" : "Пробег меньше последнего известного (")
                    + Format.km(min) + " км). Проверьте одометр.");
        }
        int limit = trip != null ? TripService.MAX_TRIP_KM : TripService.MAX_GAP_KM;
        if (km - min > limit) return retry("Это на " + Format.km(km - min) + " км больше — похоже на опечатку. Проверьте одометр.");
        var data = new LinkedHashMap<>(session.data());
        data.put("odometer", km.toString());
        sessions.save(driver.getTelegramId(), BotState.REFUEL_LITERS, data);
        return List.of(Reply.of("Сколько литров залили? Например: <i>45</i>",
                List.of(List.of(button("Знаю только сумму", Buttons.FUEL_BY_TOTAL)),
                        List.of(button("Отмена", Buttons.CANCEL)))));
    }

    List<Reply> liters(Driver driver, BotSessionStore.Session session, String text) {
        var liters = amount(text);
        if (liters == null) return retry("Нужно число литров, например <i>45</i> или <i>38,5</i>");
        var tank = car(driver, session).getTankLiters();
        if (tank != null && liters.compareTo(tank.multiply(new BigDecimal("1.1"))) > 0) {
            return retry("Больше объёма бака (" + Format.number(tank) + " л). Сколько литров залили?");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("liters", liters.toPlainString());
        return askPrice(driver, session, data, "Цена за литр, сом? Например: <i>87,9</i>",
                List.of(button("Ввести сумму вместо цены", Buttons.FUEL_BY_TOTAL)));
    }

    /** «Знаю только сумму» (на шаге литров) или «Ввести сумму вместо цены» (на шаге цены). */
    List<Reply> byTotal(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.REFUEL_LITERS && session.state() != BotState.REFUEL_PRICE) return stale(driver);
        sessions.save(driver.getTelegramId(), BotState.REFUEL_TOTAL, new LinkedHashMap<>(session.data()));
        return List.of(Reply.of("Сколько заплатили всего, сом? Например: <i>3 950</i>", Buttons.cancel()));
    }

    List<Reply> total(Driver driver, BotSessionStore.Session session, String text) {
        var total = amount(text);
        if (total == null) return retry("Нужна сумма в сомах, например <i>3 950</i>");
        var data = new LinkedHashMap<>(session.data());
        data.put("total", total.toPlainString());
        if (data.containsKey("liters")) {
            var price = total.divide(new BigDecimal(data.get("liters")), 2, java.math.RoundingMode.HALF_UP);
            if (!plausiblePrice(price)) {
                return retry("Получается " + Format.number(price) + " сом за литр — проверьте сумму.");
            }
            return save(driver, data);
        }
        // Литров не знаем — нужна цена, литры посчитаем
        return askPrice(driver, session, data, "Цена за литр, сом? Литры посчитаю сам. Например: <i>87,9</i>", null);
    }

    List<Reply> price(Driver driver, BotSessionStore.Session session, String text) {
        var price = amount(text);
        if (price == null || !plausiblePrice(price)) return retry("Нужна цена за литр в сомах (от 10 до 1000), например <i>87,9</i>");
        if (session.data().containsKey("total") && !session.data().containsKey("liters")) {
            var tank = car(driver, session).getTankLiters();
            var liters = new BigDecimal(session.get("total")).divide(price, 2, java.math.RoundingMode.HALF_UP);
            if (tank != null && liters.compareTo(tank.multiply(new BigDecimal("1.1"))) > 0) {
                return retry("Получается " + Format.number(liters) + " л — больше бака. Проверьте цену или нажмите «Отмена».");
            }
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("price", price.toPlainString());
        return save(driver, data);
    }

    List<Reply> lastPrice(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.REFUEL_PRICE) return stale(driver);
        var price = economy.lastPrice(car(driver, session)).orElse(null);
        if (price == null) return stale(driver);
        var data = new LinkedHashMap<>(session.data());
        data.put("price", price.toPlainString());
        return save(driver, data);
    }

    private List<Reply> askPrice(Driver driver, BotSessionStore.Session session, Map<String, String> data,
                                 String question, List<Reply.Button> extra) {
        sessions.save(driver.getTelegramId(), BotState.REFUEL_PRICE, data);
        var rows = new ArrayList<List<Reply.Button>>();
        economy.lastPrice(car(driver, session)).ifPresent(p ->
                rows.add(List.of(button(Format.number(p) + " сом/л — как в прошлый раз", Buttons.FUEL_LAST_PRICE))));
        if (extra != null) rows.add(extra);
        rows.add(List.of(button("Отмена", Buttons.CANCEL)));
        return List.of(Reply.of(question, rows));
    }

    private List<Reply> save(Driver driver, Map<String, String> data) {
        sessions.clear(driver.getTelegramId());
        var result = refuels.record(driver,
                Long.parseLong(data.get("carId")),
                Integer.parseInt(data.get("odometer")),
                decimal(data.get("liters")), decimal(data.get("price")), decimal(data.get("total")));
        var r = result.refuel();
        var car = result.car();
        var sb = new StringBuilder("⛽ <b>Заправка записана</b> — «").append(esc(car.getName())).append("»\n\n")
                .append(Format.number(r.getLiters())).append(" л × ").append(Format.number(r.getPricePerLiter()))
                .append(" сом = <b>").append(Format.number(r.getTotalCost())).append(" сом</b>\n")
                .append("Пробег ").append(Format.km(r.getOdometerKm())).append(" км");
        economy.consumption(car).filter(FuelEconomy.Consumption::fromRefuels).ifPresent(c ->
                sb.append("\nСредний расход по заправкам: ").append(Format.number(c.litersPer100Km())).append(" л/100 км"));
        var replies = new ArrayList<Reply>();
        if (result.trip() != null) {
            replies.add(Reply.of(sb.toString(), List.of(
                    List.of(button("🏁 Закончить поездку", Buttons.TRIP_FINISH + result.trip().getId())),
                    List.of(button("Меню", Buttons.MENU)))));
        } else {
            replies.add(Reply.of(sb.toString()));
        }
        if (result.gap() != null) {
            replies.add(screens.gapQuestion(driver, result.gap()));
        } else if (result.trip() == null) {
            replies.add(screens.menu(driver));
        }
        return replies;
    }

    // ---------- Мелочи ----------

    private Car car(Driver driver, BotSessionStore.Session session) {
        return cars.require(driver, Long.parseLong(session.get("carId")));
    }

    private List<Reply> stale(Driver driver) {
        return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
    }

    private static BigDecimal amount(String text) {
        var v = Format.decimal(text);
        return v == null || v.signum() <= 0 || v.compareTo(MAX_INPUT) > 0 ? null : v;
    }

    private static boolean plausiblePrice(BigDecimal price) {
        return price.compareTo(BigDecimal.TEN) >= 0 && price.compareTo(new BigDecimal("1000")) <= 0;
    }

    private static BigDecimal decimal(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static List<Reply> retry(String text) {
        return List.of(Reply.of("⚠️ " + text, Buttons.cancel()));
    }

    /** Можно ли заправлять эту машину из меню: дизель, свободна или в моей поездке. */
    static boolean canRefuel(Car car, Driver driver) {
        return !car.isElectric() && (car.getState() == CarState.FREE
                || (car.getState() == CarState.ON_TRIP && driver.getId().equals(car.getCurrentDriverId())));
    }
}
