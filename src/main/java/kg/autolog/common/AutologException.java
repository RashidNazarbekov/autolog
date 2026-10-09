package kg.autolog.common;

/**
 * Ошибка предметной области. Сообщение пишется для человека — бот и API показывают его как есть.
 */
public abstract sealed class AutologException extends RuntimeException
        permits AutologException.NotFound, AutologException.Forbidden,
                AutologException.Invalid, AutologException.Conflict {

    protected AutologException(String message) {
        super(message);
    }

    /** Нет такого объекта (или он чужой — не раскрываем, что он существует). */
    public static final class NotFound extends AutologException {
        public NotFound(String message) { super(message); }
    }

    /** Объект есть, но действие этому водителю не разрешено. */
    public static final class Forbidden extends AutologException {
        public Forbidden(String message) { super(message); }
    }

    /** Неверные данные: отрицательный пробег, пустое имя и т. п. */
    public static final class Invalid extends AutologException {
        public Invalid(String message) { super(message); }
    }

    /** Действие противоречит текущему состоянию: уже в доме, машина занята и т. п. */
    public static final class Conflict extends AutologException {
        public Conflict(String message) { super(message); }
    }
}
