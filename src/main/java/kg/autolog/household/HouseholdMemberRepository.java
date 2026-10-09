package kg.autolog.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, Long> {

    /** В первой версии водитель состоит не больше чем в одном доме. */
    Optional<HouseholdMember> findFirstByDriverId(long driverId);

    Optional<HouseholdMember> findByHouseholdIdAndDriverId(long householdId, long driverId);

    List<HouseholdMember> findByHouseholdIdOrderByJoinedAt(long householdId);

    boolean existsByDriverId(long driverId);
}
