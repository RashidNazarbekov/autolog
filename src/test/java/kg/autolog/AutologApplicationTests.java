package kg.autolog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Приложение поднимается на настоящем PostgreSQL, миграции применяются, health отвечает UP. */
class AutologApplicationTests extends IntegrationTest {

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
    void flywayAppliedAllMigrations() {
        var value = jdbc.queryForObject("select value from app_info where key = 'schema_baseline'", String.class);
        assertThat(value).isEqualTo("2026-10-09");
        var tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("driver", "household", "household_member", "car");
    }
}
