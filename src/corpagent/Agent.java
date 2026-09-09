package corpagent;

import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.files.FileCreator;
import corpagent.llm.DeepSeekClient;
import corpagent.tools.ToolExecutor;
import corpagent.tools.ToolRegistry;
import corpagent.util.Config;
import corpagent.util.IntentRouter;
import corpagent.util.Json;
import corpagent.util.TokenBudget;
import corpagent.util.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ядро ИИ-агента: обрабатывает задачи (чат, презентация, Excel, БД),
 * формирует контекст БД/навыков/инструментов, авто-обновляет БД.
 * Аналог process_task и связанных функций в v2.
 */
public class Agent {

    private final Config config;
    private final DataStore store;
    private final DatabaseManager dbManager;
    private final FileCreator fileCreator;
    private final DeepSeekClient llm;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    public Agent(Config config, DataStore store, DatabaseManager dbManager, FileCreator fileCreator) {
        this.config = config;
        this.store = store;
        this.dbManager = dbManager;
        this.fileCreator = fileCreator;
        this.llm = new DeepSeekClient(config);
        this.toolRegistry = new ToolRegistry(store);
        this.toolExecutor = new ToolExecutor(store, toolRegistry);
    }

        public DeepSeekClient getLlm() {
        return llm;
    }

    /**
     * Задаёт обработчик подтверждения записи в БД для встроенной функции
     * {@code db_execute}. Делегируется исполнителю инструментов. Если задан —
     * любая запись в БД будет требовать явного согласия пользователя (безопасность).
     */
    public void setDbWriteConfirmation(ToolExecutor.WriteConfirmation confirmation) {
        toolExecutor.setWriteConfirmation(confirmation);
    }

    /** Возвращает имя используемой модели. */
    public String getModel() {
        return config.getModel();
    }

    /** Оценочный бюджет истории в «токенах»: в LLM уходит только хвост диалога
     *  в пределах этой границы, более ранние сообщения отбрасываются.
     *  Эвристика: токены ≈ длина_в_символах / 3.5. */
    private static final double HISTORY_TOKEN_BUDGET = 20000.0;
    private static final double CHARS_PER_TOKEN = 3.5;

