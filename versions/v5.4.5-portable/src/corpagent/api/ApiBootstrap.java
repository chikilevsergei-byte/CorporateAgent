package corpagent.api;


/**
 * Сборка и запуск HTTP-слоя (Этап 1 миграции v3 → v4).
 *
 * <p>Инкапсулирует порядок инициализации: создание токена, статики, сервера,
 * регистрацию маршрутов, запуск, открытие браузера. Вызывается из {@code App}.</p>
 */
public final class ApiBootstrap {

    private ApiBootstrap() {
    }

    /** Результат запуска сервера. */
    public static final class StartResult {
        public final ApiServer server;
        public final int port;

        StartResult(ApiServer server, int port) {
            this.server = server;
            this.port = port;
        }
    }

    /**
     * Создаёт и запускает сервер со стандартными маршрутами.
     *
     * @param baseDir базовая папка приложения (ищется подпапка {@code web/})
     * @return запущенный сервер и выбранный порт
     * @throws java.io.IOException если не удалось занять ни один порт
     */
    public static StartResult start(String baseDir) throws java.io.IOException {
        return start(baseDir, null, null);
    }

    /**
     * Создаёт и запускает сервер с маршрутами чата (Этап 3) и CRUD-разделов (Этап 5).
     *
     * @param baseDir базовая папка приложения
     * @param agent   ядро агента (нужно для {@code /api/chat*}); может быть null
     * @param store   хранилище данных (чаты/проекты/навыки/инструменты/БД); может быть null
     * @return запущенный сервер и выбранный порт
     * @throws java.io.IOException если не удалось занять ни один порт
     */
    public static StartResult start(String baseDir, corpagent.Agent agent,
                                    corpagent.data.DataStore store) throws java.io.IOException {
        return start(baseDir, agent, store, null);
    }
    /**
     * Полная сборка сервера: чат (Этап 3), CRUD проектов/навыков/инструментов/БД (Этап 5).
     *
     * @param dbManager менеджер БД (нужен для создания проектов); может быть null
     */
    public static StartResult start(String baseDir, corpagent.Agent agent,
                                    corpagent.data.DataStore store,
                                    corpagent.db.DatabaseManager dbManager) throws java.io.IOException {
        return start(baseDir, agent, store, dbManager, null, null);
    }

    /**
     * Полная сборка сервера с настройками (Этап 6.1).
     *
     * @param config   конфигурация (нужна для {@code /api/settings}); может быть null
     * @param settings настройки интерфейса (settings.json); может быть null
     */
    public static StartResult start(String baseDir, corpagent.Agent agent,
                                    corpagent.data.DataStore store,
                                    corpagent.db.DatabaseManager dbManager,
                                    corpagent.util.Config config,
                                    corpagent.util.Settings settings) throws java.io.IOException {
        Auth auth = Auth.withRandomToken();
        String webRoot = System.getProperty("corpagent.install",baseDir) + java.io.File.separator + "web";
        StaticHandler staticHandler = new StaticHandler(webRoot);
        ApiServer server = new ApiServer(auth, staticHandler);

        // Прод-маршруты (health/shutdown/open-folder).
        SystemRoutes.register(server);
        // Маршруты чата (Этап 3) — только при наличии агента и хранилища.
        if (agent != null && store != null) {
            new ChatRoutes(agent, store, settings).register(server);
        }
        // Справочники контекста чата (Этап 3.13) — требуют только хранилища.
        if (store != null) {
            new CatalogRoutes(store).register(server);
        }
        // Заметки (Этап 4.5) — требуют только хранилища.
        if (store != null) {
            new NotesRoutes(store).register(server);
        }
        // Проекты/навыки/инструменты/БД (Этап 4.2 + Этап 5) — требуют хранилища.
        if (store != null) {
            new ProjectsRoutes(store, dbManager).register(server);
            new SkillsRoutes(store).register(server);
            new ToolsRoutes(store).register(server);
            new DatabasesRoutes(store, dbManager).register(server);
        }
        // Настройки (Этап 6.1) — требуют конфига и настроек интерфейса.
        if (config != null && settings != null) {
            new SettingsRoutes(config, settings, new DeepSeekLlmTester()).register(server);
        }
        // Файлы (Этап 6.2): скачивание output/, загрузка на анализ, открытие папки.
        new FilesRoutes(baseDir, store).register(server);

        String configuredPort=System.getProperty("corpagent.port");
        int port = configuredPort==null?server.start():server.start(new int[]{Integer.parseInt(configuredPort)});
        return new StartResult(server, port);
    }

    /** Opens the web UI using the desktop browser when available. */
    public static void openBrowser(final String url) {
        try { SystemRoutes.browse(url); }
        catch (Exception e) { System.err.println("Open in browser: " + url); }
    }
}

