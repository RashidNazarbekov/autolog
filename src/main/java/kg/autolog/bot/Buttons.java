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
    /** Пробег не изменился с прошлой отметки */
    static final String TRIP_SAME_ODOMETER = "trip:same";
    /** Разобрать неучтённый пробег */
    static final String GAPS = "gaps";
    /** gap:{gapId}:{driverId}; driverId = 0 — «не знаю» */
    static final String GAP = "gap:";

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
