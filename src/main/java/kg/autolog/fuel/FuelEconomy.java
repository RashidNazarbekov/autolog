package kg.autolog.fuel;

import kg.autolog.car.Car;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Расход и цена топлива по заправкам.
 *
 * <p>Расход = литры, залитые после первой заправки окна, / км между первой и последней заправкой окна.
 * Заправляться до полного не обязательно: на длинном отрезке остаток в баке на краях почти не влияет.
 */
@Service
@RequiredArgsConstructor
public class FuelEconomy {

    /** Сколько последних заправок учитываем. */
    static final int WINDOW = 15;
    /** Меньше — расход по заправкам ещё слишком неточен, берём заводской. */
    static final int MIN_KM = 300;
    /** Для средней цены — последние заправки. */
    static final int PRICE_WINDOW = 5;

    private final RefuelRepository refuels;

    /** @param fromRefuels {@code true} — посчитан по заправкам, {@code false} — заводской */
    public record Consumption(BigDecimal litersPer100Km, boolean fromRefuels) {
    }

    public Optional<Consumption> consumption(Car car) {
        var list = refuels.findByCarIdOrderByOdometerKmDescRefueledAtDesc(car.getId(), Limit.of(WINDOW));
        if (list.size() >= 2) {
            var newest = list.get(0);
            var oldest = list.get(list.size() - 1);
            int km = newest.getOdometerKm() - oldest.getOdometerKm();
            if (km >= MIN_KM) {
                // Литры первой (самой старой) заправки окна сожжены до начала отрезка — их не считаем
                var liters = list.subList(0, list.size() - 1).stream()
                        .map(Refuel::getLiters)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                var per100 = liters.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(km), 1, RoundingMode.HALF_UP);
                return Optional.of(new Consumption(per100, true));
            }
        }
        return Optional.ofNullable(car.getRatedConsumption()).map(r -> new Consumption(r, false));
    }

    /** Средняя цена литра по последним заправкам (взвешенная по литрам). */
    public Optional<BigDecimal> averagePrice(Car car) {
        var list = refuels.findByCarIdOrderByOdometerKmDescRefueledAtDesc(car.getId(), Limit.of(PRICE_WINDOW));
        if (list.isEmpty()) return Optional.empty();
        var liters = list.stream().map(Refuel::getLiters).reduce(BigDecimal.ZERO, BigDecimal::add);
        var cost = list.stream().map(Refuel::getTotalCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(cost.divide(liters, 2, RoundingMode.HALF_UP));
    }

    /** Цена последней заправки — для кнопки «как в прошлый раз». */
    public Optional<BigDecimal> lastPrice(Car car) {
        return refuels.findFirstByCarIdOrderByRefueledAtDesc(car.getId()).map(Refuel::getPricePerLiter);
    }

    /**
     * Оценка поездки на дизеле.
     *
     * @return литры и, если известна цена, сомы; пусто, если не знаем расход
     */
    public Optional<TripFuel> tripFuel(Car car, int km) {
        return consumption(car).map(c -> {
            var liters = c.litersPer100Km().multiply(BigDecimal.valueOf(km)).divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
            var cost = averagePrice(car).map(p -> p.multiply(liters).setScale(0, RoundingMode.HALF_UP)).orElse(null);
            return new TripFuel(liters, cost, c);
        });
    }

    /** @param cost сомы; {@code null}, если заправок ещё не было и цены нет */
    public record TripFuel(BigDecimal liters, BigDecimal cost, Consumption basis) {
    }
}
