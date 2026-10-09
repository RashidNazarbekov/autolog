package kg.autolog.bot;

import kg.autolog.car.CarRepository;
import kg.autolog.common.AutologProperties;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverRepository;
import kg.autolog.trip.Trip;
import kg.autolog.trip.TripService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/**
 * Напоминания о незакрытых поездках: забыли нажать «Закончить» — машина числится занятой,
 * а километры потом приходится разбирать как неучтённые.
 * Само сообщение отправляет адаптер Telegram; здесь — кому и что.
 */
@Component
public class TripReminders {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM");

    /** Сообщение для водителя по его Telegram id. */
    public record Notice(long telegramId, Reply reply) {
    }

    private final TripService trips;
    private final CarRepository cars;
    private final DriverRepository drivers;
    private final AutologProperties.Reminders settings;
    private final Clock clock;

    public TripReminders(TripService trips, CarRepository cars, DriverRepository drivers, AutologProperties props, Clock clock) {
        this.trips = trips;
        this.cars = cars;
        this.drivers = drivers;
        this.settings = props.reminders();
        this.clock = clock;
    }

    /** Кому пора напомнить на момент {@code now}. В тихие часы — никому: напомним утром. */
    public List<Notice> collect(Instant now) {
        if (settings.isQuiet(now.atZone(clock.getZone()).toLocalTime())) return List.of();
        var notices = new ArrayList<Notice>();
        for (var trip : trips.takeDueReminders(now, settings.openTripAfter(), settings.repeatEvery())) {
            var driver = drivers.findById(trip.getDriverId()).orElse(null);
            var car = cars.findById(trip.getCarId()).orElse(null);
            if (driver == null || car == null) continue;
            notices.add(new Notice(driver.getTelegramId(), reminder(trip, car.getName(), now)));
        }
        return notices;
    }

    Reply reminder(Trip trip, String carName, Instant now) {
        var text = "⏰ Поездка на «" + esc(carName) + "» открыта уже " + duration(Duration.between(trip.getStartedAt(), now))
                + " — с " + since(trip.getStartedAt(), now) + ".\n\n"
                + "Если вы уже вернулись, закончите её: машина освободится для других, а километры не потеряются.";
        return Reply.of(text, List.of(
                List.of(button("🏁 Закончить поездку", Buttons.TRIP_FINISH + trip.getId())),
                List.of(button("🚗 Ещё еду", Buttons.TRIP_SNOOZE + trip.getId()))));
    }

    /** «Ещё еду». */
    List<Reply> snoozePressed(Driver driver, long tripId) {
        var trip = trips.snooze(driver, tripId);
        return List.of(Reply.of("Хорошо, хорошей дороги! Напомню через " + duration(settings.repeatEvery()) + ", если поездка ещё будет открыта.",
                List.of(List.of(button("🏁 Закончить поездку", Buttons.TRIP_FINISH + trip.getId())),
                        List.of(button("Меню", Buttons.MENU)))));
    }

    /** «7 ч», «1 д 3 ч», «45 мин». */
    static String duration(Duration d) {
        long hours = d.toHours();
        if (hours < 1) return Math.max(d.toMinutes(), 1) + " мин";
        if (hours < 24) return hours + " ч";
        long rest = hours % 24;
        return (hours / 24) + " д" + (rest > 0 ? " " + rest + " ч" : "");
    }

    /** «08:15», «вчера 21:40», «07.10 08:15». */
    private String since(Instant startedAt, Instant now) {
        var start = startedAt.atZone(clock.getZone());
        var today = now.atZone(clock.getZone()).toLocalDate();
        var time = TIME.format(start);
        if (start.toLocalDate().equals(today)) return time;
        if (start.toLocalDate().equals(today.minusDays(1))) return "вчера " + time;
        return DATE.format(start) + " " + time;
    }
}
