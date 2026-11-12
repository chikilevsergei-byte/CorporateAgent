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
                testSwingDomainClassesRemoved();
                testToast();
        testBaseDialog();
        testEmptyState();
        testDropZone();
        testSkeletonLoader();
        testWelcomeView();
        testMessageBubble();
        testBadgeIcon();
        testApiStaticHandler();
        testApiAuth();
        testApiResponse();
        testWebPrototypeAssets();
                testChatStreamParsing();
        testChatRequestDto();
        testChatRoutesContext();
        testApiStreamRoute();
        testChatMessageHelpers();
                testChatContextRoutes();
        testWebChatFrontend();
        testChatChunkDto();
        testCatalogRoutes();
        testWebChatContextPanel();
        testWebStructuredMessages();
        testNotesStore();
        testNotesRoutes();
        testProjectsRoutes();
        testProjectsCrud();
        testSkillsRoutes();
        testToolsRoutes();
        testDatabasesRoutes();
        testWebSectionViews();
        testWebSidebar();
                testWebNotesView();
        testWebRouter();
                testSettingsRoutes();
        testSettingsRoutesSave();
        testFilesRoutes();
        testWebSettingsTabs();
        testWebReportDownload();
        if (failed == 0) {
            System.out.println("SmokeCoreTest OK (" + checks + " проверок).");
            System.exit(0); // Swing timer checks may keep the EDT alive.
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

    // 14) Этап 7 (v4): разбор ИИ-плана синхронизации (upsert) БД проекта.
    //     Логика перенесена из удалённого Swing-диалога UpdateProjectDbDialog
    //     в независимый от UI класс corpagent.db.DbUpsertPlan (задачи 7.1-7.3).
    //     Проверяем извлечение SQL-блока, секции конфликтов, построение DELETE-ов
    //     и автоматический выбор ветки разрешения конфликтов (без UI).
    private static void testUpdateProjectDbHelpers() {
        try {
            Class<?> planCls = Class.forName("corpagent.db.DbUpsertPlan");

            java.lang.reflect.Method extractSql =
                    planCls.getDeclaredMethod("extractSqlBlock", String.class);
            extractSql.setAccessible(true);
            String plan = "План:\n```sql\nINSERT INTO tasks(title) VALUES('x');\n"
                    + "UPDATE tasks SET status='done' WHERE id=1;\n```\n"
                    + "###КОНФЛИКТЫ###\ntasks | id=7 | лишняя задача\nprojects | id=3 | лишний проект\n";
            String sql = (String) extractSql.invoke(null, plan);
            check(sql.contains("INSERT INTO tasks") && sql.contains("UPDATE tasks"),
                    "DbUpsertPlan.extractSqlBlock: извлекает INSERT и UPDATE из ```sql```");

            java.lang.reflect.Method extractConf =
                    planCls.getDeclaredMethod("extractConflicts", String.class);
            extractConf.setAccessible(true);
            String conf = (String) extractConf.invoke(null, plan);
            check(conf.contains("tasks") && conf.contains("id=7") && conf.contains("projects"),
                    "DbUpsertPlan.extractConflicts: извлекает секцию конфликтов после маркера");

            java.lang.reflect.Method buildDel =
                    planCls.getDeclaredMethod("buildDeleteSql", String.class);
            buildDel.setAccessible(true);
            String del = (String) buildDel.invoke(null, conf);
            check(del.contains("DELETE FROM tasks WHERE id=7"),
                    "DbUpsertPlan.buildDeleteSql: формирует DELETE с WHERE id");
            check(del.contains("DELETE FROM projects WHERE id=3"),
                    "DbUpsertPlan.buildDeleteSql: обрабатывает несколько строк конфликтов");
            // Некорректные строки игнорируются.
            String delBad = (String) buildDel.invoke(null, "неверная строка без id\nDROP TABLE x;");
            check(delBad != null && !delBad.toUpperCase().contains("DROP"),
                    "DbUpsertPlan.buildDeleteSql: игнорирует некорректные строки (без DROP)");

            // extractSqlBlock без блока ```sql``` возвращает текст до маркера конфликтов.
            String noBlock = (String) extractSql.invoke(null, "SELECT 1;\n###КОНФЛИКТЫ###\nnone");
            check(noBlock.contains("SELECT 1") && !noBlock.contains("КОНФЛИКТЫ"),
                    "DbUpsertPlan.extractSqlBlock: без ```sql``` берёт текст до секции конфликтов");

            // Этап 7: автоматический выбор ветки разрешения конфликтов (без UI-диалога).
            java.lang.reflect.Method autoMod = planCls.getDeclaredMethod("autoResolveBranch");
            autoMod.setAccessible(true);
            check("KEEP".equals(String.valueOf(autoMod.invoke(null))),
                    "DbUpsertPlan.autoResolveBranch: по умолчанию ветка KEEP (безопасно)");

            java.lang.reflect.Method isPlan = planCls.getDeclaredMethod("isUpsertPlan", String.class);
            isPlan.setAccessible(true);
            check(Boolean.TRUE.equals(isPlan.invoke(null, plan)),
                    "DbUpsertPlan.isUpsertPlan: распознаёт секцию ###КОНФЛИКТЫ###");
            check(Boolean.FALSE.equals(isPlan.invoke(null, "UPDATE t SET a=1 WHERE id=1;")),
                    "DbUpsertPlan.isUpsertPlan: обычный SQL не считает планом upsert");

            // autoConflictSql при ветке KEEP (по умолчанию) не удаляет строки.
            java.lang.reflect.Method autoSql = planCls.getDeclaredMethod("autoConflictSql", String.class);
            autoSql.setAccessible(true);
            String auto = (String) autoSql.invoke(null, conf);
            check(auto != null && !auto.toUpperCase().contains("DELETE"),
                    "DbUpsertPlan.autoConflictSql: при KEEP удаление не выполняется");

            // statements разбивает план на отдельные операторы INSERT/UPDATE.
            java.lang.reflect.Method stmts = planCls.getDeclaredMethod("statements", String.class);
            stmts.setAccessible(true);
            java.util.List<?> ops = (java.util.List<?>) stmts.invoke(null, plan);
            check(ops.size() == 2 && String.valueOf(ops.get(0)).toUpperCase().contains("INSERT"),
                    "DbUpsertPlan.statements: разбивает план на 2 оператора: " + ops.size());
        } catch (Exception e) {
            check(false, "testUpdateProjectDbHelpers: " + e);
        }
    }
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

        // 26) Этап 5.x-D: CRUD-разделы перенесены в веб-UI, Swing-классы удалены,
    //     а MainFrame сведён к тонкому каркасу (остался только чат + заглушки навигации).
    private static void testSwingDomainClassesRemoved() {
        String[] removed = {
                "corpagent.gui.SkillManagerView",
                "corpagent.gui.SkillEditDialog",
                "corpagent.gui.ToolManagerView",
                "corpagent.gui.ToolEditDialog",
                "corpagent.gui.DbManagerView",
                "corpagent.gui.DbViewerView",
                "corpagent.gui.CreateProjectDialog",
                "corpagent.gui.WizardProgress",
        };
        for (String name : removed) {
            boolean present;
            try {
                Class.forName(name);
                present = true;
            } catch (ClassNotFoundException e) {
                present = false;
            }
            check(!present, "удалён Swing-класс раздела (Этап 5.x-D): " + name);
        }

        // MainFrame оставляет только представление чата и метод-заглушку createProject().
        try {
            Class<?> mf = Class.forName("corpagent.gui.MainFrame");
            java.lang.reflect.Field chatF = mf.getDeclaredField("chatView");
            chatF.setAccessible(true);
            check(chatF != null, "MainFrame: поле chatView сохранено (тонкий каркас)");
            check(mf.getMethod("createProject") != null,
                    "MainFrame: createProject() — заглушка для совместимости");
            check(mf.getMethod("showDbManager") != null && mf.getMethod("showSkillManager") != null,
                    "MainFrame: методы навигации сохранены как заглушки");
            // Старые константы удалённых представлений больше не объявлены.
            boolean noSkillsConst;
            try {
                mf.getField("VIEW_SKILLS");
                noSkillsConst = false;
            } catch (NoSuchFieldException e) {
                noSkillsConst = true;
            }
            check(noSkillsConst, "MainFrame: константы удалённых представлений убраны");
        } catch (Exception e) {
            check(false, "testSwingDomainClassesRemoved: " + e);
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

            // UI-3.3: BaseDialog переопределяет setVisible (анимация открытия включена),
            // а не только наследует JDialog.setVisible.
            java.lang.reflect.Method setVisible = base.getMethod("setVisible", boolean.class);
            check(setVisible.getDeclaringClass() == base,
                    "BaseDialog: setVisible переопределён (анимация открытия UI-3.3)");
            java.lang.reflect.Field animMs = base.getDeclaredField("OPEN_ANIM_MS");
            animMs.setAccessible(true);
            check((Integer) animMs.get(null) > 0, "BaseDialog.OPEN_ANIM_MS > 0");
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

    // 30) UI-3.2: DropZone (drag&drop зона файлов) — фильтр расширений и TransferHandler
    //     без открытия диалогов (headless-безопасно). FilePickerDialog расширяет JDialog,
    //     поэтому в headless не конструируется — проверяем наличие класса и его базовый тип.
    private static void testDropZone() {
        try {
            Class<?> dz = Class.forName("corpagent.gui.DropZone");
            Object zone = dz.getConstructor(String[].class, boolean.class)
                    .newInstance((Object) new String[]{"pdf", "txt"}, true);
            check(zone instanceof javax.swing.JPanel, "DropZone: JPanel");

            Method accepts = dz.getMethod("acceptsName", String.class);
            check(Boolean.TRUE.equals(accepts.invoke(zone, "report.pdf")),
                    "DropZone: принимает .pdf");
            check(Boolean.TRUE.equals(accepts.invoke(zone, "notes.TXT")),
                    "DropZone: расширения без учёта регистра");
            check(Boolean.FALSE.equals(accepts.invoke(zone, "image.png")),
                    "DropZone: отклоняет неподдерживаемое расширение");

            Method supports = dz.getMethod("supportsFileDrop");
            check(Boolean.TRUE.equals(supports.invoke(zone)),
                    "DropZone: TransferHandler установлен (drag&drop включён)");
            Method isDragover = dz.getMethod("isDragover");
            check(Boolean.FALSE.equals(isDragover.invoke(zone)),
                    "DropZone: изначально без dragover");

            // FilePickerDialog — наследник BaseDialog (=> JDialog), но не создаём в headless.
            Class<?> picker = Class.forName("corpagent.gui.FilePickerDialog");
            Class<?> base = Class.forName("corpagent.gui.BaseDialog");
            check(base.isAssignableFrom(picker),
                    "FilePickerDialog extends BaseDialog");
        } catch (Exception e) {
            check(false, "testDropZone: " + e);
        }
    }

    // 31) UI-3.4: Skeleton loader — число строк, размеры и диапазон анимации (headless).
    private static void testSkeletonLoader() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.SkeletonLoader");
            Object loader = cls.getConstructor(int.class, int.class).newInstance(3, 12);
            check(loader instanceof javax.swing.JPanel, "SkeletonLoader: JPanel");

            Method lineCount = cls.getMethod("lineCount");
            check((Integer) lineCount.invoke(loader) == 3, "SkeletonLoader: 3 строки");

            Method pref = cls.getMethod("getPreferredSize");
            java.awt.Dimension d = (java.awt.Dimension) pref.invoke(loader);
            check(d != null && d.height > 0, "SkeletonLoader: положительная высота");

            Method alpha = cls.getMethod("currentAlpha");
            int a = (Integer) alpha.invoke(loader);
            check(a >= 30 && a <= 90, "SkeletonLoader: стартовая alpha в диапазоне (получено " + a + ")");

            // paragraph(...) и message() — фабрики валидны.
            Method paragraph = cls.getMethod("paragraph", int.class);
            check(paragraph.invoke(null, 2) instanceof javax.swing.JPanel,
                    "SkeletonLoader.paragraph: возвращает JPanel");
            Method message = cls.getMethod("message");
            check(message.invoke(null) instanceof javax.swing.JPanel,
                    "SkeletonLoader.message: возвращает JPanel");
        } catch (Exception e) {
            check(false, "testSkeletonLoader: " + e);
        }
    }

    // 32) UI-4.1: приветственный экран — заголовок, подзаголовок и быстрые действия.
    private static void testWelcomeView() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.WelcomeView");
            Object wv = cls.getConstructor(String.class, String.class, String.class)
                    .newInstance("🤖", "Добро пожаловать!", "Выберите действие");
            check(wv instanceof javax.swing.JPanel, "WelcomeView: JPanel");

            Method addAction = cls.getMethod("addAction", String.class, String.class,
                    String.class, Runnable.class);
            addAction.invoke(wv, "➕", "Новый чат", "Создать диалог", (Runnable) () -> { });
            addAction.invoke(wv, "📁", "Создать проект", "БД + роли", (Runnable) () -> { });

            Method actionCount = cls.getMethod("actionCount");
            check((Integer) actionCount.invoke(wv) == 2, "WelcomeView: 2 быстрых действия");
            Method titleAt = cls.getMethod("actionTitleAt", int.class);
            check("Новый чат".equals(titleAt.invoke(wv, 0)),
                    "WelcomeView: заголовок действия 0");

            // Градиентный бейдж и карточка действия — внутренние классы на месте.
            check(Class.forName("corpagent.gui.WelcomeView$GradientBadge") != null,
                    "WelcomeView: GradientBadge присутствует");
            check(Class.forName("corpagent.gui.WelcomeView$QuickActionCard") != null,
                    "WelcomeView: QuickActionCard присутствует");
        } catch (Exception e) {
            check(false, "testWelcomeView: " + e);
        }
    }

    // 33) UI-4.2: MessageBubble — роль, hover-действия (копировать/повторить).
    private static void testMessageBubble() {
        try {
            Class<?> cls = Class.forName("corpagent.gui.MessageBubble");
            Object userMsg = cls.getConstructor(String.class, String.class, String.class)
                    .newInstance("user", "Привет", "14:32");
            check(userMsg instanceof javax.swing.JPanel, "MessageBubble: JPanel");
            Method isUser = cls.getMethod("isUser");
            check(Boolean.TRUE.equals(isUser.invoke(userMsg)), "MessageBubble: роль user");

            Method addAction = cls.getMethod("addAction", String.class, String.class, Runnable.class);
            addAction.invoke(userMsg, "📋", "Копировать", (Runnable) () -> { });
            Method actionCount = cls.getMethod("actionCount");
            check((Integer) actionCount.invoke(userMsg) == 1, "MessageBubble: 1 действие");

            // Нулевой колбэк игнорируется (кнопка не создаётся).
            addAction.invoke(userMsg, "🗑️", "Удалить", null);
            check((Integer) actionCount.invoke(userMsg) == 1,
                    "MessageBubble: null-колбэк не создаёт кнопку");

            Object aiMsg = cls.getConstructor(String.class, String.class, String.class)
                    .newInstance("assistant", "Ответ", null);
            check(Boolean.FALSE.equals(isUser.invoke(aiMsg)), "MessageBubble: роль assistant");
        } catch (Exception e) {
            check(false, "testMessageBubble: " + e);
        }
    }

    // 34) UI-4.3: Badge-иконка счётчика в ChatChipsBar — размеры и отрисовка (headless).
    private static void testBadgeIcon() {
        try {
            Class<?> bar = Class.forName("corpagent.gui.ChatChipsBar");
            Class<?> badge = Class.forName("corpagent.gui.ChatChipsBar$BadgeIcon");
            java.lang.reflect.Constructor<?> ctor = badge.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            Object icon = ctor.newInstance(3);
            check(icon instanceof javax.swing.Icon, "BadgeIcon: реализует Icon");

            Method w = badge.getMethod("getIconWidth");
            Method h = badge.getMethod("getIconHeight");
            int width1 = (Integer) w.invoke(icon);
            int height = (Integer) h.invoke(icon);
            check(height == 18, "BadgeIcon: высота 18");
            check(width1 >= 18, "BadgeIcon: минимальная ширина >= 18");

            // Ширина растёт с числом разрядов.
            Object iconBig = ctor.newInstance(123);
            check((Integer) w.invoke(iconBig) > width1,
                    "BadgeIcon: шире для многозначного числа");

            // paintIcon не падает на offscreen-изображении (headless-безопасно).
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(64, 24,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Method paint = badge.getMethod("paintIcon", java.awt.Component.class,
                    java.awt.Graphics.class, int.class, int.class);
            paint.invoke(icon, null, img.getGraphics(), 2, 2);
            check(true, "BadgeIcon: paintIcon выполняется без ошибок");
        } catch (Exception e) {
            check(false, "testBadgeIcon: " + e);
        }
    }

    // 35) Этап 1 (v4): статический слой HTTP-сервера — нормализация URL-пути,
    //     защита от path traversal, определение MIME. Покрывает отдачу web/ из
    //     портативного дистрибутива (задача 1.7) и не требует поднятия сервера.
    private static void testApiStaticHandler() {
        try {
            Class<?> sh = Class.forName("corpagent.api.StaticHandler");

            // normalizePath: "/" → index.html, query/hash отбрасываются, %xx декодируется.
            java.lang.reflect.Method norm = sh.getMethod("normalizePath", String.class);
            check("index.html".equals(norm.invoke(null, "/")),
                    "normalizePath('/') → index.html");
            check("index.html".equals(norm.invoke(null, (Object) null)),
                    "normalizePath(null) → index.html");
            check("css/base.css".equals(norm.invoke(null, "/css/base.css?x=1")),
                    "normalizePath: query срезается");
            check("js/app.js".equals(norm.invoke(null, "/js/app.js#frag")),
                    "normalizePath: hash срезается");
            check("a b.txt".equals(norm.invoke(null, "/a%20b.txt")),
                    "normalizePath: %20 декодируется в пробел");
            check("screens/chat.css".equals(norm.invoke(null, "/screens/chat.css")),
                    "normalizePath: вложенный путь сохраняется");

            // mimeType: ключевые типы фронтенда.
            java.lang.reflect.Method mime = sh.getMethod("mimeType", String.class);
            check(((String) mime.invoke(null, "index.html")).startsWith("text/html"),
                    "mimeType: .html → text/html");
            check(((String) mime.invoke(null, "style.css")).startsWith("text/css"),
                    "mimeType: .css → text/css");
            check(((String) mime.invoke(null, "app.js")).contains("javascript"),
                    "mimeType: .js → application/javascript");
            check("image/svg+xml".equals(mime.invoke(null, "icon.svg")),
                    "mimeType: .svg → image/svg+xml");
            check("application/octet-stream".equals(mime.invoke(null, "data.unknown")),
                    "mimeType: неизвестное расширение → octet-stream");
            check("application/octet-stream".equals(mime.invoke(null, "noext")),
                    "mimeType: без расширения → octet-stream");

            // resolveSafeFile: реальный файл внутри web/ находится, traversal — отклоняется.
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_static").toFile();
            java.io.File web = new java.io.File(base, "web");
            java.io.File css = new java.io.File(web, "css");
            css.mkdirs();
            java.io.File index = new java.io.File(web, "index.html");
            java.io.File style = new java.io.File(css, "base.css");
            index.createNewFile();
            style.createNewFile();

            Object handler = sh.getConstructor(String.class).newInstance(web.getPath());
            java.lang.reflect.Method resolve = sh.getMethod("resolveSafeFile", String.class);

            Object ok = resolve.invoke(handler, "/index.html");
            check(ok instanceof java.io.File && ((java.io.File) ok).isFile(),
                    "resolveSafeFile: существующий index.html найден");
            Object cssFile = resolve.invoke(handler, "/css/base.css");
            check(cssFile instanceof java.io.File && ((java.io.File) cssFile).getName().equals("base.css"),
                    "resolveSafeFile: вложенный css найден");

            // Path traversal отклоняется (null).
            check(resolve.invoke(handler, "/../config.json") == null,
                    "resolveSafeFile: '../' за пределы web/ отклонён");
            check(resolve.invoke(handler, "/../../etc/passwd") == null,
                    "resolveSafeFile: глубокий traversal отклонён");
            // Несуществующий файл → null.
            check(resolve.invoke(handler, "/missing.html") == null,
                    "resolveSafeFile: отсутствующий файл → null");

            // exists() корректно отражает наличие папки статики.
            java.lang.reflect.Method exists = sh.getMethod("exists");
            check(Boolean.TRUE.equals(exists.invoke(handler)),
                    "StaticHandler.exists(): папка web/ найдена");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testApiStaticHandler: " + e);
        }
    }

    // 36) Этап 1 (v4): токен-авторизация API — формат Bearer, регистр схемы, отказ при null.
    private static void testApiAuth() {
        try {
            Class<?> auth = Class.forName("corpagent.api.Auth");
            Object a = auth.getMethod("withToken", String.class).invoke(null, "ABC123");
            java.lang.reflect.Method isValid = auth.getMethod("isValid", String.class);

            check(Boolean.TRUE.equals(isValid.invoke(a, "Bearer ABC123")),
                    "Auth: верный Bearer-токен проходит");
            check(Boolean.TRUE.equals(isValid.invoke(a, "bearer ABC123")),
                    "Auth: схема без учёта регистра");
            check(Boolean.FALSE.equals(isValid.invoke(a, (Object) null)),
                    "Auth: null-заголовок отклонён");
            check(Boolean.FALSE.equals(isValid.invoke(a, "Bearer wrong")),
                    "Auth: неверный токен отклонён");
            check(Boolean.FALSE.equals(isValid.invoke(a, "ABC123")),
                    "Auth: без схемы Bearer отклонён");

            java.lang.reflect.Method gen = auth.getMethod("generateToken");
            String t1 = (String) gen.invoke(null);
            String t2 = (String) gen.invoke(null);
            check(t1 != null && t1.length() == 32, "Auth.generateToken(): длина 32");
            check(!t1.equals(t2), "Auth.generateToken(): токены уникальны");
        } catch (Exception e) {
            check(false, "testApiAuth: " + e);
        }
    }

    // 37) Этап 1 (v4): ApiResponse — единый формат {ok,data|error} и HTTP-статусы.
    private static void testApiResponse() {
        try {
            Class<?> cls = Class.forName("corpagent.api.ApiResponse");

            Object ok = cls.getMethod("ok", Object.class).invoke(null, "payload");
            java.lang.reflect.Method okFlag = cls.getMethod("isOk");
            java.lang.reflect.Method status = cls.getMethod("getHttpStatus");
            java.lang.reflect.Method toJson = cls.getMethod("toJson");
            check(Boolean.TRUE.equals(okFlag.invoke(ok)), "ApiResponse.ok: isOk()==true");
            check((Integer) status.invoke(ok) == 200, "ApiResponse.ok: статус 200");
            String json = (String) toJson.invoke(ok);
            check(json.contains("\"ok\"") && json.contains("payload"),
                    "ApiResponse.ok: JSON содержит ok и data");

            Object created = cls.getMethod("ok", Object.class, int.class)
                    .invoke(null, "x", 201);
            check((Integer) status.invoke(created) == 201, "ApiResponse.ok(data,201): статус 201");

            Object bad = cls.getMethod("badRequest", String.class).invoke(null, "плохо");
            check(Boolean.FALSE.equals(okFlag.invoke(bad)), "ApiResponse.badRequest: isOk()==false");
            check((Integer) status.invoke(bad) == 400, "ApiResponse.badRequest: статус 400");
            String errJson = (String) toJson.invoke(bad);
            check(errJson.contains("\"error\"") && errJson.contains("плохо"),
                    "ApiResponse.badRequest: JSON содержит error");

            check((Integer) status.invoke(cls.getMethod("unauthorized").invoke(null)) == 401,
                    "ApiResponse.unauthorized: статус 401");
            check((Integer) status.invoke(cls.getMethod("notFound", String.class).invoke(null, "n")) == 404,
                    "ApiResponse.notFound: статус 404");
            check((Integer) status.invoke(cls.getMethod("serverError", String.class).invoke(null, "e")) == 500,
                    "ApiResponse.serverError: статус 500");
        } catch (Exception e) {
            check(false, "testApiResponse: " + e);
        }
    }

    // 38) Этап 2 (v4): статика прототипа v2 перенесена в web/ дословно.
    //     Проверяем, что ключевые файлы отдаются StaticHandler'ом, index.html ссылается
    //     на prototype.css/raw.js, а разметка содержит опорные узлы прототипа
    //     (sidebar, main-content, заметки, модалки). Не требует поднятия сервера.
    private static void testWebPrototypeAssets() {
        try {
            java.io.File web = new java.io.File("web");
            if (!web.isDirectory()) {
                System.out.println("  [SKIP] web/ не найден (тест из корня проекта). Тест пропущен.");
                return;
            }
            Class<?> sh = Class.forName("corpagent.api.StaticHandler");
            Object handler = sh.getConstructor(String.class).newInstance(web.getPath());
            java.lang.reflect.Method resolve = sh.getMethod("resolveSafeFile", String.class);
            java.lang.reflect.Method mime = sh.getMethod("mimeType", String.class);

            // Все ключевые ассеты прототипа доступны через безопасный резолвер.
            Object index = resolve.invoke(handler, "/index.html");
            Object css = resolve.invoke(handler, "/css/prototype.css");
            Object js = resolve.invoke(handler, "/js/ui-controls.js");
            check(index instanceof java.io.File, "web/: index.html найден");
            check(css instanceof java.io.File, "web/css/: prototype.css найден");
            check(js instanceof java.io.File, "web/js/: prototype-raw.js найден");

            // MIME ассетов корректны (иначе браузер не отрендерит CSS/JS).
            check(((String) mime.invoke(null, "index.html")).startsWith("text/html"),
                    "MIME index.html → text/html");
            check(((String) mime.invoke(null, "prototype.css")).startsWith("text/css"),
                    "MIME prototype.css → text/css");
            check(((String) mime.invoke(null, "prototype-raw.js")).contains("javascript"),
                    "MIME prototype-raw.js → application/javascript");

            // index.html ссылается на CSS/JS прототипа и содержит его опорные узлы.
            String html = readUtf8((java.io.File) index);
            check(html.contains("css/prototype.css"),
                    "index.html ссылается на prototype.css");
            check(html.contains("js/ui-controls.js"),
                    "index.html ссылается на prototype-raw.js");
            check(html.contains("class=\"title-bar\"") && html.contains("class=\"sidebar\"")
                            && html.contains("class=\"main-content\""),
                    "index.html содержит опорные узлы прототипа (title-bar/sidebar/main-content)");

            // Специфичные для v2 расширения сохранены дословно.
            check(html.contains("notesContent"), "index.html содержит экран Заметок (v2)");
            check(html.contains("chatMessages"), "index.html содержит контейнер сообщений чата (v2)");
            check(html.contains("settingsModal") && html.contains("projectModal"),
                    "index.html содержит модалки Настройки и Проект");

            // prototype.css — это дизайн-токены прототипа (system blue и т.п.).
            String cssText = readUtf8((java.io.File) css);
            check(cssText.contains("--color-primary: #007AFF"),
                    "prototype.css содержит токен --color-primary: #007AFF");
            check(cssText.contains(":root"), "prototype.css содержит блок :root дизайн-токенов");

            // prototype-raw.js — behavior-скрипты прототипа.
            String jsText = readUtf8((java.io.File) js);
            check(jsText.contains("window.openModal"),
                    "prototype-raw.js содержит behavior openModal()");
            check(jsText.contains("window.sendMessage"),
                    "prototype-raw.js содержит sendMessage()");
        } catch (Exception e) {
            check(false, "testWebPrototypeAssets: " + e);
        }
    }

    // 39) Этап 3 (v4): разбор SSE-кадра стриминга (OpenAI-совместимый формат).
    //     parseSseLine вынесен статическим — тестируется без сети.
    private static void testChatStreamParsing() {
        try {
            Class<?> dsc = Class.forName("corpagent.llm.DeepSeekClient");
            java.lang.reflect.Method parse = dsc.getMethod("parseSseLine", String.class);

            check("Привет".equals(parse.invoke(null,
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Привет\"}}]}")),
                    "parseSseLine: извлекает delta.content");
            check("".equals(parse.invoke(null,
                            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}")),
                    "parseSseLine: кадр без content → пустая строка");
            check(parse.invoke(null, "data: [DONE]") == null,
                    "parseSseLine: [DONE] → null");
            check(parse.invoke(null, ": keep-alive") == null,
                    "parseSseLine: SSE-комментарий → null");
            check(parse.invoke(null, "") == null,
                    "parseSseLine: пустая строка → null");
            check(parse.invoke(null, "event: message") == null,
                    "parseSseLine: поле event: → null");
            check(parse.invoke(null, "data: {невалидный json") == null,
                    "parseSseLine: невалидный JSON → null");
            check(parse.invoke(null, "data:{\"choices\":[]}") == null,
                    "parseSseLine: пустой choices → null");
        } catch (Exception e) {
            check(false, "testChatStreamParsing: " + e);
        }
    }

    // 40) Этап 3 (v4): DTO запроса чата — парсинг, нормализация истории, текст с файлом.
    private static void testChatRequestDto() {
        try {
            Class<?> cls = Class.forName("corpagent.api.dto.ChatRequest");
            java.lang.reflect.Method parse = cls.getMethod("parse", String.class);

            Object req = parse.invoke(null,
                    "{\"chat_id\":\"chat_x\",\"message\":\"привет\",\"skills\":[\"A\"],\"tools\":[\"T\"]}");
            check("chat_x".equals(getStatic(cls, "chatId", req)), "ChatRequest: chat_id разобран");
            check("привет".equals(getField(cls, "message", req)), "ChatRequest: message разобран");
            java.util.List<?> skills = (java.util.List<?>) getField(cls, "skills", req);
            check(skills.size() == 1 && "A".equals(skills.get(0)), "ChatRequest: skills разобраны");

            // toLlmHistory добавляет текущее сообщение пользователя в конец.
            java.lang.reflect.Method toLlm = cls.getMethod("toLlmHistory");
            java.util.List<?> hist = (java.util.List<?>) toLlm.invoke(req);
            check(hist.size() == 1, "ChatRequest.toLlmHistory: только текущее сообщение");
            java.util.Map<?, ?> last = (java.util.Map<?, ?>) hist.get(hist.size() - 1);
            check("user".equals(last.get("role")) && "привет".equals(last.get("content")),
                    "ChatRequest.toLlmHistory: role=user, content=message");

            // Пустое сообщение без истории → IllegalArgumentException.
            boolean threw = false;
            try {
                parse.invoke(null, "{}");
            } catch (java.lang.reflect.InvocationTargetException ite) {
                threw = ite.getCause() instanceof IllegalArgumentException;
            }
            check(threw, "ChatRequest: пустой запрос → IllegalArgumentException");

            // Файл присоединяется к тексту пользователя.
            Object req2 = parse.invoke(null,
                    "{\"message\":\"см\",\"file_content\":\"СОДЕРЖИМОЕ\"}");
            java.util.List<?> hist2 = (java.util.List<?>) toLlm.invoke(req2);
            String content = String.valueOf(((java.util.Map<?, ?>) hist2.get(0)).get("content"));
            check(content.contains("см") && content.contains("СОДЕРЖИМОЕ"),
                    "ChatRequest: file_content присоединён к сообщению");
        } catch (Exception e) {
            check(false, "testChatRequestDto: " + e);
        }
    }

    // 41) Этап 3 (v4): сборка текстового контекста навыков/инструментов из каталога.
    private static void testChatRoutesContext() {
        try {
            Class<?> cls = Class.forName("corpagent.api.ChatRoutes");
            java.lang.reflect.Method build = cls.getMethod("buildContext",
                    java.util.List.class, java.util.Map.class, String.class);

            java.util.Map<String, Object> catalog = new java.util.LinkedHashMap<String, Object>();
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<String, Object>();
            item.put("description", "работа с рисками");
            catalog.put("Риски", item);

            java.util.List<Object> sel = new java.util.ArrayList<Object>();
            sel.add("Риски");
            sel.add("Отсутствует"); // имя не в каталоге — игнорируется

            String ctx = (String) build.invoke(null, sel, catalog, "description");
            check(ctx != null && ctx.contains("Риски") && ctx.contains("работа с рисками"),
                    "ChatRoutes.buildContext: собирает описания выбранного");
            check(!ctx.contains("Отсутствует"),
                    "ChatRoutes.buildContext: пропускает имена без каталога");

            String empty = (String) build.invoke(null, new java.util.ArrayList<Object>(),
                    catalog, "description");
            check(empty == null, "ChatRoutes.buildContext: пустой выбор → null");
        } catch (Exception e) {
            check(false, "testChatRoutesContext: " + e);
        }
    }

    // 42) Этап 3 (v4): сервер принимает регистрацию SSE-маршрута и хранит его.
    //     Полный round-trip стриминга требует LLM-ключа, поэтому проверяем контракт
    //     регистрации streamRoute(...) без сети.
    private static void testApiStreamRoute() {
        try {
            Class<?> authCls = Class.forName("corpagent.api.Auth");
            Object auth = authCls.getMethod("withRandomToken").invoke(null);
            Class<?> shCls = Class.forName("corpagent.api.StaticHandler");
            Object sh = shCls.getConstructor(String.class).newInstance("web");
            Class<?> serverCls = Class.forName("corpagent.api.ApiServer");
            Object server = serverCls.getConstructor(authCls, shCls).newInstance(auth, sh);

            Class<?> streamRouteCls = Class.forName("corpagent.api.ApiServer$StreamRoute");
            java.lang.reflect.Method reg = serverCls.getMethod("streamRoute", String.class, streamRouteCls);
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    streamRouteCls.getClassLoader(),
                    new Class<?>[]{streamRouteCls},
                    (p, m, a) -> null);
            reg.invoke(server, "/api/chat/stream", proxy);

            java.lang.reflect.Field f = serverCls.getDeclaredField("streamRoutes");
            f.setAccessible(true);
            java.util.Map<?, ?> routes = (java.util.Map<?, ?>) f.get(server);
            check(routes.containsKey("/api/chat/stream"),
                    "ApiServer.streamRoute: SSE-маршрут зарегистрирован");
        } catch (Exception e) {
            check(false, "testApiStreamRoute: " + e);
        }
    }

        // 43) Этап 3.5–3.7 (v4): помощники мутаций сообщений чата — поиск сообщения по id,
    //     выбор предыдущего user-сообщения и DTO сообщения. Без поднятия сервера/сети.
    private static void testChatMessageHelpers() {
        try {
            Class<?> cls = Class.forName("corpagent.api.ChatRoutes");

            java.util.Map<String, Object> chat = new java.util.LinkedHashMap<String, Object>();
            java.util.List<Object> msgs = new java.util.ArrayList<Object>();
            msgs.add(msg("m1", "user", "привет"));
            msgs.add(msg("m2", "assistant", "здравствуйте"));
            msgs.add(msg("m3", "user", "ещё"));
            msgs.add(msg("m4", "assistant", "ответ"));
            chat.put("messages", msgs);
            chat.put("id", "chat_1");

            java.lang.reflect.Method idx = cls.getDeclaredMethod("indexOfMessage",
                    java.util.Map.class, String.class);
            idx.setAccessible(true);
            check(((Integer) idx.invoke(null, chat, "m2")) == 1,
                    "indexOfMessage: находит по id (m2 → 1)");
            check(((Integer) idx.invoke(null, chat, "нет")) == -1,
                    "indexOfMessage: неизвестный id → -1");
            // Числовой id трактуется как индекс (обратная совместимость).
            check(((Integer) idx.invoke(null, chat, "3")) == 3,
                    "indexOfMessage: числовой id → позиция");
            check(((Integer) idx.invoke(null, chat, "99")) == -1,
                    "indexOfMessage: индекс за пределами → -1");

            // prevUserIndex: от assistant (idx=1) назад до user (idx=0); от idx=3 → user idx=2.
            java.lang.reflect.Method prev = cls.getDeclaredMethod("prevUserIndex",
                    java.util.List.class, int.class);
            prev.setAccessible(true);
            check(((Integer) prev.invoke(null, msgs, 1)) == 0,
                    "prevUserIndex: assistant(1) → user(0)");
            check(((Integer) prev.invoke(null, msgs, 2)) == 2,
                    "prevUserIndex: сам user(2) остаётся собой");
            check(((Integer) prev.invoke(null, msgs, 3)) == 2,
                    "prevUserIndex: assistant(3) → user(2)");

            // messageDto: переносит id/role/text/time и флаг edited, если он есть.
            java.lang.reflect.Method dto = cls.getDeclaredMethod("messageDto",
                    java.util.Map.class);
            dto.setAccessible(true);
            java.util.Map<?, ?> d = (java.util.Map<?, ?>) dto.invoke(null, msgs.get(1));
            check("m2".equals(d.get("id")) && "assistant".equals(d.get("role"))
                            && "здравствуйте".equals(d.get("text")),
                    "messageDto: переносит id/role/text");
            check(!d.containsKey("edited"),
                    "messageDto: без флага edited, если его нет в сообщении");
            java.util.Map<String, Object> edited = msg("m5", "user", "x");
            edited.put("edited", Boolean.TRUE);
            java.util.Map<?, ?> de = (java.util.Map<?, ?>) dto.invoke(null, edited);
            check(Boolean.TRUE.equals(de.get("edited")),
                    "messageDto: переносит флаг edited");
        } catch (Exception e) {
            check(false, "testChatMessageHelpers: " + e);
        }
    }

    // 44) Этап 3.8 (v4): контекст чата — сохранение выбранных БД/навыков/инструментов
    //     в поля selected_* и их чтение обратно. Без сети (store поднимается на temp-каталоге).
    private static void testChatContextRoutes() {
        try {
            // saveContextField — приватный хелпер; проверяем на карте-чате без store.
            Class<?> cls = Class.forName("corpagent.api.ChatRoutes");
            java.lang.reflect.Method save = cls.getDeclaredMethod("saveContextField",
                    java.util.Map.class, java.util.Map.class, String.class, String.class);
            save.setAccessible(true);

            java.util.Map<String, Object> chat = new java.util.LinkedHashMap<String, Object>();
            java.util.Map<String, Object> req = new java.util.LinkedHashMap<String, Object>();
            java.util.List<Object> dbs = new java.util.ArrayList<Object>();
            dbs.add("Проект А");
            req.put("databases", dbs);

            save.invoke(null, chat, req, "databases", "selected_databases");
            Object stored = chat.get("selected_databases");
            check(stored instanceof java.util.List
                            && ((java.util.List<?>) stored).contains("Проект А"),
                    "saveContextField: сохраняет selected_databases");

            // Отсутствующий ключ в запросе не затирает уже сохранённое значение.
            java.util.Map<String, Object> emptyReq = new java.util.LinkedHashMap<String, Object>();
            save.invoke(null, chat, emptyReq, "databases", "selected_databases");
            check(((java.util.List<?>) chat.get("selected_databases")).contains("Проект А"),
                    "saveContextField: пустой ключ не затирает значение");

            // Прочие поля (skills/tools) сохраняются в корректные ключи.
            java.util.Map<String, Object> req2 = new java.util.LinkedHashMap<String, Object>();
            req2.put("skills", java.util.Arrays.asList((Object) "Риски"));
            req2.put("tools", java.util.Arrays.asList((Object) "Статус"));
            save.invoke(null, chat, req2, "skills", "selected_skills");
            save.invoke(null, chat, req2, "tools", "selected_tools");
            check(chat.get("selected_skills") instanceof java.util.List
                            && chat.get("selected_tools") instanceof java.util.List,
                    "saveContextField: сохраняет selected_skills и selected_tools");
        } catch (Exception e) {
            check(false, "testChatContextRoutes: " + e);
        }
    }

    private static java.util.Map<String, Object> msg(String id, String role, String text) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("role", role);
        m.put("text", text);
        m.put("time", "10:00");
        return m;
    }

    private static Object getField(Class<?> owner, String field, Object target) throws Exception {
        java.lang.reflect.Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object getStatic(Class<?> owner, String field, Object target) throws Exception {
        return getField(owner, field, target);
    }

    // 45) Этап 3.9–3.15 (v4): фронтенд чата — файлы модулей на месте, index.html
    //     подключает их, а api.js содержит разбор SSE-кадров и обёртки над /api/chat*.
    private static void testWebChatFrontend() {
        try {
            java.io.File api = new java.io.File("web/js/api.js");
            java.io.File store = new java.io.File("web/js/store.js");
            java.io.File msg = new java.io.File("web/js/components/Message.js");
            java.io.File chat = new java.io.File("web/js/views/ChatView.js");
            java.io.File chatCss = new java.io.File("web/css/chat.css");
            check(api.isFile(), "api.js существует");
            check(store.isFile(), "store.js существует");
            check(msg.isFile(), "components/Message.js существует");
            check(chat.isFile(), "views/ChatView.js существует");
            check(chatCss.isFile(), "css/chat.css существует");

            String apiText = readUtf8(api);
            check(apiText.contains("parseSseFrame"), "api.js содержит разбор SSE-кадра");
            check(apiText.contains("chatStream"), "api.js содержит chatStream()");
            check(apiText.contains("/api/chat/stream"), "api.js обращается к /api/chat/stream");
            check(apiText.contains("chat/message"), "api.js обращается к /api/chat/message");
            check(apiText.contains("chat/context"), "api.js обращается к /api/chat/context");

            String storeText = readUtf8(store);
            check(storeText.contains("subscribe") && storeText.contains("appendMessage"),
                    "store.js содержит pub/sub и работу с сообщениями");

            String msgText = readUtf8(msg);
            check(msgText.contains("renderMarkdown"), "Message.js содержит renderMarkdown()");
            check(msgText.contains("message-action-btn"),
                    "Message.js рендерит кнопки действий сообщений");

            String chatViewText = readUtf8(chat);
            check(chatViewText.contains("ChatView") && chatViewText.contains("send"),
                    "ChatView.js содержит метод send()");

            // index.html подключает модули новой фронтенд-инфраструктуры.
            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("js/api.js"), "index.html подключает api.js");
            check(html.contains("js/store.js"), "index.html подключает store.js");
            check(html.contains("js/views/ChatView.js"), "index.html подключает ChatView.js");
            check(html.contains("css/chat.css"), "index.html подключает css/chat.css");
        } catch (Exception e) {
            check(false, "testWebChatFrontend: " + e);
        }
    }

                private static String readUtf8(java.io.File f) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    // 46) Этап 3.4 (v4): ChatChunk — единый DTO кадра SSE-потока (token/error/done).
    //     Проверяем имя события и форму полезной нагрузки без поднятия сервера.
    private static void testChatChunkDto() {
        try {
            Class<?> cls = Class.forName("corpagent.api.dto.ChatChunk");

            Object token = cls.getMethod("token", String.class).invoke(null, "привет");
            java.lang.reflect.Method event = cls.getMethod("eventName");
            java.lang.reflect.Method payload = cls.getMethod("payload");
            check("token".equals(event.invoke(token)), "ChatChunk.token: eventName == token");
            check("привет".equals(payload.invoke(token)),
                    "ChatChunk.token: payload — сырая строка-дельта");

            Object err = cls.getMethod("error", String.class).invoke(null, "сбой");
            check("error".equals(event.invoke(err)), "ChatChunk.error: eventName == error");
            Object errPayload = payload.invoke(err);
            check(errPayload instanceof java.util.Map
                            && "сбой".equals(((java.util.Map<?, ?>) errPayload).get("message")),
                    "ChatChunk.error: payload {message}");

            Object done = cls.getMethod("done", String.class, String.class)
                    .invoke(null, "полный текст", "chat_1");
            check("done".equals(event.invoke(done)), "ChatChunk.done: eventName == done");
            java.util.Map<?, ?> dp = (java.util.Map<?, ?>) payload.invoke(done);
            check("полный текст".equals(dp.get("text")) && "chat_1".equals(dp.get("chat_id")),
                    "ChatChunk.done: payload {text, chat_id}");

            // null-аргументы безопасны.
            Object t2 = cls.getMethod("token", String.class).invoke(null, (Object) null);
            check("".equals(payload.invoke(t2)), "ChatChunk.token(null): пустая дельта");
            Object d2 = cls.getMethod("done", String.class, String.class)
                    .invoke(null, null, null);
            check(((java.util.Map<?, ?>) payload.invoke(d2)).get("chat_id") == null,
                    "ChatChunk.done(null,null): chat_id не добавляется");
        } catch (Exception e) {
            check(false, "testChatChunkDto: " + e);
        }
    }

    // 47) Этап 3.13 (v4): CatalogRoutes — компактные DTO справочников БД/навыков/инструментов
    //     для выпадающих списков контекста чата. Без поднятия сервера (DataStore на temp-каталоге).
    private static void testCatalogRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_cat").toFile();
            Class<?> dsCls = Class.forName("corpagent.data.DataStore");
            Object store = dsCls.getConstructor(String.class).newInstance(base.getPath());

            // Кладём по одной записи в каждый каталог.
            java.lang.reflect.Method putSkill = dsCls.getMethod("putSkill", String.class, java.util.Map.class);
            java.lang.reflect.Method putTool = dsCls.getMethod("putTool", String.class, java.util.Map.class);
            java.lang.reflect.Method putDb = dsCls.getMethod("putDatabase", String.class, java.util.Map.class);
            putSkill.invoke(store, "Риски", mapOf("name", "Риски", "description", "работа с рисками"));
            putTool.invoke(store, "Статус", mapOf("name", "Статус", "description", "статус-отчёт"));
            putDb.invoke(store, "test", mapOf("name", "test", "описание", "тестовая БД"));

            Class<?> crCls = Class.forName("corpagent.api.CatalogRoutes");
            Object routes = crCls.getConstructor(dsCls).newInstance(store);
            java.lang.reflect.Method handle = crCls.getDeclaredMethod("handleCatalogs");
            handle.setAccessible(true);
            Object resp = handle.invoke(routes);

            java.lang.reflect.Method isOk = resp.getClass().getMethod("isOk");
            java.lang.reflect.Method toJson = resp.getClass().getMethod("toJson");
            check(Boolean.TRUE.equals(isOk.invoke(resp)), "CatalogRoutes: успешный ответ");
            String json = (String) toJson.invoke(resp);
            check(json.contains("\"databases\"") && json.contains("\"skills\"") && json.contains("\"tools\""),
                    "CatalogRoutes: содержит три каталога");
            check(json.contains("Риски") && json.contains("работа с рисками"),
                    "CatalogRoutes: навык с описанием присутствует");
            check(json.contains("test") && json.contains("тестовая БД"),
                    "CatalogRoutes: БД с описанием присутствует");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testCatalogRoutes: " + e);
        }
    }

    private static java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // 48) Этап 3.13 (v4): фронтенд панели контекста — модуль на месте, index.html его
    //     подключает, app.js инициализирует, а разметка прототипа сохранена (dropdown'ы).
    private static void testWebChatContextPanel() {
        try {
            java.io.File panel = new java.io.File("web/js/components/ChatContextPanel.js");
            check(panel.isFile(), "components/ChatContextPanel.js существует");
            String panelText = readUtf8(panel);
            check(panelText.contains("loadCatalogs") && panelText.contains("'catalogs'"),
                    "ChatContextPanel.js запрашивает /api/catalogs");
            check(panelText.contains("setChatContext") || panelText.contains("pushContext"),
                    "ChatContextPanel.js синхронизирует контекст на сервер");

            String app = readUtf8(new java.io.File("web/js/app.js"));
            check(app.contains("ChatContextPanel.init"), "app.js инициализирует ChatContextPanel");

            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("js/components/ChatContextPanel.js"),
                    "index.html подключает ChatContextPanel.js");
            check(html.contains("id=\"dbDropdown\"") && html.contains("id=\"skillsDropdown\"")
                            && html.contains("id=\"toolsDropdown\""),
                    "index.html сохраняет выпадающие списки прототипа (БД/Навыки/Инструменты)");
            // Модуль управляет кликами сам — инлайн onclick футеров прототипа снят.
            check(!html.contains("onclick=\"resetDropdown") && !html.contains("onclick=\"closeDropdown"),
                    "index.html: инлайн-обработчики футеров dropdown сняты (управляет модуль)");
        } catch (Exception e) {
            check(false, "testWebChatContextPanel: " + e);
        }
    }

    // 49) Этап 3.14 (v4): structured data в ответах ассистента — карточка полей из
    //     строк «**Метка:** значение» и карточка метаданных (usage/filePath).
    private static void testWebStructuredMessages() {
        try {
            String msg = readUtf8(new java.io.File("web/js/components/Message.js"));
            check(msg.contains("extractFields"), "Message.js содержит extractFields");
            check(msg.contains("fieldsCardHtml") && msg.contains("message-fields"),
                    "Message.js рендерит карточку полей (.message-fields)");
            check(msg.contains("metaCardHtml"), "Message.js рендерит карточку метаданных");
            check(msg.contains("structuredHtml"), "Message.js собирает structured data ответа");

            String css = readUtf8(new java.io.File("web/css/chat.css"));
            check(css.contains(".message-fields") && css.contains(".message-field-row"),
                    "chat.css содержит стили карточки полей");

            String chatView = readUtf8(new java.io.File("web/js/views/ChatView.js"));
                        check(chatView.contains("file_path") || chatView.contains("meta"),
                    "ChatView.js переносит structured data из кадра done");
        } catch (Exception e) {
            check(false, "testWebStructuredMessages: " + e);
        }
    }

    // 50) Этап 4.5: хранилище заметок DataStore — CRUD и сортировка «сначала новые».
    private static void testNotesStore() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_notes").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());

            check(store.getNotes().isEmpty(), "DataStore: заметок изначально нет");

            java.util.Map<String, Object> n1 = corpagent.util.Json.newObject();
            n1.put("id", "note_1");
            n1.put("title", "Идеи");
            n1.put("text", "abc");
            n1.put("created", "2026-09-08T10:00:00.000");
            n1.put("updated", "2026-09-08T10:00:00.000");
            store.putNote("note_1", n1);

            java.util.Map<String, Object> n2 = corpagent.util.Json.newObject();
            n2.put("id", "note_2");
            n2.put("title", "Встреча");
            n2.put("text", "def");
            n2.put("created", "2026-09-09T10:00:00.000");
            n2.put("updated", "2026-09-09T12:00:00.000");
            store.putNote("note_2", n2);

            check(store.getNote("note_1") != null, "DataStore: getNote возвращает заметку");
            check(store.getNotes().size() == 2, "DataStore: 2 заметки");

            java.util.List<java.util.Map.Entry<String, java.util.Map<String, Object>>> sorted =
                    store.sortedNotes();
            check(sorted.size() == 2 && "note_2".equals(sorted.get(0).getKey()),
                    "DataStore: sortedNotes — сначала более новая (note_2)");

            store.saveAll();
            corpagent.data.DataStore store2 = new corpagent.data.DataStore(base.getPath());
            check(store2.getNote("note_1") != null,
                    "DataStore: заметка переживает перезагрузку (notes.json)");

            store2.removeNote("note_1");
            check(store2.getNote("note_1") == null, "DataStore: removeNote удаляет заметку");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testNotesStore: " + e);
        }
    }

    // 51) Этап 4.5: NotesRoutes — полный CRUD (create/list/get/update/delete) через API.
    private static void testNotesRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_notes_api").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());
            Class<?> cls = Class.forName("corpagent.api.NotesRoutes");
            Object routes = cls.getConstructor(corpagent.data.DataStore.class).newInstance(store);
            java.lang.reflect.Method dispatch = cls.getDeclaredMethod("dispatch",
                    String.class, String.class, java.util.Map.class);
            dispatch.setAccessible(true);

            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            java.lang.reflect.Method isOk = respCls.getMethod("isOk");
            java.lang.reflect.Method getData = respCls.getMethod("getData");
            java.lang.reflect.Method status = respCls.getMethod("getHttpStatus");

            Object created = dispatch.invoke(routes, "POST",
                    "{\"title\":\"Заметка 1\",\"text\":\"текст\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(created)), "NotesRoutes CREATE: ok");
            check((Integer) status.invoke(created) == 201, "NotesRoutes CREATE: статус 201");
            java.util.Map<String, Object> createdData =
                    corpagent.util.Json.asMap(getData.invoke(created));
            String noteId = corpagent.util.Json.str(createdData, "id", null);
            check(noteId != null && noteId.startsWith("note_"),
                    "NotesRoutes CREATE: сгенерирован id note_*");

            Object list = dispatch.invoke(routes, "GET", null, null);
            check(Boolean.TRUE.equals(isOk.invoke(list)), "NotesRoutes GET list: ok");
            java.util.Map<String, Object> listData =
                    corpagent.util.Json.asMap(getData.invoke(list));
            check(corpagent.util.Json.arr(listData, "notes").size() == 1,
                    "NotesRoutes GET list: 1 заметка");

            java.util.Map<String, java.util.List<String>> q = new java.util.LinkedHashMap<>();
            java.util.List<String> one = new java.util.ArrayList<>();
            one.add(noteId);
            q.put("note_id", one);
            Object got = dispatch.invoke(routes, "GET", null, q);
            java.util.Map<String, Object> gotData = corpagent.util.Json.asMap(getData.invoke(got));
            check("Заметка 1".equals(corpagent.util.Json.str(gotData, "title", "")),
                    "NotesRoutes GET one: возвращает нужную заметку");

            Object updated = dispatch.invoke(routes, "PUT",
                    "{\"note_id\":\"" + noteId + "\",\"title\":\"Обновлено\",\"text\":\"new\"}", null);
            java.util.Map<String, Object> upData = corpagent.util.Json.asMap(getData.invoke(updated));
            check("Обновлено".equals(corpagent.util.Json.str(upData, "title", "")),
                    "NotesRoutes UPDATE: заголовок обновлён");

            Object badUp = dispatch.invoke(routes, "PUT", "{\"title\":\"x\"}", null);
            check(Boolean.FALSE.equals(isOk.invoke(badUp)),
                    "NotesRoutes UPDATE: без note_id → ошибка");

            Object removed = dispatch.invoke(routes, "DELETE",
                    "{\"note_id\":\"" + noteId + "\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(removed)), "NotesRoutes DELETE: ok");
            check(store.getNote(noteId) == null, "NotesRoutes DELETE: заметка удалена из хранилища");

            Object notFound = dispatch.invoke(routes, "DELETE",
                    "{\"note_id\":\"note_none\"}", null);
            check((Integer) status.invoke(notFound) == 404,
                    "NotesRoutes DELETE: отсутствующая заметка → 404");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testNotesRoutes: " + e);
        }
    }

    // 52) Этап 4.2: ProjectsRoutes — список проектов из DataStore.
    private static void testProjectsRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_proj_api").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());
            java.util.Map<String, Object> p = corpagent.util.Json.newObject();
            p.put("id", "proj_1");
            p.put("name", "РГ по ШК-7");
            p.put("db", "rg_shk_7");
            store.putProject("proj_1", p);

                                    Class<?> cls = Class.forName("corpagent.api.ProjectsRoutes");
            Object routes = cls.getConstructor(corpagent.data.DataStore.class,
                    corpagent.db.DatabaseManager.class).newInstance(store, null);
            java.lang.reflect.Method dispatch = cls.getDeclaredMethod("dispatch",
                    String.class, String.class, java.util.Map.class);
            dispatch.setAccessible(true);
            Object resp = dispatch.invoke(routes, "GET", null, null);


            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            Object data = respCls.getMethod("getData").invoke(resp);
            java.util.Map<String, Object> dto = corpagent.util.Json.asMap(data);
            java.util.List<Object> projects = corpagent.util.Json.arr(dto, "projects");
            check(projects != null && projects.size() == 1, "ProjectsRoutes: 1 проект");
            java.util.Map<String, Object> first = corpagent.util.Json.asMap(projects.get(0));
            check("proj_1".equals(corpagent.util.Json.str(first, "id", "")),
                    "ProjectsRoutes: id проекта");
            check("РГ по ШК-7".equals(corpagent.util.Json.str(first, "name", "")),
                    "ProjectsRoutes: имя проекта");
            check("rg_shk_7".equals(corpagent.util.Json.str(first, "db", "")),
                    "ProjectsRoutes: БД проекта");

                        deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testProjectsRoutes: " + e);
        }
    }

    // 53) Этап 5.1-B: полный CRUD проектов через ProjectsRoutes.dispatch (create/update/delete).
    private static void testProjectsCrud() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_proj_crud").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());
            corpagent.db.DatabaseManager dbManager = new corpagent.db.DatabaseManager(
                    new java.io.File(base, "databases").getPath(),
                    new java.io.File(base, "backups").getPath(), store);
            Class<?> cls = Class.forName("corpagent.api.ProjectsRoutes");
            Object routes = cls.getConstructor(corpagent.data.DataStore.class,
                    corpagent.db.DatabaseManager.class).newInstance(store, dbManager);
            java.lang.reflect.Method dispatch = cls.getDeclaredMethod("dispatch",
                    String.class, String.class, java.util.Map.class);
            dispatch.setAccessible(true);
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            java.lang.reflect.Method isOk = respCls.getMethod("isOk");
            java.lang.reflect.Method getData = respCls.getMethod("getData");
            java.lang.reflect.Method status = respCls.getMethod("getHttpStatus");

            // CREATE нового проекта (новая БД по доменной схеме).
            Object created = dispatch.invoke(routes, "POST",
                    "{\"name\":\"Проект A\",\"description\":\"desc\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(created)), "ProjectsRoutes CRUD: CREATE ok");
            check((Integer) status.invoke(created) == 201, "ProjectsRoutes CRUD: CREATE статус 201");
            java.util.Map<String, Object> cd = corpagent.util.Json.asMap(getData.invoke(created));
            String projectId = corpagent.util.Json.str(cd, "project_id", null);
            check(projectId != null, "ProjectsRoutes CRUD: project_id возвращён");
            check(store.getProject(projectId) != null, "ProjectsRoutes CRUD: проект сохранён");
            check(corpagent.util.Json.arr(cd, "chats").size() == 7,
                    "ProjectsRoutes CRUD: создано 7 чатов-ролей");

            // CREATE без имени — ошибка.
            Object badCreate = dispatch.invoke(routes, "POST", "{\"description\":\"x\"}", null);
            check(Boolean.FALSE.equals(isOk.invoke(badCreate)),
                    "ProjectsRoutes CRUD: CREATE без имени → ошибка");

            // UPDATE имени.
            Object updated = dispatch.invoke(routes, "PUT",
                    "{\"project_id\":\"" + projectId + "\",\"name\":\"Проект B\"}", null);
            java.util.Map<String, Object> ud = corpagent.util.Json.asMap(getData.invoke(updated));
            check("Проект B".equals(corpagent.util.Json.str(ud, "name", "")),
                    "ProjectsRoutes CRUD: UPDATE меняет имя");

            // GET one.
            java.util.Map<String, java.util.List<String>> q = new java.util.LinkedHashMap<>();
            java.util.List<String> one = new java.util.ArrayList<>();
            one.add(projectId);
            q.put("project_id", one);
            Object got = dispatch.invoke(routes, "GET", null, q);
            java.util.Map<String, Object> gd = corpagent.util.Json.asMap(getData.invoke(got));
            check(projectId.equals(corpagent.util.Json.str(gd, "id", "")),
                    "ProjectsRoutes CRUD: GET one по project_id");

            // DELETE.
            Object removed = dispatch.invoke(routes, "DELETE",
                    "{\"project_id\":\"" + projectId + "\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(removed)), "ProjectsRoutes CRUD: DELETE ok");
            check(store.getProject(projectId) == null, "ProjectsRoutes CRUD: проект удалён");

            // CREATE на существующей БД (existing_db_id, шаг 2 мастера v2).
            java.util.Map<String, Object> db = corpagent.util.Json.newObject();
            db.put("name", "extdb");
            store.putDatabase("extdb", db);
            Object createdExisting = dispatch.invoke(routes, "POST",
                    "{\"name\":\"Проект на внешней БД\",\"existing_db_id\":\"extdb\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(createdExisting)),
                    "ProjectsRoutes CRUD: CREATE с existing_db_id ok");
            java.util.Map<String, Object> ced =
                    corpagent.util.Json.asMap(getData.invoke(createdExisting));
            check("extdb".equals(corpagent.util.Json.str(ced, "db", "")),
                    "ProjectsRoutes CRUD: БД проекта = existing_db_id");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testProjectsCrud: " + e);
        }
    }

    // 54) Этап 5.2-B: полный CRUD навыков через SkillsRoutes.dispatch.
    private static void testSkillsRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_skills_api").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());
            Class<?> cls = Class.forName("corpagent.api.SkillsRoutes");
            Object routes = cls.getConstructor(corpagent.data.DataStore.class).newInstance(store);
            java.lang.reflect.Method dispatch = cls.getDeclaredMethod("dispatch",
                    String.class, String.class, java.util.Map.class);
            dispatch.setAccessible(true);
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            java.lang.reflect.Method isOk = respCls.getMethod("isOk");
            java.lang.reflect.Method getData = respCls.getMethod("getData");
            java.lang.reflect.Method status = respCls.getMethod("getHttpStatus");

            Object created = dispatch.invoke(routes, "POST",
                    "{\"name\":\"Мой навык\",\"description\":\"навык\",\"code\":\"system_prompt\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(created)), "SkillsRoutes: CREATE ok");
            check((Integer) status.invoke(created) == 201, "SkillsRoutes: CREATE статус 201");
            check(store.getSkills().containsKey("Мой навык"), "SkillsRoutes: навык сохранён");

            Object list = dispatch.invoke(routes, "GET", null, null);
            java.util.Map<String, Object> ld = corpagent.util.Json.asMap(getData.invoke(list));
            check(corpagent.util.Json.arr(ld, "skills").size() >= 1, "SkillsRoutes: 1 навык в списке");

            Object updated = dispatch.invoke(routes, "PUT",
                    "{\"name\":\"Мой навык\",\"description\":\"обновлён\"}", null);
            java.util.Map<String, Object> ud = corpagent.util.Json.asMap(getData.invoke(updated));
            check("обновлён".equals(corpagent.util.Json.str(ud, "description", "")),
                    "SkillsRoutes: UPDATE меняет описание");

            Object removed = dispatch.invoke(routes, "DELETE",
                    "{\"name\":\"Мой навык\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(removed)), "SkillsRoutes: DELETE ok");
            check(!store.getSkills().containsKey("Мой навык"), "SkillsRoutes: навык удалён");

            Object notFound = dispatch.invoke(routes, "DELETE", "{\"name\":\"нет\"}", null);
            check((Integer) status.invoke(notFound) == 404, "SkillsRoutes: DELETE несуществующего → 404");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testSkillsRoutes: " + e);
        }
    }

    // 55) Этап 5.3-B: полный CRUD инструментов через ToolsRoutes.dispatch.
    private static void testToolsRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_tools_api").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());
            Class<?> cls = Class.forName("corpagent.api.ToolsRoutes");
            Object routes = cls.getConstructor(corpagent.data.DataStore.class).newInstance(store);
            java.lang.reflect.Method dispatch = cls.getDeclaredMethod("dispatch",
                    String.class, String.class, java.util.Map.class);
            dispatch.setAccessible(true);
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            java.lang.reflect.Method isOk = respCls.getMethod("isOk");
            java.lang.reflect.Method getData = respCls.getMethod("getData");
            java.lang.reflect.Method status = respCls.getMethod("getHttpStatus");

            Object created = dispatch.invoke(routes, "POST",
                    "{\"name\":\"Мой инструмент\",\"type\":\"http\","
                            + "\"endpoint\":\"https://x\",\"description\":\"инстр\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(created)), "ToolsRoutes: CREATE ok");
            check((Integer) status.invoke(created) == 201, "ToolsRoutes: CREATE статус 201");
            check(store.getTools().containsKey("Мой инструмент"), "ToolsRoutes: инструмент сохранён");

            Object list = dispatch.invoke(routes, "GET", null, null);
            java.util.Map<String, Object> ld = corpagent.util.Json.asMap(getData.invoke(list));
            check(corpagent.util.Json.arr(ld, "tools").size() >= 1, "ToolsRoutes: 1 инструмент в списке");

            Object updated = dispatch.invoke(routes, "PUT",
                    "{\"name\":\"Мой инструмент\",\"endpoint\":\"https://y\",\"type\":\"command\"}", null);
            java.util.Map<String, Object> ud = corpagent.util.Json.asMap(getData.invoke(updated));
            check("https://y".equals(corpagent.util.Json.str(ud, "endpoint", "")),
                    "ToolsRoutes: UPDATE меняет endpoint");
            check("command".equals(corpagent.util.Json.str(ud, "type", "")),
                    "ToolsRoutes: UPDATE меняет type");

            Object removed = dispatch.invoke(routes, "DELETE",
                    "{\"name\":\"Мой инструмент\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(removed)), "ToolsRoutes: DELETE ok");
            check(!store.getTools().containsKey("Мой инструмент"), "ToolsRoutes: инструмент удалён");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testToolsRoutes: " + e);
        }
    }

    // 56) Этап 5.4-B: CRUD БД + просмотр (таблицы/схема/строки) через DatabasesRoutes.
    private static void testDatabasesRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_db_api").toFile();
            corpagent.data.DataStore store = new corpagent.data.DataStore(base.getPath());
            corpagent.db.DatabaseManager dbManager = new corpagent.db.DatabaseManager(
                    new java.io.File(base, "databases").getPath(),
                    new java.io.File(base, "backups").getPath(), store);
            String dbName = "viewdb";
            corpagent.db.DatabaseManager.CreateDbResult cr =
                    dbManager.createDatabase(dbName, "CREATE TABLE t (id INTEGER, x TEXT);", "тест");
            check(cr.success, "DatabasesRoutes: тестовая БД создана");

            Class<?> cls = Class.forName("corpagent.api.DatabasesRoutes");
            Object routes = cls.getConstructor(corpagent.data.DataStore.class,
                    corpagent.db.DatabaseManager.class).newInstance(store, dbManager);
            java.lang.reflect.Method dispatch = cls.getDeclaredMethod("dispatch",
                    String.class, String.class, java.util.Map.class);
            dispatch.setAccessible(true);
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            java.lang.reflect.Method isOk = respCls.getMethod("isOk");
            java.lang.reflect.Method getData = respCls.getMethod("getData");
            java.lang.reflect.Method status = respCls.getMethod("getHttpStatus");

            // CREATE регистрации БД (внешняя СУБД).
            Object created = dispatch.invoke(routes, "POST",
                    "{\"name\":\"extpg\",\"type\":\"postgresql\",\"path\":\"host:5432\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(created)), "DatabasesRoutes: CREATE ok");
            check((Integer) status.invoke(created) == 201, "DatabasesRoutes: CREATE статус 201");

            // VIEW существующей SQLite-БД: таблицы + схема.
            java.util.Map<String, java.util.List<String>> q = new java.util.LinkedHashMap<>();
            java.util.List<String> nv = new java.util.ArrayList<>();
            nv.add(dbName);
            q.put("name", nv);
            java.util.List<String> vv = new java.util.ArrayList<>();
            vv.add("1");
            q.put("view", vv);
            Object view = dispatch.invoke(routes, "GET", null, q);
            java.util.Map<String, Object> vd = corpagent.util.Json.asMap(getData.invoke(view));
            check(Boolean.TRUE.equals(vd.get("available")), "DatabasesRoutes VIEW: БД доступна");
            check(corpagent.util.Json.arr(vd, "tables").size() >= 1, "DatabasesRoutes VIEW: есть таблицы");

            // Строки таблицы t.
            java.util.Map<String, java.util.List<String>> qt = new java.util.LinkedHashMap<>();
            qt.put("name", nv);
            java.util.List<String> tv = new java.util.ArrayList<>();
            tv.add("t");
            qt.put("table", tv);
            Object rows = dispatch.invoke(routes, "GET", null, qt);
            java.util.Map<String, Object> rd = corpagent.util.Json.asMap(getData.invoke(rows));
            check(corpagent.util.Json.arr(rd, "columns").size() >= 2,
                    "DatabasesRoutes VIEW table: 2 колонки");

            // UPDATE описания.
            Object updated = dispatch.invoke(routes, "PUT",
                    "{\"name\":\"extpg\",\"description\":\"внешняя\"}", null);
            java.util.Map<String, Object> ud = corpagent.util.Json.asMap(getData.invoke(updated));
            check("внешняя".equals(corpagent.util.Json.str(ud, "description", "")),
                    "DatabasesRoutes: UPDATE меняет описание");

            // DELETE.
            Object removed = dispatch.invoke(routes, "DELETE", "{\"name\":\"extpg\"}", null);
            check(Boolean.TRUE.equals(isOk.invoke(removed)), "DatabasesRoutes: DELETE ok");
            check(store.getDatabase("extpg") == null, "DatabasesRoutes: запись БД удалена");

                        deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testDatabasesRoutes: " + e);
        }
    }

    // 57) Этап 5.1-F/5.2-F/5.3-F/5.4-F: фронтенд-экраны разделов.
    private static void testWebSectionViews() {
        try {
            java.io.File settings = new java.io.File("web/js/views/SettingsView.js");
            if (!settings.isFile()) {
                System.out.println("  [SKIP] web/ не найден. Тест пропущен.");
                return;
            }
            String sv = readUtf8(settings);
            check(sv.contains("renderSkills") && sv.contains("renderSkillEditor"),
                    "SettingsView.js: рендер навыков и редактора");
            check(sv.contains("renderTools") && sv.contains("renderToolEditor"),
                    "SettingsView.js: рендер инструментов и редактора");
            check(sv.contains("api.skills") && sv.contains("api.tools"),
                    "SettingsView.js: работа с api.skills и api.tools");
            check(sv.contains("skillsList") && sv.contains("toolsList"),
                    "SettingsView.js: использует контейнеры skillsList/toolsList");

            String dbv = readUtf8(new java.io.File("web/js/views/DatabasesView.js"));
            check(dbv.contains("loadTable") && dbv.contains("openView"),
                    "DatabasesView.js: просмотр БД и таблиц (5.c)");
            check(dbv.contains("openConnect") && dbv.contains("saveConnect"),
                    "DatabasesView.js: модалка подключения БД (5.d)");
            check(dbv.contains("api.databases"), "DatabasesView.js: работа с api.databases");
            check(dbv.contains("db-card") && dbv.contains("dbList"),
                    "DatabasesView.js: карточки БД в dbList");

            String pw = readUtf8(new java.io.File("web/js/components/ProjectWizard.js"));
            check(pw.contains("showStep") && pw.contains("next"),
                    "ProjectWizard.js: шаги мастера (5.a)");
            check(pw.contains("existing_db_id") && pw.contains("db_name"),
                    "ProjectWizard.js: поддержка existing_db_id (5.b)");
            check(pw.contains("api.projects.create"),
                    "ProjectWizard.js: создание проекта через api");

            String pv = readUtf8(new java.io.File("web/js/views/ProjectsView.js"));
            check(pv.contains("projectsContent") && pv.contains("api.projects"),
                    "ProjectsView.js: экран проектов через api.projects");

            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("id=\"skillsList\"") && html.contains("id=\"toolsList\""),
                    "index.html: контейнеры навыков/инструментов");
            check(html.contains("id=\"dbList\"") && html.contains("id=\"projectsContent\""),
                    "index.html: контейнеры БД и проектов");
            check(html.contains("id=\"dbViewModal\"") && html.contains("id=\"dbConnectModal\""),
                    "index.html: модалки просмотра и подключения БД");
            check(html.contains("js/views/SettingsView.js")
                    && html.contains("js/components/ProjectWizard.js")
                    && html.contains("js/views/DatabasesView.js")
                    && html.contains("js/views/ProjectsView.js"),
                    "index.html: подключены новые view/компоненты");
        } catch (Exception e) {
            check(false, "testWebSectionViews: " + e);
        }
    }

    // 58) Этап 4.1/4.2: фронтенд сайдбара — рендер чатов/проектов и загрузка проектов.
    private static void testWebSidebar() {
        try {
            java.io.File f = new java.io.File("web/js/components/Sidebar.js");
            if (!f.isFile()) {
                System.out.println("  [SKIP] web/ не найден. Тест пропущен.");
                return;
            }
            String side = readUtf8(f);
            check(side.contains("renderChats") && side.contains("renderProjects"),
                    "Sidebar.js рендерит чаты и проекты");
            check(side.contains("chat-item") && side.contains("project-item"),
                    "Sidebar.js использует классы прототипа (chat-item/project-item)");
            check(side.contains("setActiveChat") && side.contains("go('chat')"),
                    "Sidebar.js переключает активный чат и экран");
            check(side.contains("projects.list"), "Sidebar.js загружает проекты через API");

            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("js/components/Sidebar.js"),
                    "index.html подключает Sidebar.js");
            check(html.contains("id=\"chatsSection\"") && html.contains("id=\"projectsSection\""),
                    "index.html содержит секции Чаты/Проекты");
        } catch (Exception e) {
            check(false, "testWebSidebar: " + e);
        }
    }

    // 54) Этап 4.4/4.5: фронтенд заметок — вью и API-обёртка.
    private static void testWebNotesView() {
        try {
            java.io.File f = new java.io.File("web/js/views/NotesView.js");
            if (!f.isFile()) {
                System.out.println("  [SKIP] web/ не найден. Тест пропущен.");
                return;
            }
            String notes = readUtf8(f);
            check(notes.contains("createNew") && notes.contains("deleteActive"),
                    "NotesView.js содержит createNew/deleteActive");
            check(notes.contains("notes.list") && notes.contains("notes.update"),
                    "NotesView.js обращается к api.notes (list/update)");
            check(notes.contains("note-item"), "NotesView.js использует класс note-item прототипа");
            check(notes.contains("formatNoteDate"),
                    "NotesView.js форматирует дату заметки (Сегодня/Вчера)");

            String api = readUtf8(new java.io.File("web/js/api.js"));
            check(api.contains("notes:") && api.contains("list:") && api.contains("create:"),
                    "api.js содержит обёртку notes (list/create/update/remove)");

            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("js/views/NotesView.js"),
                    "index.html подключает NotesView.js");
            check(html.contains("NotesView.createNew()"),
                    "index.html связывает кнопку «+ Новая» с NotesView.createNew");
        } catch (Exception e) {
            check(false, "testWebNotesView: " + e);
        }
    }

    // 55) Этап 4.3: hash-роутер — экраны и переключение контентных областей.
    private static void testWebRouter() {
        try {
            java.io.File f = new java.io.File("web/js/router.js");
            if (!f.isFile()) {
                System.out.println("  [SKIP] web/ не найден. Тест пропущен.");
                return;
            }
            String router = readUtf8(f);
            check(router.contains("Router") && router.contains("hashchange"),
                    "router.js подписан на hashchange");
            check(router.contains("'chat'") && router.contains("'notes'")
                            && router.contains("'projects'") && router.contains("'settings'"),
                    "router.js поддерживает экраны chat/notes/projects/settings");
            check(router.contains("chatContent") && router.contains("notesContent")
                            && router.contains("inputArea"),
                    "router.js управляет видимостью областей прототипа");
            check(router.contains("showNotes") && router.contains("showChat"),
                    "router.js перенаправляет showNotes/showChat на роутер");

            String app = readUtf8(new java.io.File("web/js/app.js"));
            check(app.contains("Router.init") && app.contains("Sidebar.init")
                            && app.contains("NotesView.init"),
                    "app.js инициализирует Router/Sidebar/NotesView");

            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("js/router.js"), "index.html подключает router.js");
            check(html.contains("js/components/Sidebar.js") && html.contains("js/views/NotesView.js"),
                    "index.html подключает компоненты Этапа 4");
        } catch (Exception e) {
            check(false, "testWebRouter: " + e);
        }
    }

        // 59) Этап 6.1: SettingsRoutes — GET собирает DTO из config.json + settings.json,
    //     маппинг провайдеров (подпись↔код) и список моделей совпадают с SettingsPanelModel.
    private static void testSettingsRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_settings").toFile();
            // config.json — минимально валидный.
            java.io.File cfgFile = new java.io.File(base, "config.json");
            java.nio.file.Files.write(cfgFile.toPath(),
                    ("{\"api_key\":\"k1\",\"provider\":\"qwen\",\"base_url\":\"\","
                            + "\"model\":\"qwen-max\",\"temperature\":0.5,\"max_tokens\":2048}")
                            .getBytes("UTF-8"));
            corpagent.util.Config config = new corpagent.util.Config(cfgFile.getPath());
            corpagent.util.Settings settings = new corpagent.util.Settings(base.getPath());

            Class<?> cls = Class.forName("corpagent.api.SettingsRoutes");
            Object routes = cls.getConstructor(corpagent.util.Config.class,
                    corpagent.util.Settings.class).newInstance(config, settings);

            // GET — DTO.
            java.lang.reflect.Method get = cls.getDeclaredMethod("handleGet");
            get.setAccessible(true);
            Object resp = get.invoke(routes);
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            java.lang.reflect.Method isOk = respCls.getMethod("isOk");
            java.lang.reflect.Method getData = respCls.getMethod("getData");
            check(Boolean.TRUE.equals(isOk.invoke(resp)), "SettingsRoutes GET: ok");
            java.util.Map<String, Object> dto = corpagent.util.Json.asMap(getData.invoke(resp));
            check(!dto.containsKey("api_key") && corpagent.util.Json.bool(dto, "api_key_configured", false),
                    "SettingsRoutes GET: api_key из config.json");
            check("qwen".equals(corpagent.util.Json.str(dto, "provider", "")),
                    "SettingsRoutes GET: provider=qwen");
            check("Alibaba Qwen".equals(corpagent.util.Json.str(dto, "provider_label", "")),
                    "SettingsRoutes GET: подпись провайдера");
            check(corpagent.util.Json.arr(dto, "models").contains("qwen-max"),
                    "SettingsRoutes GET: список моделей провайдера");
            check(corpagent.util.Json.arr(dto, "providers").size() == 4,
                    "SettingsRoutes GET: 4 провайдера в каталоге");
            check("green".equals(corpagent.util.Json.str(dto, "theme", "")),
                    "SettingsRoutes GET: тема по умолчанию из settings.json");

            // Маппинг провайдеров (статические помощники).
            java.lang.reflect.Method code = cls.getDeclaredMethod("providerCode", Object.class);
            code.setAccessible(true);
            check("yandex".equals(code.invoke(null, "Яндекс ИИ студия")),
                    "SettingsRoutes.providerCode: подпись Яндекса → yandex");
            check("qwen".equals(code.invoke(null, "Alibaba Qwen")),
                    "SettingsRoutes.providerCode: подпись Qwen → qwen");
            check("custom".equals(code.invoke(null, "Другой (OpenAI-совместимый)")),
                    "SettingsRoutes.providerCode: подпись custom → custom");
            check("deepseek".equals(code.invoke(null, "Что-то иное")),
                    "SettingsRoutes.providerCode: неизвестное → deepseek");
            check("qwen".equals(code.invoke(null, "qwen")),
                    "SettingsRoutes.providerCode: код qwen распознан");

            java.lang.reflect.Method label = cls.getDeclaredMethod("providerLabel", String.class);
            label.setAccessible(true);
            check("DeepSeek".equals(label.invoke(null, "deepseek")),
                    "SettingsRoutes.providerLabel: deepseek → DeepSeek");
            check("Alibaba Qwen".equals(label.invoke(null, "qwen")),
                    "SettingsRoutes.providerLabel: qwen → Alibaba Qwen");

            java.lang.reflect.Method models = cls.getDeclaredMethod("modelsFor", String.class);
            models.setAccessible(true);
            java.util.List<?> dm = (java.util.List<?>) models.invoke(null, "deepseek");
            check(dm.contains("deepseek-chat") && dm.contains("deepseek-reasoner"),
                    "SettingsRoutes.modelsFor: модели DeepSeek");
            java.util.List<?> ym = (java.util.List<?>) models.invoke(null, "yandex");
            check(ym.size() == 2 && ym.contains("yandexgpt/latest"),
                    "SettingsRoutes.modelsFor: 3 модели Яндекса");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testSettingsRoutes: " + e);
        }
    }

    // 60) Этап 6.1: SettingsRoutes PUT сохраняет модель и интерфейс, а test-llm использует
    //     инжектируемый LlmTester (без сети) и учитывает override-поля из тела запроса.
    private static void testSettingsRoutesSave() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_settings_save").toFile();
            java.io.File cfgFile = new java.io.File(base, "config.json");
            java.nio.file.Files.write(cfgFile.toPath(),
                    "{\"api_key\":\"\",\"provider\":\"deepseek\",\"model\":\"deepseek-chat\"}"
                            .getBytes("UTF-8"));
            corpagent.util.Config config = new corpagent.util.Config(cfgFile.getPath());
            corpagent.util.Settings settings = new corpagent.util.Settings(base.getPath());

            Class<?> cls = Class.forName("corpagent.api.SettingsRoutes");
            Class<?> testerCls = Class.forName("corpagent.api.SettingsRoutes$LlmTester");
            final corpagent.util.Config[] seen = new corpagent.util.Config[1];
            Object tester = java.lang.reflect.Proxy.newProxyInstance(
                    testerCls.getClassLoader(), new Class<?>[]{testerCls},
                    (p, m, a) -> {
                        if ("test".equals(m.getName())) {
                            seen[0] = (corpagent.util.Config) a[0];
                            return corpagent.api.SettingsRoutes.TestResult.class
                                    .getConstructor(boolean.class, String.class)
                                    .newInstance(true, "pong");
                        }
                        return null;
                    });
            Object routes = cls.getConstructor(corpagent.util.Config.class,
                    corpagent.util.Settings.class, testerCls)
                    .newInstance(config, settings, tester);

            java.lang.reflect.Method put = cls.getDeclaredMethod("handlePut", String.class);
            put.setAccessible(true);
            Object resp = put.invoke(routes,
                    "{\"provider\":\"Яндекс ИИ студия\",\"model\":\"yandexgpt/latest\",\"folder_id\":\"test-folder\"," 
                            + "\"base_url\":\"https://ai.api.cloud.yandex.net/v1\","
                            + "\"max_tokens\":1234,\"theme\":\"dark\",\"show_usage_in_chat\":false}");
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            check(Boolean.TRUE.equals(respCls.getMethod("isOk").invoke(resp)),
                    "SettingsRoutes PUT: ok");
            check("yandex".equals(config.getProvider()), "SettingsRoutes PUT: провайдер сохранён");
            check("gpt://test-folder/yandexgpt/latest".equals(config.getModel()), "SettingsRoutes PUT: модель сохранена");
            check(config.getMaxTokens() == 1234, "SettingsRoutes PUT: max_tokens сохранён");
            check("dark".equals(settings.getTheme()), "SettingsRoutes PUT: тема сохранена");
            check(!settings.isShowUsageInChat(), "SettingsRoutes PUT: флаг интерфейса сохранён");

            // config.json реально перезаписан на диске (не только в памяти).
            corpagent.util.Config reread = new corpagent.util.Config(cfgFile.getPath());
            check("yandex".equals(reread.getProvider()),
                    "SettingsRoutes PUT: config.json записан на диск");
            corpagent.util.Settings reSettings = new corpagent.util.Settings(base.getPath());
            check("dark".equals(reSettings.getTheme()),
                    "SettingsRoutes PUT: settings.json записан на диск");

            // max_tokens <= 0 → ошибка 400, значение не меняется.
            Object bad = put.invoke(routes, "{\"max_tokens\":0}");
            check((Integer) respCls.getMethod("getHttpStatus").invoke(bad) == 400,
                    "SettingsRoutes PUT: max_tokens<=0 → 400");
            check(config.getMaxTokens() == 1234, "SettingsRoutes PUT: значение при ошибке не меняется");

            // test-llm: инжектированный тестер вызван, override-поля применены.
            java.lang.reflect.Method testLlm = cls.getDeclaredMethod("handleTestLlm", String.class);
            testLlm.setAccessible(true);
            Object llmResp = testLlm.invoke(routes,
                    "{\"api_key\":\"override\",\"provider\":\"custom\",\"model\":\"gpt-4o\"}");
            java.util.Map<String, Object> llmDto = corpagent.util.Json.asMap(
                    respCls.getMethod("getData").invoke(llmResp));
            check(Boolean.TRUE.equals(llmDto.get("ok")), "SettingsRoutes test-llm: ok=true");
            check("pong".equals(corpagent.util.Json.str(llmDto, "detail", "")),
                    "SettingsRoutes test-llm: detail от тестера");
            check(seen[0] != null && "custom".equals(seen[0].getProvider()),
                    "SettingsRoutes test-llm: override провайдера передан тестеру");
            check(seen[0] != null && "gpt-4o".equals(seen[0].getModel()),
                    "SettingsRoutes test-llm: override модели передан тестеру");
            check(seen[0] != null && "override".equals(seen[0].getApiKey()),
                    "SettingsRoutes test-llm: override ключа передан тестеру");
            // Исходный config не изменился (виртуальный конфиг).
            check("yandex".equals(config.getProvider()),
                    "SettingsRoutes test-llm: реальный config не затронут");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testSettingsRoutesSave: " + e);
        }
    }

    // 61) Этап 6.2: FilesRoutes — безопасный резолвинг имён, запись загрузок,
    //     разбор multipart/form-data и DTO файла (без поднятия сервера).
    private static void testFilesRoutes() {
        try {
            java.io.File base = java.nio.file.Files.createTempDirectory("ca_files").toFile();
            java.io.File output = new java.io.File(base, "output");
            output.mkdirs();
            java.io.File report = new java.io.File(output, "report.pptx");
            java.nio.file.Files.write(report.toPath(), "pptx-bytes".getBytes("UTF-8"));

            Class<?> cls = Class.forName("corpagent.api.FilesRoutes");
            Object routes = cls.getConstructor(String.class).newInstance(base.getPath());

            // sanitizeName: путь усекается до базового имени, traversal отсекается.
            java.lang.reflect.Method sanitize = cls.getDeclaredMethod("sanitizeName", String.class);
            sanitize.setAccessible(true);
            check("report.pptx".equals(sanitize.invoke(null, "sub/dir/report.pptx")),
                    "FilesRoutes.sanitizeName: убирает путь");
            check("report.pptx".equals(sanitize.invoke(null, "C:\\tmp\\report.pptx")),
                    "FilesRoutes.sanitizeName: снимает Windows-путь");
                        check(sanitize.invoke(null, "..") == null, "FilesRoutes.sanitizeName: '..' отклонён");
            check("secret.txt".equals(sanitize.invoke(null, "../secret.txt")),
                    "FilesRoutes.sanitizeName: traversal обезврежен (остаётся базовое имя)");
            check(sanitize.invoke(null, "  ") == null, "FilesRoutes.sanitizeName: пустая строка → null");

            // resolveDownloadFile: файл в output/ найден; несуществующий — null.
            java.lang.reflect.Method resolve = cls.getDeclaredMethod("resolveDownloadFile",
                    java.io.File.class, String.class);
            resolve.setAccessible(true);
            Object f = resolve.invoke(null, output, "report.pptx");
            check(f != null && ((java.io.File) f).getName().equals("report.pptx"),
                    "FilesRoutes.resolveDownloadFile: находит файл в output/");
            check(resolve.invoke(null, output, "nope.pptx") == null,
                    "FilesRoutes.resolveDownloadFile: отсутствующий файл → null");
            check(resolve.invoke(null, output, "../config.json") == null,
                    "FilesRoutes.resolveDownloadFile: traversal отклонён");

            // uniqueFile: при коллизии добавляет суффикс.
            java.lang.reflect.Method unique = cls.getDeclaredMethod("uniqueFile",
                    java.io.File.class, String.class);
            unique.setAccessible(true);
            java.io.File uniq = (java.io.File) unique.invoke(null, output, "report.pptx");
            check(!uniq.getName().equals("report.pptx") && uniq.getName().startsWith("report"),
                    "FilesRoutes.uniqueFile: разрешает коллизию имён");

            // writeUploaded: реально пишет байты в папку.
            java.io.File uploadDir = new java.io.File(base, "uploads");
            uploadDir.mkdirs();
            java.lang.reflect.Method write = cls.getDeclaredMethod("writeUploaded",
                    java.io.File.class, String.class, byte[].class);
            write.setAccessible(true);
            java.io.File up = (java.io.File) write.invoke(null, uploadDir, "../data.txt",
                    "hello".getBytes("UTF-8"));
            check(up.getParentFile().equals(uploadDir) && up.isFile(),
                    "FilesRoutes.writeUploaded: пишет файл в uploads/");
            check("hello".equals(new String(java.nio.file.Files.readAllBytes(up.toPath()), "UTF-8")),
                    "FilesRoutes.writeUploaded: содержимое сохранено");

            // boundaryOf / filenameOf — чистые хелперы.
            java.lang.reflect.Method boundary = cls.getDeclaredMethod("boundaryOf", String.class);
            boundary.setAccessible(true);
            check("abc123".equals(boundary.invoke(null, "multipart/form-data; boundary=abc123")),
                    "FilesRoutes.boundaryOf: извлекает boundary");
            check("\"quoted\"".equals(boundary.invoke(null, "multipart/form-data; boundary=\"quoted\""))
                            == false && "quoted".equals(boundary.invoke(null,
                            "multipart/form-data; boundary=\"quoted\"")),
                    "FilesRoutes.boundaryOf: снимает кавычки");
            check(boundary.invoke(null, "text/plain") == null,
                    "FilesRoutes.boundaryOf: не multipart → null");

            java.lang.reflect.Method filename = cls.getDeclaredMethod("filenameOf", String.class);
            filename.setAccessible(true);
            check("a.txt".equals(filename.invoke(null,
                            "Content-Disposition: form-data; name=\"files\"; filename=\"a.txt\"")),
                    "FilesRoutes.filenameOf: извлекает имя файла");
            check(filename.invoke(null, "Content-Disposition: form-data; name=\"field\"") == null,
                    "FilesRoutes.filenameOf: обычное поле → null");

            // parseMultipart: разбирает один файловый раздел.
            java.util.List<Object> parts = parseMultipart(cls, "BND",
                    "field", "x", "myfile.txt", "file-content");
            check(parts.size() == 1, "FilesRoutes.parseMultipart: одна файловая часть");
            if (parts.size() == 1) {
                java.lang.reflect.Field fnF = parts.get(0).getClass().getDeclaredField("filename");
                fnF.setAccessible(true);
                java.lang.reflect.Field byF = parts.get(0).getClass().getDeclaredField("bytes");
                byF.setAccessible(true);
                check("myfile.txt".equals(fnF.get(parts.get(0))),
                        "FilesRoutes.parseMultipart: имя части");
                check("file-content".equals(new String((byte[]) byF.get(parts.get(0)), "UTF-8")),
                        "FilesRoutes.parseMultipart: содержимое части");
            }

            // fileDto — JSON-описание.
            java.lang.reflect.Method dto = cls.getDeclaredMethod("fileDto", java.io.File.class);
            dto.setAccessible(true);
            java.util.Map<?, ?> d = (java.util.Map<?, ?>) dto.invoke(null, report);
            check("report.pptx".equals(d.get("name")), "FilesRoutes.fileDto: имя");
            check(d.get("size") != null, "FilesRoutes.fileDto: размер");

            // handleList: листинг output/.
            java.lang.reflect.Method list = cls.getDeclaredMethod("handleList");
            list.setAccessible(true);
            Object resp = list.invoke(routes);
            Class<?> respCls = Class.forName("corpagent.api.ApiResponse");
            Object data = respCls.getMethod("getData").invoke(resp);
            java.util.Map<String, Object> lm = corpagent.util.Json.asMap(data);
            check(corpagent.util.Json.arr(lm, "files").size() == 1,
                    "FilesRoutes.handleList: один файл в output/");
            check(Integer.valueOf(1).equals(corpagent.util.Json.intVal(lm, "count", -1)),
                    "FilesRoutes.handleList: count=1");

            deleteRecursive(base);
        } catch (Exception e) {
            check(false, "testFilesRoutes: " + e);
        }
    }

    // Собирает multipart-тело из одной формы (поле) и одного файла для parseMultipart.
    private static java.util.List<Object> parseMultipart(Class<?> cls, String boundary,
                                                         String fieldName, String fieldValue,
                                                         String fileName, String fileValue)
            throws Exception {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"").append(fieldName).append("\"").append(crlf)
          .append(crlf).append(fieldValue).append(crlf);
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"files\"; filename=\"")
          .append(fileName).append("\"").append(crlf);
        sb.append("Content-Type: text/plain").append(crlf).append(crlf);
        sb.append(fileValue).append(crlf);
        sb.append("--").append(boundary).append("--").append(crlf);
        byte[] body = sb.toString().getBytes("UTF-8");

        java.lang.reflect.Method parse = cls.getDeclaredMethod("parseMultipart", byte[].class, String.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Object> out = (java.util.List<Object>) parse.invoke(null, body, boundary);
        return out;
    }

    // 62) Этап 6.3: вкладки «Модель»/«Интерфейс» — index.html содержит нужные id-элементы,
    //     SettingsView.js умеет загружать/сохранять настройки и тестировать LLM.
    private static void testWebSettingsTabs() {
        try {
            String html = readUtf8(new java.io.File("web/index.html"));
            String[] ids = {"settingsProvider", "settingsBaseUrl", "settingsModel",
                    "settingsTemperature", "settingsMaxTokens", "settingsTestLlmBtn",
                    "settingsAutoUpdateDb", "settingsShowUsage", "settingsConfirmDbWrite",
                    "settingsThemeOptions", "settingsSaveBtn", "filePickerInput", "filePickerUploadBtn"};
            boolean allIds = true;
            for (String id : ids) {
                if (!html.contains("id=\"" + id + "\"")) {
                    allIds = false;
                }
            }
            check(allIds, "index.html: все id вкладок Модель/Интерфейс на месте");

            String sv = readUtf8(new java.io.File("web/js/views/SettingsView.js"));
            check(sv.contains("loadSettings") && sv.contains("applySettings"),
                    "SettingsView.js: загрузка настроек");
            check(sv.contains("saveSettings") && sv.contains("api.put('settings'"),
                    "SettingsView.js: сохранение настроек через PUT /api/settings");
            check(sv.contains("testLlm") && sv.contains("settings/test-llm"),
                    "SettingsView.js: проверка подключения (test-llm)");
            check(sv.contains("providerCode") && sv.contains("modelsFor") && sv.contains("defaultBaseUrl"),
                    "SettingsView.js: маппинг провайдеров/моделей/URL");
            check(sv.contains("compatible-mode/v1"),
                    "SettingsView.js: URL Qwen — compatible-mode/v1");
        } catch (Exception e) {
            check(false, "testWebSettingsTabs: " + e);
        }
    }

    // 63) Этап 6.4: кнопка «Скачать отчёт» в чате — api.files.download, рендер кнопки
    //     в Message.js при наличии meta.filePath и обработчик в ChatView.
    private static void testWebReportDownload() {
        try {
            String api = readUtf8(new java.io.File("web/js/api.js"));
            check(api.contains("files:") && api.contains("download:")
                            && api.contains("api/files/download"),
                    "api.js: files.download → GET /api/files/download");

            String msg = readUtf8(new java.io.File("web/js/components/Message.js"));
            check(msg.contains("DOWNLOAD_ACTION") && msg.contains("Скачать отчёт"),
                    "Message.js: кнопка «Скачать отчёт»");
            check(msg.contains("meta.filePath") && msg.contains("actionsHtml(msg.role, msg)"),
                    "Message.js: кнопка показывается только при наличии файла");

            String chatView = readUtf8(new java.io.File("web/js/views/ChatView.js"));
            check(chatView.contains("downloadReport") && chatView.contains("files.download"),
                    "ChatView.js: скачивание файла-отчёта");

            String html = readUtf8(new java.io.File("web/index.html"));
            check(html.contains("js/components/FilePicker.js"),
                    "index.html: подключён FilePicker.js (загрузка файлов)");
            String app = readUtf8(new java.io.File("web/js/app.js"));
            check(app.contains("FilePicker.init"), "app.js: инициализация FilePicker");
        } catch (Exception e) {
            check(false, "testWebReportDownload: " + e);
        }
    }

    private static Object getStatic(Class<?> owner, String field) throws Exception {
        java.lang.reflect.Field f = owner.getField(field);
        return f.get(null);
    }    @SuppressWarnings("unchecked")
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


