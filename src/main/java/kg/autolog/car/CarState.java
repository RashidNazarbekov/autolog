package kg.autolog.car;

/** Текущее состояние машины (см. «Состояние машины» в docs/REQUIREMENTS.md). */
public enum CarState {
    /** Машину может взять любой водитель дома. */
    FREE,
    /** В поездке у водителя {@code currentDriverId}. */
    ON_TRIP,
    /** На зарядке (только электро). */
    CHARGING
}
