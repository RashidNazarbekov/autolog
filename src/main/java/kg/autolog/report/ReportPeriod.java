package kg.autolog.report;

import kg.autolog.common.AutologException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

/** Период отчёта — даты включительно. */
public record ReportPeriod(LocalDate from, LocalDate to, String title) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM");
    private static final String[] MONTHS = {"Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"};
    private static final Pattern RANGE = Pattern.compile("\\s*(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?\\s*[-–—]\\s*(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?\\s*");

    public ReportPeriod {
        if (to.isBefore(from)) throw new AutologException.Invalid("Конец периода раньше начала");
        if (ChronoUnit.DAYS.between(from, to) > 3 * 366) throw new AutologException.Invalid("Период — не длиннее трёх лет");
    }

    /** Последние 7 дней, включая сегодня. */
    public static ReportPeriod week(LocalDate today) {
        return new ReportPeriod(today.minusDays(6), today, "Неделя");
    }

    /** С 1-го числа по сегодня. */
    public static ReportPeriod thisMonth(LocalDate today) {
        return new ReportPeriod(today.withDayOfMonth(1), today, MONTHS[today.getMonthValue() - 1] + " " + today.getYear());
    }

    public static ReportPeriod lastMonth(LocalDate today) {
        var m = YearMonth.from(today).minusMonths(1);
        return new ReportPeriod(m.atDay(1), m.atEndOfMonth(), MONTHS[m.getMonthValue() - 1] + " " + m.getYear());
    }

    /** С 1 января по сегодня. */
    public static ReportPeriod thisYear(LocalDate today) {
        return new ReportPeriod(today.withDayOfYear(1), today, today.getYear() + " год");
    }

    /** «01.09–30.09», «1.9-30.9.2026», «01.09.2026 - 15.10.2026». Год по умолчанию — текущий. */
    public static ReportPeriod parse(String text, LocalDate today) {
        var m = RANGE.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new AutologException.Invalid("Напишите период как «01.09–30.09» или «01.09.2026–15.10.2026»");
        }
        try {
            var from = date(m.group(1), m.group(2), m.group(3), today);
            var to = date(m.group(4), m.group(5), m.group(6), today);
            return new ReportPeriod(from, to, SHORT.format(from) + "–" + SHORT.format(to));
        } catch (java.time.DateTimeException e) {
            throw new AutologException.Invalid("Такой даты нет — проверьте день и месяц");
        }
    }

    private static LocalDate date(String d, String m, String y, LocalDate today) {
        int year = y == null ? today.getYear() : (y.length() == 2 ? 2000 + Integer.parseInt(y) : Integer.parseInt(y));
        return LocalDate.of(year, Integer.parseInt(m), Integer.parseInt(d));
    }

    public Instant start(ZoneId zone) {
        return from.atStartOfDay(zone).toInstant();
    }

    /** Последний миг периода (включительно). */
    public Instant end(ZoneId zone) {
        return to.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
    }

    public int days() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** «Октябрь 2026 · 01.10.2026–09.10.2026». */
    public String label() {
        return title + " · " + DAY.format(from) + "–" + DAY.format(to);
    }

    @Override
    public String toString() {
        return label().toLowerCase(Locale.ROOT);
    }
}
