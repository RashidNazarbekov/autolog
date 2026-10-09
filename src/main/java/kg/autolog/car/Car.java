package kg.autolog.car;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Машина дома. */
@Entity
@Table(name = "car")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Car {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", nullable = false, length = 16)
    private FuelType fuelType;

    @Column(length = 16)
    private String plate;

    /** Объём бака, л — только дизель. */
    @Column(name = "tank_liters", precision = 5, scale = 1)
    private BigDecimal tankLiters;

    /** Ёмкость батареи, кВт·ч — только электро. */
    @Column(name = "battery_kwh", precision = 5, scale = 1)
    private BigDecimal batteryKwh;

    /** Заводской расход: л/100 км или кВт·ч/100 км. */
    @Column(name = "rated_consumption", precision = 5, scale = 2)
    private BigDecimal ratedConsumption;

    @Column(name = "odometer_km", nullable = false)
    private int odometerKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CarState state = CarState.FREE;

    @Column(name = "current_driver_id")
    private Long currentDriverId;

    @Column(name = "state_since")
    private Instant stateSince;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Защита от одновременных изменений: два водителя не начнут поездку на одной машине. */
    @Version
    @Column(nullable = false)
    private long version;

    public Car(long householdId, String name, FuelType fuelType, Instant createdAt) {
        this.householdId = householdId;
        this.name = name;
        this.fuelType = fuelType;
        this.createdAt = createdAt;
    }

    public boolean isElectric() {
        return fuelType == FuelType.ELECTRIC;
    }
}
