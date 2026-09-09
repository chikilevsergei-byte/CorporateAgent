package corpagent.data;

import corpagent.util.Json;
import corpagent.util.Utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Загрузка предустановленных (preset) данных при первом запуске:
 * навыки (P1.1), инструменты (P1.2), БД-методологии (P1.3).
 *
 * <p>Пресеты хранятся как встроенные ресурсы в формате JSON и загружаются
 * один раз — если запись уже существует в хранилище, она не перезаписывается.</p>
 *
 * <p>Каждый пресет помечается полем {@code preset: true}, что запрещает
 * его редактирование и удаление через UI.</p>
 */
public final class PresetLoader {

    private PresetLoader() {
    }

    // ==================== НАВЫКИ (P1.1) ====================

    /** 7 предустановленных навыков для рабочего места РП. */
    private static final String[] PRESET_SKILLS = {
            // 1. Работа с паспортом проекта
            loadResource("presets/skills/passport_project.json"),
            // 2. Работа с рисками
            loadResource("presets/skills/risks.json"),
            // 3. Работа с планом проекта
            loadResource("presets/skills/plan.json"),
            // 4. Работа со статусом проекта
            loadResource("presets/skills/status.json"),
            // 5. Работа с протоколами встреч
            loadResource("presets/skills/meetings.json"),
            // 6. Подготовка к УК
            loadResource("presets/skills/uk_prep.json"),
            // 7. Проверка страницы проекта в Confluence
            loadResource("presets/skills/confluence.json")
    };

    // ==================== ИНСТРУМЕНТЫ (P1.2) ====================

    /** 4 предустановленных инструмента для генерации документов. */
    private static final String[] PRESET_TOOLS = {
            // 1. Создание паспорта проекта в Excel
            loadResource("presets/tools/create_passport_excel.json"),
            // 2. Создание реестра рисков в Excel
            loadResource("presets/tools/create_risks_excel.json"),
            // 3. Создание статуса проекта в PPT
            loadResource("presets/tools/create_status_ppt.json"),
            // 4. Создание презентации в шаблоне Супер Ленты
            loadResource("presets/tools/create_uk_presentation_ppt.json")
    };

    // ==================== БД-МЕТОДОЛОГИИ (P1.3) ====================

    /** 2 предустановленные БД-методологии. */
    private static final String[] PRESET_DBS = {
            loadResource("presets/databases/lpg_methodology.json"),
            loadResource("presets/databases/it_methodology.json")
    };

    // ==================== ЗАГРУЗКА РЕСУРСОВ ====================

