package corpagent.api;

import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.List;
import java.util.Map;

/**
 * Маршруты раздела «Базы данных» (Этап 5.4-B миграции v3 → v4).
 *
 * <p>CRUD БД + расширенные операции переноса живых диалогов v3:
 * <ul>
 *   <li>{@code GET /api/databases} — список БД (карточки настроек);</li>
 *   <li>{@code GET /api/databases?name=…&view=1} — просмотр: таблицы + схема (аналог {@code DbViewerView});</li>
 *   <li>{@code GET /api/databases?name=…&table=…} — строки одной таблицы (просмотр);</li>
 *   <li>{@code POST /api/databases} — регистрация/создание записи БД (тело: {@code name},
 *       {@code path}, {@code description}, {@code type});</li>
 *   <li>{@code PUT /api/databases} — обновление записи (тело: {@code name}, поля для правки);</li>
 *   <li>{@code DELETE /api/databases} — удаление записи (тело или query: {@code name}).</li>
 * </ul>
 *
 * <p>Просмотр и схема реализуются поверх {@link SqliteHelper}. Пресеты защищены
 * {@link DataStore#putDatabase}/{@link DataStore#removeDatabase}.</p>
 */
public final class DatabasesRoutes {

    /** Сколько строк таблицы показывать в просмотре (как в {@code DbViewerView}). */
    private static final int VIEW_ROW_LIMIT = 200;

    private final DataStore store;
    private final DatabaseManager dbManager;

    public DatabasesRoutes(DataStore store, DatabaseManager dbManager) {
        this.store = store;
        this.dbManager = dbManager;
    }

