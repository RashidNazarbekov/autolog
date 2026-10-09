package kg.autolog.fuel;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.trip.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static kg.autolog.TestData.diesel;
import static kg.autolog.TestData.electric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class RefuelServiceTest extends IntegrationTest {

    @Autowired
    RefuelService refuels;

    @Autowired
    FuelEconomy economy;

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
    Driver mother;
    Car prado; // дизель, бак 87 л, 150 000 км, заводской 9,5 л/100
    Car emka;

    @BeforeEach
    void home() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        mother = data.driver("Мама");
        households.create(owner, "Дом");
        var code = households.createInvite(owner);
        households.join(brother, code);
        households.join(mother, code);
        prado = cars.add(owner, diesel("Прадо"));
        emka = cars.add(owner, electric("Эмка"));
    }

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void anyTwoOfThreeAreEnough() {
        var a = RefuelService.complete(d("40"), d("87.9"), null);
        assertThat(a.total()).isEqualByComparingTo("3516");

        var b = RefuelService.complete(null, d("87.9"), d("3516"));
        assertThat(b.liters()).isEqualByComparingTo("40");

        var c = RefuelService.complete(d("40"), null, d("3516"));
        assertThat(c.price()).isEqualByComparingTo("87.9");

        var all = RefuelService.complete(d("40"), d("87.9"), d("3520"));
        assertThat(all.total()).isEqualByComparingTo("3520");
    }

    @Test
    void inconsistentOrMissingAmountsAreRejected() {
        assertThatThrownBy(() -> RefuelService.complete(d("40"), d("87.9"), d("5000")))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("Не сходится");
        assertThatThrownBy(() -> RefuelService.complete(d("40"), null, null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("два значения");
        assertThatThrownBy(() -> RefuelService.complete(d("40"), d("5"), null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("Цена за литр");
        assertThatThrownBy(() -> RefuelService.complete(d("1"), null, d("3500")))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("сом за литр");
    }

    @Test
    void refuelOnFreeCar() {
        var r = refuels.record(brother, prado.getId(), 150_000, d("45"), d("87.9"), null);

        assertThat(r.refuel().getDriverId()).isEqualTo(brother.getId());
        assertThat(r.refuel().getTotalCost()).isEqualByComparingTo("3955.5");
        assertThat(r.trip()).isNull();
        assertThat(r.gap()).isNull();
    }

    @Test
    void odometerJumpOnFreeCarBecomesGap() {
        var r = refuels.record(owner, prado.getId(), 150_120, d("45"), d("87.9"), null);

        assertThat(r.gap()).isNotNull();
        assertThat(r.gap().km()).isEqualTo(120);
        assertThat(r.car().getOdometerKm()).isEqualTo(150_120);
    }

    @Test
    void refuelDuringTripIsLinkedToTrip() {
        var trip = trips.start(brother, prado.getId(), 150_000, null, null).trip();

        var r = refuels.record(brother, prado.getId(), 150_210, null, d("88"), d("3520"));

        assertThat(r.trip().getId()).isEqualTo(trip.getId());
        assertThat(r.refuel().getTripId()).isEqualTo(trip.getId());
        assertThat(r.refuel().getLiters()).isEqualByComparingTo("40");
        assertThat(r.gap()).isNull();
        assertThat(r.car().getOdometerKm()).isEqualTo(150_000); // пробег обновит финиш поездки

        assertThatThrownBy(() -> refuels.record(mother, prado.getId(), 150_220, d("10"), d("88"), null))
                .isInstanceOf(AutologException.Forbidden.class);
        assertThatThrownBy(() -> refuels.record(brother, prado.getId(), 149_000, d("10"), d("88"), null))
                .isInstanceOf(AutologException.Invalid.class);
    }

    @Test
    void electricCarsAreNotRefuelled() {
        assertThatThrownBy(() -> refuels.record(owner, emka.getId(), 1_200, d("10"), d("88"), null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("только для дизеля");
    }

    @Test
    void moreThanTankIsRejected() {
        assertThatThrownBy(() -> refuels.record(owner, prado.getId(), 150_000, d("100"), d("88"), null))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("больше объёма бака");
    }

    @Test
    void consumptionComesFromRefuelsOnceThereIsEnoughDistance() {
        refuels.record(owner, prado.getId(), 150_000, d("40"), d("90"), null);
        assertThat(economy.consumption(prado).orElseThrow().fromRefuels()).isFalse(); // пока заводской

        refuels.record(owner, prado.getId(), 150_400, d("48"), d("90"), null);
        refuels.record(owner, prado.getId(), 150_800, d("52"), d("90"), null);

        var c = economy.consumption(prado).orElseThrow();
        assertThat(c.fromRefuels()).isTrue();
        assertThat(c.litersPer100Km()).isEqualByComparingTo("12.5"); // (48 + 52) л / 800 км
        assertThat(economy.averagePrice(prado)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("90"));
        assertThat(economy.lastPrice(prado)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("90"));

        var trip = trips.start(owner, prado.getId(), 150_800, null, null).trip();
        var end = trips.finish(owner, trip.getId(), 150_900, null, null);
        assertThat(end.fromRefuels()).isTrue();
        assertThat(end.estimatedLiters()).isEqualByComparingTo("12.5");
        assertThat(end.estimatedCost()).isEqualByComparingTo("1125");
    }

    @Test
    void shortDistanceFallsBackToRatedConsumption() {
        refuels.record(owner, prado.getId(), 150_000, d("40"), d("90"), null);
        refuels.record(owner, prado.getId(), 150_100, d("30"), d("90"), null);

        var c = economy.consumption(prado).orElseThrow();
        assertThat(c.fromRefuels()).isFalse();
        assertThat(c.litersPer100Km()).isEqualByComparingTo("9.5");
    }
}
