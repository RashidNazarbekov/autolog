package kg.autolog.household;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HouseholdRepository extends JpaRepository<Household, Long> {

    Optional<Household> findByInviteCode(String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