    /** Регистрирует маршруты БД. */
    public void register(ApiServer server) {
        server.route("/api/databases/action", new ApiServer.ApiRoute() {
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                if (!"POST".equals(method)) return ApiResponse.badRequest("POST required");
                return handleAction(body);
            }
        });
        server.route("/api/databases", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return dispatch(method, body, query);
            }
        });
    }

    synchronized ApiResponse handleAction(String body) {
        Map<String, Object> req = parse(body);
        if (req == null) return ApiResponse.badRequest("JSON required");
        String name = Json.str(req, "name", "");
        Map<String, Object> db = store.getDatabase(name);
        if (db == null) return ApiResponse.notFound("Database not found");
        String path = dbPath(name, db);
        if (path == null || !new java.io.File(path).isFile()) return ApiResponse.badRequest("Database file not found");
        String action = Json.str(req, "action", "");
        Map<String, Object> data = Json.newObject();
        if ("export".equals(action)) {
            data.put("text", SqliteHelper.exportToSql(path)); return ApiResponse.ok(data);
        }
        if ("backup".equals(action)) {
            if (dbManager == null) return ApiResponse.badRequest("Backup manager unavailable");
            String backup = dbManager.backupDatabase("backup_" + java.util.UUID.randomUUID(), path);
            if (backup == null) return ApiResponse.serverError("Backup failed");
            data.put("path", backup); return ApiResponse.ok(data);
        }
        if (Json.bool(db, "preset", false)) return ApiResponse.badRequest("Preset database is read-only");
        if ("sql".equals(action)) {
            String sql = Json.str(req, "sql", "").trim();
            if (sql.isEmpty()) return ApiResponse.badRequest("SQL required");
            if (SqliteHelper.splitSqlStatements(sql).size() != 1) return ApiResponse.badRequest("Execute one statement at a time");
            if (dbManager != null && dbManager.backupDatabase("before_sql_" + java.util.UUID.randomUUID(), path) == null)
                return ApiResponse.serverError("Backup failed; SQL not executed");
            SqliteHelper.QueryResult result = SqliteHelper.executeQuery(path, sql);
            if (!result.success) return ApiResponse.badRequest(result.error);
            data.put("columns", result.columns); data.put("rows", result.rows); data.put("rows_affected", result.rowsAffected);
            return ApiResponse.ok(data);
        }
        if ("import".equals(action)) {
            try {
                if (dbManager != null && dbManager.backupDatabase("before_import_" + java.util.UUID.randomUUID(), path) == null)
                    return ApiResponse.serverError("Backup failed; import cancelled");
                int count = importCsv(path, Json.str(req, "table", ""), Json.str(req, "text", ""), Json.str(req, "mode", "insert"));
                data.put("count", count); return ApiResponse.ok(data);
            } catch (Exception e) { return ApiResponse.badRequest(e.getMessage()); }
        }
        return ApiResponse.badRequest("Unknown database action");
    }

    static int importCsv(String path, String table, String csv, String mode) throws Exception {
        if (!SqliteHelper.getTables(path).contains(table)) throw new IllegalArgumentException("Table not found");
        if (!"insert".equals(mode) && !"update".equals(mode)) throw new IllegalArgumentException("Invalid import mode");
        List<List<String>> rows = parseCsv(csv);
        if (rows.size() < 2) throw new IllegalArgumentException("CSV requires header and data");
        List<String> columns = rows.get(0);
        List<String> actual = SqliteHelper.getTableColumns(path, table);
        if (new java.util.HashSet<String>(columns).size() != columns.size() || !actual.containsAll(columns))
            throw new IllegalArgumentException("Unknown or duplicate CSV column");
        if ("update".equals(mode) && columns.size() < 2) throw new IllegalArgumentException("Update requires key and values");
        List<String> quoted = new java.util.ArrayList<String>();
        for (String column : columns) quoted.add(Utils.quoteIdent(column));
        String sql;
        if ("insert".equals(mode)) {
            List<String> params = new java.util.ArrayList<String>(); for (String c : columns) params.add("?");
            sql = "INSERT INTO " + Utils.quoteIdent(table) + " (" + String.join(",", quoted) + ") VALUES (" + String.join(",", params) + ")";
        } else {
            List<String> setters = new java.util.ArrayList<String>();
            for (int i = 1; i < columns.size(); i++) setters.add(quoted.get(i) + "=?");
            sql = "UPDATE " + Utils.quoteIdent(table) + " SET " + String.join(",", setters) + " WHERE " + quoted.get(0) + "=?";
        }
        try (java.sql.Connection conn = SqliteHelper.open(path)) {
            conn.setAutoCommit(false);
            try (java.sql.PreparedStatement st = conn.prepareStatement(sql)) {
                int count = 0;
                for (int r = 1; r < rows.size(); r++) {
                    List<String> row = rows.get(r);
                    if (row.size() != columns.size()) throw new IllegalArgumentException("CSV width mismatch at row " + (r + 1));
                    for (int i = 0; i < columns.size(); i++) st.setString(i + 1, row.get("insert".equals(mode) ? i : (i + 1) % columns.size()));
                    count += st.executeUpdate();
                }
                conn.commit(); return count;
            } catch (Exception e) { conn.rollback(); throw e; }
        }
    }

    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new java.util.ArrayList<List<String>>();
        List<String> row = new java.util.ArrayList<String>(); StringBuilder field = new StringBuilder(); boolean quoted = false;
        if (csv.startsWith("\uFEFF")) csv = csv.substring(1);
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { row.add(field.toString()); field.setLength(0); }
            else if ((c == '\n' || c == '\r') && !quoted) {
                row.add(field.toString()); field.setLength(0); rows.add(row); row = new java.util.ArrayList<String>();
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
            } else field.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
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

    /** GET: список БД, просмотр одной БД (таблицы+схема) или строки таблицы. */
    ApiResponse handleGet(Map<String, List<String>> query) {
        String name = firstParam(query, "name");
        if (name == null || name.isEmpty()) {
            return handleList();
        }
        Map<String, Object> db = store.getDatabase(name);
        if (db == null) {
            return ApiResponse.notFound("БД не найдена: " + name);
        }
        String table = firstParam(query, "table");
        String dbPath = dbPath(name, db);
        if (table != null && !table.isEmpty()) {
            return handleTable(dbPath, name, table);
        }
        String view = firstParam(query, "view");
        if (view != null && !view.isEmpty()) {
            return handleView(dbPath, name, db);
        }
        return ApiResponse.ok(dbDto(name, db));
    }

    /** Список БД (карточки). */
    ApiResponse handleList() {
        List<Object> out = Json.newArray();
        for (Map.Entry<String, Object> e : store.getDatabases().entrySet()) {
            Map<String, Object> d = Json.asMap(e.getValue());
            if (d == null) {
                continue;
            }
            out.add(dbDto(e.getKey(), d));
        }
        Map<String, Object> data = Json.newObject();
        data.put("databases", out);
        return ApiResponse.ok(data);
    }

    /** Просмотр БД: список таблиц + схема (перенос {@code DbViewerView}). */
    ApiResponse handleView(String dbPath, String name, Map<String, Object> db) {
        Map<String, Object> data = Json.newObject();
        data.put("name", name);
        data.put("path", dbPath);
        data.put("type", Json.str(db, "type", "sqlite"));
        data.put("description", Json.str(db, "description", ""));
        if (dbPath == null || !Utils.exists(dbPath) || !SqliteHelper.isAvailable()) {
            data.put("available", Boolean.FALSE);
            data.put("tables", Json.newArray());
            data.put("schema", "");
            return ApiResponse.ok(data);
        }
        data.put("available", Boolean.TRUE);
        List<String> tables = SqliteHelper.getTables(dbPath);
        List<Object> tableList = Json.newArray();
        for (String t : tables) {
            List<String> cols = SqliteHelper.getTableColumns(dbPath, t);
            List<List<Object>> rows = SqliteHelper.getTableRows(dbPath, t, 1);
            Map<String, Object> td = Json.newObject();
            td.put("name", t);
            td.put("columns", toJsonArray(cols));
            td.put("columns_count", cols.size());
            td.put("has_rows", !rows.isEmpty());
            tableList.add(td);
        }
        data.put("tables", tableList);
        data.put("schema", SqliteHelper.getDbSchema(dbPath));
        return ApiResponse.ok(data);
    }

    /** Строки одной таблицы для просмотра. */
    ApiResponse handleTable(String dbPath, String dbName, String table) {
        Map<String, Object> data = Json.newObject();
        data.put("name", dbName);
        data.put("table", table);
        if (dbPath == null || !Utils.exists(dbPath) || !SqliteHelper.isAvailable()) {
            data.put("available", Boolean.FALSE);
            data.put("columns", Json.newArray());
            data.put("rows", Json.newArray());
            return ApiResponse.ok(data);
        }
        data.put("available", Boolean.TRUE);
        List<String> cols = SqliteHelper.getTableColumns(dbPath, table);
        List<List<Object>> rows = SqliteHelper.getTableRows(dbPath, table, VIEW_ROW_LIMIT);
        data.put("columns", toJsonArray(cols));
        List<Object> rowList = Json.newArray();
        for (List<Object> r : rows) {
            rowList.add(toJsonArray(r));
        }
        data.put("rows", rowList);
        data.put("limit", VIEW_ROW_LIMIT);
        return ApiResponse.ok(data);
    }

    /** POST: регистрирует запись БД (путь к .db или внешняя СУБД). */
    ApiResponse handleCreate(String body) {
        Map<String, Object> req = parse(body);
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String name = Json.str(req, "name", "").trim();
        if (name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        if (store.getDatabase(name) != null) {
            return ApiResponse.badRequest("БД уже существует: " + name);
        }
        Map<String, Object> db = Json.newObject();
        applyFields(db, req, true);
        db.put("name", name);
        db.put("created", Utils.nowIso());
        if (!db.containsKey("schema")) {
            db.put("schema", "");
        }
        store.putDatabase(name, db);
        store.saveAll();
        return ApiResponse.ok(dbDto(name, db), 201);
    }

    /** PUT: обновляет запись БД (описание/enabled/path/type). */
    ApiResponse handleUpdate(String body) {
        Map<String, Object> req = parse(body);
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        String name = Json.str(req, "name", null);
        if (name == null || name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        Map<String, Object> db = Json.asMap(store.getDatabases().get(name));
        if (db == null) {
            return ApiResponse.notFound("БД не найдена: " + name);
        }
        applyFields(db, req, false);
        db.put("updated", Utils.nowIso());
        store.putDatabase(name, db);
        store.saveAll();
        return ApiResponse.ok(dbDto(name, db));
    }

    /** DELETE: удаляет запись БД (файл .db не трогаем — вне зоны ответственности API). */
    ApiResponse handleDelete(String body, Map<String, List<String>> query) {
        String name = null;
        Map<String, Object> req = parse(body);
        if (req != null) {
            name = Json.str(req, "name", null);
        }
        if (name == null || name.isEmpty()) {
            name = firstParam(query, "name");
        }
        if (name == null || name.isEmpty()) {
            return ApiResponse.badRequest("Поле 'name' обязательно.");
        }
        Map<String, Object> db = Json.asMap(store.getDatabases().get(name));
        if (db == null) {
            return ApiResponse.notFound("БД не найдена: " + name);
        }
        if (Json.bool(db, "preset", false)) {
            return ApiResponse.badRequest("Нельзя удалить предустановленную БД: " + name);
        }
        store.removeDatabase(name);
        store.removeDbLogs(name);
        store.saveAll();
        Map<String, Object> data = Json.newObject();
        data.put("name", name);
        data.put("removed", Boolean.TRUE);
        return ApiResponse.ok(data);
    }

    private static void applyFields(Map<String, Object> target, Map<String, Object> req, boolean create) {
        if (create || req.containsKey("path")) {
            target.put("path", Json.str(req, "path", ""));
        }
        if (create || req.containsKey("file")) {
            target.put("file", Json.str(req, "file", ""));
        }
        if (create || req.containsKey("description")) {
            target.put("description", Json.str(req, "description", ""));
        }
        if (create || req.containsKey("type")) {
            target.put("type", Json.str(req, "type", "sqlite"));
        }
        if (create || req.containsKey("enabled")) {
            target.put("enabled", !req.containsKey("enabled") || Json.bool(req, "enabled", true));
        }
        if (create && !target.containsKey("usage_count")) {
            target.put("usage_count", 0);
        }
    }

    /** Разрешает путь к файлу .db: поле записи {@code path}/{@code file} либо от менеджера. */
    private String dbPath(String name, Map<String, Object> db) {
        String p = Json.str(db, "path", "");
        if (p.isEmpty()) {
            p = Json.str(db, "file", "");
        }
        if (!p.isEmpty()) {
            return p;
        }
        return dbManager == null ? null : dbManager.dbPath(name);
    }

    static Map<String, Object> dbDto(String name, Map<String, Object> d) {
        Map<String, Object> dto = Json.newObject();
        dto.put("name", name);
        dto.put("title", Json.str(d, "name", name));
        dto.put("description", Json.str(d, "description", ""));
        dto.put("type", Json.str(d, "type", "sqlite"));
        dto.put("path", Json.str(d, "path", Json.str(d, "file", "")));
        dto.put("schema", Json.str(d, "schema", ""));
        dto.put("tables_count", Json.intVal(d, "tables_count", 0));
        dto.put("records_count", Json.intVal(d, "records_count", 0));
        dto.put("enabled", Json.bool(d, "enabled", true));
        dto.put("preset", Json.bool(d, "preset", false));
        dto.put("usage_count", Json.intVal(d, "usage_count", 0));
        return dto;
    }

    /** Преобразует список значений (строки/колонки) в JSON-массив. */
    private static List<Object> toJsonArray(List<?> values) {
        List<Object> out = Json.newArray();
        for (Object v : values) {
            out.add(v);
        }
        return out;
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

    private static String firstParam(Map<String, List<String>> query, String key) {
        if (query == null) {
            return null;
        }
        List<String> vals = query.get(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }
}
