package corpagent;

import corpagent.data.DataStore;
import corpagent.data.DbContextBuilder;
import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.files.FileCreator;
import corpagent.llm.DeepSeekClient;
import corpagent.tools.ToolExecutor;
import corpagent.tools.ToolRegistry;
import corpagent.util.Config;
import corpagent.util.IntentRouter;
import corpagent.util.Json;
import corpagent.util.ProtocolExtractor;
import corpagent.util.StatusReport;
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
    private final DbContextBuilder dbContextBuilder;

    public Agent(Config config, DataStore store, DatabaseManager dbManager, FileCreator fileCreator) {
        this.config = config;
        this.store = store;
        this.dbManager = dbManager;
        this.dbContextBuilder = new DbContextBuilder(store);
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

    /**
     * Строит системный промпт для чата по активным контекстам (навыки/инструменты/БД).
     * Вынесено из {@link #processTask}, чтобы стриминговый чат использовал тот же
     * контракт системного промпта. Чистая функция (без LLM/БД).
     */
    public static String buildSystemPrompt(String skillsContext, String toolsContext, String dbContext) {
        String systemPrompt = "Ты — корпоративный ИИ-ассистент. Отвечай структурированно на русском языке.";
        if (skillsContext != null && !skillsContext.isEmpty()) {
            systemPrompt += "\n\nАктивные навыки:\n" + skillsContext;
        }
        if (toolsContext != null && !toolsContext.isEmpty()) {
            systemPrompt += "\n\nАктивные инструменты:\n" + toolsContext;
        }
        if (dbContext != null && !dbContext.isEmpty()) {
            systemPrompt += "\n\nУ вас есть доступ к базе данных. Структура и данные БД (обзор):\n" + dbContext;
        }
        return systemPrompt;
    }

    /**
     * Стриминговый ответ чата: отправляет историю в LLM в режиме SSE и дёргает
     * {@code handler} на каждый токен. Инструменты не вызываются (стрим — текстовый
     * путь). История обрезается тем же бюджетом, что и в {@link #processTask}.
     *
     * @param history       история сообщений (включая текущее сообщение пользователя)
     * @param systemPrompt  системный промпт (см. {@link #buildSystemPrompt})
     * @param handler       обработчик токенов
     * @return полный текст ответа (или сообщение об ошибке, начинающееся с "❌")
     */
    public String chatStream(List<Map<String, Object>> history, String systemPrompt,
                             DeepSeekClient.StreamHandler handler) {
        if (TokenBudget.isExhaustedToday()) {
            String msg = "⛔ Дневной лимит токенов израсходован. Попробуйте позже.";
            if (handler != null) {
                handler.onDone(msg);
            }
            return msg;
        }
        List<Map<String, Object>> trimmed = trimHistory(history, systemPrompt);
        return llm.callStreaming(trimmed, systemPrompt, handler);
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
     *
     * @param history      история сообщений
     * @param systemPrompt системный промпт: его объём вычитается из бюджета,
     *                     чтобы суммарный контекст не превышал границу
     */
    private static List<Map<String, Object>> trimHistory(List<Map<String, Object>> history,
                                                         String systemPrompt) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        // Бюджет на саму историю = общий бюджет минус стоимость системного промпта.
        double budget = HISTORY_TOKEN_BUDGET;
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            budget -= systemPrompt.length() / CHARS_PER_TOKEN;
        }
        if (budget <= 0) {
            budget = 0;
        }
        // Идём с конца (новейшие) и копим «токены» до исчерпания бюджета.
        double acc = 0.0;
        int keepFrom = history.size(); // индекс первого сохраняемого сообщения
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> m = history.get(i);
            Object contentObj = m.get("content");
            if (contentObj == null) {
                contentObj = m.get("text");
            }
            String text = contentObj == null ? "" : String.valueOf(contentObj);
            double weight = text.length() / CHARS_PER_TOKEN;
            // Учитываем и полезную нагрузку tool_calls (аргументы инструментов).
            Object toolCalls = m.get("tool_calls");
            if (toolCalls != null) {
                weight += String.valueOf(toolCalls).length() / CHARS_PER_TOKEN;
            }
            double next = acc + weight;
            // Последнее сообщение сохраняем всегда, даже если оно больше бюджета.
            if (next > budget && i < history.size() - 1) {
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
                // Для инструмента «Создание презентации … Супер Ленты» используем фирменный корп.
                // шаблон (папка Шаблоны/): мастер/тема применяется, слайды-примеры убираются.
                // Остальные PPT-генерации (например «Создание статуса в PPT») идут как раньше — без шаблона.
                String template = isSuperLentaPresentationTool(toolsContext)
                        ? fileCreator.getCorporatePresentationTemplate() : null;
                String filePath = fileCreator.createPptx(content, template);
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
                // C2.2: для пресет-инструментов Excel используем корп. шаблон из Шаблоны/
                // (паспорт проекта / реестр рисков), данные дописываются под шапкой шаблона.
                String excelTemplate = fileCreator.getCorporateExcelTemplate(excelTemplateKeys(toolsContext));
                String filePath = fileCreator.createExcel(data, excelTemplate);
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
            } else if (intent == IntentRouter.Intent.PROTOCOL) {
                // C3.2: разбор протокола встречи в meetings/meeting_decisions/tasks.
                // При активной БД — через управляемый контур db_query/db_execute (запись
                // подтверждается в UI). Без активной БД — только разбор текстом + SQL-скрипт.
                String pid = currentProjectId(dbContext);
                systemPrompt += "\n\n" + ProtocolExtractor.buildExtractionPrompt(fullMessage, pid);
                if (dbPath == null || dbPath.trim().isEmpty() || !Utils.exists(dbPath) || dbName == null) {
                    systemPrompt += "\n\n" + ProtocolExtractor.buildNoDbContextHint();
                }
                                // Дальше — общий путь чата/инструментов (единая точка для БД + tool-контура).
                                return runConversation(result, history, fullMessage, systemPrompt, dbContext,
                                        skillsContext, toolsContext, dbPath, dbName, autoUpdateDb);
            } else if (intent == IntentRouter.Intent.STATUS) {
                                // C3.3: статус-отчёт «сделано / осталось / фокус» (агрегация tasks).
                                String pid = currentProjectId(dbContext);
                                systemPrompt += "\n\n" + StatusReport.buildStatusPrompt(pid);
                                if (dbPath == null || dbPath.trim().isEmpty() || !Utils.exists(dbPath) || dbName == null) {
                                    systemPrompt += "\n\n" + StatusReport.buildNoDbContextHint();
                                }
                                TaskResult sr = runConversation(result, history, fullMessage, systemPrompt, dbContext,
                                        skillsContext, toolsContext, dbPath, dbName, autoUpdateDb);
                                // Краткая сводка по распознанным числам (сделано/осталось/фокус) — в чат.
                                String summary = StatusReport.formatSummary(StatusReport.parseCounts(sr.response));
                                if (!summary.isEmpty()) {
                                    sr.response = sr.response + "\n\n" + summary;
                                }
                                return sr;
            } else {
                                // Свободный диалог / анализ (CHAT): общий путь с контекстом БД и инструментами.
                                return runConversation(result, history, fullMessage, systemPrompt, dbContext,
                                        skillsContext, toolsContext, dbPath, dbName, autoUpdateDb);
            }
        } catch (Exception e) {
            result.response = "❌ Ошибка: " + e.getMessage();
            return result;
        }
    }

    /**
     * Общий путь диалога: формирует сообщение с контекстом БД, обрезает историю и вызывает
     * LLM — с function calling (когда активна БД и/или включены инструменты) либо без него.
     * Используется ветками CHAT и PROTOCOL, чтобы не дублировать tool-контур (C3.2).
     */
    private TaskResult runConversation(TaskResult result, List<Map<String, Object>> history,
            String fullMessage, String systemPrompt, String dbContext, String skillsContext,
            String toolsContext, String dbPath, String dbName, boolean autoUpdateDb) {
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
        historyWithContext = trimHistory(historyWithContext, systemPrompt);

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
            result.dbUpdates = dbManager.autoExecuteSqlUpdates(dbPath, dbName, response, fullMessage);
        }
        return result;
    }

    /**
     * C3.2: пытается определить идентификатор проекта (для полей project_id при записи
     * протокола) из контекста БД. Пока контекст БД не содержит id проекта напрямую,
     * возвращает null — функция-заглушка с заделом на будущее расширение.
     */
    private static String currentProjectId(String dbContext) {
        return null;
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

        /** Признак того, что выбран PPT-инструмент, строящий презентацию по корп. шаблону Супер Ленты.
     *  Учитываются и текущее (новое) имя, и унаследованное «Создание презентации к УК в PPT»:
     *  в уже сохранённых данных (tools.json) имя может остаться прежним до обновления пресета. */
    private static boolean isSuperLentaPresentationTool(String toolsContext) {
        if (toolsContext == null || toolsContext.isEmpty()) {
            return false;
        }
        String lower = toolsContext.toLowerCase();
        // Новое имя инструмента («…в шаблоне Супер Ленты») либо унаследованное «…к УК».
        return lower.contains("создание презентации в шаблоне супер ленты")
                || lower.contains("создание презентации в шаблоне супер")
                                || lower.contains("создание презентации к ук")
                || lower.contains("создание презентации в шаблоне");
    }

    /**
     * C2.2: ключи корп. Excel-шаблона по выбранному инструменту. Для пресет-инструментов
     * «Создание паспорта проекта в Excel» / «Создание реестра рисков в Excel» возвращает
     * фрагменты имени файла-шаблона в {@code Шаблоны/}. Для остальных — пустой массив,
     * что означает создание Excel без шаблона.
     */
    private static String[] excelTemplateKeys(String toolsContext) {
        if (toolsContext == null || toolsContext.isEmpty()) {
            return new String[0];
        }
        String lower = toolsContext.toLowerCase();
        if (lower.contains("паспорт")) {
            return new String[]{"паспорт"};
        }
        if (lower.contains("реестр") || lower.contains("риск")) {
            return new String[]{"реестр рисков", "реестр", "риск"};
        }
        return new String[0];
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
     * Делегируется {@link DbContextBuilder} (примеры данных выбираются в первую
     * очередь по таблицам, релевантным {@code userMessage}).
     */
    public String buildDbContext(List<String> selectedDbs, String userMessage) {
        return dbContextBuilder.build(selectedDbs, userMessage);
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
         * R-P1.9.6: строит план синхронизации (upsert) БД проекта ИИ-сопоставлением.
         * На вход — текущее содержимое БД проекта (схема + данные) и содержимое источника
         * (данные другой БД или текст файлов). Модель возвращает план в текстовом виде:
         * блок {@code ```sql```} с INSERT/UPDATE-операторами для добавления/обновления строк и
         * секцию {@code ###КОНФЛИКТЫ###} с перечнем строк, которые есть в проекте, но отсутствуют
         * в источнике (кандидаты на удаление — решение принимает пользователь).
         *
         * @param projectDbContext текущее содержимое БД проекта (схема + данные)
         * @param sourceContent    содержимое источника синхронизации
         * @return текст плана синхронизации или сообщение об ошибке (начинается с «❌»)
         */
        public String generateDbUpsertPlan(String projectDbContext, String sourceContent) {
            String prompt = "Ты — специалист по синхронизации данных. Есть БАЗА ДАННЫХ ПРОЕКТА (текущее) "
                    + "и ИСТОЧНИК (новое). Построй план синхронизации источника в БД проекта "
                    + "через сопоставление записей (по смыслу/ключевым полям).\n"
                    + "ПРАВИЛА:\n"
                    + "1. Для совпавших записей, у которых изменились поля — UPDATE ... WHERE id=<id>.\n"
                    + "2. Для новых записей — INSERT INTO ... (укажи все необходимые поля; project_id сохраняй существующий, если есть).\n"
                    + "3. НЕ удаляй ничего сам — вместо этого перечисли кандидатов на удаление "
                    + "(записи, которые есть в проекте, но отсутствуют в источнике) в отдельной секции "
                    + "после строки ###КОНФЛИКТЫ### в формате: <таблица> | id=<id> | <краткое описание строки>\n"
                    + "4. Верни сначала блок ```sql ... ``` со всеми INSERT/UPDATE, затем секцию ###КОНФЛИКТЫ###.\n"
                    + "\n[ТЕКУЩАЯ БД ПРОЕКТА]:\n" + projectDbContext
                    + "\n\n[ИСТОЧНИК (новое)]:\n" + sourceContent;
            try {
                return llm.call(singleUser(prompt), "");
            } catch (Exception e) {
                return "❌ Ошибка построения плана синхронизации: " + e.getMessage();
            }
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
