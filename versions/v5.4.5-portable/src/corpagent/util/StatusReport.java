package corpagent.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C3.3: отчёт «сделано / осталось / фокус» по доменной таблице {@code tasks}.
 *
 * <p>Строит инструкцию для LLM (какие агрегаты взять из БД проекта) и разбирает
 * числовые показатели из ответа модели для краткой сводки в чате. Чистая логика,
 * пригодная для тестирования без сети/БД. Сами запросы выполняет модель через
 * контур {@code db_query} (A4).</p>
 */
public final class StatusReport {

    private StatusReport() {
    }

    /** Статусы задач доменной схемы проекта. */
    public static final String STATUS_OPEN = "open";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_DONE = "done";

    /**
     * Строит инструкцию для LLM по формированию статус-отчёта проекта.
     * Ориентирует модель на агрегацию таблицы {@code tasks} и выделение фокуса.
     *
     * @param projectId идентификатор проекта (или null)
     * @return текст инструкции для системного промпта
     */
    public static String buildStatusPrompt(String projectId) {
        String pid = projectId == null || projectId.trim().isEmpty() ? "1" : projectId.trim();
        return "Ты — ассистент руководителя проектов по статус-отчёту. "
                + "Сформируй отчёт по таблице tasks (project_id=" + pid + ") в трёх блоках:\n"
                + "1. СДЕЛАНО: число задач со status='done' и краткий список ключевых.\n"
                + "2. ОСТАЛОСЬ: число задач с status IN ('open','in_progress'); разбей по статусам.\n"
                + "3. ФОКУС: задачи, требующие внимания — открытые с priority='high' или "
                + "с близким/просроченным due_date (сегодня/раньше).\n\n"
                + "ПОРЯДОК (используй инструмент db_query, только чтение):\n"
                + "- SELECT status, COUNT(*) AS cnt FROM tasks WHERE project_id=" + pid
                + " GROUP BY status;\n"
                + "- SELECT id, title, assignee, priority, due_date FROM tasks "
                + "WHERE project_id=" + pid + " AND status<>'done' ORDER BY priority, due_date;\n"
                + "НЕ изменяй данные. Заверши отчёт кратким резюме: «Сделано N, осталось M, в фокусе K».";
    }

    /**
     * Подсказка, когда активной БД нет: отчёт невозможен без данных, ассистент
     * просит выбрать БД проекта.
     */
    public static String buildNoDbContextHint() {
        return "Активная БД проекта не выбрана, поэтому агрегация по tasks невозможна. "
                + "Попроси пользователя выбрать БД проекта (чипы над полем ввода) и повторить запрос, "
                + "либо предложи SQL-агрегацию в блоке ```sql``` для самостоятельного применения.";
    }

