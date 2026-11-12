package corpagent.data;

import corpagent.db.SqliteHelper;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Формирует контекст выбранных БД (схема + примеры данных) для промпта LLM.
 *
 * <p>Выделено из {@code Agent}: построение контекста БД — самостоятельная
 * ответственность, зависящая только от {@link DataStore}, {@link SqliteHelper}
 * и утилит. Примеры данных выбираются в первую очередь по таблицам, релевантным
 * сообщению пользователя, иначе — по первым таблицам схемы.</p>
 */
public class DbContextBuilder {

    private final DataStore store;

    public DbContextBuilder(DataStore store) {
        this.store = store;
    }

    /**
     * Формирует контекст БД со схемой и примерами данных для выбранных БД.
     * Примеры данных выбираются в первую очередь по таблицам, релевантным
     * {@code userMessage} (если он не пуст), иначе — по первым таблицам.
     *
     * @return объединённый текст контекста или null, если контекст пуст
     */
    public String build(List<String> selectedDbs, String userMessage) {
        List<String> parts = new ArrayList<String>();
        for (String dbName : selectedDbs) {
            Map<String, Object> dbInfo = store.getDatabase(dbName);
            if (dbInfo == null) {
                continue;
            }
            String dbPath = Json.str(dbInfo, "path");
            if (!Utils.exists(dbPath)) {
                continue;
            }
            String schema = SqliteHelper.getDbSchema(dbPath);
            parts.add("=== База данных: " + dbName + " ===\n" + schema);

            List<String> tables = SqliteHelper.getTables(dbPath);
            int maxTables = 8;
            List<String> chosen = chooseSampleTables(userMessage, dbPath, tables, maxTables);
            for (String table : chosen) {
                Map<String, Object> sample = SqliteHelper.getSampleData(dbPath, table, 5);
                if (sample.containsKey("rows")) {
                    parts.add("\n--- Пример данных из таблицы '" + table + "' ---");
                    @SuppressWarnings("unchecked")
                    List<Object> rows = (List<Object>) sample.get("rows");
                    @SuppressWarnings("unchecked")
                    List<Object> columns = (List<Object>) sample.get("columns");
                    if (!rows.isEmpty()) {
                        List<String> colNames = new ArrayList<String>();
                        for (Object c : columns) {
                            colNames.add(String.valueOf(c));
                        }
                        parts.add("Колонки: " + String.join(", ", colNames));
                        for (Object rowObj : rows) {
                            parts.add("  " + String.valueOf(rowObj));
                        }
                    } else {
                        parts.add("  (таблица пуста)");
                    }
                }
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /**
     * Выбирает до {@code maxTables} таблиц для показа примеров данных.
     * При непустом запросе сначала берутся таблицы, наиболее релевантные ему
     * (по совпадению в имени и именах колонок), затем — по порядку схемы.
     */
    private static List<String> chooseSampleTables(String userMessage, String dbPath,
                                                   List<String> tables, int maxTables) {
        List<String> result = new ArrayList<String>();
        if (tables.size() <= maxTables) {
            return new ArrayList<String>(tables);
        }
        String q = userMessage == null ? "" : userMessage.toLowerCase();
        if (q.trim().isEmpty()) {
            return new ArrayList<String>(tables.subList(0, maxTables));
        }
        // Токены запроса: сочетания букв/цифр/«_», длиной от 3 символов.
        List<String> tokens = new ArrayList<String>();
        Matcher m = Pattern.compile("[\\wа-яёА-ЯЁ]{3,}").matcher(q);
        while (m.find()) {
            tokens.add(m.group().toLowerCase());
        }
        if (tokens.isEmpty()) {
            return new ArrayList<String>(tables.subList(0, maxTables));
        }

        // Плоская строка «таблица: колонки» для оценки.
        List<Map<String, Object>> scored = new ArrayList<Map<String, Object>>();
        for (String table : tables) {
            StringBuilder hay = new StringBuilder(table.toLowerCase());
            for (String col : SqliteHelper.getTableColumns(dbPath, table)) {
                hay.append(' ').append(col.toLowerCase());
            }
            double score = 0.0;
            for (String tok : tokens) {
                if (hay.toString().contains(tok)) {
                    score += 1.0;
                }
            }
            Map<String, Object> e = Json.newObject();
            e.put("table", table);
            e.put("score", Double.valueOf(score));
            scored.add(e);
        }
        // Сначала высокорелевантные, у остальных сохраняем исходный порядок.
        Collections.sort(scored, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Double.compare(((Number) b.get("score")).doubleValue(),
                        ((Number) a.get("score")).doubleValue());
            }
        });
        for (Map<String, Object> e : scored) {
            if (result.size() >= maxTables) {
                break;
            }
            result.add(Json.str(e, "table"));
        }
        return result;
    }
}