    private static String loadResource(String path) {
        try {
            InputStream is = PresetLoader.class.getClassLoader().getResourceAsStream(path);
            if (is == null) {
                System.err.println("[PresetLoader] Ресурс не найден: " + path);
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[PresetLoader] Ошибка загрузки ресурса " + path + ": " + e.getMessage());
            return null;
        }
    }

    // ==================== ПРИМЕНЕНИЕ К ХРАНИЛИЩУ ====================

    /**
     * Применяет все пресеты к хранилищу. Вызывается один раз при старте,
     * если хранилище пустое (первый запуск).
     *
     * @return true, если какие-то данные были добавлены
     */
    public static boolean applyIfEmpty(DataStore store, String baseDir) {
        boolean changed = false;
        changed |= applySkills(store);
        changed |= applyTools(store);
        changed |= applyDatabases(store, baseDir);
        // Синхронизируем презентационный инструмент: если в уже сохранённом хранилище
        // осталась пресет-запись под прежним именем («…к УК в PPT», endpoint
        // agent:create_uk_presentation_ppt), приводим её к актуальному пресету
        // («…в шаблоне Супер Ленты», endpoint agent:create_presentation) — и не только
        // при первом запуске: пресеты не переустанавливаются в непустое хранилище.
        changed |= upgradePresentationTool(store);
        return changed;
    }

    /**
     * Приводит пресет-инструмент презентаций к актуальному имени/endpoint/коду в
     * существующем (уже не пустом) хранилище, вынося переименование из «к УК в PPT»
     * в «в шаблоне Супер Ленты». Возвращает true, если что-то изменилось.
     */
    private static boolean upgradePresentationTool(DataStore store) {
        String legacyKey = null;
        for (Map.Entry<String, Object> e : store.getTools().entrySet()) {
            Map<String, Object> t = Json.asMap(e.getValue());
            if (t == null || !isPresetTool(t)) {
                continue;
            }
            String name = Json.str(t, "name", "").toLowerCase();
            String ep = Json.str(t, "endpoint", "");
            if (name.contains("презентации к ук")
                    || "agent:create_uk_presentation_ppt".equals(ep)) {
                legacyKey = e.getKey();
                break;
            }
        }
        Map<String, Object> canonical = parsePresetTool(
                "presets/tools/create_uk_presentation_ppt.json");
        String canonicalName = canonical == null ? "" : Json.str(canonical, "name", "");
        if (canonical == null || canonicalName.isEmpty()) {
            return false;
        }
        // Если актуальная (новое имя/endpoint) уже на месте и старой записи нет — нечего делать.
        if (legacyKey == null && store.getTools().containsKey(canonicalName)) {
            return false;
        }
        // Обязательные служебные поля пресета.
        canonical.put("preset", true);
        canonical.put("icon", Json.str(canonical, "icon", "🛠️"));
        canonical.put("type", Json.str(canonical, "type", "http"));
        canonical.put("endpoint", Json.str(canonical, "endpoint", ""));
        if (!canonical.containsKey("enabled")) canonical.put("enabled", true);
        if (!canonical.containsKey("created")) canonical.put("created", Utils.nowIso());
        canonical.put("updated", Utils.nowIso());
        store.migratePresetTool(legacyKey, canonical);
        return true;
    }

    /** Парсит пресет-инструмент из classpath-ресурса в карту (или null). */
    private static Map<String, Object> parsePresetTool(String resource) {
        String jsonStr = loadResource(resource);
        if (jsonStr == null) {
            return null;
        }
        try {
            Map<String, Object> t = Json.asMap(Json.parse(jsonStr));
            return t == null ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    /** Загружает пресет-навыки, если хранилище навыков пустое. */
    private static boolean applySkills(DataStore store) {
        if (!store.getSkills().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String jsonStr : PRESET_SKILLS) {
            if (jsonStr == null) continue;
            try {
                Object parsed = Json.parse(jsonStr);
                Map<String, Object> skill = Json.asMap(parsed);
                if (skill == null) continue;
                String name = Json.str(skill, "name");
                if (name.isEmpty()) continue;
                skill.put("preset", true);
                skill.put("icon", Json.str(skill, "icon", "🧠"));
                skill.put("enabled", Json.bool(skill, "enabled", true));
                skill.put("description", Json.str(skill, "description", ""));
                skill.put("code", Json.str(skill, "code", ""));
                skill.put("created", Utils.nowIso());
                skill.put("updated", Utils.nowIso());
                skill.put("usage_count", 0);
                store.putSkill(name, skill);
                changed = true;
            } catch (Exception e) {
                System.err.println("[PresetLoader] Ошибка загрузки навыка: " + e.getMessage());
            }
        }
        return changed;
    }

    /** Загружает пресет-инструменты, если хранилище инструментов пустое. */
    private static boolean applyTools(DataStore store) {
        if (!store.getTools().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String jsonStr : PRESET_TOOLS) {
            if (jsonStr == null) continue;
            try {
                Object parsed = Json.parse(jsonStr);
                Map<String, Object> tool = Json.asMap(parsed);
                if (tool == null) continue;
                String name = Json.str(tool, "name");
                if (name.isEmpty()) continue;
                tool.put("preset", true);
                tool.put("icon", Json.str(tool, "icon", "🛠️"));
                tool.put("enabled", Json.bool(tool, "enabled", true));
                tool.put("description", Json.str(tool, "description", ""));
                tool.put("type", Json.str(tool, "type", "http"));
                tool.put("endpoint", Json.str(tool, "endpoint", ""));
                tool.put("created", Utils.nowIso());
                tool.put("updated", Utils.nowIso());
                tool.put("usage_count", 0);
                store.putTool(name, tool);
                changed = true;
            } catch (Exception e) {
                System.err.println("[PresetLoader] Ошибка загрузки инструмента: " + e.getMessage());
            }
        }
        return changed;
    }

    /**
     * Загружает пресет-БД, если хранилище БД пустое. Создаёт .db-файл с schema.
     *
     * <p>Путь в пресет-JSON относительный вида {@code databases/name.db} (со слешами,
     * т.к. это ресурсное соглашение). Резолвим его к реальному baseDir и сохраняем
     * в записи абсолютный путь — иначе на Windows File.separator='\' не совпадает с
     * '/' в пути, а .db создавался бы в рабочей папке, а не в corpagent.base.</p>
     */
    private static boolean applyDatabases(DataStore store, String baseDir) {
        if (!store.getDatabases().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (String jsonStr : PRESET_DBS) {
            if (jsonStr == null) continue;
            try {
                Object parsed = Json.parse(jsonStr);
                Map<String, Object> db = Json.asMap(parsed);
                if (db == null) continue;
                String name = Json.str(db, "name");
                if (name.isEmpty()) continue;
                String relPath = Json.str(db, "path");
                String schema = Json.str(db, "schema", "");
                // Резолвим путь к baseDir и нормализуем разделители под текущую ОС.
                String absPath = resolveDbPath(baseDir, relPath);
                db.put("preset", true);
                db.put("type", "sqlite");
                db.put("path", absPath);
                db.put("enabled", Json.bool(db, "enabled", true));
                db.put("created", Utils.nowIso());
                db.put("usage_count", 0);
                store.putDatabase(name, db);
                // Создаём .db-файл с schema
                if (!schema.isEmpty()) {
                    createPresetDb(absPath, schema, name);
                }
                changed = true;
            } catch (Exception e) {
                System.err.println("[PresetLoader] Ошибка загрузки БД: " + e.getMessage());
            }
        }
        return changed;
    }

    /**
     * Превращает относительный путь пресета (например {@code databases/lpg_methodology.db})
     * в абсолютный под {@code baseDir}, заменяя и '/' (как в ресурсном пути), и '\' на
     * разделитель текущей платформы. Абсолютный путь возвращается как есть.
     */
    private static String resolveDbPath(String baseDir, String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return baseDir;
        }
        String norm = relPath.replace('/', java.io.File.separatorChar)
                             .replace('\\', java.io.File.separatorChar);
        java.io.File f = new java.io.File(norm);
        if (f.isAbsolute()) {
            return f.getPath();
        }
        java.io.File combined = new java.io.File(baseDir, norm);
        return combined.getAbsolutePath();
    }

    /** Создаёт SQLite-файл пресет-БД из schema по уже зарезолвленному абсолютному пути. */
    private static void createPresetDb(String absDbPath, String schema, String dbName) {
        try {
            // Директория (без разделителя в конце) для .db-файла.
            String dir = absDbPath.substring(0, absDbPath.lastIndexOf(java.io.File.separatorChar));
            Utils.mkdirs(dir);
            // Создаём БД
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + absDbPath);
            Statement stmt = conn.createStatement();
            // Разбиваем schema на отдельные операторы
            String[] statements = schema.split(";");
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    stmt.execute(trimmed);
                }
            }
            stmt.close();
            conn.close();
            System.out.println("[PresetLoader] Создана БД: " + dbName + " -> " + absDbPath);
        } catch (Exception e) {
            System.err.println("[PresetLoader] Ошибка создания БД " + dbName + ": " + e.getMessage());
        }
    }

    /** Проверяет, является ли навык предустановленным. */
    public static boolean isPresetSkill(Map<String, Object> skill) {
        return Json.bool(skill, "preset", false);
    }

    /** Проверяет, является ли инструмент предустановленным. */
    public static boolean isPresetTool(Map<String, Object> tool) {
        return Json.bool(tool, "preset", false);
    }

    /** Проверяет, является ли БД предустановленной методологией. */
    public static boolean isPresetDatabase(Map<String, Object> db) {
        return Json.bool(db, "preset", false);
    }
}
