package corpagent.api;

import corpagent.data.DataStore;
import corpagent.util.Json;

import java.util.List;
import java.util.Map;

/**
 * Маршруты справочников для панели контекста чата (Этап 3.13 миграции v3 → v4).
 *
 * <p>{@code GET /api/catalogs} отдаёт списки доступных БД, навыков и инструментов
 * в компактном виде ({@code name}/{@code title}/{@code description}/{@code meta}), чтобы
 * фронтенд мог наполнить выпадающие списки «БД / Навыки / Инструменты» (перенос v2).
 * Источник — {@link DataStore} (те же каталоги, что использует GUI-чат).</p>
 */
public final class CatalogRoutes {

    private final DataStore store;

    public CatalogRoutes(DataStore store) {
        this.store = store;
    }

    /** Регистрирует маршруты справочников. */
    public void register(ApiServer server) {
        server.route("/api/catalogs", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return handleCatalogs();
            }
        });
    }

    /** Собирает каталоги БД/навыков/инструментов в единый ответ. */
    ApiResponse handleCatalogs() {
        Map<String, Object> data = Json.newObject();
        data.put("databases", catalog(store.getDatabases(), "type", "описание"));
        data.put("skills", catalog(store.getSkills(), "code", "description"));
        data.put("tools", catalog(store.getTools(), "type", "description"));
        return ApiResponse.ok(data);
    }

    /**
     * Преобразует каталог {@code Map<name, record>} в список компактных DTO.
     * Поле {@code title} берётся из записи ({@code name}/{@code title}), иначе — из ключа.
     */
    private static List<Object> catalog(Map<String, Object> source, String typeField, String descField) {
        List<Object> out = Json.newArray();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Map<String, Object> rec = Json.asMap(e.getValue());
            if (rec == null) {
                continue;
            }
            if(!Json.bool(rec,"enabled",true))continue;
            String key = e.getKey();
            String title = firstNonEmpty(Json.str(rec, "title", null), Json.str(rec, "name", null), key);
            Map<String, Object> dto = Json.newObject();
            dto.put("name", key);
            dto.put("title", title);
            dto.put("description", Json.str(rec, descField, ""));
            String type = Json.str(rec, typeField, "");
            if (!type.isEmpty()) {
                dto.put("type", type);
            }
            out.add(dto);
        }
        return out;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