    /**
     * Обрезает историю, оставляя лишь новейшие сообщения в рамках
     * {@link #HISTORY_TOKEN_BUDGET}. Последнее сообщение всегда сохраняется.
     * В начало обрезанного списка добавляется заметка о потере раннего контекста.
     * Исходный список не изменяется.
     */
    private static List<Map<String, Object>> trimHistory(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        // Идём с конца (новейшие) и копим «токены» до исчерпания бюджета.
        double acc = 0.0;
        int keepFrom = history.size(); // индекс первого сохраняемого сообщения
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> m = history.get(i);
            String role = Json.str(m, "role", "");
            Object contentObj = m.get("content");
            if (contentObj == null) {
                contentObj = m.get("text");
            }
            double weight;
            if ("tool".equals(role)) {
                // Результаты вызовов инструментов почти не режем — важны для цепи вызовов.
                weight = 250.0;
            } else {
                String text = contentObj == null ? "" : String.valueOf(contentObj);
                weight = text.length() / CHARS_PER_TOKEN;
            }
            double next = acc + weight;
            // Последнее сообщение сохраняем всегда, даже если оно больше бюджета.
            if (next > HISTORY_TOKEN_BUDGET && i < history.size() - 1) {
                break;
            }
            acc = next;
            keepFrom = i;
        }
        if (keepFrom <= 0) {
            return history;
        }
        List<Map<String, Object>> trimmed = new ArrayList<Map<String, Object>>();
        for (int i = keepFrom; i < history.size(); i++) {
            trimmed.add(history.get(i));
        }
        Map<String, Object> note = Json.newObject();
        note.put("role", "user");
        note.put("content", "[Более ранние сообщения разговора опущены для экономии объёма контекста. "
                + "При необходимости освежите ключевые факты в этом сообщении.]");
        trimmed.add(0, note);
        return trimmed;
    }

    /** Результат обработки задачи. */
    public static class TaskResult {
        public String response;
        public String filePath;   // путь к созданному файлу (если есть)
        public List<Map<String, Object>> dbUpdates = new ArrayList<Map<String, Object>>();
        // Сведения об использовании БД/навыков/инструментов для визуализации в чате.
        public List<Map<String, Object>> usage = new ArrayList<Map<String, Object>>();
    }

    /**
     * Обрабатывает сообщение пользователя.
     *
     * @param message       текст сообщения
     * @param history       история сообщений (без текущего)
     * @param fileContent   содержимое загруженных файлов (может быть null)
     * @param dbContext     контекст БД (может быть null)
     * @param skillsContext контекст навыков (может быть null)
     * @param toolsContext  контекст инструментов (может быть null)
     * @param dbPath        путь к активной БД (может быть null)
     * @param dbName        имя активной БД (может быть null)
     * @param autoUpdateDb  авто-обновление БД
     */
    public TaskResult processTask(String message, List<Map<String, Object>> history, String fileContent,
                                  String dbContext, String skillsContext, String toolsContext,
                                  String dbPath, String dbName, boolean autoUpdateDb) {
        TaskResult result = new TaskResult();
        if (history == null) {
            history = new ArrayList<Map<String, Object>>();
        }

        String systemPrompt = "Ты — корпоративный ИИ-ассистент. Отвечай структурированно на русском языке.";
        if (skillsContext != null && !skillsContext.isEmpty()) {
            systemPrompt += "\n\nАктивные навыки:\n" + skillsContext;
        }
        if (toolsContext != null && !toolsContext.isEmpty()) {
            systemPrompt += "\n\nАктивные инструменты:\n" + toolsContext;
        }
        if (dbContext != null && !dbContext.isEmpty()) {
                        systemPrompt += "\n\nУ вас есть доступ к базе данных. Структура и данные БД (обзор):\n" + dbContext
                    + "\n\nКРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА РАБОТЫ С БД:\n"
                    + "1. АНАЛИЗ ДАННЫХ: При вопросе о данных — СНАЧАЛА проверяйте фактические данные "
                    + "инструментами db_query (SELECT/PRAGMA и т.п.), не полагайтесь только на обзор выше.\n"
                    + "2. ИЗМЕНЕНИЯ: Для любого изменения данных используйте ТОЛЬКО функцию db_execute "
                    + "(INSERT/UPDATE/DELETE/CREATE). В UPDATE и DELETE ОБЯЗАТЕЛЬНО указывайте WHERE.\n"
                    + "3. ПОРЯДОК ЗАПИСИ: сначала db_query (посмотреть структуру/текущие данные), затем "
                    + "точечный db_execute, после чего подтвердите пользователю, какие строки/что изменилось.\n"
                    + "4. НЕ создавайте новую схему БД, если пользователь явно этого не просит.";
        }

        String fullMessage = message;
        if (fileContent != null && !fileContent.isEmpty()) {
            fullMessage = message + "\n\n[Содержимое загруженного файла]:\n" + fileContent;
        }

                // Классификация интента вынесена в IntentRouter (C1.1): поведение прежнее,
                // но детекция стала переиспользуемой и удобно-тестируемой.
                IntentRouter.Intent intent = IntentRouter.detect(fullMessage);

        try {
            // Суточный лимит токенов (завершение A1): если расход дня исчерпан,
            // коротко останавливаем обращения к LLM и сообщаем пользователю.
            if (TokenBudget.isExhaustedToday()) {
                result.response = "⛔ Дневной лимит токенов израсходован "
                        + "(" + TokenBudget.formatTokens(TokenBudget.tokensToday())
                        + " из лимита " + TokenBudget.formatTokens(TokenBudget.getDailyLimit())
                        + ").\n\nОткройте «⚙️ Настройки», чтобы повысить или сбросить лимит "
                        + "(вкладка «Интерфейс»).";
                return result;
            }
            if (intent == IntentRouter.Intent.PRESENTATION) {
                String prompt = "Создай структуру презентации по запросу: " + fullMessage
                        + "\nФормат:\nЗаголовок слайда 1\nТекст слайда 1\n---SLIDE---\nЗаголовок слайда 2\nТекст слайда 2\n---SLIDE---\nСоздай 5-7 слайдов.";
                String content = llm.call(singleUser(prompt), systemPrompt);
                if (content.startsWith("❌")) {
                    result.response = content;
                    return result;
                }
                String filePath = fileCreator.createPptx(content, null);
                if (filePath.startsWith("ОШИБКА")) {
                    result.response = "❌ " + filePath;
                    return result;
                }
                result.response = "✅ Презентация создана!\n\n" + Utils.truncate(content, 500);
                result.filePath = filePath;
                return result;
            } else if (intent == IntentRouter.Intent.EXCEL) {
                String prompt = "Создай данные для Excel по запросу: " + fullMessage
                        + "\nФормат: ячейки через ; строки через перенос.";
                String data = llm.call(singleUser(prompt), systemPrompt);
                if (data.startsWith("❌")) {
                    result.response = data;
                    return result;
                }
                String filePath = fileCreator.createExcel(data, null);
                if (filePath.startsWith("ОШИБКА")) {
                    result.response = "❌ " + filePath;
                    return result;
                }
                result.response = "✅ Excel создан!\n\n" + Utils.truncate(data, 500);
                result.filePath = filePath;
                return result;
                        } else if (intent == IntentRouter.Intent.DATABASE) {
                // Если к запросу прикреплены файлы — реально создаём БД по их содержимому.
                                if (fileContent != null && !fileContent.trim().isEmpty()) {
                    String createdDbName = guessDbNameFromRequest(fullMessage);
                    if (createdDbName == null) {
                        createdDbName = "db_" + System.currentTimeMillis();
                    }
                    String desc = Utils.truncate(message, 200);
                    DatabaseManager.CreateDbResult cr =
                            createDatabaseFromFiles(createdDbName, desc, fileContent);
                    if (!cr.success) {
                        result.response = "❌ Не удалось создать БД из файлов: " + cr.error;
                        return result;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("✅ База данных «").append(createdDbName).append("» создана из загруженных файлов!\n")
                      .append("Таблиц: ").append(cr.tables.size()).append("\n")
                      .append("Записей: ").append(cr.totalRecords).append("\n\n")
                      .append("Путь: ").append(dbManager.dbPath(createdDbName)).append("\n")
                      .append("Она добавлена в список баз — выберите её в чипах БД над полем ввода.");
                    result.response = sb.toString();
                    collectUsage(result, createdDbName, null, null);
                    return result;
                }
                // Без файлов — прежнее поведение: вернуть текстом сгенерированную схему.
                String prompt = "Создай SQL-схему БД по запросу: " + fullMessage + "\nТолько CREATE TABLE.";
                String schema = llm.call(singleUser(prompt), systemPrompt);
                if (schema.startsWith("❌")) {
                    result.response = schema;
                    return result;
                }
                result.response = "✅ Схема БД:\n\n```sql\n" + schema + "\n```";
                return result;
            } else {
                List<Map<String, Object>> historyWithContext = new ArrayList<Map<String, Object>>(history);
                if (dbContext != null && !dbContext.isEmpty()) {
                    String analysisInstruction = fullMessage
                            + "\n\nИНСТРУКЦИЯ: \n1. Изучите предоставленную структуру базы данных и примеры данных.\n"
                            + "2. Если пользователь просит изменить данные — сгенерируйте SQL-запросы в формате ```sql ... ```\n"
                            + "3. Эти запросы будут автоматически выполнены в базе данных.\n"
                            + "4. После выполнения запросов — подтвердите изменения и покажите обновленные данные.";
                    Map<String, Object> userMsg = Json.newObject();
                    userMsg.put("role", "user");
                    userMsg.put("text", analysisInstruction);
                    historyWithContext.add(userMsg);
                } else {
                    Map<String, Object> userMsg = Json.newObject();
                    userMsg.put("role", "user");
                    userMsg.put("text", fullMessage);
                    historyWithContext.add(userMsg);
                }

                // Не отправляем всю историю целиком: ограничиваем объём контекста
                // новейшими сообщениями (старые отбрасываются по бюджету токенов).
                historyWithContext = trimHistory(historyWithContext);

                                String response;
                // Зафиксировали расход до запроса, чтобы показать затраты именно этого ответа.
                long preTokens = TokenBudget.tokensToday();
                // Function calling активен при выбранной БД или включённых инструментах.
                // Активная БД дополнительно подключает управляемые функции db_query/db_execute (A2):
                // модель читает схему/данные и пишет точечно вместо хрупкого ```sql```-парсинга.
                boolean dbPathOk = dbPath != null && !dbPath.trim().isEmpty() && Utils.exists(dbPath);
                boolean dbActiveTool = dbPathOk && dbName != null;
                boolean userToolsOn = toolsContext != null && !toolsContext.isEmpty()
                        && !toolRegistry.buildToolsArray().isEmpty();
                boolean toolsActive = dbActiveTool || userToolsOn;
                if (toolsActive) {
                    toolExecutor.setActiveDatabase(dbPath, dbName);
                    toolExecutor.resetExecutedTools();
                    List<Object> tools = new ArrayList<Object>();
                    if (userToolsOn) {
                        tools.addAll(toolRegistry.buildToolsArray());
                    }
                    if (dbActiveTool) {
                        tools.addAll(toolExecutor.systemDbTools());
                    }
                    response = llm.callWithTools(historyWithContext, systemPrompt, tools,
                            new DeepSeekClient.ToolCallHandler() {
                                @Override
                                public String execute(String name, Map<String, Object> arguments) {
                                    if ("db_query".equals(name) || "db_execute".equals(name)) {
                                        toolExecutor.recordExecuted(name);
                                        return toolExecutor.executeBuiltinSql(name, arguments);
                                    }
                                    return toolExecutor.execute(name, arguments);
                                }
                            });
                    // Запись уже могла быть выполнена управляемой функцией db_execute,
                    // поэтому повторный авто-парсинг ```sql``` из текста ответа отключаем.
                    autoUpdateDb = false;
                } else {
                    response = llm.call(historyWithContext, systemPrompt);
                }
                                result.response = response;

                // Затраты токенов именно этого ответа (показ в чате + сводный расход).
                long spent = TokenBudget.tokensToday() - preTokens;
                if (spent > 0) {
                    Map<String, Object> u = Json.newObject();
                    u.put("kind", "token");
                    u.put("icon", "⚡");
                    u.put("name", TokenBudget.formatTokens(spent)
                            + " · всего " + TokenBudget.formatMoney(TokenBudget.costTodayUsd()));
                    result.usage.add(u);
                }

                // Визуализация использования: какие БД/навыки/инструменты были задействованы.
                collectUsage(result, dbName, skillsContext, toolsContext);

                if (dbPath != null && dbName != null && autoUpdateDb) {
                    result.dbUpdates = dbManager.autoExecuteSqlUpdates(dbPath, dbName, response, message);
                }
                return result;
            }
        } catch (Exception e) {
            result.response = "❌ Ошибка: " + e.getMessage();
            return result;
        }
    }

    /**
     * Собирает сведения об использовании БД/навыков/инструментов для визуализации в чате.
     * Заполняет {@code result.usage} записями вида {kind, name, icon}.
     */
        private void collectUsage(TaskResult result, String dbName,
                              String skillsContext, String toolsContext) {
        // БД показывается, только если она реально затронута запросом чтения/записи
        // (db_query / db_execute), а не просто выбрана в чипах.
        if (dbName != null && !dbName.isEmpty() && isActiveDbUsed()) {
            Map<String, Object> u = Json.newObject();
            u.put("kind", "db");
            u.put("name", dbName);
            u.put("icon", "🗄️");
            result.usage.add(u);
        }
        // Навыки (выбранные — инжектятся в system prompt, считаются применёнными)
        for (String name : parseContextNames(skillsContext)) {
            Map<String, Object> u = Json.newObject();
            u.put("kind", "skill");
            u.put("name", name);
            u.put("icon", "🧠");
            result.usage.add(u);
        }
        // Инструменты: показываем только реально выполненные через function calling.
        for (String name : toolExecutor.getExecutedTools()) {
            // Системные db-функции уже учитываются строкой об использовании БД выше.
            if ("db_query".equals(name) || "db_execute".equals(name)) {
                continue;
            }
            Map<String, Object> u = Json.newObject();
            u.put("kind", "tool");
            u.put("name", name);
            u.put("icon", "🔧");
            result.usage.add(u);
        }
        // Если инструменты были выбраны, но ни один не выполнен — не показываем их как применённые.
        // (toolsContext используется только для активации function calling.)
    }

    /** Признак того, что активная БД действительно участвовала через db_query/db_execute. */
    private boolean isActiveDbUsed() {
        for (String name : toolExecutor.getExecutedTools()) {
            if ("db_query".equals(name) || "db_execute".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Извлекает имена из контекста вида "- имя: описание" (по строке на элемент). */
    private static List<String> parseContextNames(String context) {
        List<String> names = new ArrayList<String>();
        if (context == null || context.isEmpty()) {
            return names;
        }
        for (String line : context.split("\n")) {
            String t = line.trim();
            if (t.startsWith("- ")) {
                String name = t.substring(2).trim();
                int colon = name.indexOf(':');
                if (colon > 0) {
                    name = name.substring(0, colon).trim();
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private List<Map<String, Object>> singleUser(String text) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        Map<String, Object> m = Json.newObject();
        m.put("role", "user");
        m.put("text", text);
        list.add(m);
        return list;
    }

    /** Генерирует короткое название чата по первому сообщению. */
    public String generateChatTitle(String firstMessage) {
        try {
            String titlePrompt = "Придумай короткое название (2-4 слова) для чата: "
                    + Utils.truncate(firstMessage, 100);
            String autoTitle = llm.call(singleUser(titlePrompt), "");
            if (autoTitle.startsWith("❌")) {
                return Utils.truncate(firstMessage, 50);
            }
            String t = autoTitle.trim();
            return t.length() > 50 ? t.substring(0, 50) : t;
        } catch (Exception e) {
            return Utils.truncate(firstMessage, 50);
        }
    }

    /**
     * Формирует контекст БД со схемой и примерами данных для выбранных БД.
     * Примеры данных выбираются в первую очередь по таблицам, релевантным
     * {@code userMessage} (если он не пуст), иначе — по первым таблицам.
     */
    public String buildDbContext(List<String> selectedDbs, String userMessage) {
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
        java.util.List<String> tokens = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\wа-яёА-ЯЁ]{3,}")
                .matcher(q);
        while (m.find()) {
            tokens.add(m.group().toLowerCase());
        }
        if (tokens.isEmpty()) {
            return new ArrayList<String>(tables.subList(0, maxTables));
        }

        // Плоская строка «таблица: колонки» для оценки.
        java.util.List<java.util.Map<String, Object>> scored =
                new ArrayList<java.util.Map<String, Object>>();
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
            java.util.Map<String, Object> e = Json.newObject();
            e.put("table", table);
            e.put("score", Double.valueOf(score));
            scored.add(e);
        }
        // Сначала высокорелевантные, у остальных сохраняем исходный порядок.
        java.util.Collections.sort(scored, new java.util.Comparator<java.util.Map<String, Object>>() {
            @Override
            public int compare(java.util.Map<String, Object> a, java.util.Map<String, Object> b) {
                return Double.compare(((Number) b.get("score")).doubleValue(),
                        ((Number) a.get("score")).doubleValue());
            }
        });
        for (java.util.Map<String, Object> e : scored) {
            if (result.size() >= maxTables) {
                break;
            }
                        result.add(Json.str(e, "table"));
        }
        return result;
    }

    /**
     * Создаёт БД из содержимого загруженных файлов.
     * ИИ по объединённому тексту файлов строит SQLite-схему {@code CREATE TABLE}
     * (+ стартовые {@code INSERT} для табличных данных), после чего создаётся
     * реальный локальный файл {@code databases/<dbName>.db}.
     *
     * @param dbName             целевое имя БД (валидное, латиница/цифры/_-)
     * @param description        описание/назначение БД (или null)
     * @param combinedFilesText  объединённое текстовое содержимое загруженных файлов
     * @return результат создания БД
     */
    public DatabaseManager.CreateDbResult createDatabaseFromFiles(String dbName,
            String description, String combinedFilesText) {
        DatabaseManager.CreateDbResult failed = new DatabaseManager.CreateDbResult();
        if (combinedFilesText == null || combinedFilesText.trim().isEmpty()) {
            failed.success = false;
            failed.error = "Нет текстового содержимого файлов для построения схемы.";
            return failed;
        }
        String desc = description == null ? "" : description.trim();
        String prompt = "Создай SQLite-схему базы данных по содержимому загруженных файлов."
                + (desc.isEmpty() ? "" : " Назначение БД: " + desc)
                + "\nВерни ТОЛЬКО SQL-операторы. Для каждой сущности добавь CREATE TABLE: "
                + "поле id INTEGER PRIMARY KEY AUTOINCREMENT; строковые значения — TEXT, "
                + "числа — INTEGER/REAL, даты/время — TEXT (ISO). "
                + "НЕ добавляй FOREIGN KEY и REFERENCES. "
                + "Если в файлах есть табличные данные (CSV или XLSX и т.п.) — после соответствующих "
                + "CREATE TABLE добавь стартовые INSERT INTO ... (все строки фактических данных)."
                + "\n\n[Содержимое файлов]:\n" + combinedFilesText;
        String schema;
        try {
            schema = llm.call(singleUser(prompt), "");
        } catch (Exception e) {
            failed.success = false;
            failed.error = "Ошибка генерации схемы ИИ: " + e.getMessage();
            return failed;
        }
        if (schema.startsWith("❌")) {
            failed.success = false;
            failed.error = schema;
            return failed;
        }
        // Схема содержит CREATE TABLE + при необходимости INSERT — создаём реальный .db.
        return dbManager.createDatabase(dbName, schema, description);
    }

        /**
     * Пытается извлечь валидное имя БД (латиница/цифры/_-) из текста запроса вида
     * «создай базу данных имя» / «база данных vendors» / «БД team».
     * Учитывает необязательное слово «данных» между «база/БД» и именем.
     * Если не найдено по фразе — возвращает первое валидное латинское слово из запроса.
     */
    private static String guessDbNameFromRequest(String request) {
        if (request == null) {
            return null;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?i)(?:баз[а-я]*|бд)"
                + "(?:\\s*[—–\\-]?\\s*данн[а-яЁё]*)?"
                + "\\s*[\"“«`]?([a-z][a-z0-9_\\-]{1,39})");
        java.util.regex.Matcher m = p.matcher(request);
        while (m.find()) {
            String n = m.group(1);
            if (Utils.isValidDbName(n)) {
                return n;
            }
        }
        // Запасной вариант: первое валидное латинское слово запроса (например, имя в отдельном слове).
        java.util.regex.Matcher w = java.util.regex.Pattern
                .compile("[a-zA-Z][a-zA-Z0-9_\\-]{2,39}").matcher(request);
        while (w.find()) {
            String t = w.group();
            if (Utils.isValidDbName(t)) {
                return t;
            }
        }
        return null;
    }
}
