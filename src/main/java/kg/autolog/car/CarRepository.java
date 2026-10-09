package kg.autolog.car;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CarRepository extends JpaRepository<Car, Long> {

    List<Car> findByHouseholdIdAndArchivedFalseOrderById(long householdId);

    Optional<Car> findByIdAndHouseholdIdAndArchivedFalse(long id, long householdId);

    boolean existsByHouseholdIdAndArchivedFalseAndNameIgnoreCase(long householdId, String name);
}
