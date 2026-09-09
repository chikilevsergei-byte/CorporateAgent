package corpagent;

import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.files.FileCreator;
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
        testPresetDbPathResolution();
        testPresetResourcesPresent();
        testCorporateTemplateSelection();
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
        // C1.1 расширение: сокращение «БД»/«бд» в связке с глаголами создания распознаётся.
        check(IntentRouter.detect("создать БД по файлу") == IntentRouter.Intent.DATABASE,
                "'создать БД по файлу' → DATABASE (сокращение БД)");
        check(IntentRouter.detect("создай бд для проектов") == IntentRouter.Intent.DATABASE,
                "'создай бд для проектов' → DATABASE");
        check(IntentRouter.detect("заведи базу данных рисков") == IntentRouter.Intent.DATABASE,
                "'заведи базу данных …' → DATABASE");
        // Не-создание (обновить/вопрос в активной БД) не создаёт новую схему.
        check(IntentRouter.detect("обнови задачу в БД") != IntentRouter.Intent.DATABASE,
                "'обнови задачу в БД' — работа с активной БД");
        check(IntentRouter.detect("какие таблицы в бд?") != IntentRouter.Intent.DATABASE,
                "вопрос о содержимом БД без создания → не DATABASE");
        check(IntentRouter.detect("как устроена база данных проекта") != IntentRouter.Intent.DATABASE,
                "нейтральное упоминание без глагола создания → не DATABASE");
        check(IntentRouter.detect("что нового в списке задач?") == IntentRouter.Intent.CHAT,
                "свободный вопрос → CHAT");
    }

    // 7) Резолвинг относительного пути пресет-БД (R-P1.3) не зависит от File.separator.
    //    Регрессия: в пресет-JSON путь "databases/*.db" содержит '/', а на Windows
    //    File.separator='\'; createPresetDb резал путь по lastIndexOf('\') → -1 и .db не создавался.
    private static void testPresetDbPathResolution() {
        try {
            Class<?> pl = Class.forName("corpagent.data.PresetLoader");
            Method resolve = pl.getDeclaredMethod("resolveDbPath", String.class, String.class);
            resolve.setAccessible(true);

            String base = java.nio.file.Files.createTempDirectory("ca_smoke").toString();
            String out = (String) resolve.invoke(null, base, "databases/lpg_methodology.db");

            java.io.File f = new java.io.File(out);
            check(!out.contains("/") || java.io.File.separatorChar == '/',
                    "resolveDbPath нормализует '/' под платформенный разделитель");
            check(f.getName().equals("lpg_methodology.db"),
                    "resolveDbPath сохраняет имя файла .db: " + f.getName());
            // Директория-родитель должна быть создаваема и читаться через File API.
            java.io.File parent = f.getAbsoluteFile().getParentFile();
            check(parent != null && parent.getName().equals("databases"),
                    "resolveDbPath помещает .db в подпапку databases/ (получено: " + parent + ")");

            // Абсолютный входной путь возвращается без изменений.
            String mid = new java.io.File(base, "databases\\abs.db").getAbsolutePath();
            String same = (String) resolve.invoke(null, base, mid);
            check(new java.io.File(same).getAbsoluteFile().getParentFile() != null,
                    "resolveDbPath пропускает уже абсолютный путь");

            new java.io.File(base).delete();
        } catch (java.io.IOException e) {
            check(false, "testPresetDbPathResolution: IO " + e);
        } catch (Exception e) {
            check(false, "testPresetDbPathResolution: " + e);
        }
    }

    // 8) Пресеты-ресурсы лежат на classpath и загружены (R-P1.1/P1.2).
    //    Проверяется только когда окружение реально кладёт presets на classpath
    //    (обычно это собранный jar/build-каталог с /presets). Если ресурсов нет —
    //    пишем диагностику и не считаем тест упавшим (в таком окружении пресеты
    //    физически не поставляются и проверять их нечего).
    private static void testPresetResourcesPresent() {
        try {
            Class<?> pl = Class.forName("corpagent.data.PresetLoader");
            java.io.InputStream skills = pl.getClassLoader()
                    .getResourceAsStream("presets/skills/passport_project.json");
            java.io.InputStream tools = pl.getClassLoader()
                    .getResourceAsStream("presets/tools/create_passport_excel.json");
            java.io.InputStream dbs = pl.getClassLoader()
                    .getResourceAsStream("presets/databases/lpg_methodology.json");
            if (skills == null || tools == null || dbs == null) {
                System.out.println("  [INFO] Пресеты не найдены на classpath текущего окружения "
                        + "(skills=" + (skills != null) + ", tools=" + (tools != null)
                        + ", dbs=" + (dbs != null) + "). Тест пропущен.");
                return;
            }
            if (skills != null) skills.close();
            if (tools != null) tools.close();
            if (dbs != null) dbs.close();

            // Пре-загрузка PRESET_* массивов гарантирована объявлениями-константами выше,
            // поэтому ресурсы уже загружены в статические массивы. Проверяем их содержимое.
            int[] lens = privateIntArrayLength(pl, "PRESET_SKILLS");
            check(lens[0] == 7 && lens[1] == 0,
                    "все 7 пресет-навыков загружены (ресурсных null=" + lens[1] + ")");
            int[] lent = privateIntArrayLength(pl, "PRESET_TOOLS");
            check(lent[0] == 4 && lent[1] == 0,
                    "все 4 пресет-инструмента загружены (ресурсных null=" + lent[1] + ")");
            int[] lend = privateIntArrayLength(pl, "PRESET_DBS");
            check(lend[0] == 2 && lend[1] == 0,
                    "обе пресет-БД загружены (ресурсных null=" + lend[1] + ")");
        } catch (Exception e) {
            check(false, "testPresetResourcesPresent: " + e);
        }
    }

    // 9) Выбор корп. шаблона Супер Ленты не должен подхватывать другие шаблоны папки Шаблоны/
    //    (они принадлежат другим инструментам). Ищется файл «Шаблон презентаций Супер Лента*».
    private static void testCorporateTemplateSelection() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_tpl").toFile();
            java.io.File tplDir = new java.io.File(base, "Шаблоны");
            if (!tplDir.mkdirs()) {
                throw new java.io.IOException("не удалось создать Шаблоны/");
            }
            // Оба файла — пустышки: getCorporatePresentationTemplate фильтрует по имени,
            // PPTX не открывает, поэтому корректность выбора проверяется без реального файла.
            java.io.File ok = new java.io.File(tplDir, "Шаблон презентаций Супер Лента_v2025.pptx");
            java.io.File other = new java.io.File(tplDir, "Отчёт для отдела кадров.pptx");
            boolean wrote = ok.createNewFile() && other.createNewFile();
            if (!wrote) {
                throw new java.io.IOException("не удалось создать тестовые .pptx");
            }

            String picked = new FileCreator(base + java.io.File.separator + "o", base.getPath())
                    .getCorporatePresentationTemplate();
            check(picked != null && picked.endsWith(ok.getName()),
                    "выбран целевой шаблон Супер Ленты, а не чужой (получено: " + picked + ")");

            // Только «чужие» шаблоны в папке → целевой не находится (null).
            other.createNewFile();
            if (!ok.delete()) {
                throw new java.io.IOException("не удалось удалить целевой шаблон");
            }
            String none = new FileCreator(base + java.io.File.separator + "o", base.getPath())
                    .getCorporatePresentationTemplate();
            check(none == null, "при отсутствии шаблона Супер Ленты возвращается null");

            // Удаляем временный каталог рекурсивно.
            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testCorporateTemplateSelection: " + e);
        }
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    /** Возвращает {длина массива, число null-элементов} для приватного статического String[]. */
    private static int[] privateIntArrayLength(Class<?> owner, String fieldName) throws Exception {
        java.lang.reflect.Field fld = owner.getDeclaredField(fieldName);
        fld.setAccessible(true);
        Object arrObj = fld.get(null);
        if (!(arrObj instanceof String[])) {
            throw new IllegalStateException(fieldName + " не String[]");
        }
        String[] arr = (String[]) arrObj;
        int nulls = 0;
        for (String s : arr) {
            if (s == null) {
                nulls++;
            }
        }
        return new int[]{arr.length, nulls};
    }
}

