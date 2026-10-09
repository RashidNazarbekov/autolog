package kg.autolog.charge;

import kg.autolog.household.Household;

import java.math.BigDecimal;

/** Где заряжались. Цена кВт·ч берётся из настроек дома. */
public enum ChargeLocation {
    HOME("дома"),
    DC40("станция 40 кВт"),
    DC80("станция 80 кВт"),
    DC120("станция 120 кВт");

    private final String title;

    ChargeLocation(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    public BigDecimal price(Household h) {
        return switch (this) {
            case HOME -> h.getHomeKwhPrice();
            case DC40 -> h.getDc40KwhPrice();
            case DC80 -> h.getDc80KwhPrice();
            case DC120 -> h.getDc120KwhPrice();
        };
    }

    /** Потери при зарядке, %: из сети берётся больше, чем попадает в батарею. Быстрые станции — около 5 %. */
    public BigDecimal lossPct(Household h) {
        return this == HOME ? h.getHomeLossPct() : new BigDecimal("5");
    }
}
