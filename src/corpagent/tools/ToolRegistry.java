package corpagent.tools;

import corpagent.data.DataStore;
import corpagent.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр инструментов для function calling (OpenAI-совместимый формат).
 * Строит массив {@code tools} из включённых инструментов хранилища
 * и предоставляет поиск определения инструмента по имени.
 *
 * Каждый инструмент превращается в функцию с именем, описанием и JSON-схемой
 * параметров (производной от списка {@code params} инструмента).
 */
public class ToolRegistry {

    private final DataStore store;

    public ToolRegistry(DataStore store) {
        this.store = store;
    }

    /**
     * Строит массив {@code tools} для запроса к API из включённых инструментов.
     *
     * @return список объектов tools (каждый: {type:"function", function:{name, description, parameters}})
     */
    public List<Object> buildToolsArray() {
        List<Object> tools = new ArrayList<Object>();
        for (String name : store.enabledTools()) {
            Map<String, Object> tool = Json.asMap(store.getTools().get(name));
            if (tool == null) {
                continue;
            }
            Map<String, Object> fn = buildFunction(name, tool);
            if (fn != null) {
                Map<String, Object> entry = Json.newObject();
                entry.put("type", "function");
                entry.put("function", fn);
                tools.add(entry);
            }
        }
        return tools;
    }

    /**
     * Строит описание одной функции (name/description/parameters) из данных инструмента.
     * Имя функции нормализуется (только латиница, цифры, подчёркивание, дефис).
     */
    private Map<String, Object> buildFunction(String name, Map<String, Object> tool) {
        String fnName = normalizeName(name);
        if (fnName.isEmpty()) {
            return null;
        }
        Map<String, Object> fn = Json.newObject();
        fn.put("name", fnName);
        String desc = Json.str(tool, "description");
        String type = Json.str(tool, "type", "http");
        String icon = Json.str(tool, "icon", "🔧");
        if (desc.isEmpty()) {
            desc = "Инструмент " + name;
        }
        fn.put("description", icon + " [" + type + "] " + name + ". " + desc);

        // JSON-схема параметров
        Map<String, Object> parameters = Json.newObject();
        parameters.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        List<String> required = new ArrayList<String>();

        List<Object> params = Json.arr(tool, "params");
        if (params != null) {
            for (Object pObj : params) {
                Map<String, Object> p = Json.asMap(pObj);
                if (p == null) {
                    continue;
                }
                String pName = Json.str(p, "name");
                if (pName.isEmpty()) {
                    continue;
                }
                Map<String, Object> prop = Json.newObject();
                String pType = Json.str(p, "type", "text");
                prop.put("type", jsonType(pType));
                String pDesc = Json.str(p, "description");
                if (pDesc.isEmpty()) {
                    pDesc = "Параметр " + pName;
                }
                prop.put("description", pDesc);
                properties.put(pName, prop);
                if (Json.bool(p, "required", false)) {
                    required.add(pName);
                }
            }
        }
        parameters.put("properties", properties);
        parameters.put("required", required);
        fn.put("parameters", parameters);
        return fn;
    }

    /** Возвращает определение функции по нормализованному имени (или null). */
    public Map<String, Object> getFunction(String normalizedName) {
        for (String name : store.enabledTools()) {
            Map<String, Object> tool = Json.asMap(store.getTools().get(name));
            if (tool == null) {
                continue;
            }
            if (normalizedName.equals(normalizeName(name))) {
                return tool;
            }
        }
        return null;
    }

    /** Нормализует имя инструмента в допустимое имя функции OpenAI. */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /** Сопоставляет тип параметра инструмента с JSON-типом. */
    private static String jsonType(String type) {
        if (type == null) {
            return "string";
        }
        String t = type.toLowerCase();
        if (t.contains("int") || t.contains("number") || t.contains("float") || t.contains("double")) {
            return "number";
        }
        if (t.contains("bool")) {
            return "boolean";
        }
        if (t.contains("array") || t.contains("list")) {
            return "array";
        }
        if (t.contains("obj") || t.contains("json")) {
            return "object";
        }
        return "string";
    }
}
