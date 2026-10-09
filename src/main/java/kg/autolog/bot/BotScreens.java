package kg.autolog.bot;

import kg.autolog.car.Car;
import kg.autolog.car.CarService;
import kg.autolog.car.CarState;
import kg.autolog.common.AutologProperties;
import kg.autolog.driver.Driver;
import kg.autolog.driver.DriverRepository;
import kg.autolog.household.HouseholdService;
import kg.autolog.household.MemberRole;
import kg.autolog.trip.MileageGap;
import kg.autolog.trip.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static kg.autolog.bot.Format.esc;
import static kg.autolog.bot.Reply.button;

/** Экраны бота: приветствие, меню, списки, приглашение. Только показывают, ничего не меняют (кроме кода приглашения). */
@Component
@RequiredArgsConstructor
class BotScreens {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final HouseholdService households;
    private final CarService cars;
    private final TripService trips;
    private final DriverRepository drivers;
    private final AutologProperties props;
    private final Clock clock;

    Reply welcome(Driver driver) {
        return Reply.of(
                "Привет, " + esc(driver.getName()) + "! 👋\n\n"
                        + "Я веду журнал машин вашей семьи: кто на какой машине ездил, "
                        + "сколько ушло топлива или заряда и во что обходится километр.\n\n"
                        + "Для начала создайте дом и добавьте машины — или вступите в дом по коду от владельца.",
                List.of(List.of(button("🏠 Создать дом", Buttons.CREATE_HOME)),
                        List.of(button("🔑 У меня есть код", Buttons.JOIN_HOME))));
    }

    /** Главное меню участника дома; не участнику — приветствие. */
    Reply menu(Driver driver) {
        var member = households.membershipOf(driver).orElse(null);
        if (member == null) return welcome(driver);
        var home = households.requireHousehold(driver);
        var list = cars.list(driver);
        var names = driverNames(list);
        var sb = new StringBuilder("🏠 <b>").append(esc(home.getName())).append("</b>\n\n");
        var rows = new ArrayList<List<Reply.Button>>();
        if (list.isEmpty()) {
            sb.append(member.isOwner() ? "Машин пока нет — добавьте первую." : "Машин пока нет — их добавит владелец дома.");
        }
        for (var c : list) {
            sb.append(carLine(c, names)).append('\n');
            var row = new ArrayList<Reply.Button>();
            if (c.getState() == CarState.FREE) {
                row.add(button("▶ Поехать на «" + c.getName() + "»", Buttons.TRIP_START + c.getId()));
            } else if (c.getState() == CarState.ON_TRIP && driver.getId().equals(c.getCurrentDriverId())) {
                trips.openTripOf(c.getId()).ifPresent(t ->
                        row.add(button("🏁 Закончить поездку на «" + c.getName() + "»", Buttons.TRIP_FINISH + t.getId())));
            }
            if (RefuelFlow.canRefuel(c, driver)) row.add(button("⛽ Заправка", Buttons.FUEL_CAR + c.getId()));
            if (!row.isEmpty()) rows.add(row);
        }
        var gaps = trips.openGaps(driver);
        if (!gaps.isEmpty()) {
            int km = gaps.stream().mapToInt(MileageGap::km).sum();
            sb.append("\n⚠️ Неучтённый пробег: ").append(Format.km(km)).append(" км — кто ездил?");
            rows.add(List.of(button("⚠️ Разобрать неучтённые км", Buttons.GAPS)));
        }
        rows.add(List.of(button("🚗 Машины", Buttons.CARS), button("👥 Водители", Buttons.MEMBERS)));
        if (member.isOwner()) {
            rows.add(List.of(button("➕ Машина", Buttons.ADD_CAR), button("🔗 Пригласить", Buttons.INVITE)));
        }
        return Reply.of(sb.toString().trim(), rows);
    }

    Reply carList(Driver driver) {
        var list = cars.list(driver);
        if (list.isEmpty()) return Reply.of("Машин пока нет.", Buttons.menu());
        var names = driverNames(list);
        var sb = new StringBuilder("<b>Машины</b>\n\n");
        for (var c : list) {
            sb.append(carLine(c, names)).append('\n');
            sb.append(c.isElectric()
                    ? "   батарея " + Format.number(c.getBatteryKwh()) + " кВт·ч"
                    : "   бак " + Format.number(c.getTankLiters()) + " л");
            if (c.getRatedConsumption() != null) {
                sb.append(" · расход ").append(Format.number(c.getRatedConsumption()))
                        .append(c.isElectric() ? " кВт·ч/100 км" : " л/100 км");
            }
            sb.append("\n\n");
        }
        return Reply.of(sb.toString().trim(), Buttons.menu());
    }

