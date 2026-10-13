package kg.autolog.car;

import java.math.BigDecimal;

/**
 * Данные машины при создании или изменении. При изменении {@code null} = оставить как было.
 *
 * @param name             как машину называют дома: «Прадо», «Эмка»
 * @param fuelType         бензин, дизель, газ или электро; после создания не меняется
 * @param plate            госномер, по желанию
 * @param tankLiters       объём бака, л — не для электро
 * @param batteryKwh       ёмкость батареи, кВт·ч — для электро
 * @param ratedConsumption заводской расход: л/100 км или кВт·ч/100 км
 * @param odometerKm       текущий пробег, км
 */
public record CarDraft(
        String name,
        FuelType fuelType,
        String plate,
        BigDecimal tankLiters,
        BigDecimal batteryKwh,
        BigDecimal ratedConsumption,
        Integer odometerKm
) {
}
