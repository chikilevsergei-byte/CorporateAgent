package corpagent.data;

import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.List;
import java.util.Map;

/**
 * Миграция данных при первом запуске новой версии.
 *
 * Задача: бесшовно перенести существующие навыки/инструменты/БД из старого
 * формата в новый (расширенный), НЕ требуя ручного редактирования JSON и
 * НЕ ломая существующие данные.
 *
 * Старый формат:
 *   - навык:   {description, code, created, updated}
 *   - инструмент: {description, code, created, updated}
 *   - БД:      {path, description, created, schema, tables_count, records_count}
 *
 * Новый формат добавляет поля по умолчанию:
 *   - навык:   + {icon, enabled, params:{}, usage_count}
 *   - инструмент: + {icon, enabled, type, params:{}, usage_count}
 *   - БД:      + {type, enabled, usage_count}
 *
 * Перед изменением файлов создаются резервные копии *.bak.<timestamp>.
 */
public final class Migration {

    private Migration() {
    }

    /**
     * Выполняет миграцию данных. Вызывается один раз при старте приложения
     * (после загрузки DataStore, до сохранения). Возвращает true, если
     * какие-то данные были изменены (тогда вызывающий должен сохранить).
     */
    public static boolean migrate(DataStore store) {
        boolean changed = false;
        changed |= migrateSkills(store);
        changed |= migrateTools(store);
        changed |= migrateDatabases(store);
        return changed;
    }

    // ==================== НАВЫКИ ====================

    private static boolean migrateSkills(DataStore store) {
        Map<String, Object> skills = store.getSkills();
        if (skills.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, Object> e : skills.entrySet()) {
            Map<String, Object> s = Json.asMap(e.getValue());
            if (s == null) {
                continue;
            }
            if (ensureSkillDefaults(s)) {
                changed = true;
            }
        }
        return changed;
    }

    /** Добавляет недостающие поля навыка. Возвращает true, если что-то добавлено. */
    public static boolean ensureSkillDefaults(Map<String, Object> s) {
        boolean changed = false;
        if (!s.containsKey("icon")) {
            s.put("icon", "🧠");
            changed = true;
        }
        if (!s.containsKey("enabled")) {
            s.put("enabled", true);
            changed = true;
        }
        if (!s.containsKey("params")) {
            s.put("params", Json.newObject());
            changed = true;
        }
        if (!s.containsKey("usage_count")) {
            s.put("usage_count", 0);
            changed = true;
        }
        return changed;
    }

    // ==================== ИНСТРУМЕНТЫ ====================

    private static boolean migrateTools(DataStore store) {
        Map<String, Object> tools = store.getTools();
        if (tools.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, Object> e : tools.entrySet()) {
            Map<String, Object> t = Json.asMap(e.getValue());
            if (t == null) {
                continue;
            }
            if (ensureToolDefaults(t)) {
                changed = true;
            }
        }
        return changed;
    }

    /** Добавляет недостающие поля инструмента. Возвращает true, если что-то добавлено. */
    public static boolean ensureToolDefaults(Map<String, Object> t) {
        boolean changed = false;
        if (!t.containsKey("icon")) {
            t.put("icon", "🛠️");
            changed = true;
        }
        if (!t.containsKey("enabled")) {
            t.put("enabled", true);
            changed = true;
        }
        if (!t.containsKey("type")) {
            // Старые инструменты были "кодовыми" (Python-скрипты). По умолчанию — python.
            t.put("type", "python");
            changed = true;
        }
        if (!t.containsKey("params")) {
            t.put("params", Json.newObject());
            changed = true;
        }
        if (!t.containsKey("usage_count")) {
            t.put("usage_count", 0);
            changed = true;
        }
        return changed;
    }

    // ==================== БАЗЫ ДАННЫХ ====================

    private static boolean migrateDatabases(DataStore store) {
        Map<String, Object> databases = store.getDatabases();
        if (databases.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, Object> e : databases.entrySet()) {
            Map<String, Object> d = Json.asMap(e.getValue());
            if (d == null) {
                continue;
            }
            if (ensureDatabaseDefaults(d)) {
                changed = true;
            }
        }
        return changed;
    }

    /** Добавляет недостающие поля БД. Возвращает true, если что-то добавлено. */
    public static boolean ensureDatabaseDefaults(Map<String, Object> d) {
        boolean changed = false;
        if (!d.containsKey("type")) {
            // Старые БД были только SQLite (хранили путь к файлу).
            d.put("type", "sqlite");
            changed = true;
        }
        if (!d.containsKey("enabled")) {
            d.put("enabled", true);
            changed = true;
        }
        if (!d.containsKey("usage_count")) {
            d.put("usage_count", 0);
            changed = true;
        }
        return changed;
    }

    // ==================== РЕЗЕРВНОЕ КОПИРОВАНИЕ ====================

    /**
     * Создаёт резервные копии всех JSON-файлов данных (если они существуют)
     * с суффиксом .bak.<timestamp>. Вызывается перед первой записью в новом формате.
     */
    public static void backupDataFiles(String baseDir) {
        String[] names = {"chats", "skills", "tools", "databases", "db_logs", "projects", "config"};
        String ts = Utils.timestamp();
        for (String name : names) {
            String src = baseDir + java.io.File.separator + name + ".json";
            if (Utils.exists(src)) {
                String dest = src + ".bak." + ts;
                Utils.copyFile(src, dest);
            }
        }
    }

    // ==================== СЧЁТЧИКИ ИСПОЛЬЗОВАНИЯ ====================

    /** Увеличивает счётчик использования навыка. */
    public static void bumpSkillUsage(DataStore store, String name) {
        Map<String, Object> s = Json.asMap(store.getSkills().get(name));
        if (s == null) {
            return;
        }
        s.put("usage_count", Json.intVal(s, "usage_count", 0) + 1);
    }

    /** Увеличивает счётчик использования инструмента. */
    public static void bumpToolUsage(DataStore store, String name) {
        Map<String, Object> t = Json.asMap(store.getTools().get(name));
        if (t == null) {
            return;
        }
        t.put("usage_count", Json.intVal(t, "usage_count", 0) + 1);
    }

    /** Увеличивает счётчик использования БД. */
    public static void bumpDatabaseUsage(DataStore store, String name) {
        Map<String, Object> d = Json.asMap(store.getDatabases().get(name));
        if (d == null) {
            return;
        }
        d.put("usage_count", Json.intVal(d, "usage_count", 0) + 1);
    }

    /** Возвращает список включённых (enabled) ключей из карты. */
    public static List<String> enabledKeys(Map<String, Object> map) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Map<String, Object> m = Json.asMap(e.getValue());
            if (m == null || Json.bool(m, "enabled", true)) {
                result.add(e.getKey());
            }
        }
        return result;
    }
}
