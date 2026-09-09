package corpagent.data;

import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Хранилище данных приложения: чаты, навыки, инструменты, базы данных, логи БД.
 * Данные хранятся в JSON-файлах рядом с приложением (аналог session_state + load_data/save_data в v2).
 */
public class DataStore {

    private final String baseDir;

    private final Map<String, Object> chats = new LinkedHashMap<String, Object>();
    private final Map<String, Object> skills = new LinkedHashMap<String, Object>();
    private final Map<String, Object> tools = new LinkedHashMap<String, Object>();
    private final Map<String, Object> databases = new LinkedHashMap<String, Object>();
    private final Map<String, Object> dbLogs = new LinkedHashMap<String, Object>();

    public DataStore(String baseDir) {
        this.baseDir = baseDir;
        loadAll();
        // Миграция данных из старого формата в новый (добавление полей по умолчанию).
        // Резервная копия создаётся только если данные реально изменялись.
        if (Migration.migrate(this)) {
            Migration.backupDataFiles(baseDir);
            saveAll();
        }
        // Загрузка предустановленных данных (P1.1–P1.3) при первом запуске.
        boolean presetsApplied = PresetLoader.applyIfEmpty(this);
        if (presetsApplied) {
            saveAll();
        }
    }

    private String filePath(String name) {
        return baseDir + java.io.File.separator + name + ".json";
    }

    private void loadAll() {
        loadInto(chats, "chats");
        loadInto(skills, "skills");
        loadInto(tools, "tools");
        loadInto(databases, "databases");
        loadInto(dbLogs, "db_logs");
    }

