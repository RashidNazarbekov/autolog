package kg.autolog.expense;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, Long> {

    List<ExpenseCategory> findByHouseholdIdAndArchivedFalseOrderBySortOrderAscIdAsc(long householdId);

    Optional<ExpenseCategory> findByIdAndHouseholdId(long id, long householdId);

    boolean existsByHouseholdIdAndArchivedFalseAndNameIgnoreCase(long householdId, String name);
}
