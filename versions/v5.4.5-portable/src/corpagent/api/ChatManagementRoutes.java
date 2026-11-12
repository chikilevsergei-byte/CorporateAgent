package corpagent.api;

import corpagent.data.DataStore;
import corpagent.data.ProjectManager;
import corpagent.util.Json;
import corpagent.util.TaskControl;
import corpagent.util.Utils;
import java.util.*;

/** Metadata and attachments are persisted without deleting uploaded files. */
public final class ChatManagementRoutes {
    private final DataStore store;
    public ChatManagementRoutes(DataStore store) { this.store = store; }
    public void register(ApiServer server) {
        server.route("/api/chats", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method, String body, Map<String,List<String>> query) {
                synchronized (store) { return dispatch(method, body); }
            }
        });
        server.route("/api/chat/attachment", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method, String body, Map<String,List<String>> query) {
                if (!"DELETE".equals(method)) return ApiResponse.badRequest("DELETE required");
                synchronized (store) {
                    Map<String,Object> req = Json.parseObject(body);
                    Map<String,Object> chat = store.getChat(Json.str(req,"chat_id",""));
                    if (chat == null) return ApiResponse.notFound("Чат не найден");
                    if (TaskControl.busy(Json.str(chat,"id",""))) return ApiResponse.badRequest("Сначала остановите ответ в этом чате");
                    List<Object> files = Json.arr(chat,"attachments");
                    String id = Json.str(req,"attachment_id",""); boolean found = false;
                    if (files != null) for (Iterator<Object> it = files.iterator(); it.hasNext();) {
                        if (id.equals(Json.str(Json.asMap(it.next()),"id",""))) { it.remove(); found = true; break; }
                    }
                    if (!found) return ApiResponse.notFound("Вложение не найдено в этом чате");
                    save(chat); return ApiResponse.ok(ChatRoutes.chatDto(chat));
                }
            }
        });
    }
    ApiResponse dispatch(String method, String body) {
        if ("GET".equals(method)) {
            List<Object> chats = Json.newArray();
            for (Map.Entry<String,Map<String,Object>> entry : store.sortedChats()) {
                entry.getValue().put("id",entry.getKey()); chats.add(ChatRoutes.chatDto(entry.getValue()));
            }
            Map<String,Object> data = Json.newObject(); data.put("chats",chats); return ApiResponse.ok(data);
        }
        Map<String,Object> req = Json.parseObject(body);
        if (req == null) return ApiResponse.badRequest("Ожидался JSON-объект");
        String id = Json.str(req,"chat_id","");
        if (id.isEmpty() && "POST".equals(method)) id = "chat_" + UUID.randomUUID();
        if (!id.matches("[a-zA-Z0-9_-]{1,160}")) return ApiResponse.badRequest("Некорректный идентификатор чата");
        Map<String,Object> chat = store.getChat(id);
        if ("POST".equals(method)) {
            if (chat != null) return ApiResponse.ok(ChatRoutes.chatDto(chat));
            String title = Json.str(req,"title","Новый чат").trim();
            if(title.isEmpty() || title.length()>200)return ApiResponse.badRequest("Название должно содержать от 1 до 200 символов");
            chat = Json.newObject(); chat.put("id",id); chat.put("title",title);
            chat.put("messages",Json.newArray()); chat.put("attachments",Json.newArray());
            chat.put("created",Utils.nowIso()); chat.put("pinned",false);
            String projectId = Json.str(req,"project_id","");
            if (!projectId.isEmpty()) {
                Map<String,Object> project = Json.asMap(store.getProjects().get(projectId));
                if (project == null) return ApiResponse.notFound("Проект не найден");
                ProjectManager.applyProjectDefaults(store,chat,project);
            }
            store.putChat(id,chat); save(chat); return ApiResponse.ok(ChatRoutes.chatDto(chat),201);
        }
        if (chat == null) return ApiResponse.notFound("Чат не найден");
        if (TaskControl.busy(id)) return ApiResponse.badRequest("Сначала остановите ответ в этом чате");
        if ("DELETE".equals(method)) {
            // Persist a recovery copy before removing the chat from the active store.
            try {
                java.nio.file.Path archive = java.nio.file.Paths.get(store.getBaseDir(),"backups","deleted-chat-"+UUID.randomUUID()+".json");
                java.nio.file.Files.createDirectories(archive.getParent());
                java.nio.file.Files.write(archive,Json.stringify(chat,true).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) { return ApiResponse.serverError("Не удалось сохранить резервную копию чата"); }
            store.removeChat(id); store.saveAll(); return ApiResponse.ok(Json.newObject());
        }
        if (!"PUT".equals(method)) return ApiResponse.badRequest("Неподдерживаемый метод");
        if (req.containsKey("project_id") && !Json.str(req,"project_id","").isEmpty() && !store.getProjects().containsKey(Json.str(req,"project_id","")))
            return ApiResponse.notFound("Проект не найден");
        if (req.containsKey("title")) {
            String title = Json.str(req,"title","").trim();
            if (title.isEmpty() || title.length()>200) return ApiResponse.badRequest("Название должно содержать от 1 до 200 символов");
            chat.put("title",title);
        }
        if (req.containsKey("pinned")) chat.put("pinned",Json.bool(req,"pinned",false));
        if (req.containsKey("project_id")) {
            String projectId = Json.str(req,"project_id","");
            Map<String,Object> project = Json.asMap(store.getProjects().get(projectId));
            if (!projectId.isEmpty() && project == null) return ApiResponse.notFound("Проект не найден");
            String oldDb = Json.str(chat,"project_db","");
            if (project == null) {
                chat.remove("project"); chat.remove("project_name"); chat.remove("project_db"); chat.remove("role");
                List<Object> selected = Json.arr(chat,"selected_databases");
                if (selected != null && !oldDb.isEmpty()) selected.remove(oldDb);
            } else ProjectManager.applyProjectDefaults(store,chat,project);
        }
        save(chat); return ApiResponse.ok(ChatRoutes.chatDto(chat));
    }
    private void save(Map<String,Object> chat) { chat.put("updated",Utils.nowIso()); store.saveAll(); }
}
