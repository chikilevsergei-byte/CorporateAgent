package corpagent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C3.2: разбор протокола встречи в доменную модель проекта.
 *
 * <p>Отвечает за построение инструкций для LLM (что извлекать из текста встречи) и
 * за доменные дефолты схемы проекта ({@code meetings}, {@code meeting_decisions},
 * {@code tasks}). Само извлечение выполняет LLM, а запись в БД идёт через управляемый
 * контур {@code db_execute} (A4, с подтверждением C1.2) — здесь только чистая логика,
 * удобная для тестирования без обращения к сети/БД.</p>
 */
public final class ProtocolExtractor {

    private ProtocolExtractor() {
    }

    /** Таблицы доменной схемы проекта, задействованные при разборе протокола. */
    public static final String MEETINGS_TABLE = "meetings";
    public static final String DECISIONS_TABLE = "meeting_decisions";
    public static final String TASKS_TABLE = "tasks";

    /**
     * Ключевые поля доменной модели, которые должны быть извлечены из протокола.
     * Используется в тестах и как единый источник правды для сопоставления с БД.
     */
    public static final String[] EXTRACTED_FIELDS = {
            "meeting_title",   // тема встречи
            "meeting_date",    // дата (ISO)
            "participants",    // участники
            "summary",         // краткое резюме
            "decisions",       // решения (что решено)
            "assignments",     // поручения (кто/что/срок)
    };

    /**
     * Строит системную инструкцию для LLM по извлечению данных из протокола встречи.
     * Ориентирует модель на доменную схему проекта и порядок записи через db_query/db_execute.
     *
     * @param meetingText текст протокола/заметок встречи
     * @param projectId   идентификатор проекта (или null, если неизвестен)
     * @return текст инструкции для системного промпта
     */
    public static String buildExtractionPrompt(String meetingText, String projectId) {
        String pid = projectId == null || projectId.trim().isEmpty() ? "1" : projectId.trim();
        return "Ты — ассистент руководителя проектов по протоколам встреч. "
                + "Из текста встречи извлеки строго структурированные данные:\n"
                + "1. МЕТАДАННЫЕ ВСТРЕЧИ: тема (title), дата (meeting_date, формат ГГГГ-ММ-ДД), "
                + "участники (participants, через запятую), краткое резюме (summary).\n"
                + "2. РЕШЕНИЯ: список принятых решений (decision, owner, due_date).\n"
                + "3. ПОРУЧЕНИЯ (задачи): список поручений (title — что сделать, assignee — кто, "
                + "due_date — срок ГГГГ-ММ-ДД, priority — high/medium/low). Каждое поручение — "
                + "отдельная задача в таблице tasks.\n\n"
                + "ПОРЯДОК ЗАПИСИ (используй инструменты):\n"
                + "- Сначала проверь схему через db_query (PRAGMA table_info(meetings/tasks)).\n"
                + "- Затем вставь встречу в meetings (project_id=" + pid + ") и получи её id "
                + "(last_insert_id из ответа db_execute). Не вызывай last_insert_rowid() в другом соединении.\n"
                + "- Вставь решения в meeting_decisions (meeting_id = id встречи).\n"
                + "- Для каждого поручения вставь строку в tasks (project_id=" + pid
                + ", status='open', linked_meeting = id встречи).\n"
                + "- Перед завершением прочитай созданные встречи, решения и задачи через db_query. Не заявляй об успехе, если хотя бы одно поручение не записано.\n"
                + "- Не удаляй существующие данные. Для UPDATE/DELETE обязательно указывай WHERE.\n\n"
                + "Текст встречи:\n" + (meetingText == null ? "" : meetingText);
    }

    /**
     * Возвращает пояснительные строки-рекомендации для модели, если активной БД нет:
     * тогда запись невозможна, и ассистент выдаёт разбор текстом + SQL-скрипт.
     */
    public static String buildNoDbContextHint() {
        return "Активная БД проекта не выбрана, поэтому запись невозможна. "
                + "Разбери протокол текстом (метаданные, решения, поручения) и предложи "
                + "SQL-скрипт для meetings/meeting_decisions/tasks в блоке ```sql```, "
                + "чтобы пользователь мог выбрать БД проекта и применить его.";
    }

    /**
     * Парсит текст-ответ модели и возвращает структурированное представление извлечённого
     * протокола. Понимает простой формат «МЕТАДАННЫЕ / РЕШЕНИЯ / ПОРУЧЕНИЯ» со строками
     * «ключ: значение», а также маркированные списки «- ...».
     *
     * <p>Устойчив к свободному тексту: неизвестные строки игнорируются. Возвращаемая карта
     * содержит ключи {@code meeting_title, meeting_date, participants, summary,
     * decisions, assignments}.</p>
     *
     * @param modelText текст ответа LLM
     * @return карта извлечённых полей (никогда null)
     */
    public static Map<String, Object> parseExtraction(String modelText) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("meeting_title", "");
        out.put("meeting_date", "");
        out.put("participants", "");
        out.put("summary", "");
        out.put("decisions", new ArrayList<String>());
        out.put("assignments", new ArrayList<String>());
        if (modelText == null || modelText.trim().isEmpty()) {
            return out;
        }
        List<String> decisions = new ArrayList<String>();
        List<String> assignments = new ArrayList<String>();
        String section = "";
        for (String raw : modelText.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase();
            if (upper.contains("МЕТАДАННЫЕ") && !upper.contains(":")) {
                section = "meta";
                continue;
            }
            if (upper.contains("РЕШЕНИ") && !upper.contains(":")) {
                section = "decisions";
                continue;
            }
            if ((upper.contains("ПОРУЧЕНИ") || upper.contains("ЗАДАЧ")) && !upper.contains(":")) {
                section = "assignments";
                continue;
            }
            // Строки «ключ: значение» для метаданных.
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith("-") && !line.startsWith("•")) {
                String key = line.substring(0, colon).trim().toLowerCase();
                String val = line.substring(colon + 1).trim();
                if (key.contains("тема") || key.contains("title")) {
                    out.put("meeting_title", val);
                } else if (key.contains("дата") || key.contains("date")) {
                    out.put("meeting_date", val);
                } else if (key.contains("участник") || key.contains("participant")) {
                    out.put("participants", val);
                } else if (key.contains("резюме") || key.contains("summary")) {
                    out.put("summary", val);
                }
                continue;
            }
            // Маркированные элементы по текущей секции.
            if (line.startsWith("-") || line.startsWith("•") || line.startsWith("*")) {
                String item = line.substring(1).trim();
                if (item.isEmpty()) {
                    continue;
                }
                if ("decisions".equals(section)) {
                    decisions.add(item);
                } else if ("assignments".equals(section)) {
                    assignments.add(item);
                }
            }
        }
        out.put("decisions", decisions);
        out.put("assignments", assignments);
        return out;
    }

    /** Число извлечённых решений (для краткой сводки в чате). */
    public static int countDecisions(Map<String, Object> parsed) {
        return listSize(parsed.get("decisions"));
    }

    /** Число извлечённых поручений/задач (для краткой сводки в чате). */
    public static int countAssignments(Map<String, Object> parsed) {
        return listSize(parsed.get("assignments"));
    }

    private static int listSize(Object o) {
        return (o instanceof List) ? ((List<?>) o).size() : 0;
    }
}
