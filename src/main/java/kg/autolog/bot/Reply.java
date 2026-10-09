package kg.autolog.bot;

import java.util.List;

/**
 * Ответ бота: текст (HTML-разметка Telegram) и ряды кнопок под ним.
 *
 * @param text    текст сообщения; пользовательские строки в нём уже экранированы
 * @param buttons ряды кнопок, может быть пустым
 */
public record Reply(String text, List<List<Button>> buttons) {

    /** Кнопка: подпись и данные, которые вернутся боту при нажатии (до 64 байт). */
    public record Button(String text, String data) {
    }

    public static Reply of(String text) {
        return new Reply(text, List.of());
    }

    public static Reply of(String text, List<List<Button>> buttons) {
        return new Reply(text, buttons);
    }

    public static Button button(String text, String data) {
        return new Button(text, data);
    }

    /** Все кнопки ответа подряд — удобно для проверок в тестах. */
    public List<Button> allButtons() {
        return buttons.stream().flatMap(List::stream).toList();
    }
}
