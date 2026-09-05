package corpagent.db;

import corpagent.util.Utils;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Обёртка над SQLite (JDBC). Требует библиотеку sqlite-jdbc в classpath.
 * Реализует: выполнение запросов, чтение схемы, примеры данных, данные таблиц,
 * импорт CSV, экспорт в SQL, разбиение SQL на операторы.
 */
public class SqliteHelper {

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Библиотека sqlite-jdbc не найдена в classpath. Положите sqlite-jdbc.jar в папку lib/", e);
        }
    }

    private SqliteHelper() {
    }

    public static Connection open(String dbPath) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /** Результат выполнения запроса. */
    public static class QueryResult {
        public boolean success;
        public List<String> columns = new ArrayList<String>();
        public List<List<Object>> rows = new ArrayList<List<Object>>();
        public int rowsAffected;
        public String error;
    }

    /**
     * Выполняет один SQL-запрос. Если запрос возвращает строки (SELECT/PRAGMA и т.п.) —
     * возвращает колонки и строки, иначе — количество затронутых строк.
     */
    public static QueryResult executeQuery(String dbPath, String query) {
        QueryResult result = new QueryResult();
        Connection conn = null;
        try {
            conn = open(dbPath);
            Statement st = conn.createStatement();
            boolean hasResultSet = st.execute(query);
            if (hasResultSet) {
                ResultSet rs = st.getResultSet();
                ResultSetMetaData md = rs.getMetaData();
                int colCount = md.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    result.columns.add(md.getColumnLabel(i));
                }
                while (rs.next()) {
                    List<Object> row = new ArrayList<Object>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    result.rows.add(row);
                }
                rs.close();
            } else {
                result.rowsAffected = st.getUpdateCount();
            }
            st.close();
            conn.commit();
            result.success = true;
        } catch (Exception e) {
            result.success = false;
            result.error = e.getMessage();
        } finally {
            closeQuietly(conn);
        }
        return result;
    }

    /** Получает список таблиц БД (без служебных sqlite_*). */
    public static List<String> getTables(String dbPath) {
        List<String> tables = new ArrayList<String>();
        Connection conn = null;
        try {
            conn = open(dbPath);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'");
            while (rs.next()) {
                String name = rs.getString(1);
                if (!name.startsWith("sqlite_")) {
                    tables.add(name);
                }
            }
            rs.close();
            st.close();
        } catch (Exception e) {
            // ignore
        } finally {
            closeQuietly(conn);
        }
        return tables;
    }

    /** Возвращает схему БД в текстовом виде (таблицы, колонки, количество записей). */
    public static String getDbSchema(String dbPath) {
        StringBuilder schema = new StringBuilder();
        Connection conn = null;
        try {
            conn = open(dbPath);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'");
            List<String> tables = new ArrayList<String>();
            while (rs.next()) {
                String name = rs.getString(1);
                if (!name.startsWith("sqlite_")) {
                    tables.add(name);
                }
            }
            rs.close();
            for (String table : tables) {
                schema.append("\nТаблица: ").append(table).append("\n");
                ResultSet pr = st.executeQuery("PRAGMA table_info(" + Utils.quoteIdent(table) + ")");
                while (pr.next()) {
                    schema.append("  - ").append(pr.getString(2)).append(" (").append(pr.getString(3)).append(")\n");
                }
                pr.close();
                ResultSet cr = st.executeQuery("SELECT COUNT(*) FROM " + Utils.quoteIdent(table));
                if (cr.next()) {
                    schema.append("  Записей: ").append(cr.getInt(1)).append("\n");
                }
                cr.close();
            }
            st.close();
        } catch (Exception e) {
            return "Ошибка чтения схемы: " + e.getMessage();
        } finally {
            closeQuietly(conn);
        }
        return schema.toString();
    }

    /** Возвращает пример данных из таблицы (первые limit строк). */
    public static Map<String, Object> getSampleData(String dbPath, String tableName, int limit) {
        QueryResult qr = executeQuery(dbPath, "SELECT * FROM " + Utils.quoteIdent(tableName) + " LIMIT " + limit);
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        if (qr.success) {
            out.put("columns", qr.columns);
            out.put("rows", qr.rows);
        } else {
            out.put("error", qr.error);
        }
        return out;
    }

    /** Возвращает колонки и строки таблицы (первые limit строк). */
    public static List<String> getTableColumns(String dbPath, String tableName) {
        List<String> columns = new ArrayList<String>();
        Connection conn = null;
        try {
            conn = open(dbPath);
            Statement st = conn.createStatement();
            ResultSet pr = st.executeQuery("PRAGMA table_info(" + Utils.quoteIdent(tableName) + ")");
            while (pr.next()) {
                columns.add(pr.getString(2));
            }
            pr.close();
            st.close();
        } catch (Exception e) {
            // ignore
        } finally {
            closeQuietly(conn);
        }
        return columns;
    }

    public static List<List<Object>> getTableRows(String dbPath, String tableName, int limit) {
        QueryResult qr = executeQuery(dbPath, "SELECT * FROM " + Utils.quoteIdent(tableName) + " LIMIT " + limit);
        return qr.success ? qr.rows : new ArrayList<List<Object>>();
    }

    /** Экспортирует БД в SQL-скрипт (аналог iterdump). */
    public static String exportToSql(String dbPath) {
        StringBuilder sb = new StringBuilder();
        Connection conn = null;
        try {
            conn = open(dbPath);
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type IN ('table','index','trigger','view') AND sql IS NOT NULL");
            while (rs.next()) {
                sb.append(rs.getString(1)).append(";\n");
            }
            rs.close();

            for (String table : getTables(dbPath)) {
                QueryResult data = executeQuery(dbPath, "SELECT * FROM " + Utils.quoteIdent(table));
                if (!data.success || data.rows.isEmpty()) {
                    continue;
                }
                for (List<Object> row : data.rows) {
                    sb.append("INSERT INTO ").append(Utils.quoteIdent(table)).append(" VALUES (");
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        Object v = row.get(i);
                        if (v == null) {
                            sb.append("NULL");
                        } else if (v instanceof Number) {
                            sb.append(v.toString());
                        } else {
                            sb.append("'").append(String.valueOf(v).replace("'", "''")).append("'");
                        }
                    }
                    sb.append(");\n");
                }
            }
            st.close();
        } catch (Exception e) {
            return "Ошибка экспорта: " + e.getMessage();
        } finally {
            closeQuietly(conn);
        }
        return sb.toString();
    }

    /**
     * Разбивает SQL-текст на отдельные операторы по ';', не разрезая строковые литералы
     * (в одинарных кавычках ';' не считается разделителем).
     */
    public static List<String> splitSqlStatements(String sqlText) {
        List<String> statements = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        String text = sqlText == null ? "" : sqlText;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                if (inString && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    current.append(ch);
                    current.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                inString = !inString;
                current.append(ch);
            } else if (ch == ';' && !inString) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
            } else {
                current.append(ch);
            }
            i++;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    /** Удаляет markdown-обёртку ```sql ... ``` из текста. */
    public static String cleanSqlFromMarkdown(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        t = t.replaceAll("(?is)^```(?:sql)?\\s*", "");
        t = t.replaceAll("(?is)\\s*```$", "");
        return t.trim();
    }

    private static void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /** Проверяет доступность sqlite-jdbc. */
    public static boolean isAvailable() {
        try {
            Class.forName("org.sqlite.JDBC");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean fileExists(String dbPath) {
        return new File(dbPath).exists();
    }
}
