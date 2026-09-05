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
            systemPrompt += "\n\nУ вас есть доступ к базе данных. Структура и данные БД:\n" + dbContext
                    + "\n\nКРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА РАБОТЫ С БД:\n"
                    + "1. АНАЛИЗ ДАННЫХ: Если пользователь просит изучить БД или рассказать о проекте — АНАЛИЗИРУЙТЕ РЕАЛЬНЫЕ ДАННЫЕ из таблиц.\n"
                    + "2. АВТОМАТИЧЕСКОЕ ОБНОВЛЕНИЕ БД: Если пользователь просит изменить данные — ГЕНЕРИРУЙТЕ SQL-запросы в блоках ```sql ... ```. Эти запросы будут автоматически выполнены.\n"
                    + "3. КОНТЕКСТНОЕ НАПОЛНЕНИЕ: Если в разговоре упоминаются новые данные — предлагайте SQL для их добавления.\n"
                    + "4. НЕ СОЗДАВАЙТЕ новую схему БД, если пользователь не просит об этом явно.";
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

                String response;
                boolean toolsActive = toolsContext != null && !toolsContext.isEmpty()
                        && !toolRegistry.buildToolsArray().isEmpty();
                if (toolsActive) {
                    // Function calling: инструменты доступны модели как функции.
                    toolExecutor.setActiveDatabase(dbPath, dbName);
                    toolExecutor.resetExecutedTools();
                    List<Object> tools = toolRegistry.buildToolsArray();
                    response = llm.callWithTools(historyWithContext, systemPrompt, tools,
                            new DeepSeekClient.ToolCallHandler() {
                                @Override
                                public String execute(String name, Map<String, Object> arguments) {
                                    return toolExecutor.execute(name, arguments);
                                }
                            });
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
     */
    public String buildDbContext(List<String> selectedDbs) {
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
            int count = 0;
            for (String table : tables) {
                if (count >= 8) {
                    break;
                }
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
                count++;
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }
}
