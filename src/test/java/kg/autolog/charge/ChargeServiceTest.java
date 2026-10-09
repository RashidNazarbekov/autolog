package kg.autolog.charge;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.household.PriceSetting;
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
class ChargeServiceTest extends IntegrationTest {

    @Autowired
    ChargeService charges;

    @Autowired
    ChargeEconomy economy;

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
    Car emka;  // 40,3 кВт·ч, 1 200 км
    Car prado;

    @BeforeEach
    void home() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        households.create(owner, "Дом");
        households.join(brother, households.createInvite(owner));
        emka = cars.add(owner, electric("Эмка"));
        prado = cars.add(owner, diesel("Прадо"));
    }

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void homeChargeCostsByTariffWithLosses() {
        var start = charges.start(owner, emka.getId(), ChargeLocation.HOME, 1_200, 20);
        assertThat(start.car().getState()).isEqualTo(CarState.CHARGING);
        assertThat(start.car().getCurrentDriverId()).isEqualTo(owner.getId());

        // Снять с розетки может другой участник дома
        var end = charges.finish(brother, start.charge().getId(), 80, null, null);

        var c = end.charge();
        assertThat(c.getBatteryKwh()).isEqualByComparingTo("24.18");   // 60 % от 40,3
        assertThat(c.getGridKwh()).isEqualByComparingTo("27.08");      // + 12 % потерь
        assertThat(c.getPricePerKwh()).isEqualByComparingTo("1.64");
        assertThat(c.getTotalCost()).isEqualByComparingTo("44.41");
        assertThat(c.isKwhMeasured()).isFalse();
        assertThat(end.car().getState()).isEqualTo(CarState.FREE);
        assertThat(end.car().getCurrentDriverId()).isNull();

        assertThat(economy.pricePerBatteryKwh(emka)).isEqualByComparingTo("1.84"); // 44,41 / 24,18
    }

    @Test
    void stationChargeUsesMeterAndPaidAmount() {
        var start = charges.start(owner, emka.getId(), ChargeLocation.DC80, 1_200, 15);
        var c = charges.finish(owner, start.charge().getId(), 80, d("28"), d("420")).charge();

        assertThat(c.getBatteryKwh()).isEqualByComparingTo("26.20");
        assertThat(c.getGridKwh()).isEqualByComparingTo("28");
        assertThat(c.isKwhMeasured()).isTrue();
        assertThat(c.getPricePerKwh()).isEqualByComparingTo("15");
        assertThat(c.getTotalCost()).isEqualByComparingTo("420");
    }

    @Test
    void stationChargeWithoutReceiptUsesStationPrice() {
        var start = charges.start(owner, emka.getId(), ChargeLocation.DC120, 1_200, 20);
        var c = charges.finish(owner, start.charge().getId(), 70, null, null).charge();

        // 50 % от 40,3 = 20,15 кВт·ч; +5 % потерь = 21,16; × 16 сом
        assertThat(c.getGridKwh()).isEqualByComparingTo("21.16");
        assertThat(c.getPricePerKwh()).isEqualByComparingTo("16");
        assertThat(c.getTotalCost()).isEqualByComparingTo("338.56");
    }

    @Test
    void chargingRules() {
        assertThatThrownBy(() -> charges.start(owner, prado.getId(), ChargeLocation.HOME, 150_000, 20))
                .isInstanceOf(AutologException.Invalid.class).hasMessageContaining("только для электро");

        trips.start(brother, emka.getId(), 1_200, 50, null);
        assertThatThrownBy(() -> charges.start(owner, emka.getId(), ChargeLocation.HOME, 1_200, 50))
                .isInstanceOf(AutologException.Conflict.class).hasMessageContaining("в поездке");
    }

    @Test
    void endChargeCannotBeBelowStart() {
        var start = charges.start(owner, emka.getId(), ChargeLocation.HOME, 1_200, 40);
        assertThatThrownBy(() -> charges.finish(owner, start.charge().getId(), 30, null, null))
                .isInstanceOf(AutologException.Invalid.class);
    }

    @Test
    void chargingCarCannotGoOnTrip() {
        charges.start(owner, emka.getId(), ChargeLocation.HOME, 1_200, 40);
        assertThatThrownBy(() -> trips.start(brother, emka.getId(), 1_200, 40, null))
                .isInstanceOf(AutologException.Conflict.class).hasMessageContaining("на зарядке");
    }

    @Test
    void roadChargeLetsChargeGrowDuringTrip() {
        var trip = trips.start(owner, emka.getId(), 1_200, 30, null).trip();

        var road = charges.roadCharge(owner, trip.getId(), ChargeLocation.DC120, 10, 80, null, d("500"));
        assertThat(road.getTripId()).isEqualTo(trip.getId());
        assertThat(road.getTotalCost()).isEqualByComparingTo("500");

        // 30 % на старте, −20 % до станции, +70 % на станции, финиш 60 %: израсходовано 30 + 70 − 60 = 40 %
        var end = trips.finish(owner, trip.getId(), 1_400, 60, null);
        assertThat(end.energyKwh()).isEqualByComparingTo("16.1");
        assertThat(end.estimatedCost()).isNotNull();
    }

    @Test
    void roadChargeRules() {
        var trip = trips.start(brother, emka.getId(), 1_200, 30, null).trip();

        assertThatThrownBy(() -> charges.roadCharge(brother, trip.getId(), ChargeLocation.DC80, 50, 40, null, null))
                .isInstanceOf(AutologException.Invalid.class);
        var stranger = data.driver("Сосед");
        households.create(stranger, "Соседи");
        assertThatThrownBy(() -> charges.roadCharge(stranger, trip.getId(), ChargeLocation.DC80, 10, 40, null, null))
                .isInstanceOf(AutologException.NotFound.class);
    }

    @Test
    void ownerChangesTariff() {
        households.updatePrice(owner, PriceSetting.HOME_KWH, d("2.94"));
        assertThatThrownBy(() -> households.updatePrice(brother, PriceSetting.HOME_KWH, d("1")))
                .isInstanceOf(AutologException.Forbidden.class);
        assertThatThrownBy(() -> households.updatePrice(owner, PriceSetting.HOME_LOSS, d("80")))
                .isInstanceOf(AutologException.Invalid.class);

        var start = charges.start(owner, emka.getId(), ChargeLocation.HOME, 1_200, 50);
        var c = charges.finish(owner, start.charge().getId(), 60, null, null).charge();
        assertThat(c.getPricePerKwh()).isEqualByComparingTo("2.94");
    }
}
