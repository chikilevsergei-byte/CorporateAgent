package corpagent.db;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.io.BufferedReader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Высокоуровневые операции с базами данных: создание БД из SQL-схемы,
 * импорт CSV, бэкапы, авто-выполнение SQL-обновлений из ответов агента.
 */
public class DatabaseManager {

    private final String dbFolder;
    private final String backupFolder;
    private final DataStore store;

    public DatabaseManager(String dbFolder, String backupFolder, DataStore store) {
        this.dbFolder = dbFolder;
        this.backupFolder = backupFolder;
        this.store = store;
        Utils.mkdirs(dbFolder);
        Utils.mkdirs(backupFolder);
    }

    public String dbPath(String dbName) {
        return dbFolder + java.io.File.separator + dbName + ".db";
    }

    /** Создаёт резервную копию БД. Возвращает путь к бэкапу или null. */
    public String backupDatabase(String dbName, String dbPath) {
        if (!Utils.exists(dbPath)) {
            return null;
        }
        String backupPath = backupFolder + java.io.File.separator + dbName + "_" + Utils.timestamp() + ".db";
        return Utils.copyFile(dbPath, backupPath);
    }

    /**
     * Создаёт БД из SQL-схемы, полученной от LLM.
     * Возвращает результат с информацией о созданных таблицах и вставленных записях.
     */
    public static class CreateDbResult {
        public boolean success;
        public String error;
        public List<String> tables = new ArrayList<String>();
        public List<String> failedTables = new ArrayList<String>();
        public int insertedCount;
        public int failedInserts;
        public int totalRecords;
        public String schema;
    }

