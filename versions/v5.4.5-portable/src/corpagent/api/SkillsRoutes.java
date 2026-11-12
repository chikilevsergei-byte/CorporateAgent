package corpagent.api;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.List;
import java.util.Map;

/**
 * Маршруты раздела «Навыки» (Этап 5.2-B миграции v3 → v4).
 *
 * <p>Полный CRUD навыков (перенос вкладки «Навыки» из настроек прототипа v2):
 * <ul>
 *   <li>{@code GET /api/skills} — список навыков;</li>
 *   <li>{@code GET /api/skills?name=…} — один навык по имени (ключ — имя);</li>
 *   <li>{@code POST /api/skills} — создание (тело: {@code name}, {@code description},
 *       {@code code}, {@code enabled});</li>
 *   <li>{@code PUT /api/skills} — обновление (тело: {@code name} = ключ, поля для правки);</li>
 *   <li>{@code DELETE /api/skills} — удаление (тело или query: {@code name}).</li>
 * </ul>
 *
 * <p>Запись навыка хранится как {@code {name, description, code, icon, enabled, usage_count}}.
 * Пресеты защищены {@link DataStore#putSkill}/{@link DataStore#removeSkill}.</p>
 */
public final class SkillsRoutes {

    private final DataStore store;

    public SkillsRoutes(DataStore store) {
        this.store = store;
    }

    /** Регистрирует маршруты навыков. */
    public void register(ApiServer server) {
        server.route("/api/skills", new ApiServer.ApiRoute() {
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
            Map<String, Object> skill = store.getSkills().get(name) instanceof Map
                    ? Json.asMap(store.getSkills().get(name)) : null;
            if (skill == null) {
                return ApiResponse.notFound("Навык не найден: " + name);
            }
            return ApiResponse.ok(skillDto(name, skill));
        }
        List<Object> out = Json.newArray();
        for (Map.Entry<String, Object> e : store.getSkills().entrySet()) {
            Map<String, Object> s = Json.asMap(e.getValue());
            if (s == null) {
                continue;
            }
            out.add(skillDto(e.getKey(), s));
        }
        Map<String, Object> data = Json.newObject();
        data.put("skills", out);
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
        Map<String, Object> skill = Json.newObject();
        applyFields(skill, req, true);
        skill.put("name", name);
        skill.put("created", Utils.nowIso());
        store.putSkill(name, skill);
        store.saveAll();
        return ApiResponse.ok(skillDto(name, skill), 201);
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
        Map<String, Object> skill = Json.asMap(store.getSkills().get(name));
        if (skill == null) {
            return ApiResponse.notFound("Навык не найден: " + name);
        }
        applyFields(skill, req, false);
        skill.put("updated", Utils.nowIso());
        // putSkill защищает пресеты; для не-пресета просто перезапишет.
        store.putSkill(name, skill);
        store.saveAll();
        return ApiResponse.ok(skillDto(name, skill));
    }

    ApiResponse handleDelete(String body, Map<String, List<String>> query) {
        String name = nameFromBodyOrQuery(body, query);
        if (name == null || name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        Map<String, Object> skill = Json.asMap(store.getSkills().get(name));
        if (skill == null) {
            return ApiResponse.notFound("Навык не найден: " + name);
        }
        if (Json.bool(skill, "preset", false)) {
            return ApiResponse.badRequest("Нельзя удалить предустановленный навык: " + name);
        }
        store.removeSkill(name);
        store.saveAll();
        Map<String, Object> data = Json.newObject();
        data.put("name", name);
        data.put("removed", Boolean.TRUE);
        return ApiResponse.ok(data);
    }

    /** Применяет поля записи из запроса. При {@code create} — со значениями по умолчанию. */
    private static void applyFields(Map<String, Object> target, Map<String, Object> req, boolean create) {
        if (create || req.containsKey("description")) {
            target.put("description", Json.str(req, "description", ""));
        }
        if (create || req.containsKey("code")) {
            target.put("code", Json.str(req, "code", ""));
        }
        if (create || req.containsKey("icon")) {
            target.put("icon", Json.str(req, "icon", "🧠"));
        }
        if (create || req.containsKey("enabled")) {
            target.put("enabled", !req.containsKey("enabled") || Json.bool(req, "enabled", true));
        }
        if (create && !target.containsKey("usage_count")) {
            target.put("usage_count", 0);
        }
    }

    static Map<String, Object> skillDto(String name, Map<String, Object> s) {
        Map<String, Object> dto = Json.newObject();
        dto.put("name", name);
        dto.put("title", Json.str(s, "name", name));
        dto.put("description", Json.str(s, "description", ""));
        dto.put("code", Json.str(s, "code", ""));
        dto.put("icon", Json.str(s, "icon", "🧠"));
        dto.put("enabled", Json.bool(s, "enabled", true));
        dto.put("preset", Json.bool(s, "preset", false));
        dto.put("usage_count", Json.intVal(s, "usage_count", 0));
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