    @SuppressWarnings("unchecked")
    private void loadInto(Map<String, Object> target, String name) {
        String content = Utils.readFile(filePath(name));
        if (content == null) {
            return;
        }
        try {
            Object parsed = Json.parse(content);
            if (parsed instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) parsed;
                target.clear();
                target.putAll(map);
            }
        } catch (Exception e) {
            System.err.println("Не удалось прочитать " + name + ".json: " + e.getMessage());
        }
    }

    public void saveAll() {
        saveMap(chats, "chats");
        saveMap(skills, "skills");
        saveMap(tools, "tools");
        saveMap(databases, "databases");
        saveMap(dbLogs, "db_logs");
    }

    private void saveMap(Map<String, Object> map, String name) {
        Utils.writeFile(filePath(name), Json.stringify(map, true));
    }

    // ==================== ЧАТЫ ====================

    public Map<String, Object> getChats() {
        return chats;
    }

    public Map<String, Object> getChat(String chatId) {
        Object c = chats.get(chatId);
        return (c instanceof Map) ? (Map<String, Object>) c : null;
    }

    public void putChat(String chatId, Map<String, Object> chat) {
        chats.put(chatId, chat);
    }

    public void removeChat(String chatId) {
        chats.remove(chatId);
    }

    /** Возвращает список чатов, отсортированных по дате обновления (сначала новые). */
    public List<Map.Entry<String, Map<String, Object>>> sortedChats() {
        List<Map.Entry<String, Map<String, Object>>> list = new ArrayList<Map.Entry<String, Map<String, Object>>>();
        for (Map.Entry<String, Object> e : chats.entrySet()) {
            if (e.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) e.getValue();
                list.add(new java.util.AbstractMap.SimpleEntry<String, Map<String, Object>>(e.getKey(), m));
            }
        }
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, Map<String, Object>>>() {
            public int compare(Map.Entry<String, Map<String, Object>> a, Map.Entry<String, Map<String, Object>> b) {
                String ua = Json.str(a.getValue(), "updated", "");
                String ub = Json.str(b.getValue(), "updated", "");
                {
                    boolean pa = Json.bool(a.getValue(), "pinned", false);
                    boolean pb = Json.bool(b.getValue(), "pinned", false);
                    if (pa != pb) {
                        return pa ? -1 : 1;
                    }
                }
                return ub.compareTo(ua);
            }
        });
        return list;
    }

    // ==================== НАВЫКИ ====================

    public Map<String, Object> getSkills() {
        return skills;
    }

        public void putSkill(String name, Map<String, Object> skill) {
        // P1.x: не даём случайно перезаписать предустановленный навык.
        if (!allowReplace(skills, name, skill)) {
            return;
        }
        skills.put(name, skill);
    }

    public void removeSkill(String name) {
        if (isPresetEntry(skills, name)) {
            System.err.println("[DataStore] Удаление пресет-навыка запрещено: " + name);
            return;
        }
        skills.remove(name);
    }

    /** Список названий включённых навыков. */
    public java.util.List<String> enabledSkills() {
        return Migration.enabledKeys(skills);
    }

    /** Увеличивает счётчик использования навыка. */
    public void bumpSkillUsage(String name) {
        Migration.bumpSkillUsage(this, name);
    }

    // ==================== ИНСТРУМЕНТЫ ====================

    public Map<String, Object> getTools() {
        return tools;
    }



        public void putTool(String name, Map<String, Object> tool) {
        // P1.x: защита от случайной перезаписи предустановленных инструментов.
        if (!allowReplace(tools, name, tool)) {
            return;
        }
        tools.put(name, tool);
    }

    public void removeTool(String name) {
        if (isPresetEntry(tools, name)) {
            System.err.println("[DataStore] Запрещено удаление пресет-инструмента: " + name);
            return;
        }
        tools.remove(name);
    }





    /** Список названий включённых инструментов. */
    public java.util.List<String> enabledTools() {
        return Migration.enabledKeys(tools);
    }

    /** Увеличивает счётчик использования инструмента. */
    public void bumpToolUsage(String name) {
        Migration.bumpToolUsage(this, name);
    }

    // ==================== БАЗЫ ДАННЫХ ====================

    public Map<String, Object> getDatabases() {
        return databases;
    }

    public Map<String, Object> getDatabase(String name) {
        Object d = databases.get(name);
        return (d instanceof Map) ? (Map<String, Object>) d : null;
    }

        public void putDatabase(String name, Map<String, Object> db) {
        // P1.3: защита предустановленных БД от случайной перезаписи.
        if (!allowReplace(databases, name, db)) {
            return;
        }
        databases.put(name, db);
    }

    public void removeDatabase(String name) {
        if (isPresetEntry(databases, name)) {
            System.err.println("[DataStore] Удаление пресет-БД запрещено: " + name);
            return;
        }
        databases.remove(name);
    }

    /** Список названий включённых БД. */
    public java.util.List<String> enabledDatabases() {
        return Migration.enabledKeys(databases);
    }

    /** Увеличивает счётчик использования БД. */
    public void bumpDatabaseUsage(String name) {
        Migration.bumpDatabaseUsage(this, name);
    }

    // ==================== ЛОГИ БД ====================

    public Map<String, Object> getDbLogs() {
        return dbLogs;
    }

    @SuppressWarnings("unchecked")
    public void addDbLog(String dbName, String action, String details) {
        Object existing = dbLogs.get(dbName);
        List<Object> logs;
        if (existing instanceof List) {
            logs = (List<Object>) existing;
        } else {
            logs = new ArrayList<Object>();
            dbLogs.put(dbName, logs);
        }
        Map<String, Object> entry = Json.newObject();
        entry.put("timestamp", Utils.nowIso());
        entry.put("action", action);
        entry.put("details", details);
        logs.add(entry);
        saveAll();
    }

    @SuppressWarnings("unchecked")
    public List<Object> getDbLogList(String dbName) {
        Object existing = dbLogs.get(dbName);
        if (existing instanceof List) {
            return (List<Object>) existing;
        }
        return new ArrayList<Object>();
    }

        public void removeDbLogs(String dbName) {
        dbLogs.remove(dbName);
    }

    // ==================== ЗАЩИТА ПРЕСЕТОВ (P1.x) ====================

    /** Проверяет, является ли запись (по имени) предустановленной в заданной карте. */
    private static boolean isPresetEntry(Map<String, Object> map, String name) {
        Map<String, Object> entry = Json.asMap(map.get(name));
        return entry != null && Json.bool(entry, "preset", false);
    }

    /**
     * Разрешает ли запись перезапись существующей записи по имени (защита пресетов).
     * Пресет нельзя заменить записью без флага preset. Все остальные случаи разрешены.
     */
    private static boolean allowReplace(Map<String, Object> map, String name, Map<String, Object> incoming) {
        Map<String, Object> current = Json.asMap(map.get(name));
        if (current == null) {
            // Записи ещё нет — можно создавать любой инструмент/навык/БД.
            return true;
        }
        boolean currentIsPreset = Json.bool(current, "preset", false);
        if (currentIsPreset) {
            // Пресет нельзя перезаписать пользловательской редакцией без флага preset.
            boolean incomingIsPreset = Json.bool(incoming, "preset", false);
            return incomingIsPreset;
        }
        // Обычная (непресетная) запись перезаписывается всегда.
        return true;
    }
}
