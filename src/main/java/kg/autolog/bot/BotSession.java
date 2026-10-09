package kg.autolog.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "bot_session")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class BotSession {

    @Id
    @Column(name = "telegram_id")
    private Long telegramId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BotState state;

    /** Введённые на прошлых шагах значения, JSON-объект строк. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    BotSession(long telegramId) {
        this.telegramId = telegramId;
    }
}
