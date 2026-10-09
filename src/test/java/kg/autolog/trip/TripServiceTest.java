package kg.autolog.trip;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static kg.autolog.TestData.diesel;
import static kg.autolog.TestData.electric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class TripServiceTest extends IntegrationTest {

    @Autowired
    TripService trips;

    @Autowired
    CarService cars;

    @Autowired
    HouseholdService households;

    @Autowired
    TestData data;

    Driver owner;
    Driver brother;
    Car prado; // дизель, 150 000 км, 9,5 л/100
    Car emka;  // электро, 40,3 кВт·ч, 1 200 км

    @BeforeEach
    void home() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        households.create(owner, "Дом");
        households.join(brother, households.createInvite(owner));
        prado = cars.add(owner, diesel("Прадо"));
        emka = cars.add(owner, electric("Эмка"));
    }

    @Test
    void dieselTripStartsAndFinishes() {
        var start = trips.start(brother, prado.getId(), 150_000, null, 520);

        assertThat(start.gap()).isNull();
        assertThat(start.car().getState()).isEqualTo(CarState.ON_TRIP);
        assertThat(start.car().getCurrentDriverId()).isEqualTo(brother.getId());
        assertThat(trips.currentTrip(brother)).isPresent();

        var end = trips.finish(brother, start.trip().getId(), 150_042, null, 470);

        assertThat(end.distanceKm()).isEqualTo(42);
        assertThat(end.estimatedLiters()).isEqualByComparingTo("4.0"); // 42 × 9,5 / 100 = 3,99
        assertThat(end.car().getState()).isEqualTo(CarState.FREE);
        assertThat(end.car().getCurrentDriverId()).isNull();
        assertThat(end.car().getOdometerKm()).isEqualTo(150_042);
        assertThat(trips.currentTrip(brother)).isEmpty();
    }

    @Test
    void electricTripCountsEnergyFromCharge() {
        var start = trips.start(owner, emka.getId(), 1_200, 85, null);
        var end = trips.finish(owner, start.trip().getId(), 1_240, 70, null);

        assertThat(end.energyKwh()).isEqualByComparingTo("6.0"); // 15 % от 40,3 кВт·ч
        assertThat(end.consumptionPer100()).isEqualByComparingTo("15.0");
        assertThat(end.estimatedLiters()).isNull();
    }

    @Test
    void electricNeedsChargeAndChargeCannotGrow() {
        assertThatThrownBy(() -> trips.start(owner, emka.getId(), 1_200, null, null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("заряд");

        var start = trips.start(owner, emka.getId(), 1_200, 60, null);
        assertThatThrownBy(() -> trips.finish(owner, start.trip().getId(), 1_210, 80, null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("больше, чем на старте");
    }

    @Test
    void busyCarCannotBeTakenByAnotherDriver() {
        trips.start(owner, prado.getId(), 150_000, null, null);

        assertThatThrownBy(() -> trips.start(brother, prado.getId(), 150_000, null, null))
                .isInstanceOf(AutologException.Conflict.class)
                .hasMessageContaining("в поездке у: Рашид");
    }

    @Test
    void oneDriverDrivesOneCarAtATime() {
        trips.start(owner, prado.getId(), 150_000, null, null);

        assertThatThrownBy(() -> trips.start(owner, emka.getId(), 1_200, 90, null))
                .isInstanceOf(AutologException.Conflict.class)
                .hasMessageContaining("другой машине");
    }

    @Test
    void odometerCannotGoBackOrJumpTooFar() {
        assertThatThrownBy(() -> trips.start(owner, prado.getId(), 149_999, null, null))
                .isInstanceOf(AutologException.Invalid.class);
        assertThatThrownBy(() -> trips.start(owner, prado.getId(), 160_000, null, null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("опечатку");

        var start = trips.start(owner, prado.getId(), 150_000, null, null);
        assertThatThrownBy(() -> trips.finish(owner, start.trip().getId(), 149_000, null, null))
                .isInstanceOf(AutologException.Invalid.class);
    }

    @Test
    void jumpInOdometerBecomesGapThatSomeoneClaims() {
        var start = trips.start(owner, prado.getId(), 150_015, null, null);

        var gap = start.gap();
        assertThat(gap).isNotNull();
        assertThat(gap.km()).isEqualTo(15);
        assertThat(trips.openGaps(brother)).extracting(MileageGap::getId).containsExactly(gap.getId());

        trips.assignGap(brother, gap.getId(), brother.getId());

        assertThat(trips.openGaps(owner)).isEmpty();
        assertThatThrownBy(() -> trips.assignGap(owner, gap.getId(), owner.getId()))
                .isInstanceOf(AutologException.Conflict.class);
    }

    @Test
    void gapCanOnlyBeAssignedToHouseholdMember() {
        var gap = trips.start(owner, prado.getId(), 150_015, null, null).gap();
        var stranger = data.driver("Сосед");

        assertThatThrownBy(() -> trips.assignGap(owner, gap.getId(), stranger.getId()))
                .isInstanceOf(AutologException.NotFound.class);
    }

    @Test
    void onlyStarterOrOwnerFinishes() {
        var ownersTrip = trips.start(owner, prado.getId(), 150_000, null, null);
        assertThatThrownBy(() -> trips.finish(brother, ownersTrip.trip().getId(), 150_010, null, null))
                .isInstanceOf(AutologException.Forbidden.class);

        var brothersTrip = trips.start(brother, emka.getId(), 1_200, 90, null);
        var finished = trips.finish(owner, brothersTrip.trip().getId(), 1_230, 80, null);
        assertThat(finished.trip().getDriverId()).isEqualTo(brother.getId());
    }

    @Test
    void finishedTripCannotBeFinishedAgain() {
        var start = trips.start(owner, prado.getId(), 150_000, null, null);
        trips.finish(owner, start.trip().getId(), 150_010, null, null);

        assertThatThrownBy(() -> trips.finish(owner, start.trip().getId(), 150_020, null, null))
                .isInstanceOf(AutologException.NotFound.class);
    }
}
