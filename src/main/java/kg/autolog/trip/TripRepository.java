package kg.autolog.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {

    Optional<Trip> findFirstByCarIdAndStatus(long carId, TripStatus status);

    Optional<Trip> findFirstByDriverIdAndStatus(long driverId, TripStatus status);
}
