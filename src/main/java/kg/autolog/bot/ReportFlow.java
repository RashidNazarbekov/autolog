package kg.autolog.bot;

import kg.autolog.driver.Driver;
import kg.autolog.report.ReportPeriod;
import kg.autolog.report.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/** Отчёты в боте: выбор периода, отчёт, журнал событий. */
@Component
@RequiredArgsConstructor
class ReportFlow {

    private static final DateTimeFormatter KEY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final ReportService reports;
    private final BotSessionStore sessions;
    private final Clock clock;

    Reply choosePeriod() {
        return Reply.of("📊 За какой период отчёт?", periodButtons(null));
    }

    List<Reply> customPressed(Driver driver) {
        sessions.save(driver.getTelegramId(), BotState.REPORT_PERIOD, new LinkedHashMap<>());
        return List.of(Reply.of("Напишите период, например: <i>01.09–30.09</i> или <i>01.09.2026–15.10.2026</i>", Buttons.cancel()));
    }

    List<Reply> customText(Driver driver, String text) {
        var period = ReportPeriod.parse(text, today());
        sessions.clear(driver.getTelegramId());
        return List.of(report(driver, period));
    }

    /** rep:{key} */
    List<Reply> reportPressed(Driver driver, String key) {
        return List.of(report(driver, period(key)));
    }

    /** jrn:{key} */
    List<Reply> journalPressed(Driver driver, String key) {
        var period = period(key);
        var entries = reports.journal(driver, period, 30);
        var sb = new StringBuilder("📒 <b>Журнал</b> · ").append(esc(period.label())).append("\n\n");
        if (entries.isEmpty()) sb.append("За этот период событий нет.");
        for (var e : entries) {
            sb.append(STAMP.format(e.at().atZone(clock.getZone()))).append(' ').append(e.icon()).append(' ')
                    .append(esc(e.text())).append('\n');
        }
        return List.of(Reply.of(sb.toString().trim(), List.of(
                List.of(button("📊 Отчёт за этот период", Buttons.REPORT + key)),
                List.of(button("Меню", Buttons.MENU)))));
    }

