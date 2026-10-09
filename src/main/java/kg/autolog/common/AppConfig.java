package kg.autolog.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AutologProperties.class)
public class AppConfig {

    /** Все «сейчас» берутся из этих часов — в тестах их можно подменить. */
    @Bean
    public Clock clock(AutologProperties props) {
        return Clock.system(props.zone());
    }
}
