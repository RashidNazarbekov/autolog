package kg.autolog.household;

import kg.autolog.IntegrationTest;
import kg.autolog.TestData;
import kg.autolog.common.AutologException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class HouseholdServiceTest extends IntegrationTest {

    @Autowired
    HouseholdService service;

    @Autowired
    HouseholdRepository households;

    @Autowired
    TestData data;

    @Test
    void creatorBecomesOwner() {
        var owner = data.driver("Рашид");
        var home = service.create(owner, "  Дом   Назарбековых ");

        assertThat(home.getName()).isEqualTo("Дом Назарбековых");
        assertThat(service.requireOwner(owner).getHouseholdId()).isEqualTo(home.getId());
    }

    @Test
    void driverJoinsByInviteCodeAsDriver() {
        var owner = data.driver("Рашид");
        var brother = data.driver("Айбек");
        var home = service.create(owner, "Дом");

        var code = service.createInvite(owner);
        var joined = service.join(brother, " " + code.toLowerCase() + " ");

        assertThat(joined.getId()).isEqualTo(home.getId());
        assertThat(service.requireMembership(brother).getRole()).isEqualTo(MemberRole.DRIVER);
        assertThat(service.members(owner))
                .extracting(HouseholdService.MemberView::name)
                .containsExactly("Рашид", "Айбек");
    }

    @Test
    void joiningTwiceIsHarmless() {
        var owner = data.driver("Рашид");
        var brother = data.driver("Айбек");
        service.create(owner, "Дом");
        var code = service.createInvite(owner);

        service.join(brother, code);
        service.join(brother, code);

        assertThat(service.members(owner)).hasSize(2);
    }

    @Test
    void newInviteReplacesOldOne() {
        var owner = data.driver("Рашид");
        service.create(owner, "Дом");
        var old = service.createInvite(owner);
        service.createInvite(owner);

        assertThatThrownBy(() -> service.join(data.driver("Айбек"), old))
                .isInstanceOf(AutologException.NotFound.class);
    }

    @Test
    void expiredInviteIsRejected() {
        var owner = data.driver("Рашид");
        var home = service.create(owner, "Дом");
        var code = service.createInvite(owner);
        home = households.findById(home.getId()).orElseThrow();
        home.setInviteExpiresAt(home.getCreatedAt().minusSeconds(1));

        assertThatThrownBy(() -> service.join(data.driver("Айбек"), code))
                .isInstanceOf(AutologException.NotFound.class)
                .hasMessageContaining("истёк");
    }

    @Test
    void onlyOwnerInvitesAndRemoves() {
        var owner = data.driver("Рашид");
        var brother = data.driver("Айбек");
        service.create(owner, "Дом");
        service.join(brother, service.createInvite(owner));

        assertThatThrownBy(() -> service.createInvite(brother)).isInstanceOf(AutologException.Forbidden.class);
        assertThatThrownBy(() -> service.removeMember(brother, owner.getId())).isInstanceOf(AutologException.Forbidden.class);
        assertThatThrownBy(() -> service.removeMember(owner, owner.getId())).isInstanceOf(AutologException.Invalid.class);

        service.removeMember(owner, brother.getId());
        assertThat(service.membershipOf(brother)).isEmpty();
    }

    @Test
    void driverCanBeInOnlyOneHousehold() {
        var a = data.driver("Рашид");
        var b = data.driver("Сосед");
        service.create(a, "Дом А");
        service.create(b, "Дом Б");

        assertThatThrownBy(() -> service.create(a, "Второй дом")).isInstanceOf(AutologException.Conflict.class);
        assertThatThrownBy(() -> service.join(a, service.createInvite(b))).isInstanceOf(AutologException.Conflict.class);
    }

    @Test
    void emptyNameIsRejected() {
        assertThatThrownBy(() -> service.create(data.driver("Рашид"), "   "))
                .isInstanceOf(AutologException.Invalid.class);
    }
}
