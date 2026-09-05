package corpagent.tools;

import corpagent.data.DataStore;
import corpagent.db.SqliteHelper;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Исполнитель инструментов для function calling.
 * Выполняет инструмент по имени с переданными аргументами:
 * <ul>
 *   <li><b>http</b> — реальный HTTP-запрос через HttpURLConnection (подстановка {{arg}} в endpoint/body);</li>
 *   <li><b>python</b> / <b>shell</b> — выполнение команды через ProcessBuilder;</li>
 *   <li><b>sql</b> — выполнение SQL-запроса против активной SQLite-БД;</li>
 *   <li><b>custom</b> — заглушка (описание действия).</li>
 * </ul>
 * После успешного выполнения увеличивает счётчик использования инструмента.
 */
public class ToolExecutor {

    private final DataStore store;
    private final ToolRegistry registry;

    // Активная БД для SQL-инструментов (путь и имя), задаётся извне.
    private String activeDbPath;
    private String activeDbName;

    // Имена реально выполненных инструментов за последний вызов (для визуализации).
    private final java.util.List<String> executedTools = new java.util.ArrayList<String>();

    public ToolExecutor(DataStore store, ToolRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** Задаёт активную БД (для SQL-инструментов). */
    public void setActiveDatabase(String dbPath, String dbName) {
        this.activeDbPath = dbPath;
        this.activeDbName = dbName;
    }

    public String getActiveDbName() {
        return activeDbName;
    }

    /** Сбрасывает список выполненных инструментов (вызывается перед новым вызовом). */
    public void resetExecutedTools() {
        executedTools.clear();
    }

    /** Возвращает список имён инструментов, реально выполненных с момента последнего сброса. */
    public java.util.List<String> getExecutedTools() {
        return new java.util.ArrayList<String>(executedTools);
    }

    /**
     * Выполняет инструмент по нормализованному имени функции.
     *
     * @param functionName нормализованное имя (как в tools-массиве)
     * @param arguments    аргументы (Map имя → значение)
     * @return текстовый результат для передачи модели (role "tool")
     */
    public String execute(String functionName, Map<String, Object> arguments) {
        Map<String, Object> tool = registry.getFunction(functionName);
        if (tool == null) {
            return "Ошибка: инструмент '" + functionName + "' не найден или отключён.";
        }
        String type = Json.str(tool, "type", "http");
        String name = Json.str(tool, "name");
        if (name.isEmpty()) {
            name = functionName;
        }
        String icon = Json.str(tool, "icon", "🔧");

        String result;
        try {
            if ("http".equals(type)) {
                result = executeHttp(tool, arguments);
            } else if ("python".equals(type)) {
                result = executeProcess(tool, arguments, true);
            } else if ("shell".equals(type)) {
                result = executeProcess(tool, arguments, false);
            } else if ("sql".equals(type)) {
                result = executeSql(tool, arguments);
            } else {
                result = "Инструмент типа '" + type + "' выполняется вручную. "
                        + "Описание: " + Json.str(tool, "description")
                        + (arguments == null || arguments.isEmpty() ? "" : " Аргументы: " + Json.stringify(arguments));
            }
        } catch (Exception e) {
            result = "Ошибка выполнения: " + e.getMessage();
        }

        store.bumpToolUsage(name);
        if (!executedTools.contains(name)) {
            executedTools.add(name);
        }
        return icon + " Результат инструмента «" + name + "»:\n" + result;
    }

    // ==================== HTTP ====================

    private String executeHttp(Map<String, Object> tool, Map<String, Object> args) throws Exception {
        String endpoint = substitute(Json.str(tool, "endpoint"), args);
        String method = Json.str(tool, "method", "GET");
        String body = substitute(Json.str(tool, "body"), args);
        if (!endpoint.startsWith("http")) {
            endpoint = "http://" + endpoint;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);

        List<Object> headers = Json.arr(tool, "headers");
        if (headers != null) {
            for (Object hObj : headers) {
                Map<String, Object> h = Json.asMap(hObj);
                if (h == null) {
                    continue;
                }
                String hName = substitute(Json.str(h, "name"), args);
                String hValue = substitute(Json.str(h, "value"), args);
                if (!hName.isEmpty()) {
                    conn.setRequestProperty(hName, hValue);
                }
            }
        }

        if (body != null && !body.isEmpty()
                && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            conn.setDoOutput(true);
            if (conn.getRequestProperty("Content-Type") == null) {
                conn.setRequestProperty("Content-Type", "application/json");
            }
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }

        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readStream(in);
        conn.disconnect();
        if (text.length() > 4000) {
            text = text.substring(0, 4000) + "\n... (обрезано)";
        }
        return "HTTP " + code + "\n" + text;
    }

    // ==================== PYTHON / SHELL ====================

    private String executeProcess(Map<String, Object> tool, Map<String, Object> args, boolean python) throws Exception {
        String command = substitute(Json.str(tool, "endpoint", Json.str(tool, "code")), args);
        if (command == null || command.trim().isEmpty()) {
            return "Ошибка: не задана команда/скрипт.";
        }

        List<String> cmd = new ArrayList<String>();
        if (python) {
            cmd.add("python");
            cmd.add("-c");
            cmd.add(command);
        } else {
            // shell: разбиваем по пробелам (простая эвристика)
            for (String part : command.split("\\s+")) {
                if (!part.isEmpty()) {
                    cmd.add(part);
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readStream(process.getInputStream());
        int exit = process.waitFor();
        String out = output.trim();
        if (out.length() > 4000) {
            out = out.substring(0, 4000) + "\n... (обрезано)";
        }
        return "Код выхода: " + exit + "\n" + out;
    }

    // ==================== SQL ====================

    private String executeSql(Map<String, Object> tool, Map<String, Object> args) {
        if (activeDbPath == null || !Utils.exists(activeDbPath)) {
            return "Ошибка: не выбрана активная база данных для SQL-инструмента. "
                    + "Выберите БД в чипах над полем ввода.";
        }
        String query = substitute(Json.str(tool, "endpoint", Json.str(tool, "code")), args);
        if (query == null || query.trim().isEmpty()) {
            return "Ошибка: не задан SQL-запрос.";
        }
        SqliteHelper.QueryResult qr = SqliteHelper.executeQuery(activeDbPath, query);
        if (!qr.success) {
            return "Ошибка SQL: " + qr.error;
        }
        StringBuilder sb = new StringBuilder();
        if (!qr.columns.isEmpty()) {
            sb.append("Колонки: ").append(String.join(", ", qr.columns)).append("\n");
            int shown = 0;
            for (List<Object> row : qr.rows) {
                if (shown >= 50) {
                    sb.append("... (показано 50 из ").append(qr.rows.size()).append(" строк)\n");
                    break;
                }
                sb.append("  ").append(row).append("\n");
                shown++;
            }
            if (qr.rows.isEmpty()) {
                sb.append("(нет строк)\n");
            }
        } else {
            sb.append("Затронуто строк: ").append(qr.rowsAffected);
        }
        return sb.toString();
    }

    // ==================== ХЕЛПЕРЫ ====================

    /** Подставляет значения аргументов в шаблон {{имя}}. */
    private static String substitute(String template, Map<String, Object> args) {
        if (template == null) {
            return "";
        }
        if (args == null || args.isEmpty()) {
            return template;
        }
        String out = template;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            out = out.replace("{{" + key + "}}", value);
        }
        return out;
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }
}
