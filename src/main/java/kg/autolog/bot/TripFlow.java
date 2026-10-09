package kg.autolog.bot;

import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverService;
import kg.autolog.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/**
 * Сценарий поездки в боте: «Поехать» → пробег → заряд (электро) или запас хода (дизель, по желанию) →
 * … → «Закончить» → пробег → заряд или запас хода → итог.
 */
@Component
@RequiredArgsConstructor
class TripFlow {

    private final TripService trips;
    private final CarService cars;
    private final DriverService drivers;
    private final BotSessionStore sessions;
    private final BotScreens screens;

    // ---------- Старт ----------

    List<Reply> startPressed(Driver driver, long carId) {
        var car = cars.require(driver, carId);
        if (car.getState() != CarState.FREE) {
            return List.of(Reply.of("⚠️ " + esc(trips.busyMessage(car))), screens.menu(driver));
        }
        if (trips.currentTrip(driver).isPresent()) {
            return List.of(Reply.of("⚠️ У вас уже идёт поездка. Сначала закончите её."), screens.menu(driver));
        }
        var data = new LinkedHashMap<String, String>();
        data.put("carId", String.valueOf(carId));
        sessions.save(driver.getTelegramId(), BotState.TRIP_START_ODOMETER, data);
        var km = Format.km(car.getOdometerKm());
        return List.of(Reply.of("▶ Поездка на «" + esc(car.getName()) + "».\n\n"
                        + "Сколько на одометре сейчас? Последний известный пробег: <b>" + km + " км</b>",
                List.of(List.of(button("Не менялся: " + km + " км", Buttons.TRIP_SAME_ODOMETER)),
                        List.of(button("Отмена", Buttons.CANCEL)))));
    }

    List<Reply> startOdometer(Driver driver, BotSessionStore.Session session, Integer km) {
        var car = cars.require(driver, Long.parseLong(session.get("carId")));
        if (km == null) return retry("Нужно целое число километров, например <i>" + Format.km(car.getOdometerKm()) + "</i>");
        if (km < car.getOdometerKm()) {
            return retry("Пробег меньше последнего известного (" + Format.km(car.getOdometerKm()) + " км). Проверьте одометр.");
        }
        if (km - car.getOdometerKm() > TripService.MAX_GAP_KM) {
            return retry("Это на " + Format.km(km - car.getOdometerKm()) + " км больше последнего известного — похоже на опечатку. Проверьте одометр.");
        }
        var data = new LinkedHashMap<>(session.data());
        data.put("odometer", km.toString());
        if (car.isElectric()) {
            sessions.save(driver.getTelegramId(), BotState.TRIP_START_SOC, data);
            return List.of(Reply.of("Заряд батареи сейчас, %? Например: <i>85</i>", Buttons.cancel()));
        }
        sessions.save(driver.getTelegramId(), BotState.TRIP_START_RANGE, data);
        return List.of(Reply.of("Запас хода на приборке, км? Например: <i>520</i>\nМожно пропустить.", Buttons.skipOrCancel()));
    }

    List<Reply> startSoc(Driver driver, BotSessionStore.Session session, String text) {
        var soc = percent(text);
        if (soc == null) return retry("Нужен заряд в процентах — целое число от 0 до 100, например <i>85</i>");
        return doStart(driver, session.data(), soc, null);
    }

    List<Reply> startRange(Driver driver, BotSessionStore.Session session, String text) {
        var range = Format.integer(text);
        if (range == null || range < 0) return retry("Нужен запас хода в км, например <i>520</i> — или нажмите «Пропустить»");
        return doStart(driver, session.data(), null, range);
    }

