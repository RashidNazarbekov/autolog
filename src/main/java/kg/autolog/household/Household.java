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

import java.math.BigDecimal;
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

    /** Свет дома, сом за кВт·ч. */
    @Column(name = "home_kwh_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal homeKwhPrice = new BigDecimal("1.64");

    /** Потери при зарядке от розетки, %: из сети берётся больше, чем попадает в батарею. */
    @Column(name = "home_loss_pct", nullable = false, precision = 4, scale = 1)
    private BigDecimal homeLossPct = new BigDecimal("12");

    @Column(name = "dc40_kwh_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal dc40KwhPrice = new BigDecimal("12");

    @Column(name = "dc80_kwh_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal dc80KwhPrice = new BigDecimal("14");

    @Column(name = "dc120_kwh_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal dc120KwhPrice = new BigDecimal("16");

    public Household(String name, Instant createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }

    public boolean inviteValidAt(Instant now) {
        return inviteCode != null && inviteExpiresAt != null && now.isBefore(inviteExpiresAt);
    }
}
