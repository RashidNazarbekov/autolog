package kg.autolog.fuel;

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

/** Заправка дизеля: кто, когда, на каком пробеге, сколько литров и за сколько. */
@Entity
@Table(name = "refuel")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refuel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_id", nullable = false)
    private Long carId;

    /** Кто заправлял и платил. */
    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "refueled_at", nullable = false)
    private Instant refueledAt;

    @Column(name = "odometer_km", nullable = false)
    private int odometerKm;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal liters;

    @Column(name = "price_per_liter", nullable = false, precision = 8, scale = 2)
    private BigDecimal pricePerLiter;

    @Column(name = "total_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalCost;

    public Refuel(long carId, long driverId, Long tripId, Instant refueledAt, int odometerKm,
                  BigDecimal liters, BigDecimal pricePerLiter, BigDecimal totalCost) {
        this.carId = carId;
        this.driverId = driverId;
        this.tripId = tripId;
        this.refueledAt = refueledAt;
        this.odometerKm = odometerKm;
        this.liters = liters;
        this.pricePerLiter = pricePerLiter;
        this.totalCost = totalCost;
    }
}
