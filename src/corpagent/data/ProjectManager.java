package corpagent.data;

import corpagent.db.DatabaseManager;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Мастер «Создать проект» (R-P0.2) — ядро рабочего места руководителя проектов.
 *
 * <p>Создаёт проект как связку:</p>
 * <ol>
 *   <li>реальная SQLite-БД проекта ({@code databases/&lt;проект&gt;.db}) по доменной схеме
 *       ({@link #PROJECT_SCHEMA}: project, tasks, risks, meetings, meeting_decisions,
 *       team_member, problem_statement, goal_smart);</li>
 *   <li>запись о проекте в хранилище ({@code projects.json}) с системным промптом проекта;</li>
 *   <li>7 «чатов-ролей», каждый из которых предварительно настроен на
 *       БД проекта + «Методология LPG» (только чтение) и профильные навыки/инструменты.</li>
 * </ol>
 *
 * <p>Пресеты навыков/инструментов каталога R-P1.1/R-P1.2 переиспользуются по именам:
 * каждый чат-роль ссылается на соответствующий навык/инструмент, а не дублирует его.</p>
 */
public final class ProjectManager {

    private ProjectManager() {
    }

    /** Доменная схема БД проекта (C3.1): задачи, риски, встречи, решения, паспорт-блоки. */
    public static final String PROJECT_SCHEMA =
            "CREATE TABLE project (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    name TEXT NOT NULL,\n"
            + "    description TEXT,\n"
            + "    status TEXT DEFAULT 'active',\n"
            + "    start_date TEXT,\n"
            + "    end_date TEXT,\n"
            + "    owner TEXT,\n"
            + "    created TEXT\n"
            + ");\n"
            + "CREATE TABLE tasks (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    project_id INTEGER,\n"
            + "    title TEXT NOT NULL,\n"
            + "    description TEXT,\n"
            + "    assignee TEXT,\n"
            + "    status TEXT DEFAULT 'open',\n"
            + "    priority TEXT DEFAULT 'medium',\n"
            + "    due_date TEXT,\n"
            + "    linked_meeting INTEGER,\n"
            + "    created TEXT,\n"
            + "    updated TEXT\n"
            + ");\n"
            + "CREATE TABLE risks (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    project_id INTEGER,\n"
            + "    title TEXT NOT NULL,\n"
            + "    description TEXT,\n"
            + "    probability TEXT DEFAULT 'medium',\n"
            + "    impact TEXT DEFAULT 'medium',\n"
            + "    priority TEXT DEFAULT 'medium',\n"
            + "    mitigation TEXT,\n"
            + "    owner TEXT,\n"
            + "    status TEXT DEFAULT 'open',\n"
            + "    updated TEXT\n"
            + ");\n"
            + "CREATE TABLE meetings (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    project_id INTEGER,\n"
            + "    title TEXT NOT NULL,\n"
            + "    meeting_date TEXT,\n"
            + "    participants TEXT,\n"
            + "    summary TEXT,\n"
            + "    created TEXT\n"
            + ");\n"
            + "CREATE TABLE meeting_decisions (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    meeting_id INTEGER,\n"
            + "    decision TEXT NOT NULL,\n"
            + "    owner TEXT,\n"
            + "    due_date TEXT,\n"
            + "    created_task_id INTEGER\n"
            + ");\n"
            + "CREATE TABLE team_member (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    project_id INTEGER,\n"
            + "    name TEXT NOT NULL,\n"
            + "    role TEXT,\n"
            + "    responsibility TEXT,\n"
            + "    contact TEXT\n"
            + ");\n"
            + "CREATE TABLE problem_statement (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    project_id INTEGER,\n"
            + "    situation TEXT,\n"
            + "    complication TEXT,\n"
            + "    question TEXT,\n"
            + "    answer TEXT,\n"
            + "    updated TEXT\n"
            + ");\n"
            + "CREATE TABLE goal_smart (\n"
            + "    id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    project_id INTEGER,\n"
            + "    goal TEXT NOT NULL,\n"
            + "    specific TEXT,\n"
            + "    measurable TEXT,\n"
            + "    achievable TEXT,\n"
            + "    relevant TEXT,\n"
            + "    time_bound TEXT,\n"
            + "    updated TEXT\n"
            + ");";

    /**
     * Определение одного чата-роли проекта. Поле {@code skill} ссылается на пресет-навык
     * каталога R-P1.1 по точному имени; {@code tools} — на пресет-инструменты R-P1.2.
     */
    public static final class ChatRole {
        public final String title;
        public final String icon;
        public final String skill;
        public final String[] tools;

        ChatRole(String title, String icon, String skill, String[] tools) {
            this.title = title;
            this.icon = icon;
            this.skill = skill;
            this.tools = tools;
        }
    }

    /** Имя предустановленной БД-методологии LPG (подключается к каждому чату только на чтение). */
    public static final String LPG_DB = "Методология LPG";

    /** 7 чатов-ролей проекта (совпадают по составу с предустановленными навыками R-P1.1). */
    public static final ChatRole[] CHAT_ROLES = {
            new ChatRole("Работа с паспортом проекта", "📋",
                    "Работа с паспортом проекта",
                    new String[]{"Создание паспорта проекта в Excel"}),
            new ChatRole("Работа с рисками", "⚠️",
                    "Работа с рисками",
                    new String[]{"Создание реестра рисков в Excel"}),
            new ChatRole("Работа с планом проекта", "🗓️",
                    "Работа с планом проекта",
                    new String[0]),
            new ChatRole("Работа со статусом проекта", "📊",
                    "Работа со статусом проекта",
                    new String[]{"Создание статуса проекта в PPT"}),
            new ChatRole("Работа с протоколами встреч", "📝",
                    "Работа с протоколами встреч",
                    new String[0]),
            new ChatRole("Подготовка к УК", "🎯",
                    "Подготовка к УК",
                    new String[]{"Создание презентации в шаблоне Супер Ленты"}),
            new ChatRole("Проверка страницы проекта в Confluence", "🔎",
                    "Проверка страницы проекта в Confluence",
                    new String[0])
    };

    /** Результат создания проекта. */
    public static final class CreateProjectResult {
        public boolean success;
        public String error;
        public String projectId;
        public String projectDb;
        public List<String> chatIds = new ArrayList<String>();
        public int tables;
    }

    /**
     * Превращает отображаемое имя проекта в безопасный идентификатор БД
     * (латиница/цифры/_-). Пробелы и прочие символы заменяются на '_'.
     * Если после нормализации строка пуста — возвращает null.
     */
    public static String sanitizeDbName(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            // Разрешаем только ASCII-буквы/цифры, '_' и '-'; всё прочее (пробелы,
            // кириллица, спецсимволы) заменяем на '_' и затем схлопываем повторы.
            if ((Character.isLetterOrDigit(c) && c < 128) || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? null : s;
    }

    /**
     * Создаёт проект: БД проекта по доменной схеме, запись проекта и 7 авто-чатов.
     *
     * @param store       хранилище
     * @param dbManager   менеджер БД (создаёт реальный {@code .db}-файл)
     * @param name        отображаемое имя проекта
     * @param description описание проекта
     * @param systemPrompt системный промпт проекта (роль РП, цели, «без правки методологии LPG»)
     * @param dbName      имя БД проекта (санитизированный, латиница) или null (авто из name)
     * @return результат (успех/ошибки, идентификаторы проекта и чатов)
     */
    public static CreateProjectResult createProject(DataStore store, DatabaseManager dbManager,
            String name, String description, String systemPrompt, String dbName) {
        CreateProjectResult result = new CreateProjectResult();
        if (name == null || name.trim().isEmpty()) {
            result.error = "Не задано имя проекта.";
            return result;
        }
        String projName = name.trim();
        String db = (dbName == null || dbName.trim().isEmpty())
                ? sanitizeDbName(projName) : dbName.trim();
        if (db == null || !Utils.isValidDbName(db)) {
            result.error = "Некорректное имя БД проекта. Используйте латиницу, цифры, '_' или '-'.";
            return result;
        }
        boolean projectDbExists = store.getDatabase(db) != null;
        if (projectDbExists) {
            result.error = "БД проекта '" + db + "' уже существует. Выберите другое имя.";
            return result;
        }
        // 1) БД проекта по доменной схеме (файл databases/<db>.db).
        DatabaseManager.CreateDbResult cr =
                dbManager.createDatabase(db, PROJECT_SCHEMA, "Проект: " + projName);
        if (!cr.success) {
            result.error = "Не удалось создать БД проекта: " + cr.error;
            return result;
        }
        result.projectDb = db;
        result.tables = cr.tables.size();

        // 2) Запись проекта.
        String projectId = "proj_" + Utils.timestamp() + "_" + Math.abs((projName + db).hashCode());
        Map<String, Object> project = Json.newObject();
        project.put("id", projectId);
        project.put("name", projName);
        project.put("description", description == null ? "" : description.trim());
        project.put("system_prompt", systemPrompt == null ? "" : systemPrompt.trim());
        project.put("db", db);
        project.put("created", Utils.nowIso());
        project.put("updated", Utils.nowIso());
        project.put("chat_roles", roleTitles());
        store.putProject(projectId, project);
        result.projectId = projectId;

        // 3) 7 авто-чатов с предвыбранными БД/навыками/инструментами.
        List<String> lpgPresent = store.getDatabase(LPG_DB) != null
                ? java.util.Collections.singletonList(LPG_DB)
                : java.util.Collections.<String>emptyList();
        for (ChatRole role : CHAT_ROLES) {
            String chatId = "chat_" + Utils.timestamp() + "_" + Math.abs(role.title.hashCode());
            Map<String, Object> chat = Json.newObject();
            chat.put("title", role.title);
            chat.put("icon", role.icon);
            chat.put("created", Utils.nowIso());
            chat.put("updated", Utils.nowIso());
            chat.put("messages", Json.newArray());
            chat.put("pinned", Boolean.FALSE);
            chat.put("project", projectId);
            chat.put("project_name", projName);
            chat.put("project_db", db);
            chat.put("role", role.title);
            // Выбранные по умолчанию чипы: БД проекта + LPG (только чтение), навык и инструменты роли.
            List<Object> dbs = new ArrayList<Object>();
            dbs.add(db);
            dbs.addAll(lpgPresent);
            chat.put("selected_databases", dbs);
            List<Object> skillsList = new ArrayList<Object>();
            if (role.skill != null && !role.skill.isEmpty()) {
                skillsList.add(role.skill);
            }
            chat.put("selected_skills", skillsList);
            List<Object> toolsList = new ArrayList<Object>();
            for (String t : role.tools) {
                toolsList.add(t);
            }
            chat.put("selected_tools", toolsList);
            store.putChat(chatId, chat);
            result.chatIds.add(chatId);
        }
        store.saveAll();
        result.success = true;
        return result;
    }

    /**
     * R-P1.9.5: создаёт проект, используя уже зарегистрированную (существующую) БД как БД
     * проекта. Схема БД не создаётся — берётся текущий {@code .db}-файл из хранилища.
     *
     * @param store       хранилище
     * @param name        отображаемое имя проекта
     * @param description описание проекта
     * @param systemPrompt системный промпт проекта
     * @param existingDb  имя зарегистрированной БД (из {@link DataStore#getDatabase(String)})
     * @return результат (успех/ошибки, идентификаторы проекта и чатов)
     */
    public static CreateProjectResult createProjectWithExistingDb(DataStore store,
            String name, String description, String systemPrompt, String existingDb) {
        CreateProjectResult result = new CreateProjectResult();
        if (name == null || name.trim().isEmpty()) {
            result.error = "Не задано имя проекта.";
            return result;
        }
        if (existingDb == null || existingDb.trim().isEmpty() || store.getDatabase(existingDb) == null) {
            result.error = "Выбранная БД проекта не найдена: " + existingDb;
            return result;
        }
        String projName = name.trim();
        String db = existingDb.trim();
        result.projectDb = db;
        result.projectId = createProjectRecordAndChats(store, projName, db, description, systemPrompt);
        result.success = true;
        return result;
    }

    /**
     * Создаёт запись проекта и 7 авто-чатов (без создания схемы БД). Общая часть для
     * «нового проекта» (после создания .db) и «проекта на существующей БД» (R-P1.9.5).
     *
     * @return идентификатор проекта
     */
    private static String createProjectRecordAndChats(DataStore store, String projName, String db,
            String description, String systemPrompt) {
        String projectId = "proj_" + Utils.timestamp() + "_" + Math.abs((projName + db).hashCode());
        Map<String, Object> project = Json.newObject();
        project.put("id", projectId);
        project.put("name", projName);
        project.put("description", description == null ? "" : description.trim());
        project.put("system_prompt", systemPrompt == null ? "" : systemPrompt.trim());
        project.put("db", db);
        project.put("created", Utils.nowIso());
        project.put("updated", Utils.nowIso());
        project.put("chat_roles", roleTitles());
        store.putProject(projectId, project);

        List<String> lpgPresent = store.getDatabase(LPG_DB) != null
                ? java.util.Collections.singletonList(LPG_DB)
                : java.util.Collections.<String>emptyList();
        for (ChatRole role : CHAT_ROLES) {
            String chatId = "chat_" + Utils.timestamp() + "_" + Math.abs(role.title.hashCode());
            Map<String, Object> chat = Json.newObject();
            chat.put("title", role.title);
            chat.put("icon", role.icon);
            chat.put("created", Utils.nowIso());
            chat.put("updated", Utils.nowIso());
            chat.put("messages", Json.newArray());
            chat.put("pinned", Boolean.FALSE);
            chat.put("project", projectId);
            chat.put("project_name", projName);
            chat.put("project_db", db);
            chat.put("role", role.title);
            List<Object> dbs = new ArrayList<Object>();
            dbs.add(db);
            dbs.addAll(lpgPresent);
            chat.put("selected_databases", dbs);
            List<Object> skillsList = new ArrayList<Object>();
            if (role.skill != null && !role.skill.isEmpty()) {
                skillsList.add(role.skill);
            }
            chat.put("selected_skills", skillsList);
            List<Object> toolsList = new ArrayList<Object>();
            for (String t : role.tools) {
                toolsList.add(t);
            }
            chat.put("selected_tools", toolsList);
            store.putChat(chatId, chat);
        }
        store.saveAll();
        return projectId;
    }

    /** Возвращает упорядоченный список названий чатов-ролей проекта. */
    public static List<String> roleTitles() {
        List<String> titles = new ArrayList<String>();
        for (ChatRole r : CHAT_ROLES) {
            titles.add(r.title);
        }
        return titles;
    }

    /**
     * Идентификатор проекта для чата (или null, если чат не принадлежит проекту).
     */
    public static String projectIdOfChat(Map<String, Object> chat) {
        if (chat == null) {
            return null;
        }
        String id = Json.str(chat, "project", "");
        return id.isEmpty() ? null : id;
    }

    /** Собирает карту «projectId → отображаемое имя проекта». */
    public static Map<String, String> projectNames(DataStore store) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> e : store.getProjects().entrySet()) {
            Map<String, Object> p = Json.asMap(e.getValue());
            names.put(e.getKey(), p == null ? e.getKey() : Json.str(p, "name", e.getKey()));
        }
        return names;
    }

    /** Имя предустановленной БД-методологии ИТ-проектов (R-P1.4). */
    public static final String IT_METHODOLOGY_DB = "Методология ИТ проектов";

    /**
     * R-P1.9.4: применяет к чату «правила проекта» — привязку к проекту и дефолтные
     * чипы (БД проекта + LPG-методология только на чтение, профильный навык и инструменты).
     * Переиспользуется при переносе обычного чата в проект.
     *
     * <p>Навык/инструменты подбираются по роли, если название чата совпадает с одной
     * из ролей {@link #CHAT_ROLES}; иначе — берётся только БД проекта + LPG.</p>
     *
     * @return true, если чат был привязан к проекту; false, если проект не найден.
     */
    public static boolean applyProjectDefaults(DataStore store, Map<String, Object> chat,
            Map<String, Object> project) {
        if (store == null || chat == null || project == null) {
            return false;
        }
        String projectId = Json.str(project, "id", "");
        String projectName = Json.str(project, "name", "");
        String db = Json.str(project, "db", "");
        if (projectId.isEmpty() || db.isEmpty()) {
            return false;
        }
        chat.put("project", projectId);
        chat.put("project_name", projectName);
        chat.put("project_db", db);

        List<Object> dbs = new ArrayList<Object>();
        dbs.add(db);
        if (store.getDatabase(LPG_DB) != null) {
            dbs.add(LPG_DB);
        }
        chat.put("selected_databases", dbs);

        // Профильные навык/инструменты — по совпадению названия чата с ролью проекта.
        ChatRole role = roleForTitle(Json.str(chat, "title", ""));
        List<Object> skillsList = new ArrayList<Object>();
        if (role != null && role.skill != null && !role.skill.isEmpty()) {
            skillsList.add(role.skill);
        }
        chat.put("selected_skills", skillsList);
        List<Object> toolsList = new ArrayList<Object>();
        if (role != null) {
            for (String t : role.tools) {
                toolsList.add(t);
            }
        }
        chat.put("selected_tools", toolsList);
        if (role != null) {
            chat.put("role", role.title);
        }
        return true;
    }

    /** Возвращает определение роли по названию чата или null, если совпадений нет. */
    public static ChatRole roleForTitle(String title) {
        if (title == null) {
            return null;
        }
        for (ChatRole r : CHAT_ROLES) {
            if (r.title.equals(title)) {
                return r;
            }
        }
        return null;
    }
}
