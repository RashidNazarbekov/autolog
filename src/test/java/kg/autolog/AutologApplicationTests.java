package kg.autolog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Приложение поднимается на настоящем PostgreSQL, миграции применяются, health отвечает UP. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutologApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthIsUp() {
        var body = http.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayAppliedBaseline() {
        var value = jdbc.queryForObject("select value from app_info where key = 'schema_baseline'", String.class);
        assertThat(value).isEqualTo("2026-10-09");
    }
}
