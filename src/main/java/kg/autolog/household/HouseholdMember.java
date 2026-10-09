package kg.autolog.household;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Участие водителя в доме. */
@Entity
@Table(name = "household_member")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HouseholdMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MemberRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public HouseholdMember(long householdId, long driverId, MemberRole role, Instant joinedAt) {
        this.householdId = householdId;
        this.driverId = driverId;
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public boolean isOwner() {
        return role == MemberRole.OWNER;
    }
}
