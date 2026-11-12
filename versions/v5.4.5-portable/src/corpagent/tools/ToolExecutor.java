package corpagent.tools;

import corpagent.data.DataStore;
import corpagent.db.DbUpsertPlan;
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
    private java.util.Set<String> queryDatabases=new java.util.LinkedHashSet<String>();
    private final java.util.Set<String> usedDatabases=new java.util.LinkedHashSet<String>();
    public void setQueryDatabases(java.util.List<String> names){queryDatabases=new java.util.LinkedHashSet<String>(names);}
    public java.util.List<String> getUsedDatabases(){return new java.util.ArrayList<String>(usedDatabases);}
    public boolean allowStructuredWrite(String description){Map<String,Object> info=store.getDatabase(activeDbName);if(info!=null&&Json.bool(info,"preset",false))return false;return writeConfirmation==null||writeConfirmation.allow(description,activeDbName);}
    public void recordStructuredWrite(){recordExecuted("db_execute");usedDatabases.add(activeDbName);}

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
        usedDatabases.clear();
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
        corpagent.util.TaskControl.check();
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

    private String executeHttp(final Map<String,Object> tool, final Map<String,Object> args) throws Exception {
        return corpagent.util.TaskControl.await(new java.util.concurrent.Callable<String>() { public String call() throws Exception { return executeHttpNetwork(tool,args); } });
    }
    private String executeHttpNetwork(Map<String, Object> tool, Map<String, Object> args) throws Exception {
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
        corpagent.util.TaskControl.attach(process);
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
        if(!isReadStatement(query.trim().toUpperCase(java.util.Locale.ROOT))){recordExecuted("db_execute");return executeBuiltinSql("db_execute",java.util.Collections.singletonMap("sql",query));}
        recordExecuted("db_query");
        SqliteHelper.QueryResult qr = SqliteHelper.executeQuery(activeDbPath, query);
        if (!qr.success) {
            return "Ошибка SQL: " + qr.error;
        }
        usedDatabases.add(activeDbName);
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
        corpagent.util.TaskControl.check();
        if (functionName == null) {
            return "Ошибка: пустое имя функции.";
        }
        if (!hasActiveDatabase()) {
            return "Ошибка: не выбрана активная база данных. Выберите БД в чипах над полем ввода.";
        }
        Map<String, Object> args = arguments == null ? new java.util.HashMap<String, Object>() : arguments;
        String requested=Json.str(args,"database",activeDbName==null?"":activeDbName);
        if(!requested.equals(activeDbName)){
            Map<String,Object> info=store.getDatabase(requested);
            if(!queryDatabases.contains(requested)||info==null||!Json.bool(info,"enabled",true))return "База не выбрана для этого чата: "+requested;
            String previousPath=activeDbPath,previousName=activeDbName;
            try{setActiveDatabase(Json.str(info,"path",""),requested);Map<String,Object> routed=new java.util.LinkedHashMap<String,Object>(args);routed.remove("database");return executeBuiltinSql(functionName,routed);}
            finally{setActiveDatabase(previousPath,previousName);}
        }
        if("db_search".equals(functionName)){
            if(corpagent.data.ReferenceSearch.available(activeDbPath)){
                String query=Json.str(args,"text","").trim();if(query.isEmpty()||query.length()>4000)return "Укажите вопрос для поиска (до 4000 символов)";
                String excerpts=corpagent.data.ReferenceSearch.search(activeDbPath,query);usedDatabases.add(activeDbName);
                return excerpts.isBlank()?"Совпадений в материалах методологии не найдено.":"Источник: "+activeDbName+"\n"+excerpts;
            }
            String text=Json.str(args,"text","").trim();if(text.isEmpty()||text.length()>200)return "Укажите короткий текст для поиска (до 200 символов)";
            StringBuilder found=new StringBuilder();int hits=0;
            try(var connection=SqliteHelper.open(activeDbPath)){
                for(String table:SqliteHelper.getTables(activeDbPath)){
                    var columns=SqliteHelper.getTableColumns(activeDbPath,table);if(columns.isEmpty())continue;
                    String where=columns.stream().map(c->"CAST("+Utils.quoteIdent(c)+" AS TEXT) LIKE ?").collect(java.util.stream.Collectors.joining(" OR "));
                    try(var statement=connection.prepareStatement("SELECT * FROM "+Utils.quoteIdent(table)+" WHERE "+where+" LIMIT 10")){
                        for(int i=1;i<=columns.size();i++)statement.setString(i,"%"+text+"%");
                        try(var rows=statement.executeQuery()){while(rows.next()&&hits<20){found.append("Источник: ").append(activeDbName).append("; таблица: ").append(table).append("\n");for(int i=1;i<=columns.size();i++)found.append(columns.get(i-1)).append(": ").append(rows.getString(i)).append("\n");hits++;}}
                    }
                    if(hits>=20)break;
                }
            }catch(Exception e){return "Ошибка поиска: "+e.getMessage();}
            usedDatabases.add(activeDbName);
            return hits==0?"Совпадений по тексту не найдено во всех таблицах выбранной БД.":found.substring(0,Math.min(12000,found.length()));
        }
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
            usedDatabases.add(activeDbName);
            return formatResult(qr);
        }
                                if ("db_execute".equals(functionName)) {
              Map<String, Object> activeInfo = store.getDatabase(activeDbName);
              if (activeInfo != null && Json.bool(activeInfo, "preset", false)) return "Reference database is read-only";
            // Этап 7 (v4): ИИ-план синхронизации (upsert) содержит секцию
            // ###КОНФЛИКТЫ###. Ветку разрешения конфликтов выбираем автоматически
            // и логируем решение (UI-диалог обновления БД проекта удалён).
            if (DbUpsertPlan.isUpsertPlan(sql)) {
                return applyUpsertPlan(sql);
            }
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
            usedDatabases.add(activeDbName);
            return formatResult(qr);
        }
                return "Ошибка: системная функция '" + functionName + "' не найдена.";
    }

    /**
     * Этап 7 (v4): применяет ИИ-план синхронизации (upsert) к активной БД.
     * Разбирает план на отдельные операторы ({@link DbUpsertPlan#statements}),
     * проверяет каждый охранником записи ({@link #guardWrite}) и выполняет.
     * Конфликты (строки проекта, отсутствующие в источнике) разрешаются
     * автоматически — ветка выбирается {@link DbUpsertPlan#autoResolveBranch()}
     * и решение пишется в лог (см. {@link DataStore#addDbLog}).
     */
    private String applyUpsertPlan(String plan) {
        java.util.List<String> statements = DbUpsertPlan.statements(plan);
        if (statements.isEmpty()) {
            return "Нет операторов для применения (план upsert пуст).";
        }
        String conflicts = DbUpsertPlan.extractConflicts(plan);
        int applied = 0;
        StringBuilder errors = new StringBuilder();
        for (String stmt : statements) {
            String upper = stmt.trim().toUpperCase();
            String guardMsg = guardWrite(upper);
            if (guardMsg != null) {
                errors.append("пропущено (").append(guardMsg).append("); ");
                continue;
            }
            WriteConfirmation confirm = writeConfirmation;
            if (confirm != null) {
                String activeDb = activeDbName == null || activeDbName.trim().isEmpty()
                        ? activeDbPath : activeDbName;
                boolean allowed;
                try {
                    allowed = confirm.allow(stmt, activeDb);
                } catch (RuntimeException e) {
                    allowed = false;
                }
                if (!allowed) {
                    errors.append("отклонено пользователем; ");
                    continue;
                }
            }
            SqliteHelper.QueryResult qr = SqliteHelper.executeQuery(activeDbPath, stmt);
            if (!qr.success) {
                errors.append("ошибка SQL: ").append(qr.error).append("; ");
            } else {
                applied++;
            }
        }
        // Логируем автоматический выбор ветки разрешения конфликтов.
        String dbName = activeDbName == null || activeDbName.trim().isEmpty() ? activeDbPath : activeDbName;
        boolean hasConflicts = conflicts != null && !conflicts.trim().isEmpty();
        if (hasConflicts) {
            store.addDbLog(dbName, "Синхронизация БД (upsert)",
                    "Конфликтов: " + conflicts.trim().split("\\r?\\n").length
                    + ", ветка: " + DbUpsertPlan.autoResolveBranchLabel());
        } else {
            store.addDbLog(dbName, "Синхронизация БД (upsert)", "Применено операторов: " + applied);
        }
        StringBuilder sb = new StringBuilder("План upsert применён. Операторов выполнено: ")
                .append(applied).append(".");
        if (hasConflicts) {
            sb.append(" Конфликты разрешены автоматически: ")
              .append(DbUpsertPlan.autoResolveBranchLabel()).append('.');
        }
        if (errors.length() > 0) {
            sb.append(" Предупреждения: ").append(errors.toString().trim());
        }
        return sb.toString();
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
              if(qr.lastInsertId>0)sb.append("\nlast_insert_id: ").append(qr.lastInsertId);
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
                + "Для синхронизации (upsert) из источника можно передать план: блок ```sql``` "
                + "с INSERT/UPDATE и секцию ###КОНФЛИКТЫ### — ветка разрешения конфликтов "
                + "выбирается автоматически. После записи подтвердите пользователю, что изменилось.",
                "UPDATE table SET col='val' WHERE id=..."));
        var search=systemTool("db_search","Поиск короткого текста во всех полях и таблицах выбранной БД. Для поиска методологического правила сначала используй эту функцию; примеры в промпте содержат только первые строки.","text: название правила или ключевая фраза");var searchParams=Json.obj(Json.obj(search,"function"),"parameters");Json.obj(searchParams,"properties").clear();Json.obj(searchParams,"properties").put("text",java.util.Map.of("type","string","description","Короткая ключевая фраза или идентификатор для поиска по содержимому"));searchParams.put("required",java.util.List.of("text"));out.add(search);
        for(var entry:out){var fn=Json.obj(entry,"function");var parameters=Json.obj(fn,"parameters");var properties=Json.obj(parameters,"properties");Map<String,Object> target=Json.newObject();target.put("type","string");target.put("description","Имя выбранной базы данных. Для методологии IT выбирай IT-базу, для LPG — базу LPG; рабочая БД проекта хранит данные проекта. Доступные: "+queryDatabases);java.util.Set<String> available=new java.util.LinkedHashSet<String>(queryDatabases);available.add(activeDbName);target.put("enum",new java.util.ArrayList<String>(available));properties.put("database",target);}
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
