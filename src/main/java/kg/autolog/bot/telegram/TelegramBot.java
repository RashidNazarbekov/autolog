package kg.autolog.bot.telegram;

import kg.autolog.bot.BotEngine;
import kg.autolog.bot.Incoming;
import kg.autolog.bot.Reply;
import kg.autolog.common.AutologProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Адаптер Telegram: получает обновления long polling, передаёт их ядру бота и отправляет ответы.
 * Создаётся, только если задан токен бота — без него приложение работает без бота (например, в тестах).
 */
@Component
@ConditionalOnExpression("!'${autolog.telegram.bot-token:}'.isBlank()")
public class TelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private final BotEngine engine;
    private final String token;
    private final TelegramClient client;

    public TelegramBot(BotEngine engine, AutologProperties props) {
        this.engine = engine;
        this.token = props.telegram().botToken();
        this.client = new OkHttpTelegramClient(token);
        log.info("Telegram-бот подключён: @{}", props.telegram().botUsername());
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                onMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                onButton(update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Не удалось обработать обновление {}", update.getUpdateId(), e);
        }
    }

    private void onMessage(Message message) {
        // Первая версия работает только в личных сообщениях с ботом
        if (!message.getChat().isUserChat()) return;
        User u = message.getFrom();
        var replies = engine.handle(Incoming.text(u.getId(), u.getFirstName(), u.getLastName(), u.getUserName(), message.getText()));
        send(message.getChatId(), replies);
    }

    private void onButton(CallbackQuery query) {
        execute(AnswerCallbackQuery.builder().callbackQueryId(query.getId()).build());
        var message = query.getMessage();
        if (message == null) return;
        long chatId = message.getChat().getId();
        // Убираем кнопки с сообщения, на котором нажали, чтобы старые кнопки не путали
        execute(EditMessageReplyMarkup.builder()
                .chatId(String.valueOf(chatId))
                .messageId(message.getMessageId())
                .build());
        User u = query.getFrom();
        var replies = engine.handle(Incoming.button(u.getId(), u.getFirstName(), u.getLastName(), u.getUserName(), query.getData()));
        send(chatId, replies);
    }

    private void send(long chatId, List<Reply> replies) {
        for (var reply : replies) {
            var message = SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(reply.text())
                    .parseMode(ParseMode.HTML);
            if (!reply.buttons().isEmpty()) {
                var rows = reply.buttons().stream()
                        .map(row -> new InlineKeyboardRow(row.stream()
                                .map(b -> InlineKeyboardButton.builder().text(b.text()).callbackData(b.data()).build())
                                .toList()))
                        .toList();
                message.replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build());
            }
            execute(message.build());
        }
    }

    private void execute(org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> method) {
        try {
            client.execute(method);
        } catch (TelegramApiException e) {
            log.warn("Telegram отклонил {}: {}", method.getMethod(), e.getMessage());
        }
    }
}
