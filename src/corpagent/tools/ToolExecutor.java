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

    /**
     * Колбэк подтверждения записи в БД (безопасность).
     * Вызывается перед фактическим {@code db_execute}, когда требуется согласие
     * пользователя. Реализацию предоставляет слой GUI, чтобы {@link ToolExecutor}
     * оставался независимым от Swing и вызывающим кодом был только {@code Agent}.
     */
    public interface WriteConfirmation {
        /**
         * Запрашивает разрешение на выполнение записывающего оператора.
         *
         * @param sql      текст SQL-оператора (для показа пользователю в превью)
         * @param activeDb активная БД, против которой пойдёт запись
         * @return true — выполнить запись; false — отклонить
         */
        boolean allow(String sql, String activeDb);
    }

    private final DataStore store;
    private final ToolRegistry registry;

    // Необязательный обработчик подтверждения записи в БД (задаётся слоем GUI).
    private volatile WriteConfirmation writeConfirmation;

    // Активная БД для SQL-инструментов (путь и имя), задаётся извне.
    private String activeDbPath;
    private String activeDbName;

            // Имена реально выполненных инструментов за последний вызов (для визуализации).
    // Потокобезопасен: может заполняться из фонового потока (SwingWorker) LLM-цикла.
    private final java.util.List<String> executedTools =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    public ToolExecutor(DataStore store, ToolRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

        /** Задаёт активную БД (для SQL-инструментов). */
    public void setActiveDatabase(String dbPath, String dbName) {
        this.activeDbPath = dbPath;
        this.activeDbName = dbName;
    }

    /**
     * Задаёт обработчик подтверждения записи в БД.
     * Если задан (не null), {@code db_execute} перед фактическим выполнением
     * запросит через него разрешение. Если колбэк вернёт {@code false}, запись
     * не выполняется и модели возвращается сообщение об отклонении.
     *
     * @param writeConfirmation колбэк подтверждения (может быть null)
     */
    public void setWriteConfirmation(WriteConfirmation writeConfirmation) {
        this.writeConfirmation = writeConfirmation;
    }

    /** Возвращает активный обработчик подтверждения записи (может быть null). */
    public WriteConfirmation getWriteConfirmation() {
        return writeConfirmation;
    }

        /** Возвращает имя активной БД. */
    public String getActiveDbName() {
        return activeDbName;
    }

    /** Учитывает выполнение инструмента (счётчик использования + список для визуализации). */
    public void recordExecuted(String name) {
        store.bumpToolUsage(name);
        synchronized (executedTools) {
            if (!executedTools.contains(name)) {
                executedTools.add(name);
            }
        }
    }

        /** Сбрасывает список выполненных инструментов (вызывается перед новым вызовом). */
    public void resetExecutedTools() {
        synchronized (executedTools) {
            executedTools.clear();
        }
    }

    /** Возвращает список имён инструментов, реально выполненных с момента последнего сброса. */
    public java.util.List<String> getExecutedTools() {
        synchronized (executedTools) {
            return new java.util.ArrayList<String>(executedTools);
        }
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
            boolean pseudoAgent = endpointStartsWithAgent(tool);
            if (pseudoAgent) {
                // Псевдо-endpoint вида "agent:..." у пресет-инструментов означает,
                // что документ генерирует сам ассистент (Excel/PPT по данным БД),
                // а не удалённый HTTP-сервис. Сетевой вызов не выполняем.
                result = handleAgentEndpoint(tool, arguments);
            } else if ("http".equals(type)) {
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
        synchronized (executedTools) {
            if (!executedTools.contains(name)) {
                executedTools.add(name);
            }
        }
        return icon + " Результат инструмента «" + name + "»:\n" + result;
    }

        /** True, если endpoint инструмента — псевдо-команда вида "agent:...". */
    private static boolean endpointStartsWithAgent(Map<String, Object> tool) {
        String ep = Json.str(tool, "endpoint", "");
        return ep != null && ep.trim().startsWith("agent:");
    }

    /**
     * Обработчик псевдо-endpoint "agent:...". Такие инструменты соответствуют
     * встроенным генераторам документов ассистента (Excel/PPT по данным активной БД),
     * а не удалённым HTTP-сервисам. Возвращает модели понятные инструкции, чтобы
     * она создала документ через собственные интенты (Excel/PPT) вместо сетевого вызова.
     */
    private static String handleAgentEndpoint(Map<String, Object> tool, Map<String, Object> args) {
        String name = Json.str(tool, "name");
        String action = Json.str(tool, "endpoint", "").trim();
        if (action.startsWith("agent:")) {
            action = action.substring("agent:".length());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Это встроенный генератор документа ассистента (а не HTTP-сервис). ")
          .append("Чтобы его выполнить, создайте файл напрямую своими средствами генерации Excel/PPT ")
          .append("по данным активной базы данных. ");
        if (args != null && !args.isEmpty()) {
            sb.append("Запрошенные аргументы: ").append(Json.stringify(args)).append(". ");
        }
        sb.append("Действие: ").append(action).append(' ');
        if (name != null && !name.isEmpty()) {
            sb.append("(").append(name).append(")");
        }
        return "agent-action:" + action + " — сформируйте документ через интенты создания Excel/PPT.";
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
        String q = activeDbPath == null ? "" : activeDbPath;
        if (q.isEmpty() || !Utils.exists(activeDbPath)) {
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
        return formatResult(qr);
    }

    /** Есть ли выбранная и существующая активная БД для встроенных db-функций. */
    public boolean hasActiveDatabase() {
        return activeDbPath != null && !activeDbPath.trim().isEmpty() && Utils.exists(activeDbPath);
    }

    /**
     * Выполняет встроенную (системную) функцию управления БД — {@code db_query}
     * (только чтение) или {@code db_execute} (защищённая запись). Иные имена
     * возвращают ошибку «не найдена». Предназначено для вызова из Agent.
     */
    public String executeBuiltinSql(String functionName, Map<String, Object> arguments) {
        if (functionName == null) {
            return "Ошибка: пустое имя функции.";
        }
        if (!hasActiveDatabase()) {
            return "Ошибка: не выбрана активная база данных. Выберите БД в чипах над полем ввода.";
        }
        Map<String, Object> args = arguments == null ? new java.util.HashMap<String, Object>() : arguments;
        Object sqlObj = args.get("sql");
        String sql = sqlObj == null ? "" : String.valueOf(sqlObj);
        if (sql.trim().isEmpty()) {
            return "Ошибка: параметр 'sql' пуст.";
        }
        String upper = sql.trim().toUpperCase();

        if ("db_query".equals(functionName)) {
            if (!isReadStatement(upper)) {
                return "Ошибка: db_query допускает только чтение (SELECT/PRAGMA/WITH/EXPLAIN). "
                        + "Для записи используйте db_execute.";
            }
            SqliteHelper.QueryResult qr = SqliteHelper.executeQuery(activeDbPath, sql);
            if (!qr.success) {
                return "Ошибка SQL: " + qr.error;
            }
            return formatResult(qr);
        }
                if ("db_execute".equals(functionName)) {
            String guardMsg = guardWrite(upper);
            if (guardMsg != null) {
                return "Ошибка: " + guardMsg;
            }
            // Безопасность: при наличии обработчика подтверждения — спрашиваем пользователя,
            // прежде чем фактически изменить данные в БД. Отказ => запись не выполняется.
            WriteConfirmation confirm = writeConfirmation;
            if (confirm != null) {
                String activeDb = activeDbName == null || activeDbName.trim().isEmpty()
                        ? activeDbPath : activeDbName;
                boolean allowed;
                try {
                    allowed = confirm.allow(sql, activeDb);
                } catch (RuntimeException e) {
                    allowed = false;
                }
                if (!allowed) {
                    return "⛔ Запись отклонена пользователем: " + truncateForMsg(sql)
                            + " — не выполнено.";
                }
            }
            SqliteHelper.QueryResult qr = SqliteHelper.executeQuery(activeDbPath, sql);
            if (!qr.success) {
                return "Ошибка SQL: " + qr.error;
            }
            return formatResult(qr);
        }
        return "Ошибка: системная функция '" + functionName + "' не найдена.";
    }

    /** Читающие операторы, разрешённые db_query. */
    private static boolean isReadStatement(String upper) {
        return upper.startsWith("SELECT") || upper.startsWith("PRAGMA")
                || upper.startsWith("WITH") || upper.startsWith("EXPLAIN");
    }

    /** Возвращает текст причины отклонения записи, если команда небезопасна (иначе null). */
    private static String guardWrite(String upper) {
        String first = firstKeyword(upper);
        // Массовые обновления/удаления без WHERE — запрещено.
        if (first.equals("UPDATE") || first.equals("DELETE")) {
            if (!hasSqlWhere(upper)) {
                return (first.equals("UPDATE") ? "UPDATE" : "DELETE")
                        + " без WHERE запрещён (массовое изменение). Уточните условие.";
            }
        }
        // Запрещаем управление транзакциями и подключением в db_execute.
        if (first.equals("BEGIN") || first.equals("COMMIT") || first.equals("ROLLBACK")
                || first.equals("PRAGMA")) {
            return first + " не допускается через db_execute (dbo).";
        }
        return null;
    }

    /** Первое ключевое слово оператора (до пробела/скобки), без учёта регистра. */
    private static String firstKeyword(String upper) {
        StringBuilder kw = new StringBuilder();
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (Character.isLetter(c)) {
                kw.append(c);
            } else {
                break;
            }
        }
        return kw.toString();
    }

    /** Чувствительная к кавычкам проверка наличия WHERE в операторе. */
    private static boolean hasSqlWhere(String upperQuery) {
        boolean inString = false;
        for (int i = 0; i < upperQuery.length(); i++) {
            char c = upperQuery.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && startsAt(upperQuery, i, "WHERE")) {
                int after = i + 5;
                if (after >= upperQuery.length() || !Character.isLetterOrDigit(upperQuery.charAt(after))) {
                    return true;
                }
            }
        }
        return false;
    }

        private static boolean startsAt(String s, int idx, String token) {
        return s.regionMatches(idx, token, 0, token.length());
    }

    /** Обрезает SQL для краткого сообщения отмены/превью. */
    private static String truncateForMsg(String sql) {
        if (sql == null) {
            return "";
        }
        String t = sql.trim().replace('\n', ' ');
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

        /** Форматирует результат запроса в компактный текст для модели.
     *  Показываем не более {@link #MAX_ROWS_SHOWN} строк — результаты уходят
     *  в контекст LLM на каждой итерации function calling, поэтому объём держим
     *  минимальным (модель может запросить агрегат/конкретные строки при нужде). */
    private static final int MAX_ROWS_SHOWN = 10;

    private static String formatResult(SqliteHelper.QueryResult qr) {
        StringBuilder sb = new StringBuilder();
        if (!qr.columns.isEmpty()) {
            sb.append("Колонки: ").append(String.join(", ", qr.columns)).append("\n");
            int shown = 0;
            for (List<Object> row : qr.rows) {
                if (shown >= MAX_ROWS_SHOWN) {
                    sb.append("... (показано ").append(MAX_ROWS_SHOWN)
                      .append(" из ").append(qr.rows.size()).append(" строк)\n");
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

    /**
     * Возвращает описания встроенных функций {@code db_query} и {@code db_execute}
     * для передачи в tools-массив (формат OpenAI function). Пусто, если нет активной БД.
     * Актуальные схемы/описания построены так, чтобы модель могла сначала прочитать,
     * затем точечно записать.
     */
    public List<java.util.Map<String, Object>> systemDbTools() {
        java.util.List<java.util.Map<String, Object>> out = new ArrayList<java.util.Map<String, Object>>();
        if (!hasActiveDatabase()) {
            return out;
        }
        String dbDesc = " (БД: " + (activeDbName == null ? activeDbPath : activeDbName) + ")";
        out.add(systemTool("db_query",
                "[" + dbDesc + "] Выполнить ЗАПРОС ТОЛЬКО НА ЧТЕНИЕ в активной БД. "
                + "Используйте для просмотра схемы (PRAGMA table_info(...)), списка таблиц и данных (SELECT). "
                + "Параметр sql — единственный SQL-оператор чтения.",
                "SELECT ...; или PRAGMA ..."));
        out.add(systemTool("db_execute",
                "[" + dbDesc + "] Выполнить БЕЗОПАСНУЮ ЗАПИСЬ в активной БД (INSERT/UPDATE/DELETE/CREATE). "
                + "UPDATE и DELETE обязаны иметь WHERE. Для чтения используйте db_query. "
                + "После записи подтвердите пользователю, что изменилось.",
                "UPDATE table SET col='val' WHERE id=..."));
        return out;
    }

    private static java.util.Map<String, Object> systemTool(String name, String description, String example) {
        java.util.Map<String, Object> entry = Json.newObject();
        entry.put("type", "function");
        java.util.Map<String, Object> fn = Json.newObject();
        fn.put("name", name);
        fn.put("description", description + " Пример: " + example);
        java.util.Map<String, Object> parameters = Json.newObject();
        parameters.put("type", "object");
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<String, Object>();
        java.util.Map<String, Object> sqlProp = Json.newObject();
        sqlProp.put("type", "string");
        sqlProp.put("description", "Один валидный SQL-оператор на языке SQLite.");
        props.put("sql", sqlProp);
        parameters.put("properties", props);
        java.util.List<String> req = new ArrayList<String>();
        req.add("sql");
        parameters.put("required", req);
        fn.put("parameters", parameters);
        entry.put("function", fn);
        return entry;
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
