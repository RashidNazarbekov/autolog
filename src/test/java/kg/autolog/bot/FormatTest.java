package kg.autolog.bot;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FormatTest {

    @Test
    void parsesNumbersAsPeopleWriteThem() {
        assertThat(Format.decimal("40,3")).isEqualByComparingTo("40.3");
        assertThat(Format.decimal(" 87 л ")).isEqualByComparingTo("87");
        assertThat(Format.decimal("40.3 кВт·ч")).isEqualByComparingTo("40.3");
        assertThat(Format.integer("150 000")).isEqualTo(150_000);
        assertThat(Format.integer("150 000 км")).isEqualTo(150_000);
        assertThat(Format.integer("1200.0")).isEqualTo(1200);
    }

    @Test
    void rejectsNonNumbers() {
        assertThat(Format.decimal("много")).isNull();
        assertThat(Format.decimal("")).isNull();
        assertThat(Format.decimal("1.2.3")).isNull();
        assertThat(Format.integer("12,5")).isNull();
    }

    @Test
    void formatsForHumans() {
        assertThat(Format.km(150_000)).isEqualTo("150 000");
        assertThat(Format.number(new BigDecimal("40.30"))).isEqualTo("40,3");
        assertThat(Format.esc("<b>&")).isEqualTo("&lt;b&gt;&amp;");
    }
}
