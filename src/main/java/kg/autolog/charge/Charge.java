package kg.autolog.charge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Зарядка электро: отдельная (машина «на зарядке») или подзарядка в пути ({@code tripId}). */
@Entity
@Table(name = "charge")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Charge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    /** Кто поставил на зарядку (и обычно платил). */
    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "trip_id")
    private Long tripId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChargeLocation location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChargeStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "start_odometer_km")
    private Integer startOdometerKm;

    @Column(name = "start_soc_pct", nullable = false)
    private int startSocPct;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "end_soc_pct")
    private Integer endSocPct;

    @Column(name = "battery_kwh", precision = 6, scale = 2)
    private BigDecimal batteryKwh;

    @Column(name = "grid_kwh", precision = 6, scale = 2)
    private BigDecimal gridKwh;

    @Column(name = "kwh_measured", nullable = false)
    private boolean kwhMeasured;

    @Column(name = "price_per_kwh", precision = 8, scale = 2)
    private BigDecimal pricePerKwh;

    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost;

    public Charge(long carId, long driverId, Long tripId, ChargeLocation location,
                  Instant startedAt, Integer startOdometerKm, int startSocPct) {
        this.carId = carId;
        this.driverId = driverId;
        this.tripId = tripId;
        this.location = location;
        this.status = ChargeStatus.OPEN;
        this.startedAt = startedAt;
        this.startOdometerKm = startOdometerKm;
        this.startSocPct = startSocPct;
    }

    public boolean isOpen() {
        return status == ChargeStatus.OPEN;
    }

    public int socGain() {
        return endSocPct == null ? 0 : endSocPct - startSocPct;
    }
}