    /**
     * Пытается извлечь числовые показатели статус-отчёта из текста ответа модели:
     * число сделанных и оставшихся задач и число задач в фокусе. Понимает как
     * итоговое резюме «Сделано N, осталось M, в фокусе K», так и отдельные числа
     * рядом со словами «сделано/осталось/фокус».
     *
     * @param modelText текст ответа LLM
     * @return карта {done, left, focus} (значения Integer, -1 если не найдено)
     */
    public static Map<String, Integer> parseCounts(String modelText) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        out.put("done", -1);
        out.put("left", -1);
        out.put("focus", -1);
        if (modelText == null || modelText.isEmpty()) {
            return out;
        }
        String t = modelText.toLowerCase();
        Integer done = findNumberNear(t,
                new String[]{"сделано", "выполнено", "закрыт", "выполн", "готово"});
        Integer left = findNumberNear(t,
                new String[]{"осталось", "в работе", "открыт", "не закрыт", "в процессе"});
        Integer focus = findNumberNear(t,
                new String[]{"фокусе", "фокус", "внимани", "риск"});
        if (done != null) {
            out.put("done", done);
        }
        if (left != null) {
            out.put("left", left);
        }
        if (focus != null) {
            out.put("focus", focus);
        }
        return out;
    }

    /**
     * Ищет число, привязанное к ключевому слову. Приоритет — числу СРАЗУ ПОСЛЕ слова
     * (например «сделано 5», «осталось 3»), в пределах короткого разрыва без цифр.
     * Если после слова числа нет — берётся ближайшее число ПЕРЕД ним («5 задач сделано»).
     * Ограничение разрыва не даёт «захватить» число из соседней части фразы.
     */
    private static Integer findNumberNear(String text, String[] keywords) {
        for(String kw:keywords){java.util.regex.Matcher exact=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(kw)+"[\\s:*=–—-]{0,8}(\\d+)").matcher(text);if(exact.find())return Integer.valueOf(exact.group(1));}
        for (String kw : keywords) {
            int idx = text.indexOf(kw);
            if (idx < 0) {
                continue;
            }
            int end = idx + kw.length();
            // 1) Число после слова: только цифры/пробелы/«:» между словом и числом.
            Integer after = firstNumberWithin(text, end, true, MAX_GAP);
            if (after != null) {
                return after;
            }
            // 2) Число перед словом.
            Integer before = firstNumberWithin(text, idx, false, MAX_GAP);
            if (before != null) {
                return before;
            }
        }
        return null;
    }

    /** Максимальный разрыв (в символах) между словом и числом, чтобы не «ловить» чужое число. */
    private static final int MAX_GAP = 8;

    /**
     * Возвращает первое число в направлении {@code forward} от позиции {@code pos}, но не
     * далее {@code gap} «промежуточных» символов (промежуток может содержать пробелы, ':', '=', '-',
     * но как только встречается буква или ещё одно число — поиск прекращается).
     */
    private static Integer firstNumberWithin(String text, int pos, boolean forward, int gap) {
        int scanned = 0;
        int i = pos;
        while (i >= 0 && i < text.length() && scanned <= gap) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                // Собираем всё число целиком.
                int start = i;
                int endDigit = i;
                if (forward) {
                    while (endDigit < text.length() && Character.isDigit(text.charAt(endDigit))) {
                        endDigit++;
                    }
                    return Integer.valueOf(text.substring(start, endDigit));
                } else {
                    while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
                        start--;
                    }
                    return Integer.valueOf(text.substring(start, i + 1));
                }
            }
            if (Character.isLetter(c)) {
                // Между словом и числом не должно быть букв (только пробелы/знаки).
                return null;
            }
            scanned++;
            i += forward ? 1 : -1;
        }
        return null;
    }

    /**
     * Формирует краткую сводку для чата по извлечённым показателям. Возвращает
     * пустую строку, если показатели не распознаны.
     */
    public static String formatSummary(Map<String, Integer> counts) {
        if (counts == null) {
            return "";
        }
        int done = value(counts, "done");
        int left = value(counts, "left");
        int focus = value(counts, "focus");
        if (done < 0 && left < 0 && focus < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("📊 Статус: ");
        sb.append("сделано ").append(Math.max(0, done));
        sb.append(", осталось ").append(Math.max(0, left));
        sb.append(", в фокусе ").append(Math.max(0, focus));
        return sb.toString();
    }

    /** Authoritative counts and actual task titles, independent of LLM wording. */
    public static String databaseSummary(String path,String projectId){
        if(path==null||!corpagent.db.SqliteHelper.getTables(path).contains("tasks"))return "";
        int done=0,left=0,focus=0;java.util.List<String> tasks=new java.util.ArrayList<String>();
        String today=java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).toString();
        try(var c=corpagent.db.SqliteHelper.open(path);var statement=c.prepareStatement("SELECT title,status,priority,due_date FROM tasks WHERE project_id=?")){
            statement.setString(1,projectId==null?"1":projectId);
            try(var rows=statement.executeQuery()){while(rows.next()){String status=rows.getString(2),due=rows.getString(4);if("done".equals(status))done++;else if("open".equals(status)||"in_progress".equals(status)){left++;if("high".equals(rows.getString(3))||(due!=null&&!due.isBlank()&&due.compareTo(today)<=0))focus++;if(tasks.size()<20)tasks.add("- "+rows.getString(1)+" ["+status+"]"+(due==null?"":" · "+due));}}}
            Map<String,Integer> counts=new LinkedHashMap<String,Integer>();counts.put("done",done);counts.put("left",left);counts.put("focus",focus);
            return formatSummary(counts)+(tasks.isEmpty()?"":"\nОткрытые задачи по данным БД:\n"+String.join("\n",tasks));
        }catch(Exception e){return "";}
    }

    private static int value(Map<String, Integer> m, String key) {
        Integer v = m.get(key);
        return v == null ? -1 : v;
    }
}
