package corpagent.api;

import corpagent.data.DataStore;
import corpagent.data.ProjectManager;
import corpagent.db.DatabaseManager;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.List;
import java.util.Map;

/**
 * Маршруты проектов рабочего места РП (Этап 4.2 + Этап 5.1 миграции v3 → v4).
 *
 * <p>Полный CRUD проектов (перенос экранов/мастера из прототипа v2):
 * <ul>
 *   <li>{@code GET /api/projects} — список проектов (для дропдауна сайдбара);</li>
 *   <li>{@code GET /api/projects?project_id=…} — один проект по id;</li>
 *   <li>{@code POST /api/projects} — создание проекта (тело: {@code name}, {@code description},
 *       {@code system_prompt}, {@code db_name}, {@code existing_db_id});</li>
 *   <li>{@code PUT /api/projects} — обновление (тело: {@code project_id}, {@code name},
 *       {@code description}, {@code system_prompt});</li>
 *   <li>{@code DELETE /api/projects} — удаление (тело или query: {@code project_id}).</li>
 * </ul>
 *
 * <p>Создание проекта использует {@link ProjectManager}: либо новую БД по доменной схеме
 * (5.1/5.b), либо {@code existing_db_id} — уже зарегистрированную БД (v2, шаг 2 мастера).</p>
 */
public final class ProjectsRoutes {

    private final DataStore store;
    private final DatabaseManager dbManager;

    public ProjectsRoutes(DataStore store, DatabaseManager dbManager) {
        this.store = store;
        this.dbManager = dbManager;
    }

    /** Регистрирует маршруты проектов. */
    public void register(ApiServer server) {
        server.route("/api/projects", new ApiServer.ApiRoute() {
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

    /** GET: один проект ({@code project_id} в query) или список всех. */
    ApiResponse handleGet(Map<String, List<String>> query) {
        String projectId = firstParam(query, "project_id");
        if (projectId != null && !projectId.isEmpty()) {
            Map<String, Object> project = store.getProject(projectId);
            if (project == null) {
                return ApiResponse.notFound("Проект не найден: " + projectId);
            }
            return ApiResponse.ok(projectDto(projectId, project));
        }
        List<Object> out = Json.newArray();
        for (Map.Entry<String, Object> e : store.getProjects().entrySet()) {
            Map<String, Object> p = Json.asMap(e.getValue());
            if (p == null) {
                continue;
            }
            out.add(projectDto(e.getKey(), p));
        }
        Map<String, Object> data = Json.newObject();
        data.put("projects", out);
        return ApiResponse.ok(data);
    }

    /** POST: создаёт проект (новую БД по схеме или на существующей БД). */
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
        String name = Json.str(req, "name", "").trim();
        if (name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        String description = Json.str(req, "description", "");
        String systemPrompt = Json.str(req, "system_prompt", "");
        String existingDb = Json.str(req, "existing_db_id", null);

        ProjectManager.CreateProjectResult result;
        if (existingDb != null && !existingDb.trim().isEmpty()) {
            // Шаг 2 мастера (v2): проект на уже зарегистрированной БД.
            result = ProjectManager.createProjectWithExistingDb(store, name, description,
                    systemPrompt, existingDb.trim());
        } else {
            // Новая БД проекта по доменной схеме.
            if (dbManager == null) {
                return ApiResponse.serverError("Менеджер БД недоступен для создания проекта.");
            }
            String dbName = Json.str(req, "db_name", null);
            result = ProjectManager.createProject(store, dbManager, name, description,
                    systemPrompt, dbName);
        }
        if (!result.success) {
            return ApiResponse.badRequest(result.error);
        }
        Map<String, Object> data = Json.newObject();
        data.put("project_id", result.projectId);
        data.put("db", result.projectDb);
        data.put("tables", result.tables);
        data.put("chats", toJsonArray(result.chatIds));
        return ApiResponse.ok(data, 201);
    }

    /** PUT: обновляет метаданные проекта (имя/описание/промпт). */
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
        String projectId = Json.str(req, "project_id", null);
        if (projectId == null || projectId.isEmpty()) {
            return ApiResponse.badRequest("Поле 'project_id' обязательно.");
        }
        Map<String, Object> project = store.getProject(projectId);
        if (project == null) {
            return ApiResponse.notFound("Проект не найден: " + projectId);
        }
        if (req.containsKey("name")) {
            String name = Json.str(req, "name", "").trim();
            if (name.isEmpty()) {
                return ApiResponse.badRequest("Поле 'name' не может быть пустым.");
            }
            project.put("name", name);
        }
        if (req.containsKey("description")) {
            project.put("description", Json.str(req, "description", ""));
        }
        if (req.containsKey("system_prompt")) {
            project.put("system_prompt", Json.str(req, "system_prompt", ""));
        }
        project.put("updated", Utils.nowIso());
        store.saveAll();
        return ApiResponse.ok(projectDto(projectId, project));
    }

    /** DELETE: удаляет проект и связанные авто-чаты. */
    ApiResponse handleDelete(String body, Map<String, List<String>> query) {
        String projectId = null;
        if (body != null && !body.trim().isEmpty()) {
            try {
                Map<String, Object> req = Json.parseObject(body);
                projectId = Json.str(req, "project_id", null);
            } catch (Exception ignored) {
                // тело не JSON — пробуем query
            }
        }
        if (projectId == null || projectId.isEmpty()) {
            projectId = firstParam(query, "project_id");
        }
        if (projectId == null || projectId.isEmpty()) {
            return ApiResponse.badRequest("Поле 'project_id' обязательно.");
        }
        if (store.getProject(projectId) == null) {
            return ApiResponse.notFound("Проект не найден: " + projectId);
        }
        List<String> removedChats = store.removeProject(projectId);
        store.saveAll();
        Map<String, Object> data = Json.newObject();
        data.put("project_id", projectId);
        data.put("removed", Boolean.TRUE);
        data.put("removed_chats", toJsonArray(removedChats));
        return ApiResponse.ok(data);
    }

    /** DTO проекта для фронтенда. */
    static Map<String, Object> projectDto(String id, Map<String, Object> p) {
        Map<String, Object> dto = Json.newObject();
        dto.put("id", id);
        dto.put("name", Json.str(p, "name", id));
        dto.put("description", Json.str(p, "description", ""));
        dto.put("system_prompt", Json.str(p, "system_prompt", ""));
        dto.put("db", Json.str(p, "db", ""));
        dto.put("created", Json.str(p, "created", ""));
        dto.put("updated", Json.str(p, "updated", ""));
        return dto;
    }

    /** Преобразует список значений в JSON-массив. */
    private static List<Object> toJsonArray(List<?> values) {
        List<Object> out = Json.newArray();
        for (Object v : values) {
            out.add(v);
        }
        return out;
    }

    private static String firstParam(Map<String, List<String>> query, String key) {        if (query == null) {
            return null;
        }
        List<String> vals = query.get(key);
        if (vals == null || vals.isEmpty()) {
            return null;
        }
        return vals.get(0);
    }
}
