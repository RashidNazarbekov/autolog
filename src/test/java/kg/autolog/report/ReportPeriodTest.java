package kg.autolog.report;

import kg.autolog.common.AutologException;
import kg.autolog.expense.Expense;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportPeriodTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    @Test
    void presets() {
        assertThat(ReportPeriod.week(TODAY).from()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(ReportPeriod.thisMonth(TODAY).from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(ReportPeriod.thisMonth(TODAY).title()).isEqualTo("Октябрь 2026");
        var last = ReportPeriod.lastMonth(TODAY);
        assertThat(last.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(last.to()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(ReportPeriod.thisYear(TODAY).days()).isEqualTo(282);
    }

    @Test
    void parsesWhatPeopleType() {
        var p = ReportPeriod.parse("01.09–30.09", TODAY);
        assertThat(p.from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(p.to()).isEqualTo(LocalDate.of(2026, 9, 30));

        var q = ReportPeriod.parse(" 1.9.25 - 15.10.2026 ", TODAY);
        assertThat(q.from()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(q.to()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void rejectsNonsense() {
        assertThatThrownBy(() -> ReportPeriod.parse("вчера", TODAY)).isInstanceOf(AutologException.Invalid.class);
        assertThatThrownBy(() -> ReportPeriod.parse("31.02–01.03", TODAY)).isInstanceOf(AutologException.Invalid.class);
        assertThatThrownBy(() -> ReportPeriod.parse("30.09–01.09", TODAY)).isInstanceOf(AutologException.Invalid.class);
    }

    static Expense expense(String amount, LocalDate spentOn, int months) {
        return new Expense(1, 1, 1, new BigDecimal(amount), spentOn, months, null, null, Instant.EPOCH);
    }

    @Test
    void oneOffExpenseCountsOnlyInsideThePeriod() {
        var wash = expense("800", LocalDate.of(2026, 10, 5), 1);
        assertThat(ReportService.amortized(wash, ReportPeriod.thisMonth(TODAY))).isEqualByComparingTo("800");
        assertThat(ReportService.amortized(wash, ReportPeriod.lastMonth(TODAY))).isEqualByComparingTo("0");
    }

    @Test
    void spreadExpenseIsSplitByDays() {
        var insurance = expense("12000", LocalDate.of(2026, 1, 15), 12); // 1 000 сом в месяц, январь–декабрь
        var halfFeb = new ReportPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 14), "test");
        assertThat(ReportService.amortized(insurance, halfFeb)).isEqualByComparingTo("500");

        var year = new ReportPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "test");
        assertThat(ReportService.amortized(insurance, year)).isEqualByComparingTo("12000");

        var nextYear = new ReportPeriod(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), "test");
        assertThat(ReportService.amortized(insurance, nextYear)).isEqualByComparingTo("0");
    }
}
