package kg.autolog.bot;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Разбор чисел, которые человек пишет в чате, и вывод чисел и текста обратно. */
final class Format {

    private Format() {
    }

    /**
     * Число из сообщения: «40,3», «87 л», «150 000 км», «1 200». Пробелы, запятая и единицы после числа допустимы.
     *
     * @return число или {@code null}, если это не число
     */
    static BigDecimal decimal(String raw) {
        if (raw == null) return null;
        var t = raw.trim().toLowerCase(Locale.ROOT)
                .replace(' ', ' ')
                .replace(' ', ' ')
                .replace(',', '.')
                .replaceAll("(?<=\\d) (?=\\d)", "")
                .replaceAll("[^0-9.\\-]+$", "")
                .trim();
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Целое число из сообщения или {@code null}. */
    static Integer integer(String raw) {
        var d = decimal(raw);
        if (d == null) return null;
        try {
            return d.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** «150 000» — с тонким пробелом между разрядами. */
    static String km(int value) {
        return group(new DecimalFormat("#,##0", symbols())).format(value);
    }

    /** «40,3» — без лишних нулей. */
    static String number(BigDecimal value) {
        return group(new DecimalFormat("#,##0.##", symbols())).format(value);
    }

    /** Экранирование для HTML-разметки Telegram. */
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static DecimalFormatSymbols symbols() {
        var s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator(' ');
        s.setDecimalSeparator(',');
        return s;
    }

    private static DecimalFormat group(DecimalFormat f) {
        f.setGroupingUsed(true);
        return f;
    }
}
