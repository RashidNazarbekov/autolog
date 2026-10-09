package kg.autolog.household;

import kg.autolog.common.AutologException;
import kg.autolog.common.AutologProperties;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HouseholdService {

    /** Без похожих символов (0/O, 1/I), чтобы код легко было продиктовать. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final HouseholdRepository households;
    private final HouseholdMemberRepository members;
    private final DriverRepository drivers;
    private final AutologProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Участник дома вместе с данными водителя — для списков. */
    public record MemberView(long driverId, String name, String username, MemberRole role, Instant joinedAt) {
    }

    /** Создаёт дом; создатель становится владельцем. */
    @Transactional
    public Household create(Driver owner, String name) {
        if (members.existsByDriverId(owner.getId())) {
            throw new AutologException.Conflict("Вы уже состоите в доме. Сейчас можно быть только в одном доме");
        }
        var household = households.save(new Household(cleanName(name), clock.instant()));
        members.save(new HouseholdMember(household.getId(), owner.getId(), MemberRole.OWNER, clock.instant()));
        return household;
    }

    public Optional<HouseholdMember> membershipOf(Driver driver) {
        return members.findFirstByDriverId(driver.getId());
    }

    public HouseholdMember requireMembership(Driver driver) {
        return membershipOf(driver).orElseThrow(() -> new AutologException.NotFound(
                "Вы ещё не в доме. Создайте свой или вступите по коду приглашения"));
    }

    public HouseholdMember requireOwner(Driver driver) {
        var m = requireMembership(driver);
        if (!m.isOwner()) throw new AutologException.Forbidden("Это может сделать только владелец дома");
        return m;
    }

    public Household requireHousehold(Driver driver) {
        return households.findById(requireMembership(driver).getHouseholdId())
                .orElseThrow(() -> new AutologException.NotFound("Дом не найден"));
    }

    @Transactional
    public Household rename(Driver owner, String name) {
        var household = households.findById(requireOwner(owner).getHouseholdId()).orElseThrow();
        household.setName(cleanName(name));
        return household;
    }

    /** Новый код приглашения; прежний перестаёт работать. */
    @Transactional
    public String createInvite(Driver owner) {
        var household = households.findById(requireOwner(owner).getHouseholdId()).orElseThrow();
        String code;
        do {
            code = randomCode();
        } while (households.existsByInviteCode(code));
        household.setInviteCode(code);
        household.setInviteExpiresAt(clock.instant().plus(props.inviteTtl()));
        return code;
    }

    /** Вступление в дом по коду приглашения. Код можно использовать несколько раз, пока не истёк. */
    @Transactional
    public Household join(Driver driver, String rawCode) {
        var code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        var household = households.findByInviteCode(code)
                .filter(h -> h.inviteValidAt(clock.instant()))
                .orElseThrow(() -> new AutologException.NotFound("Код не найден или истёк. Попросите владельца дома прислать новый"));
        var current = members.findFirstByDriverId(driver.getId());
        if (current.isPresent()) {
            if (current.get().getHouseholdId().equals(household.getId())) return household;
            throw new AutologException.Conflict("Вы уже состоите в другом доме. Сейчас можно быть только в одном доме");
        }
        members.save(new HouseholdMember(household.getId(), driver.getId(), MemberRole.DRIVER, clock.instant()));
        return household;
    }

    public List<MemberView> members(Driver requester) {
        var householdId = requireMembership(requester).getHouseholdId();
        var list = members.findByHouseholdIdOrderByJoinedAt(householdId);
        var byId = drivers.findAllById(list.stream().map(HouseholdMember::getDriverId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Driver::getId, d -> d));
        return list.stream()
                .filter(m -> byId.containsKey(m.getDriverId()))
                .map(m -> {
                    var d = byId.get(m.getDriverId());
                    return new MemberView(d.getId(), d.getName(), d.getUsername(), m.getRole(), m.getJoinedAt());
                })
                .toList();
    }

    /** Владелец убирает водителя из дома. Себя владелец убрать не может. */
    @Transactional
    public void removeMember(Driver owner, long driverId) {
        var me = requireOwner(owner);
        if (owner.getId().equals(driverId)) {
            throw new AutologException.Invalid("Владелец не может убрать себя из дома");
        }
        var target = members.findByHouseholdIdAndDriverId(me.getHouseholdId(), driverId)
                .orElseThrow(() -> new AutologException.NotFound("Такого водителя нет в доме"));
        members.delete(target);
    }

    private String randomCode() {
        var sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    static String cleanName(String name) {
        var n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new AutologException.Invalid("Укажите название дома");
        if (n.length() > 64) throw new AutologException.Invalid("Название дома — не длиннее 64 символов");
        return n;
    }
}