    public CreateDbResult createDatabase(String dbName, String rawSchema, String description) {
        CreateDbResult result = new CreateDbResult();
        String dbPath = dbPath(dbName);
        if (Utils.exists(dbPath)) {
            Utils.delete(dbPath);
        }

        String schema = SqliteHelper.cleanSqlFromMarkdown(rawSchema);
        schema = schema.replace("AUTO_INCREMENT", "AUTOINCREMENT");
        schema = schema.replace(" INT ", " INTEGER ");
        schema = schema.replace(" INT,", " INTEGER,");
        schema = schema.replace(" INT)", " INTEGER)");
        schema = schema.replace("DATETIME", "TEXT");
        schema = schema.replace("VARCHAR", "TEXT");

        // Удаляем строки с FOREIGN KEY и REFERENCES
        StringBuilder sb = new StringBuilder();
        for (String line : schema.split("\n")) {
            String up = line.toUpperCase();
            if (up.contains("FOREIGN KEY") || up.contains("REFERENCES")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        schema = sb.toString();
        result.schema = schema;

        Connection conn = null;
        try {
                        conn = SqliteHelper.open(dbPath);
            conn.setAutoCommit(false);
            Statement st = conn.createStatement();
            st.execute("PRAGMA foreign_keys = OFF");

            List<String> allStatements = SqliteHelper.splitSqlStatements(schema);
            List<String> createStatements = new ArrayList<String>();
            List<String> insertStatements = new ArrayList<String>();
            for (String s : allStatements) {
                String up = s.trim().toUpperCase();
                if (up.startsWith("CREATE TABLE")) {
                    createStatements.add(s);
                } else if (up.startsWith("INSERT INTO")) {
                    insertStatements.add(s);
                }
            }

            for (String stmt : createStatements) {
                String stmtClean = stmt.trim() + ";";
                String tableName = extractTableName(stmtClean);
                try {
                    st.execute(stmtClean);
                    result.tables.add(tableName);
                } catch (Exception tableErr) {
                    // Пробуем без FOREIGN KEY / REFERENCES построчно
                    StringBuilder noFk = new StringBuilder();
                    for (String line : stmtClean.split("\n")) {
                        String up = line.toUpperCase();
                        if (up.contains("FOREIGN KEY") || up.contains("REFERENCES")) {
                            continue;
                        }
                        noFk.append(line).append("\n");
                    }
                    try {
                        st.execute(noFk.toString().trim());
                        result.tables.add(tableName);
                    } catch (Exception finalErr) {
                        result.failedTables.add(tableName + ": " + finalErr.getMessage());
                    }
                }
            }
            conn.commit();

            for (String insertStmt : insertStatements) {
                String insertClean = insertStmt.trim() + ";";
                try {
                    st.execute("PRAGMA foreign_keys = OFF");
                    st.execute(insertClean);
                    result.insertedCount++;
                } catch (Exception e) {
                    result.failedInserts++;
                }
            }
            conn.commit();

            // Подсчёт записей по таблицам
            for (String table : result.tables) {
                try {
                    java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + Utils.quoteIdent(table));
                    if (rs.next()) {
                        result.totalRecords += rs.getInt(1);
                    }
                    rs.close();
                } catch (Exception e) {
                    // ignore
                }
            }
            st.close();
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.error = e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        if (result.success) {
            Map<String, Object> dbInfo = Json.newObject();
            dbInfo.put("path", dbPath);
            dbInfo.put("description", description == null ? "" : description);
            dbInfo.put("created", Utils.nowIso());
            dbInfo.put("schema", schema);
            dbInfo.put("tables_count", result.tables.size());
            dbInfo.put("records_count", result.totalRecords);
            store.putDatabase(dbName, dbInfo);
            store.addDbLog(dbName, "Создание БД", "Таблиц: " + result.tables.size() + ", Записей: " + result.totalRecords);
            store.saveAll();
        }
        return result;
    }

    private String extractTableName(String createStmt) {
        String s = createStmt.trim();
        int idx = s.indexOf('(');
        if (idx < 0) {
            idx = s.length();
        }
        String head = s.substring(0, idx).replace("CREATE TABLE", "").trim();
        head = head.replace("\"", "").replace("'", "").trim();
        // Убираем IF NOT EXISTS
        head = head.replaceAll("(?i)IF NOT EXISTS", "").trim();
        return head;
    }

    /**
     * Импорт CSV в таблицу. mode: "insert" или "update".
     * Возвращает массив {количество, сообщение}.
     */
    public Object[] importCsvToTable(String dbPath, String tableName, String csvContent, String mode) {
        Connection conn = null;
        try {
                        conn = SqliteHelper.open(dbPath);
            conn.setAutoCommit(false);
            Statement st = conn.createStatement();
            BufferedReader reader = new BufferedReader(new StringReader(csvContent));
            String line;
            List<String[]> rows = new ArrayList<String[]>();
            while ((line = reader.readLine()) != null) {
                rows.add(parseCsvLine(line));
            }
            if (rows.isEmpty()) {
                return new Object[]{0, "Файл пуст"};
            }
            String[] headers = rows.get(0);
            int inserted = 0;
            for (int r = 1; r < rows.size(); r++) {
                String[] row = rows.get(r);
                if (row.length != headers.length) {
                    continue;
                }
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        placeholders.append(",");
                    }
                    placeholders.append("?");
                }
                if ("insert".equals(mode)) {
                    String sql = "INSERT INTO " + Utils.quoteIdent(tableName) + " VALUES (" + placeholders + ")";
                    java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                    for (int i = 0; i < row.length; i++) {
                        ps.setString(i + 1, row[i]);
                    }
                    ps.executeUpdate();
                    ps.close();
                } else {
                    // update: первый столбец — ключ
                    StringBuilder setClause = new StringBuilder();
                    for (int i = 1; i < headers.length; i++) {
                        if (i > 1) {
                            setClause.append(", ");
                        }
                        setClause.append(Utils.quoteIdent(headers[i])).append("=?");
                    }
                    String sql = "UPDATE " + Utils.quoteIdent(tableName) + " SET " + setClause
                            + " WHERE " + Utils.quoteIdent(headers[0]) + "=?";
                    java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                    int paramIdx = 1;
                    for (int i = 1; i < row.length; i++) {
                        ps.setString(paramIdx++, row[i]);
                    }
                    ps.setString(paramIdx, row[0]);
                    ps.executeUpdate();
                    ps.close();
                }
                inserted++;
            }
            conn.commit();
            st.close();
            return new Object[]{inserted, "OK"};
        } catch (Exception e) {
            return new Object[]{0, e.getMessage()};
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    /** Простой парсер CSV-строки с учётом кавычек. */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ';' || c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * Автоматически выполняет SQL-запросы из ответа агента (INSERT/UPDATE/DELETE/ALTER).
     * Возвращает список результатов.
     */
    public List<Map<String, Object>> autoExecuteSqlUpdates(String dbPath, String dbName, String responseText, String userMessage) {
        List<Map<String, Object>> updates = new ArrayList<Map<String, Object>>();
        List<String> sqlQueries = extractSqlFromResponse(responseText);
        if (sqlQueries.isEmpty()) {
            return updates;
        }
        String[] dmlKeywords = {"INSERT", "UPDATE", "DELETE", "ALTER"};
        for (String query : sqlQueries) {
            String queryUpper = query.trim().toUpperCase();
            boolean isDml = Utils.startsWithAny(queryUpper, dmlKeywords);
            if (!isDml) {
                continue;
            }
                        if (queryUpper.startsWith("DELETE") || queryUpper.startsWith("UPDATE")) {
                boolean hasWhere = hasSqlWhereClause(queryUpper);
                if (!hasWhere) {
                    Map<String, Object> m = Json.newObject();
                    m.put("query", query);
                    m.put("status", "skipped");
                    m.put("reason", (queryUpper.startsWith("UPDATE") ? "UPDATE" : "DELETE")
                            + " без WHERE - слишком опасно");
                    updates.add(m);
                    continue;
                }
            }
            SqliteHelper.QueryResult result = SqliteHelper.executeQuery(dbPath, query);
            Map<String, Object> m = Json.newObject();
            m.put("query", query);
            if (result.success) {
                m.put("status", "executed");
                m.put("rows_affected", result.rowsAffected);
                String actionType = queryUpper.split("\\s+")[0];
                store.addDbLog(dbName, "Авто-обновление (" + actionType + ")", "Запрос: " + Utils.truncate(query, 100));
            } else {
                m.put("status", "error");
                m.put("error", result.error);
            }
            updates.add(m);
        }
        return updates;
    }

    /** Чувствительная к кавычкам проверка наличия WHERE в SQL-операторе. */
    private static boolean hasSqlWhereClause(String upperQuery) {
        boolean inString = false;
        for (int i = 0; i < upperQuery.length(); i++) {
            char c = upperQuery.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && upperQuery.startsWith("WHERE", i)) {
                // Учитываем границы слова, чтобы не зацепить, например, "WHEREVER".
                int after = i + 5;
                if (after >= upperQuery.length() || !Character.isLetterOrDigit(upperQuery.charAt(after))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Извлекает SQL-запросы из текста ответа агента. */
    public static List<String> extractSqlFromResponse(String responseText) {
        List<String> sqlQueries = new ArrayList<String>();
        if (responseText == null) {
            return sqlQueries;
        }
        // Блоки ```sql ... ```
        java.util.regex.Pattern sqlBlock = java.util.regex.Pattern.compile("(?is)```sql\\s*(.*?)```");
        java.util.regex.Matcher m = sqlBlock.matcher(responseText);
        while (m.find()) {
            sqlQueries.add(m.group(1));
        }
        // Блоки ``` ... ```, начинающиеся с SQL-ключевых слов
        java.util.regex.Pattern codeBlock = java.util.regex.Pattern.compile("(?s)```\\s*(.*?)```");
        m = codeBlock.matcher(responseText);
        while (m.find()) {
            String block = m.group(1).trim();
            if (Utils.startsWithAny(block.toUpperCase(), new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "CREATE"})) {
                sqlQueries.add(block);
            }
        }
        // Строки, начинающиеся с SQL-ключевых слов и заканчивающиеся ';'
        for (String line : responseText.split("\n")) {
            String clean = line.trim();
            if (Utils.startsWithAny(clean.toUpperCase(), new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "ALTER", "CREATE"})) {
                if (clean.endsWith(";")) {
                    sqlQueries.add(clean);
                }
            }
        }
        List<String> cleaned = new ArrayList<String>();
        for (String query : sqlQueries) {
            String q = query.trim();
            while (q.endsWith(";")) {
                q = q.substring(0, q.length() - 1).trim();
            }
            if (!q.isEmpty() && !cleaned.contains(q)) {
                cleaned.add(q);
            }
        }
        return cleaned;
    }
}
