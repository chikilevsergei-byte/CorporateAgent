package corpagent.api.dto;

import corpagent.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DTO запроса чата (Этап 3 миграции v3 → v4).
 *
 * <p>Изолирует HTTP-тело от внутренних структур. Ожидаемый JSON:</p>
 * <pre>
 * {
 *   "chat_id": "chat_123",          // необязательно: куда дописать сообщения
 *   "message": "текст",             // сообщение пользователя (обязательно)
 *   "history": [ {role,content},… ],// необязательно: явная история
 *   "skills": ["..."],              // необязательно: имена активных навыков
 *   "tools":  ["..."],              // необязательно: имена активных инструментов
 *   "databases": ["..."],           // необязательно: выбранные БД (для контекста)
 *   "file_content": "..."           // необязательно: содержимое загруженного файла
 * }
 * </pre>
 */
public class ChatRequest {

    public String chatId;
    public String message = "";
    public List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
    public List<String> skills = new ArrayList<String>();
    public List<String> tools = new ArrayList<String>();
    public List<String> databases = new ArrayList<String>();
    public String fileContent;

    /** Разбирает тело запроса. Бросает {@link IllegalArgumentException} при отсутствии сообщения. */
    public static ChatRequest parse(String body) {
        ChatRequest req = new ChatRequest();
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Пустое тело запроса.");
        }
        Map<String, Object> map;
        try {
            map = Json.parseObject(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Некорректный JSON: " + e.getMessage());
        }
        if (map == null) {
            throw new IllegalArgumentException("Некорректный JSON: ожидался объект.");
        }
        req.chatId = Json.str(map, "chat_id", null);
        req.message = Json.str(map, "message", "").trim();
        req.fileContent = Json.str(map, "file_content", null);

        List<Object> history = Json.arr(map, "history");
        if (history != null) {
            for (Object o : history) {
                Map<String, Object> m = Json.asMap(o);
                if (m != null) {
                    req.history.add(m);
                }
            }
        }
        req.skills = stringList(map, "skills");
        req.tools = stringList(map, "tools");
        req.databases = stringList(map, "databases");

        if (req.message.isEmpty() && req.history.isEmpty()) {
            throw new IllegalArgumentException("Поле 'message' обязательно.");
        }
        return req;
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        List<String> out = new ArrayList<String>();
        List<Object> arr = Json.arr(map, key);
        if (arr != null) {
            for (Object o : arr) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    /**
     * Возвращает полную историю для LLM: явные {@code history} + текущее сообщение
     * пользователя (с присоединённым содержимым файла, если задано).
     */
    public List<Map<String, Object>> toLlmHistory() {
        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        // Нормализуем явную историю к формату role/content.
        for (Map<String, Object> m : history) {
            Object content = m.get("content");
            if (content == null) {
                content = m.get("text");
            }
            Map<String, Object> norm = Json.newObject();
            norm.put("role", Json.str(m, "role", "user"));
            norm.put("content", content == null ? "" : String.valueOf(content));
            all.add(norm);
        }
        String text = message;
        if (fileContent != null && !fileContent.isEmpty()) {
            text = text + "\n\n[Содержимое загруженного файла]:\n" + fileContent;
        }
        if (text != null && !text.isEmpty()) {
            Map<String, Object> userMsg = Json.newObject();
            userMsg.put("role", "user");
            userMsg.put("content", text);
            all.add(userMsg);
        }
        return all;
    }
}
