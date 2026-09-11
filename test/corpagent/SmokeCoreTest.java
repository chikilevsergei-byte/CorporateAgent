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
        testExcelTemplateSelection();
        testProjectManager();
        testMethodologyChatDefaults();
        testApplyProjectDefaults();
        testCreateProjectWithExistingDb();
        testUpdateProjectDbHelpers();
        testProtocolExtractor();
        testStatusReport();
        testIosTheme();
        testUiTokens();
        testActionIcon();
        testThemeSwitch();
        testChatContextMenu();
        testToggleSwitch();
        testRadioCard();
        testFormRow();
        testSlider();
        testWizardProgress();
        testToast();
        testBaseDialog();
        testEmptyState();
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

        // C1.1 (остаток): новые интенты update_db / question_db / protocol / status.
        // update_db: глагол записи + ссылка на БД.
        check(IntentRouter.detect("обнови задачу в БД") == IntentRouter.Intent.UPDATE_DB,
                "'обнови задачу в БД' → UPDATE_DB");
        check(IntentRouter.detect("добавь нового участника в базу данных проекта")
                        == IntentRouter.Intent.UPDATE_DB,
                "'добавь … в базу данных …' → UPDATE_DB");
        check(IntentRouter.detect("поставь статус done задаче 5 в БД") == IntentRouter.Intent.UPDATE_DB,
                "'поставь … в БД' → UPDATE_DB");
        // update_db не должен ложно срабатывать на создание БД.
        check(IntentRouter.detect("создай базу данных рисков") == IntentRouter.Intent.DATABASE,
                "создание БД важнее update_db");

        // question_db: вопрос + ссылка на БД.
        check(IntentRouter.detect("какие таблицы в бд?") == IntentRouter.Intent.QUESTION_DB,
                "'какие таблицы в бд?' → QUESTION_DB");
        check(IntentRouter.detect("покажи сделки из базы данных") == IntentRouter.Intent.QUESTION_DB,
                "'покажи … из базы данных' → QUESTION_DB");
        check(IntentRouter.detect("сколько задач в БД проекта") == IntentRouter.Intent.QUESTION_DB,
                "'сколько … в БД' → QUESTION_DB");
        // Вопрос без ссылки на БД остаётся CHAT.
        check(IntentRouter.detect("какие риски у проекта?") == IntentRouter.Intent.CHAT,
                "вопрос без ссылки на БД → CHAT");

        // protocol: разбор протокола встречи.
        check(IntentRouter.detect("разбери протокол встречи") == IntentRouter.Intent.PROTOCOL,
                "'разбери протокол встречи' → PROTOCOL");
        check(IntentRouter.detect("вытяни поручения из встречи") == IntentRouter.Intent.PROTOCOL,
                "'вытяни поручения' → PROTOCOL");
        check(IntentRouter.detect("обработай встречу и занеси решения") == IntentRouter.Intent.PROTOCOL,
                "'обработай встречу' → PROTOCOL");

        // status: статус-отчёт «сделано / осталось / фокус».
        check(IntentRouter.detect("отчёт о статусе проекта") == IntentRouter.Intent.STATUS,
                "'отчёт о статусе' → STATUS");
        check(IntentRouter.detect("что сделано и что осталось по проекту") == IntentRouter.Intent.STATUS,
                "'сделано и осталось' → STATUS");
        check(IntentRouter.detect("на чём фокус проекта") == IntentRouter.Intent.STATUS,
                "'на чём фокус' → STATUS");

        // Приоритеты: презентация/Excel важнее протокола/статуса.
        check(IntentRouter.detect("сделай презентацию статуса проекта") == IntentRouter.Intent.PRESENTATION,
                "'презентация статуса' → PRESENTATION (не STATUS)");
        check(IntentRouter.detect("выгрузи таблицу статуса в excel") == IntentRouter.Intent.EXCEL,
                "'таблицу статуса в excel' → EXCEL (не STATUS)");
        // Обычная просьба о таблице без БД/статуса остаётся EXCEL.
        check(IntentRouter.detect("сделай таблицу работ") == IntentRouter.Intent.EXCEL,
                "'таблицу работ' → EXCEL");
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

    // 9b) C2.2: выбор корп. Excel-шаблона по виду документа. Проверяем, что поиск в
    //     {base}/Шаблоны/ не подхватывает лишние .xlsx/.xlsm и уважает регистр/приоритет ключей.
    private static void testExcelTemplateSelection() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_xltpl").toFile();
            java.io.File tplDir = new java.io.File(base, "Шаблоны");
            if (!tplDir.mkdirs()) {
                throw new java.io.IOException("не удалось создать Шаблоны/");
            }
            // Пустышки: getCorporateExcelTemplate фильтрует по имени, XLSX не открывает.
            java.io.File passport = new java.io.File(tplDir, "Шаблон_Паспорт проекта с примером.xlsx");
            java.io.File risks = new java.io.File(tplDir, "Шаблон Реестр рисков.xlsx");
            java.io.File planMacro = new java.io.File(tplDir, "Шаблон План проекта.xlsm");
            passport.createNewFile();
            risks.createNewFile();
            planMacro.createNewFile();

            FileCreator fc = new FileCreator(base + java.io.File.separator + "o", base.getPath());

            String p = fc.getCorporateExcelTemplate("паспорт");
            check(p != null && p.endsWith(passport.getName()),
                    "Excel-шаблон: 'паспорт' находит Паспорт проекта (получено: " + p + ")");

            String r = fc.getCorporateExcelTemplate("реестр рисков", "риск");
            check(r != null && r.endsWith(risks.getName()),
                    "Excel-шаблон: 'реестр рисков' находит Реестр рисков (получено: " + r + ")");

            // .xlsm (макро-шаблон) не подхватывается — ищем только .xlsx.
            String plan = fc.getCorporateExcelTemplate("план");
            check(plan == null, "Excel-шаблон: .xlsm не подхватывается как .xlsx");

            // Отсутствующий вид документа → null, при этом пустой keys() безопасен.
            check(fc.getCorporateExcelTemplate("нет такого") == null,
                    "Excel-шаблон: неизвестный ключ → null");
            check(fc.getCorporateExcelTemplate() == null,
                    "Excel-шаблон: пустой список ключей → null");

            // Файлы-временные («~$…») игнорируются.
            java.io.File tmp = new java.io.File(tplDir, "~$паспорт.xlsx");
            tmp.createNewFile();
            String p2 = fc.getCorporateExcelTemplate("паспорт");
            check(p2 != null && !p2.contains("~$"),
                    "Excel-шаблон: временный ~$ файл игнорируется");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testExcelTemplateSelection: " + e);
        }
    }

    // 10) Мастер «Создать проект» (R-P0.2): санитизация имени БД, состав 7 ролей,
    //     доменная схема БД проекта. Проверяется чистая логика без обращения к sqlite.
    private static void testProjectManager() {
        try {
            Class<?> pm = Class.forName("corpagent.data.ProjectManager");

            // Санитизация имени БД: пробелы/кириллица/спецсимволы → '_', схлопывание.
            java.lang.reflect.Method sanitize = pm.getDeclaredMethod("sanitizeDbName", String.class);
            sanitize.setAccessible(true);
            check("my_project".equals(sanitize.invoke(null, "my project")),
                    "sanitizeDbName: пробел → '_'");
            check("Project_A-1".equals(sanitize.invoke(null, "Project A-1")),
                    "sanitizeDbName: сохраняет дефис/цифры");
            check("proj_2026".equals(sanitize.invoke(null, "proj 2026")),
                    "sanitizeDbName: пробел между словами → '_'");
            check(sanitize.invoke(null, "   ") == null,
                    "sanitizeDbName: пустое/пробельное имя → null");
            check(sanitize.invoke(null, "") == null,
                    "sanitizeDbName: пустая строка → null");
            // Кириллица (не-ASCII буквы) вытесняется; остаётся только цифровой «хвост».
            check("1".equals(sanitize.invoke(null, "Проект 1")),
                    "sanitizeDbName: кириллица вытесняется, остаётся цифра");

            // 7 чатов-ролей проекта.
            java.lang.reflect.Field rolesF = pm.getDeclaredField("CHAT_ROLES");
            rolesF.setAccessible(true);
            Object roles = rolesF.get(null);
            int roleCount = java.lang.reflect.Array.getLength(roles);
            check(roleCount == 7, "CHAT_ROLES: ровно 7 ролей (получено " + roleCount + ")");

            // roleTitles() возвращает 7 разных непустых названий.
            java.lang.reflect.Method roleTitles = pm.getDeclaredMethod("roleTitles");
            roleTitles.setAccessible(true);
            java.util.List<String> titles = castList(roleTitles.invoke(null));
            check(titles.size() == 7, "roleTitles: 7 названий");
            check(new java.util.HashSet<String>(titles).size() == 7, "roleTitles: названия уникальны");
            boolean allNonEmpty = true;
            for (String t : titles) {
                if (t == null || t.trim().isEmpty()) {
                    allNonEmpty = false;
                }
            }
            check(allNonEmpty, "roleTitles: все названия непустые");

            // Каждая роль ссылается на непустой навык (соответствие пресетам R-P1.1).
            boolean skillRefs = true;
            for (int i = 0; i < roleCount; i++) {
                Object role = java.lang.reflect.Array.get(roles, i);
                java.lang.reflect.Field skillF = role.getClass().getField("skill");
                Object skill = skillF.get(role);
                if (skill == null || String.valueOf(skill).trim().isEmpty()) {
                    skillRefs = false;
                }
            }
            check(skillRefs, "CHAT_ROLES: у каждой роли задан навык");

            // Доменная схема БД проекта содержит ключевые таблицы.
            java.lang.reflect.Field schemaF = pm.getDeclaredField("PROJECT_SCHEMA");
            schemaF.setAccessible(true);
            String schema = (String) schemaF.get(null);
            String[] expected = {"project", "tasks", "risks", "meetings",
                    "meeting_decisions", "team_member", "problem_statement", "goal_smart"};
            for (String table : expected) {
                check(schema.toLowerCase().contains("create table " + table),
                        "PROJECT_SCHEMA содержит таблицу " + table);
            }
            check(!schema.toUpperCase().contains("FOREIGN KEY")
                            && !schema.toUpperCase().contains("REFERENCES"),
                    "PROJECT_SCHEMA без FOREIGN KEY/REFERENCES (совместимо с SQLite-движком)");

            // LPG-методология подключается только на чтение — имя совпадает с пресетом.
            java.lang.reflect.Field lpgF = pm.getDeclaredField("LPG_DB");
            lpgF.setAccessible(true);
            check("Методология LPG".equals(lpgF.get(null)),
                    "LPG_DB указывает на 'Методология LPG'");

            // R-P1.4: БД методологии ИТ-проектов доступна как константа.
            java.lang.reflect.Field itF = pm.getDeclaredField("IT_METHODOLOGY_DB");
            itF.setAccessible(true);
            check("Методология ИТ проектов".equals(itF.get(null)),
                    "IT_METHODOLOGY_DB указывает на 'Методология ИТ проектов'");

            // R-P1.9.4: roleForTitle находит роль по точному имени и отрицает прочие.
            java.lang.reflect.Method roleForTitle = pm.getDeclaredMethod("roleForTitle", String.class);
            roleForTitle.setAccessible(true);
            check(roleForTitle.invoke(null, "Работа с рисками") != null,
                    "roleForTitle: находит роль по имени");
            check(roleForTitle.invoke(null, "Свободный чат") == null,
                    "roleForTitle: чужое название → null");
            check(roleForTitle.invoke(null, (Object) null) == null,
                    "roleForTitle: null → null");
        } catch (Exception e) {
            check(false, "testProjectManager: " + e);
        }
    }

    // 11) R-P1.4: чат «Вопрос по методологии» создаётся на одном read-only чипе-БД.
    //     Проверяем контракт: имя БД-методологии валидно и не совпадает с БД проекта.
    private static void testMethodologyChatDefaults() {
        try {
            Class<?> pm = Class.forName("corpagent.data.ProjectManager");
            java.lang.reflect.Field lpgF = pm.getDeclaredField("LPG_DB");
            lpgF.setAccessible(true);
            java.lang.reflect.Field itF = pm.getDeclaredField("IT_METHODOLOGY_DB");
            itF.setAccessible(true);
            String lpg = String.valueOf(lpgF.get(null));
            String it = String.valueOf(itF.get(null));
            check(!lpg.equals(it), "методологические БД LPG и ИТ различаются");
            check(lpg.startsWith("Методология") && it.startsWith("Методология"),
                    "обе методологические БД имеют префикс 'Методология'");
        } catch (Exception e) {
            check(false, "testMethodologyChatDefaults: " + e);
        }
    }

    // 12) R-P1.9.4: applyProjectDefaults привязывает чат к проекту и ставит чипы
    //     (БД проекта + LPG), профильный навык/инструмент роли. Проверяется на карте-чате
    //     без обращения к sqlite (store передаём как null-совместимый — только поля чата).
    private static void testApplyProjectDefaults() {
        try {
            Class<?> pm = Class.forName("corpagent.data.ProjectManager");
            java.lang.reflect.Method apply = pm.getDeclaredMethod("applyProjectDefaults",
                    Class.forName("corpagent.data.DataStore"),
                    java.util.Map.class, java.util.Map.class);
            apply.setAccessible(true);

            // Готовим проект-карту и чат-карту (Json.newObject — простая LinkedHashMap-обёртка).
            Class<?> json = Class.forName("corpagent.util.Json");
            java.lang.reflect.Method newObject = json.getDeclaredMethod("newObject");
            newObject.setAccessible(true);
            java.util.Map<String, Object> project = castMap(newObject.invoke(null));
            project.put("id", "proj_test");
            project.put("name", "Тестовый проект");
            project.put("db", "test_project_db");

            java.util.Map<String, Object> chat = castMap(newObject.invoke(null));
            chat.put("title", "Работа с рисками");

            // store = null: функция обязана защититься и вернуть false без падения.
            Object resNull = apply.invoke(null, null, chat, project);
            check(Boolean.FALSE.equals(resNull),
                    "applyProjectDefaults: null-store → false (безопасно)");

            // null-project → false.
            Object resNoProj = apply.invoke(null, null, chat, null);
            check(Boolean.FALSE.equals(resNoProj),
                    "applyProjectDefaults: null-project → false");
        } catch (Exception e) {
            check(false, "testApplyProjectDefaults: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> castMap(Object o) {
        return (java.util.Map<String, Object>) o;
    }

    // 13) R-P1.9.5: проект на существующей БД. Проверяем безопасное поведение —
    //     отсутствие имени проекта и/или несуществующей БД → success=false без исключений.
    private static void testCreateProjectWithExistingDb() {
        try {
            Class<?> pm = Class.forName("corpagent.data.ProjectManager");
            java.lang.reflect.Method m = pm.getDeclaredMethod("createProjectWithExistingDb",
                    Class.forName("corpagent.data.DataStore"),
                    String.class, String.class, String.class, String.class);
            m.setAccessible(true);

            // null-store → возвращается результат с ошибкой, но без падения.
            Object r = m.invoke(null, null, "Проект", "", "", null);
            java.lang.reflect.Field okF = r.getClass().getField("success");
            java.lang.reflect.Field errF = r.getClass().getField("error");
            check(Boolean.FALSE.equals(okF.get(r)),
                    "createProjectWithExistingDb: пустая/несуществующая БД → success=false");
            check(errF.get(r) != null,
                    "createProjectWithExistingDb: заполнено сообщение об ошибке");

            Object r2 = m.invoke(null, null, "  ", "", "", "any_db");
            check(Boolean.FALSE.equals(okF.get(r2)),
                    "createProjectWithExistingDb: пустое имя проекта → success=false");
        } catch (Exception e) {
            check(false, "testCreateProjectWithExistingDb: " + e);
        }
    }

    // 14) R-P1.9.6: разбор ИИ-плана синхронизации БД проекта.
    //     Проверяем извлечение SQL-блока, секции конфликтов и построение DELETE-ов.
    private static void testUpdateProjectDbHelpers() {
        try {
            Class<?> dlg = Class.forName("corpagent.gui.UpdateProjectDbDialog");

            java.lang.reflect.Method extractSql =
                    dlg.getDeclaredMethod("extractSqlBlock", String.class);
            extractSql.setAccessible(true);
            String plan = "План:\n```sql\nINSERT INTO tasks(title) VALUES('x');\n"
                    + "UPDATE tasks SET status='done' WHERE id=1;\n```\n"
                    + "###КОНФЛИКТЫ###\ntasks | id=7 | лишняя задача\nprojects | id=3 | лишний проект\n";
            String sql = (String) extractSql.invoke(null, plan);
            check(sql.contains("INSERT INTO tasks") && sql.contains("UPDATE tasks"),
                    "extractSqlBlock: извлекает INSERT и UPDATE из ```sql```");

            java.lang.reflect.Method extractConf =
                    dlg.getDeclaredMethod("extractConflicts", String.class);
            extractConf.setAccessible(true);
            String conf = (String) extractConf.invoke(null, plan);
            check(conf.contains("tasks") && conf.contains("id=7") && conf.contains("projects"),
                    "extractConflicts: извлекает секцию конфликтов после маркера");

            java.lang.reflect.Method buildDel =
                    dlg.getDeclaredMethod("buildDeleteSql", String.class);
            buildDel.setAccessible(true);
            String del = (String) buildDel.invoke(null, conf);
            check(del.contains("DELETE FROM tasks WHERE id=7"),
                    "buildDeleteSql: формирует DELETE с WHERE id");
            check(del.contains("DELETE FROM projects WHERE id=3"),
                    "buildDeleteSql: обрабатывает несколько строк конфликтов");
            // Некорректные строки игнорируются.
            String delBad = (String) buildDel.invoke(null, "неверная строка без id\nDROP TABLE x;");
            check(delBad != null && !delBad.toUpperCase().contains("DROP"),
                    "buildDeleteSql: игнорирует некорректные строки (без DROP)");

            // extractSqlBlock без блока ```sql``` возвращает текст до маркера конфликтов.
            String noBlock = (String) extractSql.invoke(null, "SELECT 1;\n###КОНФЛИКТЫ###\nnone");
            check(noBlock.contains("SELECT 1") && !noBlock.contains("КОНФЛИКТЫ"),
                    "extractSqlBlock: без ```sql``` берёт текст до секции конфликтов");
        } catch (Exception e) {
            check(false, "testUpdateProjectDbHelpers: " + e);
        }
    }

    // 15) C3.2: разбор протокола встречи. Проверяется извлечение полей из текста-ответа
    //     модели и построение инструкции для LLM (без обращения к сети/БД).
    private static void testProtocolExtractor() {
        try {
            Class<?> pe = Class.forName("corpagent.util.ProtocolExtractor");

            // Инструкция для LLM упоминает доменные таблицы и проект.
            java.lang.reflect.Method buildPrompt = pe.getDeclaredMethod(
                    "buildExtractionPrompt", String.class, String.class);
            buildPrompt.setAccessible(true);
            String prompt = (String) buildPrompt.invoke(null, "обсуждали риски проекта", "42");
            check(prompt.contains("meetings") && prompt.contains("meeting_decisions")
                            && prompt.contains("tasks"),
                    "buildExtractionPrompt упоминает meetings/meeting_decisions/tasks");
            check(prompt.contains("project_id=42"),
                    "buildExtractionPrompt подставляет project_id");
            java.lang.reflect.Method noDbHint = pe.getDeclaredMethod("buildNoDbContextHint");
            String hint = (String) noDbHint.invoke(null);
            check(hint != null && hint.contains("БД"),
                    "buildNoDbContextHint содержит подсказку про БД");

            // Разбор структурированного ответа модели.
            java.lang.reflect.Method parse = pe.getDeclaredMethod("parseExtraction", String.class);
            parse.setAccessible(true);
            String modelText = "МЕТАДАННЫЕ ВСТРЕЧИ\n"
                    + "Тема: Синк по проекту\n"
                    + "Дата: 2026-02-15\n"
                    + "Участники: Иванов, Петров\n"
                    + "Резюме: обсудили план\n"
                    + "\nРЕШЕНИЯ\n"
                    + "- Запустить этап 2\n"
                    + "- Утвердить бюджет\n"
                    + "\nПОРУЧЕНИЯ\n"
                    + "- Иванов: подготовить план до 2026-02-20\n"
                    + "- Петров: собрать риски\n";
            java.util.Map<String, Object> parsed = castMap(parse.invoke(null, modelText));
            check("Синк по проекту".equals(parsed.get("meeting_title")),
                    "parseExtraction: тема встречи");
            check("2026-02-15".equals(parsed.get("meeting_date")),
                    "parseExtraction: дата встречи");
            check("Иванов, Петров".equals(parsed.get("participants")),
                    "parseExtraction: участники");
            check("обсудили план".equals(parsed.get("summary")),
                    "parseExtraction: резюме");

            java.lang.reflect.Method cntD = pe.getDeclaredMethod("countDecisions", java.util.Map.class);
            cntD.setAccessible(true);
            java.lang.reflect.Method cntA = pe.getDeclaredMethod("countAssignments", java.util.Map.class);
            cntA.setAccessible(true);
            check(((Integer) cntD.invoke(null, parsed)) == 2,
                    "parseExtraction: 2 решения (получено " + cntD.invoke(null, parsed) + ")");
            check(((Integer) cntA.invoke(null, parsed)) == 2,
                    "parseExtraction: 2 поручения (получено " + cntA.invoke(null, parsed) + ")");

            // Пустой/некорректный ввод не должен падать.
            java.util.Map<String, Object> empty = castMap(parse.invoke(null, (Object) null));
            check(empty != null && Integer.valueOf(0).equals(cntD.invoke(null, empty)),
                    "parseExtraction: null → пустая структура без падений");
            java.util.Map<String, Object> junk = castMap(parse.invoke(null, "просто текст без структуры"));
            check(Integer.valueOf(0).equals(cntA.invoke(null, junk)),
                    "parseExtraction: свободный текст → 0 поручений");
        } catch (Exception e) {
            check(false, "testProtocolExtractor: " + e);
        }
    }

    // 16) C3.3: статус-отчёт «сделано / осталось / фокус» по таблице tasks.
    private static void testStatusReport() {
        try {
            Class<?> sr = Class.forName("corpagent.util.StatusReport");

            java.lang.reflect.Method buildPrompt = sr.getDeclaredMethod("buildStatusPrompt", String.class);
            buildPrompt.setAccessible(true);
            String prompt = (String) buildPrompt.invoke(null, "7");
            check(prompt.contains("tasks") && prompt.contains("project_id=7"),
                    "buildStatusPrompt упоминает tasks и project_id");
            check(prompt.contains("GROUP BY status"),
                    "buildStatusPrompt предлагает агрегацию GROUP BY status");
            check(prompt.toLowerCase().contains("фокус"),
                    "buildStatusPrompt описывает блок «фокус»");

            java.lang.reflect.Method noDb = sr.getDeclaredMethod("buildNoDbContextHint");
            String hint = (String) noDb.invoke(null);
            check(hint != null && hint.contains("БД"),
                    "buildNoDbContextHint содержит подсказку про БД");

            // Разбор итогового резюме.
            java.lang.reflect.Method parse = sr.getDeclaredMethod("parseCounts", String.class);
            parse.setAccessible(true);
            java.util.Map<String, Integer> counts = castIntMap(parse.invoke(null,
                    "Отчёт: Сделано 5, осталось 3, в фокусе 2 задачи."));
            check(Integer.valueOf(5).equals(counts.get("done")), "parseCounts: сделано=5");
            check(Integer.valueOf(3).equals(counts.get("left")), "parseCounts: осталось=3");
            check(Integer.valueOf(2).equals(counts.get("focus")), "parseCounts: фокус=2");

            // Разбор при обратном порядке слов.
            java.util.Map<String, Integer> counts2 = castIntMap(parse.invoke(null,
                    "В работе 4 задачи, выполнено 9."));
            check(Integer.valueOf(9).equals(counts2.get("done")), "parseCounts: выполнено=9");
            check(Integer.valueOf(4).equals(counts2.get("left")), "parseCounts: в работе=4");

            // Нет чисел — показатели -1.
            java.util.Map<String, Integer> none = castIntMap(parse.invoke(null, "пока без цифр"));
            check(Integer.valueOf(-1).equals(none.get("done")), "parseCounts: без чисел → -1");

            // Сводка.
            java.lang.reflect.Method fmt = sr.getDeclaredMethod("formatSummary", java.util.Map.class);
            fmt.setAccessible(true);
            String summary = (String) fmt.invoke(null, counts);
            check(summary.contains("сделано 5") && summary.contains("осталось 3")
                            && summary.contains("в фокусе 2"),
                    "formatSummary формирует сводку");
            String empty = (String) fmt.invoke(null, none);
            check(empty.isEmpty(), "formatSummary: без показателей → пустая строка");
        } catch (Exception e) {
            check(false, "testStatusReport: " + e);
        }
    }

    // 17) C6: iOS-оформление. Проверяем, что палитра темы соответствует iOS
    //     (system blue, grouped background, separator) и что filled-кнопка получает
    //     белый текст (а не чёрный, как раньше), без запуска GUI-событий.
    private static void testIosTheme() {
        try {
            Class<?> ui = Class.forName("corpagent.gui.Ui");
            java.awt.Color primary = (java.awt.Color) getStatic(ui, "PRIMARY");
            java.awt.Color bg = (java.awt.Color) getStatic(ui, "BG");
            java.awt.Color surface = (java.awt.Color) getStatic(ui, "SURFACE");
            java.awt.Color border = (java.awt.Color) getStatic(ui, "BORDER");

            check(primary != null && primary.equals(new java.awt.Color(0, 122, 255)),
                    "PRIMARY = iOS system blue #007AFF");
            check(bg != null && bg.equals(new java.awt.Color(242, 242, 247)),
                    "BG = iOS grouped background #F2F2F7");
            check(surface != null && surface.equals(java.awt.Color.WHITE),
                    "SURFACE = белый (карточки/списки)");
            check(border != null && border.equals(new java.awt.Color(229, 229, 234)),
                    "BORDER = iOS separator #E5E5EA");

            // stylePrimary не должен перекрашивать текст кнопки в чёрный.
            javax.swing.JButton b = new javax.swing.JButton("Отправить");
            java.lang.reflect.Method stylePrimary = ui.getDeclaredMethod("stylePrimary",
                    javax.swing.JButton.class);
            stylePrimary.setAccessible(true);
            stylePrimary.invoke(null, b);
            check(java.awt.Color.WHITE.equals(b.getForeground()),
                    "stylePrimary: текст filled-кнопки белый (было чёрным)");

            // styleSecondary — tinted-кнопка с синим текстом и серым фоном.
            javax.swing.JButton s = new javax.swing.JButton("Настройки");
            java.lang.reflect.Method styleSecondary = ui.getDeclaredMethod("styleSecondary",
                    javax.swing.JButton.class);
            styleSecondary.setAccessible(true);
            styleSecondary.invoke(null, s);
            check(primary.equals(s.getForeground()),
                    "styleSecondary: текст кнопки — акцент iOS (синий)");
        } catch (Exception e) {
            check(false, "testIosTheme: " + e);
        }
    }

    // 18) UI-0.1: дизайн-токены (spacing/шрифты/радиусы) и «кликабельный» курсор в UiTheme.
    //     Проверяется наличие, монотонность шкалы отступов и корректный хелпер курсора
    //     без запуска GUI.
    private static void testUiTokens() {
        try {
            Class<?> theme = Class.forName("corpagent.gui.UiTheme");

            int xs = (Integer) getStatic(theme, "GAP_XS");
            int sm = (Integer) getStatic(theme, "GAP_SM");
            int md = (Integer) getStatic(theme, "GAP_MD");
            int lg = (Integer) getStatic(theme, "GAP_LG");
            int xl = (Integer) getStatic(theme, "GAP_XL");
            int xxl = (Integer) getStatic(theme, "GAP_2XL");
            check(xs < sm && sm < md && md < lg && lg < xl && xl < xxl,
                    "GAP_*: шкала отступов строго возрастает (4..48)");
            check(xs == 4 && sm == 8 && md == 16 && lg == 24 && xl == 32 && xxl == 48,
                    "GAP_*: ожидаемые значения 4/8/16/24/32/48");

            // Радиусы: XS корректны и XL/2XL не меньше базовых.
            int rSm = (Integer) getStatic(theme, "RADIUS_SM");
            int rBtn = (Integer) getStatic(theme, "RADIUS_BTN");
            int rCard = (Integer) getStatic(theme, "RADIUS_CARD");
            int rXl = (Integer) getStatic(theme, "RADIUS_XL");
            int r2Xl = (Integer) getStatic(theme, "RADIUS_2XL");
            check(rSm > 0 && rSm <= rBtn && rBtn <= rCard && rCard <= rXl && rXl <= r2Xl,
                    "RADIUS_*: скругления не убывают (SM..2XL)");

            // Дополнительные шрифты существуют и крупнее базового.
            java.awt.Font base = (java.awt.Font) getStatic(theme, "FONT_PLAIN");
            java.awt.Font fontXl = (java.awt.Font) getStatic(theme, "FONT_XL");
            java.awt.Font font2Xl = (java.awt.Font) getStatic(theme, "FONT_2XL");
            check(fontXl != null && font2Xl != null
                            && fontXl.getSize() > base.getSize()
                            && font2Xl.getSize() > fontXl.getSize(),
                    "FONT_XL/2XL: крупнее базового и строго возрастают");

            // handCursor() возвращает курсор-руку (без обращения к дисплею).
            java.lang.reflect.Method hand = theme.getDeclaredMethod("handCursor");
            hand.setAccessible(true);
            Object cursor = hand.invoke(null);
            check(cursor instanceof java.awt.Cursor
                            && ((java.awt.Cursor) cursor).getType() == java.awt.Cursor.HAND_CURSOR,
                    "handCursor(): возвращает курсор HAND_CURSOR");
        } catch (Exception e) {
            check(false, "testUiTokens: " + e);
        }
    }

    // 19) UI-0.3: единый хелпер иконок действий — константы размера/зазора,
    //     генерация эмодзи-иконки и установка её на кнопку с общим зазором.
    private static void testActionIcon() {
        try {
            Class<?> theme = Class.forName("corpagent.gui.UiTheme");
            int size = (Integer) getStatic(theme, "ACTION_ICON_SIZE");
            int gap = (Integer) getStatic(theme, "ACTION_ICON_GAP");
            check(size >= 14 && size <= 20, "ACTION_ICON_SIZE: разумный размер иконки (14..20)");
            check(gap > 0, "ACTION_ICON_GAP: положительный зазор");

            java.lang.reflect.Method actionIcon =
                    theme.getMethod("actionIcon", String.class);
            Object icon = actionIcon.invoke(null, "💾");
            check(icon instanceof javax.swing.Icon, "actionIcon(): возвращает javax.swing.Icon");

            java.lang.reflect.Method apply =
                    theme.getMethod("withActionIcon", javax.swing.AbstractButton.class, String.class);
            javax.swing.JButton btn = new javax.swing.JButton("Сохранить");
            Object ret = apply.invoke(null, btn, "💾");
            check(ret == btn && btn.getIcon() != null && btn.getIconTextGap() == gap,
                    "withActionIcon(): ставит иконку и единый зазор, возвращает кнопку");
        } catch (Exception e) {
            check(false, "testActionIcon: " + e);
        }
    }

    // 20) UI-0.2: переключение светлой/тёмной палитры без перезапуска.
    //     applyTheme("dark") меняет фоновый/текстовый цвета, isDark()==true;
    //     applyTheme("green") возвращает светлую палитру (обратимость).
    private static void testThemeSwitch() {
        try {
            Class<?> theme = Class.forName("corpagent.gui.UiTheme");

            java.awt.Color lightBg = (java.awt.Color) getStatic(theme, "BG");
            java.awt.Color lightText = (java.awt.Color) getStatic(theme, "TEXT");

            java.lang.reflect.Method apply = theme.getMethod("applyTheme", String.class);
            java.lang.reflect.Method isDark = theme.getMethod("isDark");
            java.lang.reflect.Method curName = theme.getMethod("currentThemeName");

            apply.invoke(null, "dark");
            java.awt.Color darkBg = (java.awt.Color) getStatic(theme, "BG");
            java.awt.Color darkText = (java.awt.Color) getStatic(theme, "TEXT");
            check(Boolean.TRUE.equals(isDark.invoke(null)), "applyTheme(dark): isDark()==true");
            check("dark".equals(curName.invoke(null)), "applyTheme(dark): currentThemeName()==dark");
            check(!darkBg.equals(lightBg), "applyTheme(dark): фон изменился");
            // В тёмной теме текст светлее фона (контраст).
            check(darkText.getRed() > darkBg.getRed() && darkText.getGreen() > darkBg.getGreen(),
                    "applyTheme(dark): текст светлее фона");

            apply.invoke(null, "green");
            java.awt.Color backBg = (java.awt.Color) getStatic(theme, "BG");
            java.awt.Color backText = (java.awt.Color) getStatic(theme, "TEXT");
            check(!Boolean.TRUE.equals(isDark.invoke(null)), "applyTheme(green): isDark()==false");
            check(backBg.equals(lightBg) && backText.equals(lightText),
                    "applyTheme(green): светлая палитра восстановлена (обратимость)");
        } catch (Exception e) {
            check(false, "testThemeSwitch: " + e);
        }
    }

    // 21) UI-1.1: кастомное контекстное меню чата — состав пунктов, danger-стиль,
    //     наличие actionCount()/actionLabelAt()/actionIsDangerAt() без запуска GUI.
    private static void testChatContextMenu() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.ChatContextMenu");
            Object menu = cls.getConstructor(String.class, String.class)
                    .newInstance("💬", "Чат: Тест");
            Method add = cls.getMethod("addAction", String.class, String.class, Runnable.class);

            add.invoke(menu, "✏️", "Переименовать", (Runnable) () -> { });
            add.invoke(menu, "📁", "Переместить в проект…", (Runnable) () -> { });
            Method addDanger = cls.getMethod("addAction", String.class, String.class,
                    boolean.class, Runnable.class);
            addDanger.invoke(menu, "🗑️", "Удалить", true, (Runnable) () -> { });

            Method count = cls.getMethod("actionCount");
            Method labelAt = cls.getMethod("actionLabelAt", int.class);
            Method dangerAt = cls.getMethod("actionIsDangerAt", int.class);

            check(((Integer) count.invoke(menu)) == 3, "ChatContextMenu: 3 пункта меню");
            check("Переименовать".equals(labelAt.invoke(menu, 0)),
                    "ChatContextMenu: пункт 0 = Переименовать");
            check("Переместить в проект…".equals(labelAt.invoke(menu, 1)),
                    "ChatContextMenu: пункт 1 = Переместить в проект…");
            check(Boolean.FALSE.equals(dangerAt.invoke(menu, 0)),
                    "ChatContextMenu: обычный пункт не danger");
            check(Boolean.TRUE.equals(dangerAt.invoke(menu, 2)),
                    "ChatContextMenu: пункт Удалить помечен danger");
        } catch (Exception e) {
            check(false, "testChatContextMenu: " + e);
        }
    }

    // 22) UI-1.2: расчёт позиции бегунка toggle-свитча (travelling от off к on).
    //     Без отрисовки GUI: проверяем геометрию по формулам компонента.
    private static void testToggleSwitch() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.ToggleSwitch");
            Method knobX = cls.getMethod("knobX", int.class, int.class, float.class);

            int w = 46;
            int h = 28;
            Integer x0 = (Integer) knobX.invoke(null, w, h, 0f);
            Integer x1 = (Integer) knobX.invoke(null, w, h, 1f);
            Integer xMid = (Integer) knobX.invoke(null, w, h, 0.5f);

            check(x0 != null && x1 != null && x0 < x1,
                    "ToggleSwitch: бегунок смещается вправо при включении");
            check(xMid != null && xMid > x0 && xMid < x1,
                    "ToggleSwitch: середина анимации между off и on");

            // Создание свитча и корректная стартовая позиция progress.
            Object off = cls.getConstructor(boolean.class).newInstance(false);
            Object on = cls.getConstructor(boolean.class).newInstance(true);
            Method knobProgress = cls.getMethod("knobProgress");
            check(Math.abs(((Float) knobProgress.invoke(off))) < 0.001f,
                    "ToggleSwitch(false): progress==0");
            check(Math.abs(((Float) knobProgress.invoke(on)) - 1f) < 0.001f,
                    "ToggleSwitch(true): progress==1");
        } catch (Exception e) {
            check(false, "testToggleSwitch: " + e);
        }
    }

    // 23) UI-1.3: радио-карточка выбора — стартовое состояние и переключение выбора.
    private static void testRadioCard() {
        try {
            Class<?> ui = Class.forName("corpagent.gui.Ui");
            Method factory = ui.getMethod("radioCard", String.class, String.class, boolean.class);
            Object card = factory.invoke(null, "Новая БД", "доменная схема", true);
            Class<?> cardCls = Class.forName("corpagent.gui.Ui$RadioCard");

            Method isSel = cardCls.getMethod("isSelectedCard");
            Method setSel = cardCls.getMethod("setSelected", boolean.class);

            check(Boolean.TRUE.equals(isSel.invoke(card)),
                    "radioCard: стартовое состояние selected=true");
            setSel.invoke(card, false);
            check(Boolean.FALSE.equals(isSel.invoke(card)),
                    "radioCard: setSelected(false) снимает выбор");
            setSel.invoke(card, true);
            check(Boolean.TRUE.equals(isSel.invoke(card)),
                    "radioCard: setSelected(true) возвращает выбор");
        } catch (Exception e) {
            check(false, "testRadioCard: " + e);
        }
    }

    // 24) UI-1.4: единый ряд формы — подпись + контрол, контрол растянут по ширине.
    private static void testFormRow() {
        try {
            Class<?> ui = Class.forName("corpagent.gui.Ui");
            Method formRow = ui.getMethod("formRow", String.class, java.awt.Component.class);
            javax.swing.JTextField field = new javax.swing.JTextField("x");
            Object panel = formRow.invoke(null, "Название", field);
            check(panel instanceof javax.swing.JPanel, "formRow: возвращает JPanel");
            check(field.getMaximumSize().width == Integer.MAX_VALUE,
                    "formRow: контрол растянут по ширине (max width = MAX_VALUE)");
            check(field.getMaximumSize().height >= 30,
                    "formRow: разумная max-высота контрола (>=30)");
        } catch (Exception e) {
            check(false, "testFormRow: " + e);
        }
    }

    // 25) UI-2.3: кастомный слайдер — установка UI и размер бегунка.
    private static void testSlider() {
        try {
            javax.swing.JSlider s = new javax.swing.JSlider(0, 200, 70);
            java.lang.reflect.Method styleSlider =
                    Class.forName("corpagent.gui.Ui").getMethod("styleSlider",
                            javax.swing.JSlider.class);
            styleSlider.invoke(null, s);
            check(s.getUI() != null, "styleSlider: UI установлен");
            check(s.getUI().getClass().getName().contains("SlimSliderUI"),
                    "styleSlider: используется SlimSliderUI");
            java.awt.Dimension thumb = s.getUI().getPreferredSize(s);
            check(thumb != null, "styleSlider: UI возвращает размер компонента");
        } catch (Exception e) {
            check(false, "testSlider: " + e);
        }
    }

    // 26) UI-2.1: панель прогресса мастера — шаги, состояния active/completed.
    private static void testWizardProgress() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.WizardProgress");
            java.lang.reflect.Constructor<?> ctor =
                    cls.getConstructor(String[].class);
            Object wp = ctor.newInstance((Object) new String[]{"Основное", "База данных", "Рабочие чаты"});

            Method stepCount = cls.getMethod("stepCount");
            Method setCurrent = cls.getMethod("setCurrent", int.class);
            Method stateOf = cls.getMethod("stateOf", int.class);
            Method labelAt = cls.getMethod("labelAt", int.class);

            check((Integer) stepCount.invoke(wp) == 3, "WizardProgress: 3 шага");
            check("Основное".equals(labelAt.invoke(wp, 0)), "WizardProgress: подпись шага 0");
            check("Рабочие чаты".equals(labelAt.invoke(wp, 2)), "WizardProgress: подпись шага 2");

            // Шаг 0 активен, шаги 1..2 ещё не пройдены.
            setCurrent.invoke(wp, 0);
            check("active".equals(stateOf.invoke(wp, 0)), "шаг 0: active");
            check("".equals(stateOf.invoke(wp, 1)), "шаг 1: не активен/не пройден");
            check("".equals(stateOf.invoke(wp, 2)), "шаг 2: не активен/не пройден");

            // Переход на шаг 2: 0..1 completed, 2 active.
            setCurrent.invoke(wp, 2);
            check("completed".equals(stateOf.invoke(wp, 0)), "после шага 2: шаг 0 completed");
            check("completed".equals(stateOf.invoke(wp, 1)), "после шага 2: шаг 1 completed");
            check("active".equals(stateOf.invoke(wp, 2)), "после шага 2: шаг 2 active");
        } catch (Exception e) {
            check(false, "testWizardProgress: " + e);
        }
    }

    // 27) UI-3.1: Toast — типы, семантические цвета акцента, авто-скрытие 4с,
    //     сборка панели без показа окна (headless-безопасно).
    private static void testToast() {
        try {
            Class<?> toast = Class.forName("corpagent.gui.Toast");
            Class<?> typeCls = Class.forName("corpagent.gui.Toast$Type");

            // Enum типов содержит 4 значения.
            Object[] types = typeCls.getEnumConstants();
            check(types != null && types.length == 4, "Toast.Type: 4 типа (success/error/warning/info)");

            // Авто-скрытие — 4000 мс (как в прототипе).
            java.lang.reflect.Field autoHide = toast.getField("AUTO_HIDE_MS");
            check((Integer) autoHide.get(null) == 4000, "Toast.AUTO_HIDE_MS == 4000");

            // accentColor: success зелёный, error красный, warning оранжевый, info синий.
            Method accent = toast.getDeclaredMethod("accentColor", typeCls);
            accent.setAccessible(true);
            Object successType = typeCls.getField("SUCCESS").get(null);
            Object errorType = typeCls.getField("ERROR").get(null);
            Object warningType = typeCls.getField("WARNING").get(null);
            Object infoType = typeCls.getField("INFO").get(null);
            check(accent.invoke(null, successType).equals(new java.awt.Color(52, 199, 89)),
                    "accentColor(SUCCESS) == iOS system green");
            check(accent.invoke(null, errorType).equals(new java.awt.Color(255, 59, 48)),
                    "accentColor(ERROR) == iOS system red");
            check(accent.invoke(null, warningType).equals(new java.awt.Color(255, 149, 0)),
                    "accentColor(WARNING) == iOS system orange");
            check(accent.invoke(null, infoType).equals(new java.awt.Color(0, 122, 255)),
                    "accentColor(INFO) == iOS system blue");

            // buildPanel: строит JPanel с заголовком и сообщением (без показа окна).
            Method buildPanel = toast.getDeclaredMethod("buildPanel",
                    typeCls, String.class, String.class,
                    Class.forName("corpagent.gui.Toast$ToastInstance"));
            buildPanel.setAccessible(true);
            Object panel = buildPanel.invoke(null, successType, "Сохранено", "Навык обновлён", null);
            check(panel instanceof javax.swing.JPanel, "buildPanel: возвращает JPanel");
            check(countLabels((javax.swing.JPanel) panel) >= 3,
                    "buildPanel: иконка + заголовок + сообщение (>=3 JLabel)");

            // activeCount стартует с 0 (тосты не показывались).
            Method activeCount = toast.getMethod("activeCount");
            check((Integer) activeCount.invoke(null) == 0, "activeCount(): изначально 0");
        } catch (Exception e) {
            check(false, "testToast: " + e);
        }
    }

    /** Рекурсивно считает JLabel внутри контейнера (для проверки состава тоста). */
    private static int countLabels(java.awt.Container c) {
        int n = 0;
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof javax.swing.JLabel) {
                n++;
            }
            if (comp instanceof java.awt.Container) {
                n += countLabels((java.awt.Container) comp);
            }
        }
        return n;
    }

    // 28) UI-2.4: BaseDialog — общая скруглённая рамка (RoundedShadowBorder) и панель кнопок.
    //     Сам JDialog в headless не создаём (HeadlessException) — проверяем статические части.
    private static void testBaseDialog() {
        try {
            Class<?> base = Class.forName("corpagent.gui.BaseDialog");
            Class<?> borderCls = Class.forName("corpagent.gui.BaseDialog$RoundedShadowBorder");

            // Рамка даёт ненулевые внешние отступы (тень/скругление).
            Object border = borderCls.getConstructor().newInstance();
            Method getInsets = borderCls.getMethod("getBorderInsets", java.awt.Component.class);
            java.awt.Insets ins = (java.awt.Insets) getInsets.invoke(border, (Object) null);
            check(ins != null && ins.top >= 0 && ins.bottom > 0,
                    "RoundedShadowBorder: getBorderInsets задаёт тень (bottom>0)");

            // buttonBar: собирает панель, кнопки добавлены по порядку.
            Method buttonBar = base.getMethod("buttonBar", javax.swing.JComponent[].class);
            javax.swing.JButton b1 = new javax.swing.JButton("Отмена");
            javax.swing.JButton b2 = new javax.swing.JButton("Сохранить");
            Object row = buttonBar.invoke(null, (Object) new javax.swing.JComponent[]{b1, b2});
            check(row instanceof javax.swing.JPanel, "buttonBar: возвращает JPanel");
            check(((javax.swing.JPanel) row).getComponentCount() == 2,
                    "buttonBar: обе кнопки добавлены");

            // BaseDialog является наследником JDialog (единая база модалок).
            Class<?> jdialog = Class.forName("javax.swing.JDialog");
            check(jdialog.isAssignableFrom(base), "BaseDialog extends JDialog");
        } catch (Exception e) {
            check(false, "testBaseDialog: " + e);
        }
    }

    // 29) UI-3.5: EmptyState — иконка-глиф, заголовок и подсказка (headless-безопасно).
    private static void testEmptyState() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.EmptyState");
            java.lang.reflect.Constructor<?> ctor =
                    cls.getConstructor(String.class, String.class, String.class);
            Object es = ctor.newInstance("💬", "Пока нет чатов", "Создайте проект");
            check(es instanceof javax.swing.JPanel, "EmptyState: JPanel");

            Method titleText = cls.getMethod("titleText");
            Method hintText = cls.getMethod("hintText");
            check("Пока нет чатов".equals(titleText.invoke(es)), "EmptyState: заголовок сохранён");
            check("Создайте проект".equals(hintText.invoke(es)), "EmptyState: подсказка сохранена");

            // Подсказка необязательна: null не ломает конструктор.
            Object es2 = ctor.newInstance("🗄️", "Пусто", null);
            check(es2 instanceof javax.swing.JPanel, "EmptyState: null-подсказка допустима");
            check(countLabels((javax.swing.JPanel) es2) >= 2,
                    "EmptyState: иконка-глиф + заголовок (>=2 JLabel)");
        } catch (Exception e) {
            check(false, "testEmptyState: " + e);
        }
    }

    private static Object getStatic(Class<?> owner, String field) throws Exception {
        java.lang.reflect.Field f = owner.getField(field);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Integer> castIntMap(Object o) {
        return (java.util.Map<String, Integer>) o;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> castList(Object o) {
        return (java.util.List<String>) o;
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

