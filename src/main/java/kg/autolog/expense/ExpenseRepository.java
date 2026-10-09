package kg.autolog.expense;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByCarIdInOrderBySpentOnDescIdDesc(List<Long> carIds, Limit limit);
}
