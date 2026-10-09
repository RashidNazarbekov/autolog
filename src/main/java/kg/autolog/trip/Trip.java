package kg.autolog.trip;

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

import java.time.Duration;
import java.time.Instant;

/** Поездка: старт и финиш с пробегом; для электро — % заряда, для дизеля — запас хода по желанию. */
@Entity
@Table(name = "trip")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TripStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "start_odometer_km", nullable = false)
    private int startOdometerKm;

    @Column(name = "start_soc_pct")
    private Integer startSocPct;

    @Column(name = "start_range_km")
    private Integer startRangeKm;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "end_odometer_km")
    private Integer endOdometerKm;

    @Column(name = "end_soc_pct")
    private Integer endSocPct;

    @Column(name = "end_range_km")
    private Integer endRangeKm;

    public Trip(long carId, long driverId, Instant startedAt, int startOdometerKm, Integer startSocPct, Integer startRangeKm) {
        this.carId = carId;
        this.driverId = driverId;
        this.status = TripStatus.OPEN;
        this.startedAt = startedAt;
        this.startOdometerKm = startOdometerKm;
        this.startSocPct = startSocPct;
        this.startRangeKm = startRangeKm;
    }

    public boolean isOpen() {
        return status == TripStatus.OPEN;
    }

    /** Километры поездки; 0, пока она открыта. */
    public int distanceKm() {
        return endOdometerKm == null ? 0 : endOdometerKm - startOdometerKm;
    }

    public Duration duration() {
        return finishedAt == null ? Duration.ZERO : Duration.between(startedAt, finishedAt);
    }
}
