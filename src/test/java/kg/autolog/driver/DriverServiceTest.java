package kg.autolog.driver;

import kg.autolog.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class DriverServiceTest extends IntegrationTest {

    @Autowired
    DriverService service;

    @Autowired
    DriverRepository repository;

    @Test
    void registersOnceAndUpdatesNameFromTelegram() {
        var first = service.register(42L, "Рашид", null, "rashid");
        var again = service.register(42L, "Рашид", "Назарбеков", "rashid_n");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getName()).isEqualTo("Рашид Назарбеков");
        assertThat(again.getUsername()).isEqualTo("rashid_n");
        assertThat(repository.findByTelegramId(42L)).isPresent();
    }

    @Test
    void displayNameFallsBackToUsernameThenId() {
        assertThat(DriverService.displayName(" ", null, "aibek", 7)).isEqualTo("aibek");
        assertThat(DriverService.displayName(null, null, null, 7)).isEqualTo("Водитель 7");
    }
}
