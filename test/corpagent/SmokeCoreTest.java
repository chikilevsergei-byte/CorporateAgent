package corpagent;

import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.tools.ToolExecutor;
import corpagent.util.IntentRouter;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Лёгкий smoke-тест ядра без внешних фреймворков.
 * Проверяет парсинг SQL из ответов агента и разбиение SQL-скрипта на операторы
 * (краеугольные места будущей переработки). Запускается простым main():
 *
 * <pre>
 * dir = root of project (CorporateAgent v3)
 * javac -encoding UTF-8 -cp "dist/CorporateAgent.jar;dist/lib/*" -d build_test ^
 *        src/corpagent/data/DataStore.java ... (все исходники) test/...  // см. build бин
 * </pre>
 *
 * Для простоты тест скомпилирован вместе с основным кодом (переиспользует classpath билда).
 */
public class SmokeCoreTest {

    private static int checks = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testExtractSqlBlocks();
        testExtractSqlFromLines();
        testSplitSqlKeepsStringLiterals();
        testSplitSqlMultipleStatements();
        testA2DbGuards();
        testIntentRouter();
        if (failed == 0) {
            System.out.println("SmokeCoreTest OK (" + checks + " проверок).");
        } else {
            System.out.println("SmokeCoreTest FAILED: " + failed + " из " + checks);
            System.exit(1);
        }
    }

    private static void check(boolean cond, String label) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }

    // 1) Разбор ```sql ... ``` блоков в ответе агента.
    private static void testExtractSqlBlocks() {
        String resp = "Сделаю.\n```sql\nUPDATE tasks SET status='done' WHERE id=1;\n```\n" +
                "Готово, а вот обычный запрос:\n```sql\nSELECT * FROM tasks;\n```\n";
        List<String> q = DatabaseManager.extractSqlFromResponse(resp);
        check(q.size() == 2, "извлечено 2 sql-блока, получено " + q.size());
        if (q.size() == 2) {
            check(q.get(0).toUpperCase().startsWith("UPDATE TASKS"),
                    "блок 0 начинается с UPDATE: " + q.get(0));
            check(q.get(1).toUpperCase().startsWith("SELECT"),
                    "блок 1 начинается с SELECT: " + q.get(1));
        }
    }

    // 2) Распознавание отдельной строки DML с ';' и обычного текста без SQL.
    private static void testExtractSqlFromLines() {
        String resp = "Завтра.\nDELETE FROM logs WHERE id > 100;\nа здесь ещё предложение\n" +
                "UPDATE accounts SET balance = balance - 10;\nконец\n";
        List<String> q = DatabaseManager.extractSqlFromResponse(resp);
        check(q.stream().anyMatch(s -> s.toUpperCase().startsWith("DELETE FROM LOGS")),
                "найдено DELETE FROM LOGS ...; из обычной строки");
        check(q.stream().anyMatch(s -> s.toUpperCase().startsWith("UPDATE ACCOUNTS")),
                "найдено UPDATE ACCOUNTS ...;");
        check(q.stream().noneMatch(s -> s.toUpperCase().startsWith("ЗАВТРА")),
                "обычный текст без ';' не извлечён как SQL");
    }

    // 3) splitSqlStatements не разрезает точки с запятой внутри строковых литералов.
    private static void testSplitSqlKeepsStringLiterals() {
        try {
            String sql = "INSERT INTO chat(title) VALUES('a;просто;строка');";
            List<String> st = SqliteHelper.splitSqlStatements(sql);
            check(st.size() == 1, "литерал с ';' сохранён (получено операторов " + st.size() + ")");
        } catch (NoClassDefFoundError e) {
            System.out.println("  [SKIP] splitSql: отсутствует slf4j/sqlite-jdbc в classpath (" + e.getMessage() + ")");
        }
    }

    // 4) splitSqlStatements разделяет несколько операторов с кавычкой-экраном ''.
    private static void testSplitSqlMultipleStatements() {
        try {
            String sql = "CREATE TABLE t(id INTEGER); INSERT INTO t VALUES(1); INSERT INTO t VALUES('o''k')";
            List<String> st = SqliteHelper.splitSqlStatements(sql);
            check(st.size() == 3, "разбито на 3 оператора (получено " + st.size() + ")");
        } catch (NoClassDefFoundError e) {
            System.out.println("  [SKIP] splitSql: отсутствует slf4j/sqlite-jdbc в classpath (" + e.getMessage() + ")");
        }
    }

    // 5) Защита управляемого db_query/db_execute (чистая логика без обращения к sqlite).
    private static void testA2DbGuards() {
        try {
            Method isRead = ToolExecutor.class.getDeclaredMethod("isReadStatement", String.class);
            isRead.setAccessible(true);
            Method guard = ToolExecutor.class.getDeclaredMethod("guardWrite", String.class);
            guard.setAccessible(true);

            check((Boolean) isRead.invoke(null, "SELECT * FROM tasks WHERE id=1"),
                    "db_query допускает SELECT");
            check((Boolean) isRead.invoke(null, "PRAGMA table_info(tasks)"),
                    "db_query допускает PRAGMA");
            check(!(Boolean) isRead.invoke(null, "UPDATE tasks SET status='done' WHERE id=1"),
                    "db_query отклоняет UPDATE (невозможна запись через чтение)");

            // UPDATE без WHERE должен отклоняться.
            String g1 = (String) guard.invoke(null, "UPDATE tasks SET status='done'");
            check(g1 != null && g1.toLowerCase().contains("where"), "db_execute: UPDATE без WHERE отклонён");
            // UPDATE с WHERE разрешён.
            check(guard.invoke(null, "UPDATE tasks SET status='done' WHERE id=1") == null,
                    "db_execute: UPDATE с WHERE разрешён");
            // DELETE без WHERE отклоняется.
            check((String) guard.invoke(null, "DELETE FROM logs") != null,
                    "db_execute: DELETE без WHERE отклонён");
            // INSERT разрешён (нет WHERE, но это не UPDATE/DELETE).
            check(guard.invoke(null, "INSERT INTO tasks(name) VALUES('x')") == null,
                    "db_execute: INSERT разрешён");
            // PRAGMA через запись не допускается.
            check((String) guard.invoke(null, "PRAGMA foreign_keys=ON") != null,
                    "db_execute: PRAGMA не допускается через запись");
        } catch (Exception e) {
            check(false, "исключение при тестах A2-охранников: " + e);
        }
    }

    // 6) Роутер интентов (C1.1): классификация простых запросов без обращения к LLM/БД.
    private static void testIntentRouter() {
        check(IntentRouter.detect(null) == IntentRouter.Intent.CHAT,
                "null → CHAT");
        check(IntentRouter.detect("создай презентацию по проекту") == IntentRouter.Intent.PRESENTATION,
                "'презентаци' → PRESENTATION");
        check(IntentRouter.detect("сделай pptx отчёт") == IntentRouter.Intent.PRESENTATION,
                "'pptx' → PRESENTATION");
        check(IntentRouter.detect("создай excel таблицу с зарплатой") == IntentRouter.Intent.EXCEL,
                "'excel' → EXCEL");
        check(IntentRouter.detect("собери таблицу доходов") == IntentRouter.Intent.EXCEL,
                "'таблиц' → EXCEL");
        check(IntentRouter.detect("создай базу данных projects") == IntentRouter.Intent.DATABASE,
                "'создай базу данных …' → DATABASE");
        // Ограничение текущей эвристики (как и в первоначальном коде): сокращение «БД»
        // само по себе не распознаётся как create-db — осознанно сохраняем прежнее поведение,
        // пока роутер не будет расширен более точными правилами.
        check(IntentRouter.detect("создать БД по файлу") != IntentRouter.Intent.DATABASE,
                "'создать БД по файлу' не ошибочно определяется как создание новой БД");
        check(IntentRouter.detect("что нового в списке задач?") == IntentRouter.Intent.CHAT,
                "свободный вопрос → CHAT");
    }

}

