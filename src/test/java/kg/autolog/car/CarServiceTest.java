package kg.autolog.car;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.common.AutologException;
import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
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
class CarServiceTest extends IntegrationTest {

    @Autowired
    CarService service;

    @Autowired
    HouseholdService households;

    @Autowired
    TestData data;

    Driver owner;
    Driver brother;

    @BeforeEach
    void home() {
        owner = data.driver("Рашид");
        brother = data.driver("Айбек");
        households.create(owner, "Дом");
        households.join(brother, households.createInvite(owner));
    }

    @Test
    void ownerAddsDieselAndElectric() {
        var prado = service.add(owner, diesel("Прадо"));
        var q05 = service.add(owner, electric("Эмка"));

        assertThat(prado.getState()).isEqualTo(CarState.FREE);
        assertThat(prado.getTankLiters()).isEqualByComparingTo("87");
        assertThat(q05.getBatteryKwh()).isEqualByComparingTo("40.3");
        assertThat(service.list(brother)).extracting(Car::getName).containsExactly("Прадо", "Эмка");
    }

    @Test
    void capacityMustMatchFuelType() {
        var noTank = new CarDraft("Прадо", FuelType.DIESEL, null, null, null, null, 0);
        var tankOnElectric = new CarDraft("Эмка", FuelType.ELECTRIC, null, new BigDecimal("50"), new BigDecimal("40.3"), null, 0);

        assertThatThrownBy(() -> service.add(owner, noTank)).hasMessageContaining("объём бака");
        assertThatThrownBy(() -> service.add(owner, tankOnElectric)).hasMessageContaining("только для дизеля");
    }

    @Test
    void onlyOwnerManagesCars() {
        assertThatThrownBy(() -> service.add(brother, diesel("Прадо"))).isInstanceOf(AutologException.Forbidden.class);
        var car = service.add(owner, diesel("Прадо"));
        assertThatThrownBy(() -> service.archive(brother, car.getId())).isInstanceOf(AutologException.Forbidden.class);
    }

    @Test
    void duplicateNameInHouseholdIsRejected() {
        service.add(owner, diesel("Прадо"));
        assertThatThrownBy(() -> service.add(owner, electric("прадо"))).isInstanceOf(AutologException.Conflict.class);
    }

    @Test
    void odometerNeverDecreases() {
        var car = service.add(owner, diesel("Прадо"));
        var lower = new CarDraft(null, null, null, null, null, null, 149_000);
        var higher = new CarDraft(null, null, null, null, null, null, 150_500);

        assertThatThrownBy(() -> service.update(owner, car.getId(), lower)).isInstanceOf(AutologException.Invalid.class);
        assertThat(service.update(owner, car.getId(), higher).getOdometerKm()).isEqualTo(150_500);
    }

    @Test
    void fuelTypeCannotChange() {
        var car = service.add(owner, diesel("Прадо"));
        var change = new CarDraft(null, FuelType.ELECTRIC, null, null, null, null, null);
        assertThatThrownBy(() -> service.update(owner, car.getId(), change)).isInstanceOf(AutologException.Invalid.class);
    }

    @Test
    void archivedCarDisappearsFromList() {
        var car = service.add(owner, diesel("Прадо"));
        service.archive(owner, car.getId());

        assertThat(service.list(owner)).isEmpty();
        assertThatThrownBy(() -> service.require(owner, car.getId())).isInstanceOf(AutologException.NotFound.class);
    }

    @Test
    void carsOfAnotherHouseholdAreInvisible() {
        var car = service.add(owner, diesel("Прадо"));
        var neighbour = data.driver("Сосед");
        households.create(neighbour, "Соседи");

        assertThat(service.list(neighbour)).isEmpty();
        assertThatThrownBy(() -> service.require(neighbour, car.getId())).isInstanceOf(AutologException.NotFound.class);
    }
}
