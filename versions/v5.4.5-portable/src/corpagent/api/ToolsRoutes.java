package corpagent.api;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.List;
import java.util.Map;

/**
 * Маршруты раздела «Инструменты» (Этап 5.3-B миграции v3 → v4).
 *
 * <p>Полный CRUD инструментов (перенос вкладки «Инструменты» из настроек прототипа v2):
 * <ul>
 *   <li>{@code GET /api/tools} — список инструментов;</li>
 *   <li>{@code GET /api/tools?name=…} — один инструмент по имени (ключ — имя);</li>
 *   <li>{@code POST /api/tools} — создание (тело: {@code name}, {@code type},
 *       {@code endpoint}, {@code description}, {@code code}, {@code enabled});</li>
 *   <li>{@code PUT /api/tools} — обновление (тело: {@code name} = ключ, поля для правки);</li>
 *   <li>{@code DELETE /api/tools} — удаление (тело или query: {@code name}).</li>
 * </ul>
 *
 * <p>Запись инструмента хранится как
 * {@code {name, description, type, endpoint, code, icon, enabled, usage_count}}.
 * Пресеты защищены {@link DataStore#putTool}/{@link DataStore#removeTool}.</p>
 */
public final class ToolsRoutes {

    private final DataStore store;

    public ToolsRoutes(DataStore store) {
        this.store = store;
    }

    /** Регистрирует маршруты инструментов. */
    public void register(ApiServer server) {
        server.route("/api/tools", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return dispatch(method, body, query);
            }
        });
    }

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

    ApiResponse handleGet(Map<String, List<String>> query) {
        String name = firstParam(query, "name");
        if (name != null && !name.isEmpty()) {
            Map<String, Object> tool = Json.asMap(store.getTools().get(name));
            if (tool == null) {
                return ApiResponse.notFound("Инструмент не найден: " + name);
            }
            return ApiResponse.ok(toolDto(name, tool));
        }
        List<Object> out = Json.newArray();
        for (Map.Entry<String, Object> e : store.getTools().entrySet()) {
            Map<String, Object> t = Json.asMap(e.getValue());
            if (t == null) {
                continue;
            }
            out.add(toolDto(e.getKey(), t));
        }
        Map<String, Object> data = Json.newObject();
        data.put("tools", out);
        return ApiResponse.ok(data);
    }

    ApiResponse handleCreate(String body) {
        Map<String, Object> req = parse(body);
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String name = Json.str(req, "name", "").trim();
        if (name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        Map<String, Object> tool = Json.newObject();
        applyFields(tool, req, true);
        tool.put("name", name);
        tool.put("created", Utils.nowIso());
        store.putTool(name, tool);
        store.saveAll();
        return ApiResponse.ok(toolDto(name, tool), 201);
    }

    ApiResponse handleUpdate(String body) {
        Map<String, Object> req = parse(body);
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String name = Json.str(req, "name", null);
        if (name == null || name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        Map<String, Object> tool = Json.asMap(store.getTools().get(name));
        if (tool == null) {
            return ApiResponse.notFound("Инструмент не найден: " + name);
        }
        applyFields(tool, req, false);
        tool.put("updated", Utils.nowIso());
        store.putTool(name, tool);
        store.saveAll();
        return ApiResponse.ok(toolDto(name, tool));
    }

    ApiResponse handleDelete(String body, Map<String, List<String>> query) {
        String name = nameFromBodyOrQuery(body, query);
        if (name == null || name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        Map<String, Object> tool = Json.asMap(store.getTools().get(name));
        if (tool == null) {
            return ApiResponse.notFound("Инструмент не найден: " + name);
        }
        if (Json.bool(tool, "preset", false)) {
            return ApiResponse.badRequest("Нельзя удалить предустановленный инструмент: " + name);
        }
        store.removeTool(name);
        store.saveAll();
        Map<String, Object> data = Json.newObject();
        data.put("name", name);
        data.put("removed", Boolean.TRUE);
        return ApiResponse.ok(data);
    }

    private static void applyFields(Map<String, Object> target, Map<String, Object> req, boolean create) {
        if (create || req.containsKey("description")) {
            target.put("description", Json.str(req, "description", ""));
        }
        if (create || req.containsKey("type")) {
            target.put("type", Json.str(req, "type", "http"));
        }
        if (create || req.containsKey("endpoint")) {
            target.put("endpoint", Json.str(req, "endpoint", ""));
        }
        if (create || req.containsKey("code")) {
            target.put("code", Json.str(req, "code", ""));
        }
        if (create || req.containsKey("icon")) {
            target.put("icon", Json.str(req, "icon", "🛠️"));
        }
        if (create || req.containsKey("enabled")) {
            target.put("enabled", !req.containsKey("enabled") || Json.bool(req, "enabled", true));
        }
        if (create && !target.containsKey("usage_count")) {
            target.put("usage_count", 0);
        }
    }

    static Map<String, Object> toolDto(String name, Map<String, Object> t) {
        Map<String, Object> dto = Json.newObject();
        dto.put("name", name);
        dto.put("title", Json.str(t, "name", name));
        dto.put("description", Json.str(t, "description", ""));
        dto.put("type", Json.str(t, "type", "http"));
        dto.put("endpoint", Json.str(t, "endpoint", ""));
        dto.put("code", Json.str(t, "code", ""));
        dto.put("icon", Json.str(t, "icon", "🛠️"));
        dto.put("enabled", Json.bool(t, "enabled", true));
        dto.put("preset", Json.bool(t, "preset", false));
        dto.put("usage_count", Json.intVal(t, "usage_count", 0));
        return dto;
    }

    private static Map<String, Object> parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return Json.parseObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nameFromBodyOrQuery(String body, Map<String, List<String>> query) {
        String name = null;
        Map<String, Object> req = parse(body);
        if (req != null) {
            name = Json.str(req, "name", null);
        }
        if (name == null || name.isEmpty()) {
            name = firstParam(query, "name");
        }
        return name;
    }

    private static String firstParam(Map<String, List<String>> query, String key) {
        if (query == null) {
            return null;
        }
        List<String> vals = query.get(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
}
