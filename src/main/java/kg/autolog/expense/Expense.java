package kg.autolog.expense;

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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Прочий расход на машину. Разовый относится к месяцу покупки; распределённый ({@code spreadMonths > 1})
 * делится поровну на месяцы начиная с месяца покупки — так покупка шин не делает один месяц «диким».
 */
@Entity
@Table(name = "expense")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** Кто платил. */
    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Column(name = "spread_months", nullable = false)
    private int spreadMonths;

    /** Для штрафов: кто нарушил. */
    @Column(name = "offender_driver_id")
    private Long offenderDriverId;

    @Column(name = "odometer_km")
    private Integer odometerKm;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Expense(long carId, long categoryId, long driverId, BigDecimal amount, LocalDate spentOn,
                   int spreadMonths, Long offenderDriverId, String note, Instant createdAt) {
        this.carId = carId;
        this.categoryId = categoryId;
        this.driverId = driverId;
        this.amount = amount;
        this.spentOn = spentOn;
        this.spreadMonths = spreadMonths;
        this.offenderDriverId = offenderDriverId;
        this.note = note;
        this.createdAt = createdAt;
    }

    /** Сколько приходится на месяц, если расход распределён. */
    public BigDecimal perMonth() {
        return amount.divide(BigDecimal.valueOf(spreadMonths), 2, RoundingMode.HALF_UP);
    }

    /** Доля расхода, которая приходится на этот месяц. */
    public BigDecimal shareIn(YearMonth month) {
        var first = YearMonth.from(spentOn);
        var last = first.plusMonths(spreadMonths - 1L);
        if (month.isBefore(first) || month.isAfter(last)) return BigDecimal.ZERO;
        return perMonth();
    }
}
