package corpagent.api;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.List;
import java.util.Map;

/**
 * Маршруты раздела «Заметки» (Этап 4.5 миграции v3 → v4).
 *
 * <p>Полный CRUD заметок пользователя (перенос экрана «Заметки» из прототипа v2):
 * <ul>
 *   <li>{@code GET /api/notes} — список заметок (сначала новые);</li>
 *   <li>{@code GET /api/notes?note_id=…} — одна заметка по id;</li>
 *   <li>{@code POST /api/notes} — создание (тело: {@code title}, {@code text});</li>
 *   <li>{@code PUT /api/notes} — обновление (тело: {@code note_id}, {@code title}, {@code text});</li>
 *   <li>{@code DELETE /api/notes} — удаление (тело или query: {@code note_id}).</li>
 * </ul>
 *
 * <p>Заметка хранится в {@link DataStore} как карта {@code {id,title,text,created,updated}}.
 * Модель id/полей согласована с форматом чатов ({@code @code chat_…}) — префикс {@code note_}.</p>
 */
public final class NotesRoutes {

    private final DataStore store;

    public NotesRoutes(DataStore store) {
        this.store = store;
    }

    /** Регистрирует маршруты заметок. */
    public void register(ApiServer server) {
        server.route("/api/notes", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return dispatch(method, body, query);
            }
        });
    }

    /** Определяет операцию по HTTP-методу. */
    ApiResponse dispatch(String method, String body, Map<String, List<String>> query) {
        String m = method == null ? "" : method.toUpperCase();
        if ("GET".equals(m)) {
            return handleGet(query);
        }
        if ("POST".equals(m)) {
            return handleCreate(body);
        }
        if ("PUT".equals(m)) {
            return handleUpdate(body);
        }
        if ("DELETE".equals(m)) {
            return handleDelete(body, query);
        }
        return ApiResponse.badRequest("Неподдерживаемый метод: " + method);
    }

    /** GET: одна заметка ({@code note_id} в query) или список всех. */
    ApiResponse handleGet(Map<String, List<String>> query) {
        String noteId = firstParam(query, "note_id");
        if (noteId != null && !noteId.isEmpty()) {
            Map<String, Object> note = store.getNote(noteId);
            if (note == null) {
                return ApiResponse.notFound("Заметка не найдена: " + noteId);
            }
            return ApiResponse.ok(noteDto(note));
        }
        List<Object> list = Json.newArray();
        for (Map.Entry<String, Map<String, Object>> e : store.sortedNotes()) {
            list.add(noteDto(e.getValue()));
        }
        Map<String, Object> data = Json.newObject();
        data.put("notes", list);
        return ApiResponse.ok(data);
    }

    /** POST: создаёт новую заметку. */
    ApiResponse handleCreate(String body) {
        Map<String, Object> req;
        try {
            req = Json.parseObject(body);
        } catch (Exception e) {
            return ApiResponse.badRequest("Некорректный JSON: " + e.getMessage());
        }
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String title = Json.str(req, "title", "").trim();
        String text = Json.str(req, "text", "");
        if (title.isEmpty() && text.trim().isEmpty()) {
            return ApiResponse.badRequest("Поля 'title' или 'text' обязательны.");
        }
        String id = "note_" + java.util.UUID.randomUUID().toString();
        Map<String, Object> note = Json.newObject();
        note.put("id", id);
        note.put("title", title.isEmpty() ? "Без названия" : title);
        note.put("text", text);
        note.put("created", Utils.nowIso());
        note.put("updated", Utils.nowIso());
        store.putNote(id, note);
        store.saveAll();
        return ApiResponse.ok(noteDto(note), 201);
    }

    /** PUT: обновляет существующую заметку. */
    ApiResponse handleUpdate(String body) {
        Map<String, Object> req;
        try {
            req = Json.parseObject(body);
        } catch (Exception e) {
            return ApiResponse.badRequest("Некорректный JSON: " + e.getMessage());
        }
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String noteId = Json.str(req, "note_id", null);
        if (noteId == null || noteId.isEmpty()) {
            return ApiResponse.badRequest("Поле 'note_id' обязательно.");
        }
        Map<String, Object> note = store.getNote(noteId);
        if (note == null) {
            return ApiResponse.notFound("Заметка не найдена: " + noteId);
        }
        if (req.containsKey("title")) {
            String title = Json.str(req, "title", "").trim();
            note.put("title", title.isEmpty() ? "Без названия" : title);
        }
        if (req.containsKey("text")) {
            note.put("text", Json.str(req, "text", ""));
        }
        note.put("updated", Utils.nowIso());
        store.saveAll();
        return ApiResponse.ok(noteDto(note));
    }

    /** DELETE: удаляет заметку ({@code note_id} в теле или query). */
    ApiResponse handleDelete(String body, Map<String, List<String>> query) {
        String noteId = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                Map<String, Object> req = Json.parseObject(body);
                noteId = Json.str(req, "note_id", null);
            } catch (Exception ignored) {
                // тело не JSON — пробуем query
            }
        }
        if (noteId == null || noteId.isEmpty()) {
            noteId = firstParam(query, "note_id");
        }
        if (noteId == null || noteId.isEmpty()) {
            return ApiResponse.badRequest("Поле 'note_id' обязательно.");
        }
        if (store.getNote(noteId) == null) {
            return ApiResponse.notFound("Заметка не найдена: " + noteId);
        }
        store.removeNote(noteId);
        store.saveAll();
        Map<String, Object> data = Json.newObject();
        data.put("note_id", noteId);
        data.put("removed", Boolean.TRUE);
        return ApiResponse.ok(data);
    }

    /** DTO заметки для фронтенда (без служебных полей). */
    static Map<String, Object> noteDto(Map<String, Object> note) {
        Map<String, Object> dto = Json.newObject();
        dto.put("id", Json.str(note, "id", null));
        dto.put("title", Json.str(note, "title", ""));
        dto.put("text", Json.str(note, "text", ""));
        dto.put("created", Json.str(note, "created", ""));
        dto.put("updated", Json.str(note, "updated", ""));
        return dto;
    }

    private static String firstParam(Map<String, List<String>> query, String key) {
        if (query == null) {
            return null;
        }
        List<String> vals = query.get(key);
        if (vals == null || vals.isEmpty()) {
            return null;
        }
        return vals.get(0);
    }
}