    Reply report(Driver driver, ReportPeriod period) {
        var r = reports.build(driver, period);
        var key = key(period);
        var sb = new StringBuilder("📊 <b>Отчёт</b> · ").append(esc(period.label())).append("\n");

        if (r.cars().isEmpty()) sb.append("\nМашин пока нет.");
        for (var c : r.cars()) {
            var car = c.car();
            sb.append('\n').append(car.isElectric() ? "🔌" : "⛽").append(" <b>").append(esc(car.getName())).append("</b> — ")
                    .append(Format.km(c.km())).append(" км, поездок: ").append(c.trips()).append('\n');
            if (c.consumption() != null) {
                sb.append("Расход ").append(Format.number(c.consumption())).append(car.isElectric() ? " кВт·ч/100 км" : " л/100 км")
                        .append(c.consumptionMeasured() ? " (по вашим данным)" : " (заводской)");
                if (c.energyPerKm() != null) {
                    sb.append(" · ").append(car.isElectric() ? "энергия" : "топливо").append(" ")
                            .append(Format.number(c.energyPerKm())).append(" сом/км ≈ ").append(money(c.energyCost())).append(" сом");
                }
                sb.append('\n');
            }
            if (car.isElectric()) {
                if (c.chargedKwh() != null && c.chargedKwh().signum() > 0) {
                    sb.append("Зарядки: ").append(Format.number(c.chargedKwh())).append(" кВт·ч на ").append(money(c.energyPaid())).append(" сом\n");
                }
            } else if (c.fuelLiters() != null && c.fuelLiters().signum() > 0) {
                sb.append("Заправки: ").append(Format.number(c.fuelLiters())).append(" л на ").append(money(c.energyPaid())).append(" сом\n");
            }
            if (c.otherExpenses().signum() > 0) {
                sb.append("Прочие: ").append(money(c.otherExpenses())).append(" сом (")
                        .append(c.expensesByCategory().entrySet().stream()
                                .map(e -> e.getKey() + " " + money(e.getValue())).collect(Collectors.joining(", ")))
                        .append(")\n");
            }
            sb.append("Итого ≈ <b>").append(money(c.totalCost())).append(" сом</b>");
            if (c.totalPerKm() != null) sb.append(" · <b>").append(Format.number(c.totalPerKm())).append(" сом/км</b>");
            sb.append('\n');
            if (c.untrackedKm() > 0) sb.append("⚠️ ").append(Format.km(c.untrackedKm())).append(" км никому не приписаны\n");
        }

        var cmp = r.comparison();
        if (cmp != null) {
            sb.append("\n⚖️ <b>Дизель против электро</b>\n")
                    .append("1 км по энергии: дизель ").append(Format.number(cmp.dieselPerKm())).append(" сом · электро ")
                    .append(Format.number(cmp.evPerKm())).append(" сом\n");
            if (cmp.evKm() > 0 && cmp.savings().signum() > 0) {
                sb.append("Электро сэкономил ≈ <b>").append(money(cmp.savings())).append(" сом</b> на ")
                        .append(Format.km(cmp.evKm())).append(" км\n");
            }
            if (cmp.dieselTotalPerKm() != null && cmp.evTotalPerKm() != null) {
                sb.append("Полная стоимость 1 км: дизель ").append(Format.number(cmp.dieselTotalPerKm()))
                        .append(" · электро ").append(Format.number(cmp.evTotalPerKm())).append(" сом\n");
            }
        }

        if (!r.drivers().isEmpty()) {
            sb.append("\n👥 <b>Водители</b>\n");
            for (var d : r.drivers()) {
                sb.append(esc(d.name())).append(" — ").append(Format.km(d.km())).append(" км");
                if (d.kmByCar().size() > 1) {
                    sb.append(" (").append(d.kmByCar().entrySet().stream()
                            .map(e -> esc(e.getKey()) + " " + Format.km(e.getValue())).collect(Collectors.joining(", "))).append(')');
                }
                if (d.tripCost().signum() > 0) sb.append(" · поездки ≈ ").append(money(d.tripCost())).append(" сом");
                if (d.paid().signum() > 0) sb.append(" · заплатил ").append(money(d.paid())).append(" сом");
                sb.append('\n');
            }
        }
        sb.append("\nВсего: ").append(Format.km(r.totalKm())).append(" км ≈ <b>").append(money(r.totalCost())).append(" сом</b>");
        sb.append("\n\n<i>Энергия считается по расходу и средней цене, поэтому одна заправка не делает неделю «дорогой». "
                + "Сколько реально заплатили — в строках «Заправки», «Зарядки» и у водителей.</i>");

        var rows = new ArrayList<List<Reply.Button>>();
        rows.add(List.of(button("📒 Журнал за этот период", Buttons.JOURNAL + key)));
        rows.addAll(periodButtons(key));
        return Reply.of(sb.toString(), rows);
    }

    // ---------- Периоды ----------

    private List<List<Reply.Button>> periodButtons(String current) {
        var rows = new ArrayList<List<Reply.Button>>();
        rows.add(List.of(periodButton("Неделя", "week", current), periodButton("Этот месяц", "month", current)));
        rows.add(List.of(periodButton("Прошлый месяц", "last", current), periodButton("Этот год", "year", current)));
        rows.add(List.of(button("📅 Свой период", Buttons.REPORT_CUSTOM)));
        rows.add(List.of(button("Меню", Buttons.MENU)));
        return rows;
    }

    private static Reply.Button periodButton(String label, String key, String current) {
        return button((key.equals(current) ? "• " : "") + label, Buttons.REPORT + key);
    }

    private ReportPeriod period(String key) {
        var today = today();
        return switch (key) {
            case "week" -> ReportPeriod.week(today);
            case "month" -> ReportPeriod.thisMonth(today);
            case "last" -> ReportPeriod.lastMonth(today);
            case "year" -> ReportPeriod.thisYear(today);
            default -> {
                if (!key.startsWith("d:")) throw new kg.autolog.common.AutologException.NotFound("Эта кнопка устарела");
                var parts = key.substring(2).split("-");
                var from = LocalDate.parse(parts[0], KEY);
                var to = LocalDate.parse(parts[1], KEY);
                yield new ReportPeriod(from, to, from.format(DateTimeFormatter.ofPattern("dd.MM")) + "–" + to.format(DateTimeFormatter.ofPattern("dd.MM")));
            }
        };
    }

    /** Ключ периода для кнопок: стандартные — словом, свой — датами. */
    private String key(ReportPeriod p) {
        var today = today();
        for (var k : List.of("week", "month", "last", "year")) {
            if (period(k).equals(p)) return k;
        }
        return "d:" + KEY.format(p.from()) + "-" + KEY.format(p.to());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String money(BigDecimal v) {
        return v == null ? "—" : Format.number(v.setScale(0, java.math.RoundingMode.HALF_UP));
    }
}
