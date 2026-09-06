package corpagent;

import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.files.FileCreator;
import corpagent.llm.DeepSeekClient;
import corpagent.tools.ToolExecutor;
import corpagent.tools.ToolRegistry;
import corpagent.util.Config;
import corpagent.util.Json;
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

        String messageLower = fullMessage.toLowerCase();
        String detectedType = "chat";
        if (messageLower.contains("презентаци") || messageLower.contains("pptx")) {
            detectedType = "presentation";
        } else if (messageLower.contains("excel") || messageLower.contains("таблиц")) {
            detectedType = "excel";
        } else if ((messageLower.contains("создай") || messageLower.contains("создать"))
                && messageLower.contains("баз") && messageLower.contains("данны")) {
            detectedType = "database";
        } else {
            detectedType = "chat";
        }

        try {
            if ("presentation".equals(detectedType)) {
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
            } else if ("excel".equals(detectedType)) {
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
            } else if ("database".equals(detectedType)) {
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
        // БД
        if (dbName != null && !dbName.isEmpty()) {
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
            Map<String, Object> u = Json.newObject();
            u.put("kind", "tool");
            u.put("name", name);
            u.put("icon", "🔧");
            result.usage.add(u);
        }
        // Если инструменты были выбраны, но ни один не выполнен — не показываем их как применённые.
        // (toolsContext используется только для активации function calling.)
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
}