    private List<Reply> doStart(Driver driver, Map<String, String> data, Integer soc, Integer range) {
        // Что бы ни случилось (машину успели взять и т. п.), сценарий старта на этом заканчивается
        sessions.clear(driver.getTelegramId());
        var result = trips.start(driver, Long.parseLong(data.get("carId")), Integer.parseInt(data.get("odometer")), soc, range);
        var car = result.car();
        var trip = result.trip();
        var sb = new StringBuilder("▶ <b>Поехали!</b> «").append(esc(car.getName())).append("»\n")
                .append("Старт в ").append(screens.when(trip.getStartedAt()))
                .append(", ").append(Format.km(trip.getStartOdometerKm())).append(" км");
        if (trip.getStartSocPct() != null) sb.append(", заряд ").append(trip.getStartSocPct()).append(" %");
        if (trip.getStartRangeKm() != null) sb.append(", запас хода ").append(Format.km(trip.getStartRangeKm())).append(" км");
        sb.append("\n\nКогда вернётесь — нажмите «Закончить».");
        var replies = new ArrayList<Reply>();
        var rows = new ArrayList<List<Reply.Button>>();
        rows.add(List.of(button("🏁 Закончить поездку", Buttons.TRIP_FINISH + trip.getId())));
        if (!car.isElectric()) rows.add(List.of(button("⛽ Заправка в пути", Buttons.FUEL_CAR + car.getId())));
        rows.add(List.of(button("Меню", Buttons.MENU)));
        replies.add(Reply.of(sb.toString(), rows));
        if (result.gap() != null) replies.add(screens.gapQuestion(driver, result.gap()));
        return replies;
    }

    // ---------- Финиш ----------

    List<Reply> finishPressed(Driver driver, long tripId) {
        var trip = trips.requireOpenTrip(driver, tripId);
        var car = cars.require(driver, trip.getCarId());
        var data = new LinkedHashMap<String, String>();
        data.put("tripId", String.valueOf(tripId));
        sessions.save(driver.getTelegramId(), BotState.TRIP_END_ODOMETER, data);
        return List.of(Reply.of("🏁 Завершаем поездку на «" + esc(car.getName()) + "».\n\n"
                        + "Сколько на одометре? На старте было <b>" + Format.km(trip.getStartOdometerKm()) + " км</b>",
                Buttons.cancel()));
    }

    List<Reply> endOdometer(Driver driver, BotSessionStore.Session session, Integer km) {
        var trip = trips.requireOpenTrip(driver, Long.parseLong(session.get("tripId")));
        if (km == null) return retry("Нужно целое число километров, например <i>" + Format.km(trip.getStartOdometerKm() + 25) + "</i>");
        if (km < trip.getStartOdometerKm()) {
            return retry("Пробег меньше, чем на старте (" + Format.km(trip.getStartOdometerKm()) + " км). Проверьте одометр.");
        }
        if (km - trip.getStartOdometerKm() > TripService.MAX_TRIP_KM) {
            return retry("Больше " + Format.km(TripService.MAX_TRIP_KM) + " км за поездку — похоже на опечатку. Проверьте одометр.");
        }
        var car = cars.require(driver, trip.getCarId());
        var data = new LinkedHashMap<>(session.data());
        data.put("odometer", km.toString());
        if (car.isElectric()) {
            sessions.save(driver.getTelegramId(), BotState.TRIP_END_SOC, data);
            return List.of(Reply.of("Заряд батареи сейчас, %? На старте было " + trip.getStartSocPct() + " %", Buttons.cancel()));
        }
        sessions.save(driver.getTelegramId(), BotState.TRIP_END_RANGE, data);
        return List.of(Reply.of("Запас хода на приборке, км? Можно пропустить.", Buttons.skipOrCancel()));
    }

    List<Reply> endSoc(Driver driver, BotSessionStore.Session session, String text) {
        var soc = percent(text);
        if (soc == null) return retry("Нужен заряд в процентах — целое число от 0 до 100, например <i>62</i>");
        var trip = trips.requireOpenTrip(driver, Long.parseLong(session.get("tripId")));
        if (trip.getStartSocPct() != null && soc > trip.getStartSocPct()) {
            return retry("Заряд больше, чем на старте (" + trip.getStartSocPct()
                    + " %). Если подзаряжались в пути — пока укажите заряд как на старте, отметку подзарядки добавим следующей фичей.");
        }
        return doFinish(driver, session.data(), soc, null);
    }

    List<Reply> endRange(Driver driver, BotSessionStore.Session session, String text) {
        var range = Format.integer(text);
        if (range == null || range < 0) return retry("Нужен запас хода в км, например <i>430</i> — или нажмите «Пропустить»");
        return doFinish(driver, session.data(), null, range);
    }

