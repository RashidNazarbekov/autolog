package kg.autolog.bot;

/**
 * Что прислал пользователь: текст сообщения или нажатие кнопки (данные кнопки в {@code callbackData}).
 * Не зависит от библиотеки Telegram — так ядро бота можно тестировать без неё.
 */
public record Incoming(
        long userId,
        String firstName,
        String lastName,
        String username,
        String text,
        String callbackData
) {
    public static Incoming text(long userId, String firstName, String lastName, String username, String text) {
        return new Incoming(userId, firstName, lastName, username, text, null);
    }

    public static Incoming button(long userId, String firstName, String lastName, String username, String data) {
        return new Incoming(userId, firstName, lastName, username, null, data);
    }

    public boolean isButton() {
        return callbackData != null;
    }
}
