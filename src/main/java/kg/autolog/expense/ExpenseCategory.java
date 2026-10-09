package kg.autolog.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Категория прочих расходов дома: «Шины», «Страховка», «Штраф» или своя. */
@Entity
@Table(name = "expense_category")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(nullable = false, length = 48)
    private String name;

    @Column(nullable = false, length = 8)
    private String emoji;

    /** На сколько месяцев обычно распределять расход: 1 — разово. */
    @Column(name = "default_spread_months", nullable = false)
    private int defaultSpreadMonths;

    /** Спрашивать, кто нарушил (для штрафов). */
    @Column(name = "asks_offender", nullable = false)
    private boolean asksOffender;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean archived;

    public ExpenseCategory(long householdId, String name, String emoji, int defaultSpreadMonths,
                           boolean asksOffender, int sortOrder) {
        this.householdId = householdId;
        this.name = name;
        this.emoji = emoji;
        this.defaultSpreadMonths = defaultSpreadMonths;
        this.asksOffender = asksOffender;
        this.sortOrder = sortOrder;
    }

    public String title() {
        return emoji + " " + name;
    }
}
