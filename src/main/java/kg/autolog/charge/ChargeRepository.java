package kg.autolog.charge;

import java.time.Instant;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, Long> {

    Optional<Charge> findFirstByCarIdAndStatus(long carId, ChargeStatus status);

    /** Последние законченные зарядки машины — для средней цены кВт·ч. */
    List<Charge> findByCarIdAndStatusOrderByFinishedAtDesc(long carId, ChargeStatus status, Limit limit);

    /** На сколько % подзарядили машину во время поездки. */
    @Query("select coalesce(sum(c.endSocPct - c.startSocPct), 0) from Charge c "
            + "where c.tripId = :tripId and c.status = kg.autolog.charge.ChargeStatus.FINISHED")
    long socGainDuringTrip(@Param("tripId") long tripId);

    List<Charge> findByCarIdInAndStatusAndFinishedAtBetween(List<Long> carIds, ChargeStatus status, Instant from, Instant to);

    List<Charge> findByTripIdInAndStatus(List<Long> tripIds, ChargeStatus status);
}
