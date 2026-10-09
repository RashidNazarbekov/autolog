package kg.autolog.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Настройки из application.yml, раздел {@code autolog}.
 *
 * @param zone      часовой пояс для «сегодня», дат в боте и отчётах
 * @param inviteTtl сколько действует код приглашения в дом
 * @param telegram  подключение к Telegram
 */
@ConfigurationProperties("autolog")
public record AutologProperties(
        @DefaultValue("Asia/Bishkek") ZoneId zone,
        @DefaultValue("48h") Duration inviteTtl,
        @DefaultValue Telegram telegram
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
}
