package kg.autolog.bot.telegram;

import kg.autolog.bot.TripReminders;
import kg.autolog.common.AutologProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** Раз в {@code autolog.reminders.check-every} ищет незакрытые поездки и напоминает водителям. Работает вместе с ботом. */
@Component
@ConditionalOnExpression("!'${autolog.telegram.bot-token:}'.isBlank()")
class TripReminderJob implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(TripReminderJob.class);

    private final TripReminders reminders;
    private final TelegramBot bot;
    private final Clock clock;
    private final Duration every;

    TripReminderJob(TripReminders reminders, TelegramBot bot, Clock clock, AutologProperties props) {
        this.reminders = reminders;
        this.bot = bot;
        this.clock = clock;
        this.every = props.reminders().checkEvery();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::run, every, Duration.ofMinutes(1)));
    }

    void run() {
        try {
            for (var notice : reminders.collect(clock.instant())) {
                bot.push(notice.telegramId(), notice.reply());
            }
        } catch (Exception e) {
            log.error("Не удалось разослать напоминания о поездках", e);
        }
    }
}
