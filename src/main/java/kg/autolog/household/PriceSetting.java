package kg.autolog.household;

import java.math.BigDecimal;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Цены дома, которые владелец может менять. */
public enum PriceSetting {
    HOME_KWH("Свет дома", "сом/кВт·ч", "0.1", "50", Household::getHomeKwhPrice, Household::setHomeKwhPrice),
    HOME_LOSS("Потери при зарядке дома", "%", "0", "50", Household::getHomeLossPct, Household::setHomeLossPct),
    DC40("Станция 40 кВт", "сом/кВт·ч", "0.1", "200", Household::getDc40KwhPrice, Household::setDc40KwhPrice),
    DC80("Станция 80 кВт", "сом/кВт·ч", "0.1", "200", Household::getDc80KwhPrice, Household::setDc80KwhPrice),
    DC120("Станция 120 кВт", "сом/кВт·ч", "0.1", "200", Household::getDc120KwhPrice, Household::setDc120KwhPrice);

    private final String title;
    private final String unit;
    private final BigDecimal min;
    private final BigDecimal max;
    private final Function<Household, BigDecimal> getter;
    private final BiConsumer<Household, BigDecimal> setter;

    PriceSetting(String title, String unit, String min, String max,
                 Function<Household, BigDecimal> getter, BiConsumer<Household, BigDecimal> setter) {
        this.title = title;
        this.unit = unit;
        this.min = new BigDecimal(min);
        this.max = new BigDecimal(max);
        this.getter = getter;
        this.setter = setter;
    }

    public String title() {
        return title;
    }

    public String unit() {
        return unit;
    }

    public BigDecimal min() {
        return min;
    }

    public BigDecimal max() {
        return max;
    }

    public BigDecimal get(Household h) {
        return getter.apply(h);
    }

    void set(Household h, BigDecimal value) {
        setter.accept(h, value);
    }
}
