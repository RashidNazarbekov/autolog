package kg.autolog.expense;

import java.time.LocalDate;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByCarIdInOrderBySpentOnDescIdDesc(List<Long> carIds, Limit limit);

    /** Расходы с датой в окне: для распределённых окно начинается раньше периода. */
    List<Expense> findByCarIdInAndSpentOnBetween(List<Long> carIds, LocalDate from, LocalDate to);
}
