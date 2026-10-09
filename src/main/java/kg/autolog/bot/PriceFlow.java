package kg.autolog.bot;

import kg.autolog.driver.Driver;
import kg.autolog.household.HouseholdService;
import kg.autolog.household.PriceSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static kg.autolog.bot.Reply.button;

/** Цены на электричество — экран и изменение (только владелец). */
@Component
@RequiredArgsConstructor
class PriceFlow {

    private final HouseholdService households;
    private final BotSessionStore sessions;
    private final BotScreens screens;

    Reply screen(Driver driver) {
        var member = households.requireMembership(driver);
        var home = households.requireHousehold(driver);
        var sb = new StringBuilder("⚙️ <b>Цены на электричество</b>\n\n");
        for (var s : PriceSetting.values()) {
            sb.append(s.title()).append(": <b>").append(Format.number(s.get(home))).append("</b> ").append(s.unit()).append('\n');
        }
        sb.append("\nДома стоимость = кВт·ч из сети × тариф; из сети берётся больше, чем попадает в батарею, — на величину потерь.");
        var rows = new ArrayList<List<Reply.Button>>();
        if (member.isOwner()) {
            for (var s : PriceSetting.values()) {
                rows.add(List.of(button("✏️ " + s.title(), Buttons.PRICE + s.name())));
            }
        } else {
            sb.append("\n\nМенять цены может владелец дома.");
        }
        rows.add(List.of(button("Меню", Buttons.MENU)));
        return Reply.of(sb.toString(), rows);
    }

    List<Reply> edit(Driver driver, String name) {
        households.requireOwner(driver);
        PriceSetting setting;
        try {
            setting = PriceSetting.valueOf(name);
        } catch (IllegalArgumentException e) {
            return List.of(Reply.of("Эта кнопка устарела."), screens.menu(driver));
        }
        var data = new LinkedHashMap<String, String>();
        data.put("setting", setting.name());
        sessions.save(driver.getTelegramId(), BotState.SETTING_VALUE, data);
        var current = setting.get(households.requireHousehold(driver));
        return List.of(Reply.of(setting.title() + ", " + setting.unit() + "? Сейчас " + Format.number(current), Buttons.cancel()));
    }

    List<Reply> value(Driver driver, BotSessionStore.Session session, String text) {
        var setting = PriceSetting.valueOf(session.get("setting"));
        var value = Format.decimal(text);
        if (value == null) {
            return List.of(Reply.of("⚠️ Нужно число, например <i>1,64</i>", Buttons.cancel()));
        }
        households.updatePrice(driver, setting, value);
        sessions.clear(driver.getTelegramId());
        return List.of(Reply.of("✅ " + setting.title() + ": " + Format.number(value) + " " + setting.unit()), screen(driver));
    }
}
