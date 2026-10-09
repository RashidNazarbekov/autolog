package kg.autolog.bot;

/** Шаг сценария, на котором бот ждёт ответ пользователя. */
public enum BotState {
    NONE,
    AWAIT_HOUSEHOLD_NAME,
    AWAIT_INVITE_CODE,
    CAR_AWAIT_TYPE,
    CAR_AWAIT_NAME,
    CAR_AWAIT_CAPACITY,
    CAR_AWAIT_ODOMETER,
    CAR_AWAIT_CONSUMPTION,
    TRIP_START_ODOMETER,
    TRIP_START_SOC,
    TRIP_START_RANGE,
    TRIP_END_ODOMETER,
    TRIP_END_SOC,
    TRIP_END_RANGE
}
