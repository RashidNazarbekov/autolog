package kg.autolog.driver;

import kg.autolog.common.AutologException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverRepository drivers;
    private final Clock clock;

    /**
     * Находит водителя по Telegram id или регистрирует нового.
     * Имя и username обновляются, если человек сменил их в Telegram.
     */
    @Transactional
    public Driver register(long telegramId, String firstName, String lastName, String username) {
        var name = displayName(firstName, lastName, username, telegramId);
        var driver = drivers.findByTelegramId(telegramId).orElse(null);
        if (driver == null) {
            return drivers.save(new Driver(telegramId, name, username, clock.instant()));
        }
        if (!name.equals(driver.getName()) || !Objects.equals(username, driver.getUsername())) {
            driver.setName(name);
            driver.setUsername(username);
        }
        return driver;
    }

    public Driver require(long driverId) {
        return drivers.findById(driverId).orElseThrow(() -> new AutologException.NotFound("Водитель не найден"));
    }

    static String displayName(String firstName, String lastName, String username, long telegramId) {
        var full = String.join(" ", Stream.of(firstName, lastName)
                .filter(s -> s != null && !s.isBlank()).map(String::trim).toList());
        if (full.isBlank()) full = username != null && !username.isBlank() ? username : "Водитель " + telegramId;
        return full.length() > 128 ? full.substring(0, 128) : full;
    }
}
