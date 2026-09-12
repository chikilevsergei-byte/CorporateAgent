package corpagent.api;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Системные маршруты API (Этап 1 миграции v3 → v4).
 *
 * <p>Служебные эндпоинты:</p>
 * <ul>
 *   <li>{@code GET /api/system/health} — проверка живости (без токена);</li>
 *   <li>{@code POST /api/system/shutdown} — корректное завершение процесса.</li>
 * </ul>
 *
 * <p>Регистрация выполняется в {@link ApiBootstrap}. Логика отделена от HTTP-слоя,
 * чтобы smoke-тест мог проверить ответ health без поднятия сервера.</p>
 */
public final class SystemRoutes {

    private SystemRoutes() {
    }

    /** Регистрирует системные маршруты в сервере. */
    public static void register(ApiServer server) {
        server.route("/api/system/health", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                Map<String, Object> data = corpagent.util.Json.newObject();
                data.put("status", "ok");
                data.put("app", "CorporateAgent");
                data.put("version", "v4");
                data.put("port", server.getPort());
                return ApiResponse.ok(data);
            }
        });

        server.route("/api/system/shutdown", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                // Ответ отправляем до завершения, чтобы клиент получил подтверждение.
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        server.stop();
                        System.exit(0);
                    }
                }, "corpagent-shutdown").start();
                return ApiResponse.ok("Завершение работы…");
            }
        });

        server.route("/api/system/open-folder", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                List<String> paths = query.get("path");
                String rel = (paths == null || paths.isEmpty()) ? "" : paths.get(0);
                return openFolder(rel);
            }
        });
    }

    /**
     * Открывает папку в системном файловом менеджере. Разрешены только относительные
     * имена каталогов приложения (output/databases/backups) — защита от произвольного пути.
     */
    static ApiResponse openFolder(String relativePath) {
        String rel = relativePath == null ? "" : relativePath.trim();
        if (!("output".equals(rel) || "databases".equals(rel) || "backups".equals(rel))) {
            return ApiResponse.badRequest("Недопустимая папка: " + rel);
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return ApiResponse.serverError("Открытие папок не поддерживается на этой платформе");
            }
            java.io.File dir = new java.io.File(rel).getAbsoluteFile();
            if (!dir.isDirectory()) {
                return ApiResponse.notFound("Папка не найдена: " + rel);
            }
            Desktop.getDesktop().open(dir);
            return ApiResponse.ok("Открыто: " + rel);
        } catch (Exception e) {
            return ApiResponse.serverError(e.getMessage());
        }
    }

    /**
     * Открывает браузер по адресу. На некоторых ОС {@code Desktop.browse} надёжнее
     * вызывать из потока EDT — поэтому метод дергается через {@code SwingUtilities.invokeLater}
     * на стороне вызывающего (см. {@link ApiBootstrap}).
     */
    public static void browse(String url) throws Exception {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(new URI(url));
        } else {
            throw new UnsupportedOperationException("Открытие браузера не поддерживается");
        }
    }
}
