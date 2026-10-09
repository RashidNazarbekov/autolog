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
 */
@ConfigurationProperties("autolog")
public record AutologProperties(
        @DefaultValue("Asia/Bishkek") ZoneId zone,
        @DefaultValue("48h") Duration inviteTtl
) {
}
