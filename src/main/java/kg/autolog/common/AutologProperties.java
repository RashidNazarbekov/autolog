package kg.autolog.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Настройки из application.yml, раздел {@code autolog}.
 *
 * @param zone      часовой пояс для «сегодня», дат в боте и отчётах
 * @param inviteTtl сколько действует код приглашения в дом
 * @param telegram  подключение к Telegram
 * @param reminders напоминания о незакрытых поездках
 */
@ConfigurationProperties("autolog")
public record AutologProperties(
        @DefaultValue("Asia/Bishkek") ZoneId zone,
        @DefaultValue("48h") Duration inviteTtl,
        @DefaultValue Telegram telegram,
        @DefaultValue Reminders reminders
) {

    /**
     * @param botToken    токен от @BotFather; пустой — бот не запускается (например, в тестах)
     * @param botUsername имя бота без @, нужно для ссылок-приглашений вида t.me/имя?start=…
     */
    public record Telegram(
            @DefaultValue("") String botToken,
            @DefaultValue("") String botUsername
    ) {
    }

    /**
     * @param openTripAfter через сколько после старта напомнить о незакрытой поездке
     * @param repeatEvery   как часто повторять, пока поездка открыта
     * @param quietFrom     с какого часа не беспокоить (0–23, по {@code autolog.zone})
     * @param quietTo       до какого часа не беспокоить
     * @param checkEvery    как часто искать такие поездки
     */
    public record Reminders(
            @DefaultValue("6h") Duration openTripAfter,
            @DefaultValue("12h") Duration repeatEvery,
            @DefaultValue("22") int quietFrom,
            @DefaultValue("8") int quietTo,
            @DefaultValue("5m") Duration checkEvery
    ) {

        /** Тихие часы; окно может переходить через полночь (22:00–08:00). */
        public boolean isQuiet(LocalTime t) {
            int h = t.getHour();
            if (quietFrom == quietTo) return false;
            return quietFrom < quietTo
                    ? h >= quietFrom && h < quietTo
                    : h >= quietFrom || h < quietTo;
        }
    }
}
