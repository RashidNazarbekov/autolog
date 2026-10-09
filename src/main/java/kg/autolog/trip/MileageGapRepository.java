package kg.autolog.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MileageGapRepository extends JpaRepository<MileageGap, Long> {

    List<MileageGap> findByCarIdInAndResolvedAtIsNullOrderByDetectedAt(List<Long> carIds);
}