    private List<Reply> doFinish(Driver driver, Map<String, String> data, Integer soc, Integer range) {
        sessions.clear(driver.getTelegramId());
        var r = trips.finish(driver, Long.parseLong(data.get("tripId")), Integer.parseInt(data.get("odometer")), soc, range);
        var sb = new StringBuilder("🏁 <b>Поездка закончена</b> — «").append(esc(r.car().getName())).append("»\n\n")
                .append("Проехали <b>").append(Format.km(r.distanceKm())).append(" км</b> за ").append(duration(r.duration()))
                .append("\nПробег теперь ").append(Format.km(r.car().getOdometerKm())).append(" км");
        if (r.energyKwh() != null) {
            sb.append("\nУшло ").append(Format.number(r.energyKwh())).append(" кВт·ч (")
                    .append(r.trip().getStartSocPct()).append(" → ").append(r.trip().getEndSocPct()).append(" %)");
            var per100 = r.consumptionPer100();
            if (per100 != null) sb.append(" — ").append(Format.number(per100)).append(" кВт·ч на 100 км");
        }
        if (r.estimatedLiters() != null) {
            sb.append("\n≈ ").append(Format.number(r.estimatedLiters())).append(" л");
            if (r.estimatedCost() != null) sb.append(" ≈ <b>").append(Format.number(r.estimatedCost())).append(" сом</b>");
            sb.append(r.fromRefuels() ? " по вашему среднему расходу " : " по заводскому расходу ")
                    .append(Format.number(r.litersPer100Km())).append(" л/100 км");
        }
        return List.of(Reply.of(sb.toString()), screens.menu(driver));
    }

    // ---------- Пропуск и неучтённый пробег ----------

    /** «Пропустить» на шаге запаса хода. */
    List<Reply> skipRange(Driver driver, BotSessionStore.Session session) {
        return session.state() == BotState.TRIP_START_RANGE
                ? doStart(driver, session.data(), null, null)
                : doFinish(driver, session.data(), null, null);
    }

    /** «Не менялся» — пробег такой же, как последний известный. */
    List<Reply> sameOdometer(Driver driver) {
        var session = sessions.get(driver.getTelegramId());
        if (session.state() != BotState.TRIP_START_ODOMETER) {
            return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
        }
        var car = cars.require(driver, Long.parseLong(session.get("carId")));
        return startOdometer(driver, session, car.getOdometerKm());
    }

    List<Reply> gaps(Driver driver) {
        var open = trips.openGaps(driver);
        if (open.isEmpty()) return List.of(Reply.of("Неучтённого пробега нет 👍"), screens.menu(driver));
        return List.of(screens.gapQuestion(driver, open.get(0)));
    }

    /** gap:{gapId}:{driverId} */
    List<Reply> gapAnswer(Driver driver, String args) {
        var parts = args.split(":");
        if (parts.length != 2) return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
        long gapId = Long.parseLong(parts[0]);
        long assignee = Long.parseLong(parts[1]);
        if (assignee == 0) {
            return List.of(Reply.of("Хорошо, оставлю эти километры неучтёнными — разобрать можно позже из меню."), screens.menu(driver));
        }
        var gap = trips.assignGap(driver, gapId, assignee);
        var name = drivers.require(assignee).getName();
        var replies = new ArrayList<Reply>();
        replies.add(Reply.of("✅ Записал: " + Format.km(gap.km()) + " км — " + esc(name)));
        var rest = trips.openGaps(driver);
        replies.add(rest.isEmpty() ? screens.menu(driver) : screens.gapQuestion(driver, rest.get(0)));
        return replies;
    }

    // ---------- Мелочи ----------

    static Integer percent(String text) {
        var v = Format.integer(text == null ? null : text.replace("%", ""));
        return v == null || v < 0 || v > 100 ? null : v;
    }

    static String duration(Duration d) {
        long minutes = Math.max(1, Math.round(d.toSeconds() / 60.0));
        if (minutes < 60) return minutes + " мин";
        return minutes / 60 + " ч " + String.format("%02d", minutes % 60) + " мин";
    }

    private static List<Reply> retry(String text) {
        return List.of(Reply.of("⚠️ " + text, Buttons.cancel()));
    }

    /** Шаги, на которых «Пропустить» относится к запасу хода. */
    static boolean isRangeStep(BotState state) {
        return state == BotState.TRIP_START_RANGE || state == BotState.TRIP_END_RANGE;
    }
}
