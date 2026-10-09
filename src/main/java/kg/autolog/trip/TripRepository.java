package kg.autolog.trip;

import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findFirstByCarIdAndStatus(long carId, TripStatus status);

    Optional<Trip> findFirstByDriverIdAndStatus(long driverId, TripStatus status);

    /** Законченные за период поездки машин. */
    List<Trip> findByCarIdInAndStatusAndFinishedAtBetween(List<Long> carIds, TripStatus status, Instant from, Instant to);

    /** Открытые поездки машин — для журнала. */
    List<Trip> findByCarIdInAndStatus(List<Long> carIds, TripStatus status);

    /** Открытые поездки, начатые раньше {@code startedBefore}, о которых не напоминали после {@code remindedBefore}. */
    @Query("""
            select t from Trip t
            where t.status = kg.autolog.trip.TripStatus.OPEN
              and t.startedAt <= :startedBefore
              and (t.remindedAt is null or t.remindedAt <= :remindedBefore)
            order by t.startedAt""")
    List<Trip> findDueForReminder(@Param("startedBefore") Instant startedBefore, @Param("remindedBefore") Instant remindedBefore);
}
