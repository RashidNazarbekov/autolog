package kg.autolog.bot;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.common.AutologProperties;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.Trip;
import kg.autolog.trip.TripRepository;
import kg.autolog.trip.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static kg.autolog.TestData.diesel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Напоминания о незакрытой поездке. Старт поездки — 09.10.2026 08:00 по Бишкеку (02:00 UTC);
 * по умолчанию напоминаем через 6 ч, повторяем раз в 12 ч, с 22 до 8 молчим.
 */
@Transactional
class TripRemindersTest extends IntegrationTest {

    static final Instant STARTED = Instant.parse("2026-10-09T02:00:00Z");

    @Autowired TripReminders reminders;
    @Autowired TripService trips;
    @Autowired TripRepository tripRepository;
    @Autowired CarService cars;
    @Autowired HouseholdService households;
    @Autowired BotEngine engine;
    @Autowired TestData data;

    Driver owner;
    Driver brother;
    Car prado;
    Trip trip;

    @BeforeEach
    void tripStartedInTheMorning() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        households.create(owner, "Дом");
        households.join(brother, households.createInvite(owner));
        prado = cars.add(owner, diesel("Прадо"));
        trip = trips.start(brother, prado.getId(), 150_000, null, null).trip();
        trip.setStartedAt(STARTED);
        tripRepository.saveAndFlush(trip);
    }

    /** Напоминания только этому водителю — в базе могут быть поездки других тестов. */
    List<Reply> remindersAt(Instant now) {
        return reminders.collect(now).stream()
                .filter(n -> n.telegramId() == brother.getTelegramId())
                .map(TripReminders.Notice::reply)
                .toList();
    }

    static Instant hoursAfterStart(int hours) {
        return STARTED.plus(Duration.ofHours(hours));
    }

    @Test
    void remindsTheDriverOnceTheTripIsOpenTooLong() {
        assertThat(remindersAt(hoursAfterStart(5))).isEmpty();

        var replies = remindersAt(hoursAfterStart(7)); // 15:00
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).text()).contains("«Прадо» открыта уже 7 ч — с 08:00");
        assertThat(replies.get(0).allButtons()).extracting(Reply.Button::data)
                .containsExactly(Buttons.TRIP_FINISH + trip.getId(), Buttons.TRIP_SNOOZE + trip.getId());

        assertThat(remindersAt(hoursAfterStart(8))).as("повтор не раньше чем через 12 ч").isEmpty();
    }

    @Test
    void staysQuietAtNightAndRepeatsInTheMorning() {
        assertThat(remindersAt(hoursAfterStart(7))).hasSize(1);
        assertThat(remindersAt(hoursAfterStart(20))).as("04:00 — тихие часы").isEmpty();

        var morning = remindersAt(hoursAfterStart(27)); // 10.10 11:00
        assertThat(morning).hasSize(1);
        assertThat(morning.get(0).text()).contains("открыта уже 1 д 3 ч — с вчера 08:00");
    }

    @Test
    void finishedTripIsForgotten() {
        trips.finish(brother, trip.getId(), 150_050, null, null);
        assertThat(remindersAt(hoursAfterStart(7))).isEmpty();
    }

    @Test
    void stillDrivingPostponesTheNextReminder() {
        var replies = engine.handle(Incoming.button(brother.getTelegramId(), "Айбек", null, null, Buttons.TRIP_SNOOZE + trip.getId()));

        assertThat(replies.get(0).text()).contains("Напомню через 12 ч");
        assertThat(tripRepository.findById(trip.getId()).orElseThrow().getRemindedAt()).isNotNull();
    }

    @Test
    void snoozeOnFinishedTripSaysSo() {
        trips.finish(brother, trip.getId(), 150_050, null, null);
        var replies = engine.handle(Incoming.button(brother.getTelegramId(), "Айбек", null, null, Buttons.TRIP_SNOOZE + trip.getId()));
        assertThat(replies.get(0).text()).contains("уже закончена");
    }

    @Test
    void quietHoursCrossMidnight() {
        var night = new AutologProperties.Reminders(Duration.ofHours(6), Duration.ofHours(12), 22, 8, Duration.ofMinutes(5));
        assertThat(night.isQuiet(LocalTime.of(23, 30))).isTrue();
        assertThat(night.isQuiet(LocalTime.of(3, 0))).isTrue();
        assertThat(night.isQuiet(LocalTime.of(8, 0))).isFalse();
        assertThat(night.isQuiet(LocalTime.of(21, 59))).isFalse();

        var never = new AutologProperties.Reminders(Duration.ofHours(6), Duration.ofHours(12), 0, 0, Duration.ofMinutes(5));
        assertThat(never.isQuiet(LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void durationsReadNaturally() {
        assertThat(TripReminders.duration(Duration.ofMinutes(45))).isEqualTo("45 мин");
        assertThat(TripReminders.duration(Duration.ofHours(7).plusMinutes(50))).isEqualTo("7 ч");
        assertThat(TripReminders.duration(Duration.ofHours(24))).isEqualTo("1 д");
        assertThat(TripReminders.duration(Duration.ofHours(51))).isEqualTo("2 д 3 ч");
    }
}
