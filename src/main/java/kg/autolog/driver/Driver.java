package kg.autolog.driver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Водитель — человек в Telegram. */
@Entity
@Table(name = "driver")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private Long telegramId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 64)
    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Driver(long telegramId, String name, String username, Instant createdAt) {
        this.telegramId = telegramId;
        this.name = name;
        this.username = username;
        this.createdAt = createdAt;
    }
}
