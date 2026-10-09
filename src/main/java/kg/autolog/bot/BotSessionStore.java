package kg.autolog.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Где пользователь находится в сценарии и что уже ввёл. Хранится в БД, переживает перезапуск. */
@Service
@RequiredArgsConstructor
public class BotSessionStore {

    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {
    };

    private final BotSessionRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    /** Снимок сессии. {@code data} можно менять и сохранить через {@link #save}. */
    public record Session(BotState state, Map<String, String> data) {
        public String get(String key) {
            return data.get(key);
        }
    }

    @Transactional(readOnly = true)
    public Session get(long telegramId) {
        return repository.findById(telegramId)
                .map(s -> new Session(s.getState(), read(s.getPayload())))
                .orElseGet(() -> new Session(BotState.NONE, new LinkedHashMap<>()));
    }

    @Transactional
    public void save(long telegramId, BotState state, Map<String, String> data) {
        var s = repository.findById(telegramId).orElseGet(() -> new BotSession(telegramId));
        s.setState(state);
        s.setPayload(write(data));
        s.setUpdatedAt(clock.instant());
        repository.save(s);
    }

    @Transactional
    public void clear(long telegramId) {
        repository.deleteById(telegramId);
    }

    private Map<String, String> read(String payload) {
        try {
            return json.readValue(payload, MAP);
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Map<String, String> data) {
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