    Reply memberList(Driver driver) {
        var sb = new StringBuilder("<b>Водители</b>\n\n");
        for (var m : households.members(driver)) {
            sb.append(m.role() == MemberRole.OWNER ? "👑 " : "• ").append(esc(m.name()));
            if (m.username() != null) sb.append(" (@").append(esc(m.username())).append(')');
            if (m.role() == MemberRole.OWNER) sb.append(" — владелец");
            sb.append('\n');
        }
        return Reply.of(sb.toString().trim(), Buttons.menu());
    }

    Reply invite(Driver driver) {
        var code = households.createInvite(driver);
        var username = props.telegram().botUsername();
        var sb = new StringBuilder("🔗 <b>Приглашение в дом</b>\n\n");
        if (username != null && !username.isBlank()) {
            sb.append("Перешлите эту ссылку водителю — по ней он сразу попадёт в дом:\n")
                    .append("https://t.me/").append(esc(username)).append("?start=").append(BotEngine.JOIN_PREFIX).append(code)
                    .append("\n\nИли пусть нажмёт в боте «У меня есть код» и введёт: <code>").append(code).append("</code>");
        } else {
            sb.append("Пусть водитель откроет бота, нажмёт «У меня есть код» и введёт:\n<code>").append(code).append("</code>");
        }
        sb.append("\n\nКод действует ").append(props.inviteTtl().toHours()).append(" ч. Новый код отменяет прежний.");
        return Reply.of(sb.toString(), Buttons.menu());
    }

    /** Вопрос «кто проехал эти км» с кнопками-водителями. */
    Reply gapQuestion(Driver driver, MileageGap gap) {
        var car = cars.require(driver, gap.getCarId());
        var sb = new StringBuilder("⚠️ <b>Неучтённый пробег</b>\n\n«")
                .append(esc(car.getName())).append("»: ")
                .append(Format.km(gap.getFromKm())).append(" → ").append(Format.km(gap.getToKm()))
                .append(" км, это <b>").append(Format.km(gap.km())).append(" км</b>, которые никто не отметил.\n")
                .append("Кто на ней ездил?");
        var rows = new ArrayList<List<Reply.Button>>();
        for (var m : households.members(driver)) {
            var label = m.driverId() == driver.getId() ? "Я (" + m.name() + ")" : m.name();
            rows.add(List.of(button(label, Buttons.GAP + gap.getId() + ":" + m.driverId())));
        }
        rows.add(List.of(button("Не знаю — позже", Buttons.GAP + gap.getId() + ":0")));
        return Reply.of(sb.toString(), rows);
    }

    /** «⛽ Прадо — 150 000 км · в поездке у Айбека с 09:40». */
    String carLine(Car c, Map<Long, String> driverNames) {
        var icon = c.isElectric() ? "🔌" : "⛽";
        var line = icon + " <b>" + esc(c.getName()) + "</b> — " + Format.km(c.getOdometerKm()) + " км · ";
        return line + switch (c.getState()) {
            case FREE -> "свободна";
            case ON_TRIP -> "в поездке у " + esc(driverNames.getOrDefault(c.getCurrentDriverId(), "водителя"))
                    + " с " + when(c.getStateSince());
            case CHARGING -> "на зарядке с " + when(c.getStateSince());
        };
    }

    /** Строка машины без имён водителей — для коротких ответов. */
    String carLine(Car c) {
        return carLine(c, driverNames(List.of(c)));
    }

    /** «09:40» сегодня, «08.10 21:15» — в другой день. */
    String when(Instant instant) {
        if (instant == null) return "—";
        var zoned = instant.atZone(clock.getZone());
        return zoned.toLocalDate().equals(LocalDate.now(clock)) ? TIME.format(zoned) : DAY_TIME.format(zoned);
    }

    private Map<Long, String> driverNames(List<Car> list) {
        var ids = list.stream().map(Car::getCurrentDriverId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return drivers.findAllById(ids).stream().collect(Collectors.toMap(Driver::getId, Driver::getName));
    }
}
