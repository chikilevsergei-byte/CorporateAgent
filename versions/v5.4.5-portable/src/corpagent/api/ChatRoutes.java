package corpagent.api;

import com.sun.net.httpserver.HttpExchange;

import corpagent.Agent;
import corpagent.api.dto.ChatRequest;
import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.TaskControl;

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
    private final corpagent.util.Settings settings;

    public ChatRoutes(Agent agent, DataStore store) {
        this(agent, store, null);
    }
    public ChatRoutes(Agent agent, DataStore store, corpagent.util.Settings settings) {
        this.agent = agent; this.store = store; this.settings = settings;
    }

    /** Регистрирует маршруты чата в сервере. */
    public void register(ApiServer server) {
        server.route("/api/chat/save-db", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                if (!"POST".equals(method)) return ApiResponse.badRequest("POST required");
                return saveToDatabase(body);
            }
        });
        server.route("/api/chats", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                if (!"GET".equals(method)) return ApiResponse.badRequest("GET required");
                List<Object> chats = Json.newArray();
                for (Map.Entry<String, Map<String, Object>> entry : store.sortedChats()) {
                    entry.getValue().put("id", entry.getKey()); chats.add(chatDto(entry.getValue()));
                }
                Map<String, Object> data = Json.newObject(); data.put("chats", chats);
                return ApiResponse.ok(data);
            }
        });
        server.route("/api/chat", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                if (!"POST".equals(method)) return ApiResponse.badRequest("POST required");
                return handleSync(body);
            }
        });
        server.streamRoute("/api/chat/stream", new ApiServer.StreamRoute() {
            @Override
            public void stream(String method, String body, Map<String, List<String>> query,
                               HttpExchange exchange) throws Exception {
                if (!"POST".equals(method)) throw new IllegalArgumentException("POST required");
                ChatRequest parsed = ChatRequest.parse(body);
                TaskControl.Token task = TaskControl.begin(parsed.requestId, parsed.chatId);
                try { handleStream(body, exchange); } finally { TaskControl.end(task); }
            }
        });
        // Этап 3.5–3.7: правка/удаление/перегенерация сообщения (chat_id+message_id в теле).
        server.route("/api/chat/message", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                if (!"POST".equals(method)) return handleMessage(method, body);
                Map<String,Object> req = Json.parseObject(body);
                TaskControl.Token task = TaskControl.begin(Json.str(req,"request_id",java.util.UUID.randomUUID().toString()),Json.str(req,"chat_id",""));
                try { return handleMessage(method, body); } finally { TaskControl.end(task); }
            }
        });
        // Этап 3.8: контекст чата (GET — чтение, PUT — сохранение выбранных БД/навыков/инструментов).
        server.route("/api/chat/context", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return handleContext(method, body == null || body.trim().isEmpty() ? Json.stringify(queryRequest(query)) : body);
            }
        });
        new ChatManagementRoutes(store).register(server);
        server.route("/api/chat/status", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method,String body,Map<String,List<String>> query) {
                if (!"GET".equals(method)) return ApiResponse.badRequest("GET required");
                List<String> ids=query.get("request_id"), chats=query.get("chat_id");
                TaskControl.Token task=ids==null || ids.isEmpty()?null:TaskControl.find(ids.get(0));
                boolean active=task!=null && chats!=null && !chats.isEmpty() && chats.get(0).equals(task.chatId);
                Map<String,Object> data=Json.newObject();data.put("active",active);
                if(active){data.put("started",task.started);data.put("partial",task.partial);}return ApiResponse.ok(data);
            }
        });
        server.route("/api/chat/cancel", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method, String body, Map<String,List<String>> query) {
                if (!"POST".equals(method)) return ApiResponse.badRequest("POST required");
                Map<String,Object> req = Json.parseObject(body);
                TaskControl.Token task = TaskControl.find(Json.str(req,"request_id",""));
                boolean active = task != null && task.chatId != null && task.chatId.equals(Json.str(req,"chat_id",""));
                if (active) task.cancel();
                Map<String,Object> data = Json.newObject(); data.put("active",active); return ApiResponse.ok(data);
            }
        });
    }

    private synchronized ApiResponse saveToDatabase(String body) {
        Map<String, Object> req = Json.parseObject(body);
        Map<String, Object> chat = store.getChat(Json.str(req, "chat_id", ""));
        if (chat == null) return ApiResponse.notFound("Chat not found");
        int idx = indexOfMessage(chat, Json.str(req, "message_id", ""));
        if (idx < 0) return ApiResponse.notFound("Message not found");
        String selectedName;
        try { selectedName = activeDatabaseName(chat); }
        catch (IllegalArgumentException e) { return ApiResponse.badRequest(e.getMessage()); }
        Map<String, Object> db = selectedName == null ? null : store.getDatabase(selectedName);
        if (db == null || Json.bool(db, "preset", false) || !Json.bool(db, "enabled", true))
            return ApiResponse.badRequest("Database is read-only or disabled");
        String path = Json.str(db, "path", "");
        if (!new java.io.File(path).isFile()) return ApiResponse.badRequest("Database file not found");
        try (java.sql.Connection conn = corpagent.db.SqliteHelper.open(path)) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS agent_notes (id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, message_id TEXT NOT NULL, text TEXT NOT NULL, created TEXT NOT NULL, UNIQUE(chat_id,message_id))");
            }
            try (java.sql.PreparedStatement st = conn.prepareStatement("INSERT OR IGNORE INTO agent_notes (id,chat_id,message_id,text,created) VALUES (?,?,?,?,?)")) {
                st.setString(1, java.util.UUID.randomUUID().toString());
                st.setString(2, Json.str(chat, "id", ""));
                st.setString(3, Json.str(messageAt(chat, idx), "id", String.valueOf(idx)));
                st.setString(4, Json.str(messageAt(chat, idx), "text", ""));
                st.setString(5, corpagent.util.Utils.nowIso()); st.executeUpdate();
            }
            conn.commit(); return ApiResponse.ok(Json.newObject());
        } catch (Exception e) { return ApiResponse.serverError(e.getMessage()); }
    }

    // ==================== МУТАЦИИ СООБЩЕНИЙ (3.5–3.7) ====================

    /**
     * Обрабатывает операции над сообщением чата.
     * Метод {@code PUT} — редактирование текста, {@code DELETE} — удаление,
     * {@code POST} с {@code action=regenerate} — перегенерация ответа ассистента.
     */
    synchronized ApiResponse handleMessage(String method, String body) {
        Map<String, Object> req;
        try {
            req = Json.parseObject(body);
        } catch (Exception e) {
            return ApiResponse.badRequest("Некорректный JSON: " + e.getMessage());
        }
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String chatId = Json.str(req, "chat_id", null);
        String messageId = Json.str(req, "message_id", null);
        Map<String, Object> chat = chatId == null ? null : store.getChat(chatId);
        if (chat == null) {
            return ApiResponse.notFound("Чат не найден: " + chatId);
        }
        int idx = indexOfMessage(chat, messageId);
        if (idx < 0) {
            return ApiResponse.notFound("Сообщение не найдено: " + messageId);
        }
        String m = method == null ? "" : method.toUpperCase();
        if ("PUT".equals(m)) {
            return editMessage(chat, idx, Json.str(req, "text", ""));
        }
        if ("DELETE".equals(m)) {
            return deleteMessage(chat, idx);
        }
        if ("POST".equals(m)) {
            return regenerateMessage(chat, idx);
        }
        return ApiResponse.badRequest("Неподдерживаемый метод для сообщения: " + method);
    }

    /** 3.5: заменяет текст сообщения. */
    private ApiResponse editMessage(Map<String, Object> chat, int idx, String text) {
        if (text == null || text.trim().isEmpty()) {
            return ApiResponse.badRequest("Поле 'text' обязательно.");
        }
        Map<String, Object> msg = messageAt(chat, idx);
        msg.put("text", text);
        msg.put("edited", Boolean.TRUE);
        msg.put("time", nowTime());
        touch(chat);
        return ApiResponse.ok(messageDto(msg));
    }

    /** 3.6: удаляет сообщение по индексу. */
    private ApiResponse deleteMessage(Map<String, Object> chat, int idx) {
        List<Object> msgs = messagesOf(chat);
        msgs.remove(idx);
        touch(chat);
        Map<String, Object> data = Json.newObject();
        data.put("chat_id", Json.str(chat, "id", null));
        data.put("removed", idx);
        return ApiResponse.ok(data);
    }

    /** 3.7: перегенерация — удаляет ответ ассистента после user-сообщения и повторяет запрос. */
    private ApiResponse regenerateMessage(Map<String, Object> chat, int idx) {
        TaskControl.started();
        List<Object> msgs = messagesOf(chat);
        Map<String, Object> msg = messageAt(chat, idx);
        // Опорное user-сообщение: если выбрано assistant — берём ближайший предыдущий user.
        int userIdx = idx;
        if (!"user".equals(Json.str(msg, "role", ""))) {
            userIdx = prevUserIndex(msgs, idx);
            if (userIdx < 0) {
                return ApiResponse.badRequest("Нет user-сообщения для перегенерации.");
            }
        }
        String userText = Json.str(messageAt(chat, userIdx), "text", "");
        // Keep the original branch until generation succeeds.
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < userIdx; i++) {
            Map<String, Object> m = Json.asMap(msgs.get(i));
            if (m != null) {
                Map<String, Object> norm = Json.newObject();
                norm.put("role", Json.str(m, "role", "user"));
                norm.put("content", Json.str(m, "text", ""));
                history.add(norm);
            }
        }
        String skillsContext = buildContext(Json.arr(chat, "selected_skills"),
                store.getSkills(), "code");
        String toolsContext = buildContext(Json.arr(chat, "selected_tools"),
                store.getTools(), "description");
        String dbContext = buildDbContext(null, chat);
        Agent.TaskResult result;
        try {
            ChatRequest regen = new ChatRequest(); regen.message = userText;
            result = process(regen, chat, history, dbContext, skillsContext, toolsContext);
            TaskControl.check();
        } catch (java.util.concurrent.CancellationException e) {
            Map<String,Object> data = Json.newObject(); data.put("cancelled",true); data.put("chat",chatDto(chat)); return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.serverError(e.getMessage());
        }
        if (result.response == null || result.response.startsWith("❌") || result.response.startsWith("⛔"))
            return ApiResponse.serverError(result.response);
        while (msgs.size() > userIdx + 1) msgs.remove(msgs.size() - 1);
        TaskControl.check();
        appendMessage(chat, "assistant", result.response);
        saveResultMeta(chat, result);
        Map<String, Object> data = Json.newObject();
        data.put("chat_id", Json.str(chat, "id", null));
        data.put("response", result.response);
        data.put("file_path", result.filePath);
        data.put("chat", chatDto(chat));
        return ApiResponse.ok(data);
    }

    // ==================== КОНТЕКСТ ЧАТА (3.8) ====================

    /** GET — вернуть выбранный контекст, PUT — сохранить (БД/навыки/инструменты). */
    ApiResponse handleContext(String method, String body) {
        Map<String, Object> req;
        try {
            req = body == null || body.trim().isEmpty() ? Json.newObject() : Json.parseObject(body);
        } catch (Exception e) {
            return ApiResponse.badRequest("Некорректный JSON: " + e.getMessage());
        }
        String chatId = Json.str(req, "chat_id", null);
        Map<String, Object> chat = chatId == null ? null : store.getChat(chatId);
        if (chat == null) {
            return ApiResponse.notFound("Чат не найден: " + chatId);
        }
        String m = method == null ? "" : method.toUpperCase();
        if ("PUT".equals(m) || "POST".equals(m)) {
            saveContextField(chat, req, "databases", "selected_databases");
            saveContextField(chat, req, "skills", "selected_skills");
            saveContextField(chat, req, "tools", "selected_tools");
            touch(chat);
        }
        Map<String, Object> data = Json.newObject();
        data.put("chat_id", Json.str(chat, "id", null));
        data.put("databases", Json.arr(chat, "selected_databases"));
        data.put("skills", Json.arr(chat, "selected_skills"));
        data.put("tools", Json.arr(chat, "selected_tools"));
        return ApiResponse.ok(data);
    }



            private static void saveContextField(Map<String, Object> chat, Map<String, Object> req,
                                         String reqKey, String chatKey) {
        List<Object> arr = Json.arr(req, reqKey);
        if (arr != null) {
            chat.put(chatKey, arr);
        }
    }

    /** Синхронный чат: возвращает полный ответ агента. */
    synchronized ApiResponse handleSync(String body) {
        ChatRequest req;
        try {
            req = ChatRequest.parse(body);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        Map<String, Object> chat = resolveChat(req.chatId);
        applyRequestContext(chat, body);
        prepareAttachments(req, chat);
        List<Map<String, Object>> history = buildHistory(req, chat);

        String skillsContext = buildContext(Json.arr(chat, "selected_skills"),
                store.getSkills(), "code");
        String toolsContext = buildContext(Json.arr(chat, "selected_tools"),
                store.getTools(), "description");
        String dbContext = buildDbContext(req, chat);

        persistUserMessage(chat, req);

        Agent.TaskResult result;
        try {
            if (!history.isEmpty()) history.remove(history.size() - 1);
            result = process(req, chat, history, dbContext, skillsContext, toolsContext);
        } catch (Exception e) {
            return ApiResponse.serverError(e.getMessage());
        }

        persistAssistantMessage(chat, result.response);
        saveResultMeta(chat, result);

        Map<String, Object> data = Json.newObject();
        data.put("chat_id", Json.str(chat, "id", null));
        data.put("response", result.response);
        data.put("file_path", result.filePath);
        data.put("chat", chatDto(chat));
        data.put("usage", result.usage);
        return ApiResponse.ok(data);
    }

    /** Потоковый чат (SSE): токены по мере поступления, затем сохранение в чат. */
    synchronized void handleStream(String body, HttpExchange exchange) throws Exception {
        ChatRequest req;
        try {
            req = ChatRequest.parse(body);
        } catch (IllegalArgumentException e) {
            // До старта потока можно вернуть обычный JSON-ответ об ошибке.
            throw e;
        }
        Map<String, Object> chat = resolveChat(req.chatId);
        applyRequestContext(chat, body);
        prepareAttachments(req, chat);

        SseEmitter emitter = new SseEmitter(exchange);
        emitter.start();
        final StringBuilder partial = new StringBuilder();
        try {
            TaskControl.check();TaskControl.started();
            Map<String,Object> started = Json.newObject(); started.put("request_id",req.requestId); emitter.sendPayload("started",started);
            List<Map<String, Object>> history = buildHistory(req, chat);
            String skillsContext = buildContext(Json.arr(chat, "selected_skills"),
                    store.getSkills(), "code");
            String toolsContext = buildContext(Json.arr(chat, "selected_tools"),
                    store.getTools(), "description");
            String dbContext = buildDbContext(req, chat);
            String systemPrompt = Agent.buildSystemPrompt(skillsContext, toolsContext, dbContext);

            persistUserMessage(chat, req);
            final Map<String, Object> fChat = chat;

            Agent.TaskResult result = null;
            String full;
            if (req.fileContent != null || !toolsContextEmpty(chat) || corpagent.util.IntentRouter.detect(req.message) != corpagent.util.IntentRouter.Intent.CHAT
                    || !toStringList(Json.arr(chat, "selected_databases")).isEmpty()) {
                List<Map<String, Object>> prior = new ArrayList<Map<String, Object>>(history);
                if (!prior.isEmpty()) prior.remove(prior.size() - 1);
                result = process(req, chat, prior, dbContext, skillsContext, toolsContext);
                full = result.response;
                emitter.sendChunk(corpagent.api.dto.ChatChunk.token(full));
            } else {
                full = agent.chatStream(history, systemPrompt, new corpagent.llm.DeepSeekClient.StreamHandler() {
                    public void onToken(String delta) { TaskControl.check(); partial.append(delta); TaskControl.progress(partial.toString()); emitter.sendChunk(corpagent.api.dto.ChatChunk.token(delta)); }
                    public void onDone(String text) { }
                    public void onError(String message) { emitter.sendChunk(corpagent.api.dto.ChatChunk.error(message)); }
                });
            }
            TaskControl.check();
            persistAssistantMessage(fChat, full);
            if (result != null) saveResultMeta(fChat, result);
            Map<String, Object> done = Json.newObject();
            done.put("text", full); done.put("chat_id", Json.str(chat, "id", null)); done.put("chat", chatDto(chat));
            if (result != null) { done.put("file_path", result.filePath); done.put("usage", result.usage); }
            emitter.sendPayload("done", done);
        } catch (java.util.concurrent.CancellationException e) {
            Thread.interrupted();
            String stopped = partial.length() == 0 ? "Ответ остановлен пользователем." : partial.toString();
            persistAssistantMessage(chat, stopped);
            Map<String,Object> last = messageAt(chat, messagesOf(chat).size()-1); last.put("cancelled",true); touch(chat);
            Map<String,Object> done = Json.newObject(); done.put("cancelled",true); done.put("chat_id",req.chatId);
            done.put("text",stopped); done.put("chat",chatDto(chat)); emitter.sendPayload("done",done);
        } catch (Exception e) {
            emitter.sendChunk(corpagent.api.dto.ChatChunk.error("Task failed: " + e.getMessage()));
        } finally {
            emitter.close();
        }
    }

    // ==================== ХЕЛПЕРЫ ====================

    /** Находит чат по id или создаёт новый контейнер (не сохраняя), если id нет. */

    private static void prepareAttachments(ChatRequest req, Map<String,Object> chat) {
        if (req.attachmentIds == null) return;
        StringBuilder text = new StringBuilder(); List<Object> attached = Json.arr(chat,"attachments");
        for (String id : req.attachmentIds) {
            Map<String,Object> found = null;
            if (attached != null) for (Object item : attached) {
                Map<String,Object> file = Json.asMap(item); if (id.equals(Json.str(file,"id",""))) { found=file; break; }
            }
            if (found == null) throw new IllegalArgumentException("Вложение не принадлежит этому чату");
            text.append("[").append(Json.str(found,"name","")).append("]\n").append(Json.str(found,"text","")).append("\n\n");
        }
        if (text.length()>0) req.fileContent = text.toString();
    }

    private static Map<String, Object> queryRequest(Map<String, List<String>> query) {
        Map<String, Object> req = Json.newObject(); List<String> ids = query.get("chat_id");
        if (ids != null && !ids.isEmpty()) req.put("chat_id", ids.get(0)); return req;
    }
    private static boolean toolsContextEmpty(Map<String, Object> chat) {
        List<Object> tools = Json.arr(chat, "selected_tools"); return tools == null || tools.isEmpty();
    }
    private void applyRequestContext(Map<String, Object> chat, String body) {
        Map<String, Object> req = Json.parseObject(body);
        saveContextField(chat, req, "databases", "selected_databases");
        saveContextField(chat, req, "skills", "selected_skills");
        saveContextField(chat, req, "tools", "selected_tools");
        List<Object> selected=new java.util.ArrayList<Object>();if(Json.arr(chat,"selected_databases")!=null)selected.addAll(Json.arr(chat,"selected_databases"));
        for(String source:corpagent.data.MethodologyRouter.sources(Json.str(req,"message",""),store))if(!selected.contains(source))selected.add(source);
        chat.put("selected_databases",selected);
        if (!chat.containsKey("title")) chat.put("title", corpagent.util.Utils.truncate(Json.str(req, "message", "New chat"), 60));
    }
    private String activeDatabaseName(Map<String, Object> chat) {
        String name = null;
        List<String> dbs = toStringList(Json.arr(chat, "selected_databases"));
        String projectDb = Json.str(chat, "project_db", "");
        if (dbs.contains(projectDb)) name = projectDb;
        if (name == null) {
            for (String candidate : dbs) {
                Map<String, Object> info = store.getDatabase(candidate);
                if (info == null || !Json.bool(info, "enabled", true)) throw new IllegalArgumentException("Database unavailable");
                if (!Json.bool(info, "preset", false)) {
                    if (name != null) throw new IllegalArgumentException("Select one working database; reference databases may remain selected");
                    name = candidate;
                }
            }
        }
        if (name == null && !dbs.isEmpty()) name = dbs.get(0);
        return name;
    }
    private Agent.TaskResult process(ChatRequest req, Map<String, Object> chat, List<Map<String, Object>> history,
            String dbContext, String skillsContext, String toolsContext) {
        List<String> references=corpagent.data.MethodologyRouter.sources(req.message,store);
        String name=references.isEmpty()?activeDatabaseName(chat):references.get(0),path=null;
        if(references.isEmpty()&&(req.fileContent==null||req.fileContent.isBlank())&&name!=null&&Json.bool(store.getDatabase(name),"preset",false))references.add(name);
        for(String reference:references)if(corpagent.data.MethodologyRouter.empty(store,reference)){
            Agent.TaskResult missing=new Agent.TaskResult();missing.response="В базе «"+reference+"» нет материалов методологии либо она недоступна. Не могу дать ответ от имени этой методологии. Нужны исходные документы или заполненная база.";Map<String,Object> source=Json.newObject();source.put("kind","db");source.put("name",reference);missing.usage.add(source);return missing;
        }
        if(!references.isEmpty())name=references.get(0);
        agent.setQueryDatabases(toStringList(Json.arr(chat,"selected_databases")));
        corpagent.util.IntentRouter.Intent referenceIntent=corpagent.util.DocumentPlan.intent(req.message,toolsContext);
        if(!references.isEmpty()&&(referenceIntent==corpagent.util.IntentRouter.Intent.CHAT||referenceIntent==corpagent.util.IntentRouter.Intent.QUESTION_DB))return agent.answerMethodology(req.message,references,skillsContext);
        if (name != null) {
            Map<String, Object> db = store.getDatabase(name);
            if (db == null || !Json.bool(db, "enabled", true)) throw new IllegalArgumentException("Database unavailable");
            path = Json.str(db, "path", Json.str(db, "file", ""));
            if (!new java.io.File(path).isFile()) throw new IllegalArgumentException("Database file not found");
        }
        final boolean writesAllowed = req.confirmWrites || (settings != null && !settings.isConfirmDbWrite());
        agent.setDbWriteConfirmation(new corpagent.tools.ToolExecutor.WriteConfirmation() {
            public boolean allow(String sql, String activeDb) { return writesAllowed; }
        });
        final boolean autoUpdate = writesAllowed && settings != null && settings.isAutoUpdateDb()
                && name != null && !Json.bool(store.getDatabase(name), "preset", false);
        try { return agent.processTask(req.message, history, req.fileContent, dbContext, skillsContext, toolsContext, path, name, autoUpdate); }
        finally { agent.setDbWriteConfirmation(null); }
    }
    private void saveResultMeta(Map<String, Object> chat, Agent.TaskResult result) {
        List<Object> msgs = messagesOf(chat); if (msgs.isEmpty()) return;
        Map<String, Object> meta = Json.newObject(); meta.put("filePath", result.filePath); meta.put("usage", result.usage);
        Json.asMap(msgs.get(msgs.size() - 1)).put("meta", meta); store.saveAll();
    }
    static Map<String, Object> chatDto(Map<String, Object> chat) {
        Map<String, Object> copy = Json.parseObject(Json.stringify(chat)); List<Object> msgs = messagesOf(copy);
        for (int i = 0; i < msgs.size(); i++) {
            Map<String, Object> msg = Json.asMap(msgs.get(i));
            if (msg != null && Json.str(msg, "id", "").isEmpty()) msg.put("id", String.valueOf(i));
        }
        copy.put("_server", true); return copy;
    }

    private Map<String, Object> resolveChat(String chatId) {
        if (chatId != null && !chatId.isEmpty()) {
            Map<String, Object> existing = store.getChat(chatId);
            if (existing != null) {
                return existing;
            }
        }
        Map<String, Object> chat = Json.newObject();
        String id = (chatId == null || chatId.isEmpty())
                ? "chat_" + java.util.UUID.randomUUID().toString() : chatId;
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
            if(!Json.bool(item,"enabled",true))continue;
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(name);
            String desc = Json.str(item, field);
            if (desc.isEmpty()) desc = Json.str(item, "description", "");
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
        List<String> dbs = toStringList(Json.arr(chat, "selected_databases"));
        if (dbs.isEmpty()) {
            return null;
        }
        String userMessage = req != null ? req.message : lastUserText(chat);
        try {
            return agent.buildDbContext(dbs, userMessage);
        } catch (Exception e) {
            return null;
        }
    }

    /** Текст последнего user-сообщения чата (для контекста БД при перегенерации). */
    private String lastUserText(Map<String, Object> chat) {
        List<Object> msgs = messagesOf(chat);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Map<String, Object> m = Json.asMap(msgs.get(i));
            if (m != null && "user".equals(Json.str(m, "role", ""))) {
                return Json.str(m, "text", "");
            }
        }
        return "";
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
        synchronized (store) {
        Object msgs = chat.get("messages");
        List<Object> list;
        if (msgs instanceof List) {
            list = (List<Object>) msgs;
        } else {
            list = new ArrayList<Object>();
            chat.put("messages", list);
        }
                Map<String, Object> msg = Json.newObject();
        msg.put("id", newMessageId(list.size()));
        msg.put("role", role);
        msg.put("text", text == null ? "" : text);
        msg.put("time", nowTime());
        list.add(msg);
        chat.put("updated", corpagent.util.Utils.nowIso());
        store.saveAll();
        }
    }

    // ==================== ХЕЛПЕРЫ СООБЩЕНИЙ ====================

    /**
     * Ищет сообщение по {@code id}. Если {@code messageId} — числовой индекс (старые чаты
     * без id), использует позицию. Возвращает индекс в списке или -1.
     */
    static int indexOfMessage(Map<String, Object> chat, String messageId) {
        List<Object> msgs = messagesOf(chat);
        if (messageId != null && !messageId.isEmpty()) {
            for (int i = 0; i < msgs.size(); i++) {
                Map<String, Object> m = Json.asMap(msgs.get(i));
                if (m != null && messageId.equals(Json.str(m, "id", null))) {
                    return i;
                }
            }
            // Числовой id — трактуем как индекс (обратная совместимость).
            try {
                int idx = Integer.parseInt(messageId);
                if (idx >= 0 && idx < msgs.size()) {
                    return idx;
                }
            } catch (NumberFormatException ignored) {
                // не число — значит не найден
            }
        }
        return -1;
    }

    /** Ближайший предшествующий user-индекс (для перегенерации assistant-сообщения). */
    static int prevUserIndex(List<Object> msgs, int from) {
        for (int i = from; i >= 0; i--) {
            Map<String, Object> m = Json.asMap(msgs.get(i));
            if (m != null && "user".equals(Json.str(m, "role", ""))) {
                return i;
            }
        }
        return -1;
    }

    /** DTO одного сообщения для ответа фронтенду. */
    static Map<String, Object> messageDto(Map<String, Object> msg) {
        Map<String, Object> dto = Json.newObject();
        dto.put("id", Json.str(msg, "id", null));
        dto.put("role", Json.str(msg, "role", "user"));
        dto.put("text", Json.str(msg, "text", ""));
        dto.put("time", Json.str(msg, "time", ""));
        Object edited = msg.get("edited");
        if (edited != null) {
            dto.put("edited", edited);
        }
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> messagesOf(Map<String, Object> chat) {
        Object msgs = chat.get("messages");
        if (msgs instanceof List) {
            return (List<Object>) msgs;
        }
        List<Object> list = new ArrayList<Object>();
        chat.put("messages", list);
        return list;
    }

    private static Map<String, Object> messageAt(Map<String, Object> chat, int idx) {
        return Json.asMap(messagesOf(chat).get(idx));
    }

    /** Обновляет метку времени чата и сохраняет хранилище. */
    private void touch(Map<String, Object> chat) {
        chat.put("updated", corpagent.util.Utils.nowIso());
        store.saveAll();
    }

    /** Генерирует стабильный id сообщения (индекс + метка времени). */
    private static String newMessageId(int seq) {
        return "m_" + java.util.UUID.randomUUID().toString();
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
