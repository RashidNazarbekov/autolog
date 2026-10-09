package kg.autolog.bot;

import java.util.List;

import static kg.autolog.bot.Reply.button;

/** Данные кнопок (возвращаются боту при нажатии) и общие наборы кнопок. */
final class Buttons {

    private Buttons() {
    }

    static final String MENU = "menu";
    static final String CREATE_HOME = "home:create";
    static final String JOIN_HOME = "home:join";
    static final String CARS = "cars";
    static final String MEMBERS = "members";
    static final String INVITE = "invite";
    static final String ADD_CAR = "car:add";
    static final String CAR_TYPE = "car:type:";
    static final String SKIP = "skip";
    static final String CANCEL = "cancel";

    /** trip:start:{carId} */
    static final String TRIP_START = "trip:start:";
    /** trip:finish:{tripId} */
    static final String TRIP_FINISH = "trip:finish:";
    /** trip:snooze:{tripId} — «ещё еду» в ответ на напоминание */
    static final String TRIP_SNOOZE = "trip:snooze:";
    /** Пробег не изменился с прошлой отметки */
    static final String TRIP_SAME_ODOMETER = "trip:same";
    /** Разобрать неучтённый пробег */
    static final String GAPS = "gaps";
    /** gap:{gapId}:{driverId}; driverId = 0 — «не знаю» */
    static final String GAP = "gap:";

    /** fuel:car:{carId} — начать запись заправки */
    static final String FUEL_CAR = "fuel:car:";
    /** Пробег не изменился с прошлой отметки */
    static final String FUEL_SAME_ODOMETER = "fuel:same";
    /** Цена за литр как в прошлый раз */
    static final String FUEL_LAST_PRICE = "fuel:lastprice";
    /** Ввести сумму вместо литров или цены */
    static final String FUEL_BY_TOTAL = "fuel:sum";

    /** chg:car:{carId} — поставить на зарядку */
    static final String CHARGE_CAR = "chg:car:";
    /** chg:finish:{chargeId} — снять с зарядки */
    static final String CHARGE_FINISH = "chg:finish:";
    /** chg:road:{tripId} — подзарядка в пути */
    static final String ROAD_CHARGE = "chg:road:";
    /** chg:loc:{HOME|DC40|DC80|DC120} — где заряжаем */
    static final String CHARGE_LOCATION = "chg:loc:";
    /** Пробег не изменился с прошлой отметки */
    static final String CHARGE_SAME_ODOMETER = "chg:same";

    /** Цены дома (для владельца) */
    static final String PRICES = "prices";
    /** set:{PriceSetting} — изменить цену */
    static final String PRICE = "set:";

    /** Начать запись прочего расхода */
    static final String EXPENSE = "exp";
    /** exp:car:{carId} */
    static final String EXPENSE_CAR = "exp:car:";
    /** exp:cat:{categoryId} */
    static final String EXPENSE_CATEGORY = "exp:cat:";
    /** exp:spread:{months} */
    static final String EXPENSE_SPREAD = "exp:spread:";
    /** exp:who:{driverId}; 0 — «не знаю» */
    static final String EXPENSE_OFFENDER = "exp:who:";
    /** Добавить свою категорию (владелец) */
    static final String EXPENSE_NEW_CATEGORY = "exp:newcat";
    /** Последние расходы */
    static final String EXPENSES = "exps";

    /** Выбор периода отчёта */
    static final String REPORTS = "reports";
    /** rep:{week|month|last|year|d:yyyyMMdd-yyyyMMdd} — отчёт за период */
    static final String REPORT = "rep:";
    /** jrn:{тот же период} — журнал событий */
    static final String JOURNAL = "jrn:";
    /** Ввести свой период текстом */
    static final String REPORT_CUSTOM = "rep:custom";

    static List<List<Reply.Button>> cancel() {
        return List.of(List.of(button("Отмена", CANCEL)));
    }

    static List<List<Reply.Button>> menu() {
        return List.of(List.of(button("Меню", MENU)));
    }

    static List<List<Reply.Button>> skipOrCancel() {
        return List.of(List.of(button("Пропустить", SKIP)), List.of(button("Отмена", CANCEL)));
    }
}
