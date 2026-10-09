package kg.autolog.household;

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

/** Дом: семья, у которой общие машины. */
@Entity
@Table(name = "household")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Household {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "invite_code", length = 16, unique = true)
    private String inviteCode;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Household(String name, Instant createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }

    public boolean inviteValidAt(Instant now) {
        return inviteCode != null && inviteExpiresAt != null && now.isBefore(inviteExpiresAt);
    }
}
