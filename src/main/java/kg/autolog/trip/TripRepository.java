package kg.autolog.trip;

import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findFirstByCarIdAndStatus(long carId, TripStatus status);

    Optional<Trip> findFirstByDriverIdAndStatus(long driverId, TripStatus status);

    /** Законченные за период поездки машин. */
    List<Trip> findByCarIdInAndStatusAndFinishedAtBetween(List<Long> carIds, TripStatus status, Instant from, Instant to);

    /** Открытые поездки машин — для журнала. */
    List<Trip> findByCarIdInAndStatus(List<Long> carIds, TripStatus status);
}
