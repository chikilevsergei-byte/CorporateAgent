package corpagent.api;

import com.sun.net.httpserver.HttpExchange;

import corpagent.Agent;
import corpagent.api.dto.ChatRequest;
import corpagent.data.DataStore;
import corpagent.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Маршруты чата (Этап 3 миграции v3 → v4).
 *
 * <ul>
 *   <li>{@code POST /api/chat} — синхронный ответ агента (как в GUI-чате);</li>
 *   <li>{@code POST /api/chat/stream} — потоковый ответ (SSE) через {@link SseEmitter}.</li>
 * </ul>
 *
 * <p>История и сообщения хранятся в {@link DataStore} в том же формате, что и GUI-чат
 * (поля {@code role}/{@code text}/{@code time}), чтобы фронтенд и Java-клиент видели
 * одинаковые данные. Контекст (навыки/инструменты/БД) собирается из выбранного набора
 * чата ({@code selected_skills}/{@code selected_tools}/{@code selected_databases}).</p>
 */
public final class ChatRoutes {

    private final Agent agent;
    private final DataStore store;

    public ChatRoutes(Agent agent, DataStore store) {
        this.agent = agent;
        this.store = store;
    }

    /** Регистрирует маршруты чата в сервере. */
    public void register(ApiServer server) {
        server.route("/api/chat", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return handleSync(body);
            }
        });
        server.streamRoute("/api/chat/stream", new ApiServer.StreamRoute() {
            @Override
            public void stream(String method, String body, Map<String, List<String>> query,
                               HttpExchange exchange) throws Exception {
                handleStream(body, exchange);
            }
        });
    }

    /** Синхронный чат: возвращает полный ответ агента. */
    ApiResponse handleSync(String body) {
        ChatRequest req;
        try {
            req = ChatRequest.parse(body);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        Map<String, Object> chat = resolveChat(req.chatId);
        List<Map<String, Object>> history = buildHistory(req, chat);

        String skillsContext = buildContext(Json.arr(chat, "selected_skills"),
                store.getSkills(), "описание");
        String toolsContext = buildContext(Json.arr(chat, "selected_tools"),
                store.getTools(), "описание");
        String dbContext = buildDbContext(req, chat);

        persistUserMessage(chat, req);

        Agent.TaskResult result;
        try {
            result = agent.processTask(req.message, history, req.fileContent,
                    dbContext, skillsContext, toolsContext, null, null, false);
        } catch (Exception e) {
            return ApiResponse.serverError(e.getMessage());
        }

        persistAssistantMessage(chat, result.response);

        Map<String, Object> data = Json.newObject();
        data.put("chat_id", Json.str(chat, "id", null));
        data.put("response", result.response);
        data.put("file_path", result.filePath);
        data.put("usage", result.usage);
        return ApiResponse.ok(data);
    }

    /** Потоковый чат (SSE): токены по мере поступления, затем сохранение в чат. */
    void handleStream(String body, HttpExchange exchange) throws Exception {
        ChatRequest req;
        try {
            req = ChatRequest.parse(body);
        } catch (IllegalArgumentException e) {
            // До старта потока можно вернуть обычный JSON-ответ об ошибке.
            throw e;
        }
        Map<String, Object> chat = resolveChat(req.chatId);

        SseEmitter emitter = new SseEmitter(exchange);
        emitter.start();
        try {
            List<Map<String, Object>> history = buildHistory(req, chat);
            String skillsContext = buildContext(Json.arr(chat, "selected_skills"),
                    store.getSkills(), "описание");
            String toolsContext = buildContext(Json.arr(chat, "selected_tools"),
                    store.getTools(), "описание");
            String dbContext = buildDbContext(req, chat);
            String systemPrompt = Agent.buildSystemPrompt(skillsContext, toolsContext, dbContext);

            persistUserMessage(chat, req);
            final Map<String, Object> fChat = chat;

            String full = agent.chatStream(history, systemPrompt, new corpagent.llm.DeepSeekClient.StreamHandler() {
                @Override
                public void onToken(String delta) {
                    emitter.sendText("token", delta);
                }

                @Override
                public void onDone(String fullText) {
                    // содержимое дописывается ниже, после возврата из стриминга
                }

                @Override
                public void onError(String message) {
                    Map<String, Object> err = Json.newObject();
                    err.put("message", message);
                    emitter.send("error", err);
                }
            });

            persistAssistantMessage(fChat, full);
            Map<String, Object> done = Json.newObject();
            done.put("chat_id", Json.str(fChat, "id", null));
            done.put("text", full);
            emitter.send("done", done);
        } finally {
            emitter.close();
        }
    }

    // ==================== ХЕЛПЕРЫ ====================

    /** Находит чат по id или создаёт новый контейнер (не сохраняя), если id нет. */
    private Map<String, Object> resolveChat(String chatId) {
        if (chatId != null && !chatId.isEmpty()) {
            Map<String, Object> existing = store.getChat(chatId);
            if (existing != null) {
                return existing;
            }
        }
        Map<String, Object> chat = Json.newObject();
        String id = (chatId == null || chatId.isEmpty())
                ? "chat_" + corpagent.util.Utils.timestamp() : chatId;
        chat.put("id", id);
        chat.put("messages", Json.newArray());
        chat.put("created", corpagent.util.Utils.nowIso());
        chat.put("updated", corpagent.util.Utils.nowIso());
        chat.put("pinned", Boolean.FALSE);
        store.putChat(id, chat);
        return chat;
    }

    /** История для LLM: сообщения из чата (если {@code history} не задана явно). */
    private List<Map<String, Object>> buildHistory(ChatRequest req, Map<String, Object> chat) {
        if (!req.history.isEmpty()) {
            return req.toLlmHistory();
        }
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        Object msgs = chat.get("messages");
        if (msgs instanceof List) {
            for (Object o : (List<?>) msgs) {
                Map<String, Object> m = Json.asMap(o);
                if (m != null) {
                    history.add(m);
                }
            }
        }
        // Текущее сообщение пользователя добавляем в конец.
        Map<String, Object> userMsg = Json.newObject();
        userMsg.put("role", "user");
        userMsg.put("content", req.message);
        history.add(userMsg);
        return history;
    }

    /** Собирает текстовый контекст (описания) из выбранных имён навыков/инструментов. */
    public static String buildContext(List<Object> selected, Map<String, Object> catalog, String field) {
        if (selected == null || selected.isEmpty() || catalog == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object nameObj : selected) {
            String name = String.valueOf(nameObj);
            Map<String, Object> item = Json.asMap(catalog.get(name));
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(name);
            String desc = Json.str(item, field);
            if (!desc.isEmpty()) {
                sb.append(": ").append(desc);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Контекст БД: если в запросе явно указаны {@code databases} — берём их, иначе
     * выбранные в чате ({@code selected_databases}). Пока возвращаем null-совместимую
     * строку-обзор, если БД не найдены; расширение выборки — на следующих этапах.
     */
    private String buildDbContext(ChatRequest req, Map<String, Object> chat) {
        List<String> dbs = !req.databases.isEmpty()
                ? req.databases : toStringList(Json.arr(chat, "selected_databases"));
        if (dbs.isEmpty()) {
            return null;
        }
        try {
            return agent.buildDbContext(dbs, req.message);
        } catch (Exception e) {
            return null;
        }
    }

    private void persistUserMessage(Map<String, Object> chat, ChatRequest req) {
        String text = req.message;
        if (req.fileContent != null && !req.fileContent.isEmpty()) {
            text = text + "\n\n[Файл]: " + req.fileContent;
        }
        appendMessage(chat, "user", text);
    }

    private void persistAssistantMessage(Map<String, Object> chat, String response) {
        if (response == null) {
            return;
        }
        appendMessage(chat, "assistant", response);
    }

    @SuppressWarnings("unchecked")
    private void appendMessage(Map<String, Object> chat, String role, String text) {
        Object msgs = chat.get("messages");
        List<Object> list;
        if (msgs instanceof List) {
            list = (List<Object>) msgs;
        } else {
            list = new ArrayList<Object>();
            chat.put("messages", list);
        }
        Map<String, Object> msg = Json.newObject();
        msg.put("role", role);
        msg.put("text", text == null ? "" : text);
        msg.put("time", nowTime());
        list.add(msg);
        chat.put("updated", corpagent.util.Utils.nowIso());
        store.saveAll();
    }

    private static String nowTime() {
        java.time.LocalTime now = java.time.LocalTime.now();
        return String.format("%02d:%02d", now.getHour(), now.getMinute());
    }

    private static List<String> toStringList(List<Object> arr) {
        List<String> out = new ArrayList<String>();
        if (arr != null) {
            for (Object o : arr) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }
}
