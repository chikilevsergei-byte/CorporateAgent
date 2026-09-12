package corpagent.api;

import javax.swing.SwingUtilities;

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
        Auth auth = Auth.withRandomToken();
        String webRoot = baseDir + java.io.File.separator + "web";
        StaticHandler staticHandler = new StaticHandler(webRoot);
        ApiServer server = new ApiServer(auth, staticHandler);

        // Прод-маршруты (health/shutdown/open-folder) + заглушка приветствия.
        SystemRoutes.register(server);

        int port = server.start();
        return new StartResult(server, port);
    }

    /**
     * Открывает браузер на адресе приложения. На части ОС {@code Desktop.browse}
     * корректно работает только из EDT — поэтому оборачиваем в {@code invokeLater}.
     */
    public static void openBrowser(final String url) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    SystemRoutes.browse(url);
                } catch (Exception e) {
                    System.err.println("[ApiBootstrap] Не удалось открыть браузер: " + e.getMessage()
                            + "\nОткройте вручную: " + url);
                }
            }
        });
    }
}
