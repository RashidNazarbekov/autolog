package kg.autolog.fuel;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefuelRepository extends JpaRepository<Refuel, Long> {

    /** Последние заправки машины, новые первыми. */
    List<Refuel> findByCarIdOrderByOdometerKmDescRefueledAtDesc(long carId, Limit limit);

    Optional<Refuel> findFirstByCarIdOrderByRefueledAtDesc(long carId);
}
