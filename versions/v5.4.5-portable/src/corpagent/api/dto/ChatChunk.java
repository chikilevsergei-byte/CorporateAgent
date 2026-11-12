package corpagent.api.dto;

import corpagent.util.Json;

import java.util.Map;

/**
 * DTO одного кадра потокового ответа чата (Этап 3.4 миграции v3 → v4).
 *
 * <p>Описывает событие SSE-потока {@code /api/chat/stream}: тип ({@code token}/{@code error}/
 * {@code done}), полезную нагрузку и — для финального кадра — полный текст и id чата.
 * Держит контракт кадров в одном месте, чтобы фронтенд и сервер не расходились, и позволяет
 * собирать кадры без ручного конструирования JSON-карт.</p>
 *
 * <p>Сериализация совпадает с тем, что сегодня формирует {@link corpagent.api.SseEmitter}:
 * кадр {@code token} несёт строку-дельту, {@code error} — объект {@code {message}},
 * {@code done} — объект {@code {chat_id, text}}.</p>
 */
public class ChatChunk {

    /** Тип кадра потока. */
    public enum Type {
        TOKEN,
        ERROR,
        DONE
    }

    public final Type type;
    /** Дельта текста для {@link Type#TOKEN}; для прочих типов — null. */
    public final String delta;
    /** Сообщение об ошибке для {@link Type#ERROR}; иначе null. */
    public final String error;
    /** Полный текст ответа для {@link Type#DONE}; иначе null. */
    public final String fullText;
    /** Идентификатор чата для {@link Type#DONE}; иначе null. */
    public final String chatId;

    private ChatChunk(Type type, String delta, String error, String fullText, String chatId) {
        this.type = type;
        this.delta = delta;
        this.error = error;
        this.fullText = fullText;
        this.chatId = chatId;
    }

    /** Кадр с дельтой текста (токен). */
    public static ChatChunk token(String delta) {
        return new ChatChunk(Type.TOKEN, delta == null ? "" : delta, null, null, null);
    }

    /** Кадр ошибки потока. */
    public static ChatChunk error(String message) {
        return new ChatChunk(Type.ERROR, null, message == null ? "" : message, null, null);
    }

    /** Финальный кадр: полный текст и (необязательно) id чата. */
    public static ChatChunk done(String fullText, String chatId) {
        return new ChatChunk(Type.DONE, null, null, fullText == null ? "" : fullText, chatId);
    }

    /** Имя SSE-события для этого кадра ({@code token}/{@code error}/{@code done}). */
    public String eventName() {
        switch (type) {
            case ERROR:
                return "error";
            case DONE:
                return "done";
            case TOKEN:
            default:
                return "token";
        }
    }

    /**
     * Полезная нагрузка кадра в формате, ожидаемом фронтендом:
     * строка для {@code token}, объект {@code {message}} для {@code error},
     * объект {@code {chat_id, text}} для {@code done}.
     */
    public Object payload() {
        switch (type) {
            case ERROR:
                Map<String, Object> err = Json.newObject();
                err.put("message", error);
                return err;
            case DONE:
                Map<String, Object> done = Json.newObject();
                done.put("text", fullText);
                if (chatId != null) {
                    done.put("chat_id", chatId);
                }
                return done;
            case TOKEN:
            default:
                return delta;
        }
    }
}
