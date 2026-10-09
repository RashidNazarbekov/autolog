package kg.autolog.charge;

import kg.autolog.car.Car;
import kg.autolog.household.Household;
import kg.autolog.household.HouseholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Сколько стоит кВт·ч, попавший в батарею, и расчёт кВт·ч и стоимости одной зарядки. */
@Service
@RequiredArgsConstructor
public class ChargeEconomy {

    static final int PRICE_WINDOW = 10;

    private final ChargeRepository charges;
    private final HouseholdService households;

    /** Итог зарядки: в батарею, из сети, цена кВт·ч из сети, сумма. */
    public record Amounts(BigDecimal batteryKwh, BigDecimal gridKwh, boolean measured,
                          BigDecimal pricePerKwh, BigDecimal totalCost) {
    }

    /**
     * Считает зарядку. кВт·ч в батарею — по % заряда и ёмкости. Из сети — со счётчика станции, если указали,
     * иначе батарея + потери. Сумма — если указали, иначе кВт·ч из сети × цена из настроек дома.
     */
    public Amounts amounts(Car car, ChargeLocation location, int startSoc, int endSoc,
                           BigDecimal measuredKwh, BigDecimal paid) {
        Household h = households.byId(car.getHouseholdId());
        var battery = car.getBatteryKwh().multiply(BigDecimal.valueOf(endSoc - startSoc))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        var loss = BigDecimal.ONE.add(location.lossPct(h).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        var grid = measuredKwh != null ? measuredKwh.setScale(2, RoundingMode.HALF_UP)
                : battery.multiply(loss).setScale(2, RoundingMode.HALF_UP);
        BigDecimal price;
        BigDecimal total;
        if (paid != null) {
            total = paid.setScale(2, RoundingMode.HALF_UP);
            price = grid.signum() > 0 ? total.divide(grid, 2, RoundingMode.HALF_UP) : location.price(h);
        } else {
            price = location.price(h);
            total = grid.multiply(price).setScale(2, RoundingMode.HALF_UP);
        }
        return new Amounts(battery, grid, measuredKwh != null, price, total);
    }

    /**
     * Средняя цена кВт·ч, попавшего в батарею, по последним зарядкам.
     * Пока зарядок нет — домашний тариф с учётом потерь.
     */
    public BigDecimal pricePerBatteryKwh(Car car) {
        var list = charges.findByCarIdAndStatusOrderByFinishedAtDesc(car.getId(), ChargeStatus.FINISHED, Limit.of(PRICE_WINDOW));
        var battery = list.stream().map(Charge::getBatteryKwh).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (battery.signum() > 0) {
            var cost = list.stream().map(Charge::getTotalCost).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            return cost.divide(battery, 2, RoundingMode.HALF_UP);
        }
        var h = households.byId(car.getHouseholdId());
        var loss = BigDecimal.ONE.add(h.getHomeLossPct().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        return h.getHomeKwhPrice().multiply(loss).setScale(2, RoundingMode.HALF_UP);
    }
}
