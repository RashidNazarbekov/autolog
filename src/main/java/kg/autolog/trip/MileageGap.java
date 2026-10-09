package kg.autolog.trip;

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

/** Неучтённый пробег между прошлым известным одометром и стартом новой поездки. */
@Entity
@Table(name = "mileage_gap")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MileageGap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    @Column(name = "from_km", nullable = false)
    private int fromKm;

    @Column(name = "to_km", nullable = false)
    private int toKm;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "before_trip_id")
    private Long beforeTripId;

    /** Кто проехал эти км; {@code null}, пока не выяснили. */
    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public MileageGap(long carId, int fromKm, int toKm, Instant detectedAt, Long beforeTripId) {
        this.carId = carId;
        this.fromKm = fromKm;
        this.toKm = toKm;
        this.detectedAt = detectedAt;
        this.beforeTripId = beforeTripId;
    }

    public int km() {
        return toKm - fromKm;
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }
}
