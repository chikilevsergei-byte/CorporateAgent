package corpagent.db;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Этап 7 (v4): разбор и автоматическое разрешение ИИ-плана синхронизации (upsert)
 * БД проекта. Логика перенесена из удалённого Swing-диалога
 * {@code UpdateProjectDbDialog} в независимый от UI класс, чтобы её мог
 * использовать инструмент {@code db_execute} (задача 7.2).
 *
 * <p>Модель возвращает план в текстовом виде: блок {@code ```sql```} с
 * INSERT/UPDATE-операторами (добавление/обновление строк) и секцию
 * {@code ###КОНФЛИКТЫ###} с перечнем строк, которые есть в проекте, но отсутствуют
 * в источнике (кандидаты на удаление). Пользовательского выбора больше нет —
 * ветка разрешения конфликтов выбирается автоматически ({@link #autoResolveBranch()})
 * и решение пишется в лог.</p>
 */
public final class DbUpsertPlan {

    /** Маркер начала секции конфликтов в ответе модели. */
    public static final String CONFLICTS_MARKER = "###КОНФЛИКТЫ###";

    private DbUpsertPlan() {
    }

    /**
     * Ветка разрешения конфликтов синхронизации: что делать со строками, которые
     * есть в БД проекта, но отсутствуют в источнике.
     */
    public enum Branch {
        /** Оставить существующие записи (безопасно, значение по умолчанию). */
        KEEP,
        /** Удалить строки, отсутствующие в источнике. */
        DELETE
    }

    /**
     * Автоматический выбор ветки при конфликте. По умолчанию — {@link Branch#KEEP}
     * (безопасно: автоматическое удаление данных без подтверждения недопустимо).
     * Решение логируется вызывающим кодом.
     */
    public static Branch autoResolveBranch() {
        return Branch.KEEP;
    }

    /**
     * Человекочитаемое описание автоматически выбранной ветки — для записи в лог.
     */
    public static String autoResolveBranchLabel() {
        switch (autoResolveBranch()) {
            case DELETE:
                return "удаление отсутствующих записей";
            case KEEP:
            default:
                return "сохранение существующих записей (удаление не выполняется)";
        }
    }

    /** Извлекает содержимое первого блока {@code ```sql ... ```} или текст до маркера конфликтов. */
    public static String extractSqlBlock(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?is)```sql\\s*(.*?)```").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        int idx = text.indexOf(CONFLICTS_MARKER);
        return idx >= 0 ? text.substring(0, idx).trim() : text.trim();
    }

    /** Извлекает секцию конфликтов после маркера {@code ###КОНФЛИКТЫ###}. */
    public static String extractConflicts(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf(CONFLICTS_MARKER);
        if (idx < 0) {
            return "";
        }
        String rest = text.substring(idx + CONFLICTS_MARKER.length()).trim();
        // Обрезаем возможный закрывающий блок кода.
        return rest.replace("```", "").trim();
    }

    /** True, если текст содержит ИИ-план upsert (секцию конфликтов). */
    public static boolean isUpsertPlan(String text) {
        return text != null && text.contains(CONFLICTS_MARKER);
    }

    /**
     * Строит SQL-удаления из строк секции конфликтов формата
     * {@code <таблица> | id=<id> | <описание>}. Безопасно пропускает некорректные строки.
     */
    public static String buildDeleteSql(String conflicts) {
        StringBuilder sb = new StringBuilder();
        for (String line : conflicts.split("\\r?\\n")) {
            String t = line.trim().replaceAll("^[-*•]\\s*", "");
            if (t.isEmpty() || t.startsWith("Конфликт") || t.startsWith("(") || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\|");
            if (parts.length < 2) {
                continue;
            }
            String table = parts[0].trim().replace('`', ' ');
            Matcher idm = Pattern.compile("id\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(t);
            if (idm.find() && table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                sb.append("DELETE FROM ").append(table)
                  .append(" WHERE id=").append(idm.group(1)).append(";\n");
            }
        }
        return sb.toString();
    }

    /**
     * Разрешает конфликты автоматически: возвращает SQL удаления, если выбранная
     * ветка — {@link Branch#DELETE}, иначе пустую строку (записи сохраняются).
     */
    public static String autoConflictSql(String conflicts) {
        if (conflicts == null || conflicts.trim().isEmpty()) {
            return "";
        }
        return autoResolveBranch() == Branch.DELETE ? buildDeleteSql(conflicts) : "";
    }

    /**
     * Полный SQL-скрипт для применения плана upsert: INSERT/UPDATE из блока
     * {@code ```sql```} плюс автоматически определённый скрипт удаления конфликтов.
     */
    public static String buildApplyScript(String plan) {
        String sql = extractSqlBlock(plan);
        String conflicts = extractConflicts(plan);
        String deleteSql = autoConflictSql(conflicts);
        return (sql + "\n" + deleteSql).trim();
    }

    /**
     * Разбивает план на отдельные SQL-операторы (INSERT/UPDATE из основного блока
     * и, при ветке DELETE, операторы удаления конфликтов).
     */
    public static List<String> statements(String plan) {
        List<String> out = new ArrayList<String>();
        for (String st : SqliteHelper.splitSqlStatements(buildApplyScript(plan))) {
            String s = st.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
