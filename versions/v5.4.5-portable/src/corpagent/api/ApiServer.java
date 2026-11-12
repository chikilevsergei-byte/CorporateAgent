package corpagent.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP-сервер приложения (Этап 1 миграции v3 → v4).
 *
 * <p>Тонкая обёртка над встроенным в JDK 8 {@link HttpServer} ({@code com.sun.net.httpserver}):
 * слушает только {@code 127.0.0.1}, роутит по пути, проверяет Bearer-токен для
 * {@code /api/*} и раздаёт статику из {@code web/}. Без внешних зависимостей.</p>
 *
 * <p>Порты перебираются по списку (8080 → 8081 → 8082), чтобы приложение стартовало,
 * даже если предыдущий порт занят. Реально выбранный порт доступен через {@link #getPort()}.</p>
 */
public class ApiServer {

    /** Кандидаты портов (по порядку). */
    public static final int[] DEFAULT_PORTS = {8080, 8081, 8082};

    /** Хост прослушивания — только локальная петля. */
    public static final String HOST = "127.0.0.1";

    private final Auth auth;
    private final StaticHandler staticHandler;

    /**
     * Реестр API-маршрутов: точный путь → обработчик.
     * Используется для {@code /api/*}. Статика обрабатывается отдельно.
     */
    private final Map<String, ApiRoute> routes = new LinkedHashMap<String, ApiRoute>();

    /**
     * Реестр стриминговых маршрутов (SSE): обработчик сам пишет в тело ответа.
     * Используется для {@code /api/chat/stream}.
     */
    private final Map<String, StreamRoute> streamRoutes = new LinkedHashMap<String, StreamRoute>();

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private int port = -1;
    private final String instanceId=java.util.UUID.randomUUID().toString();
    public String getInstanceId(){return instanceId;}

    /** Обработчик API-маршрута: получает тело запроса и возвращает ответ. */
    public interface ApiRoute {
        ApiResponse handle(String method, String body, Map<String, List<String>> query) throws Exception;
    }

    /**
     * Обработчик стримингового маршрута (SSE). Получает живой {@link HttpExchange}
     * (уже после проверки токена) и сам управляет заголовками/телом ответа.
     */
    public interface StreamRoute {
        void stream(String method, String body, Map<String, List<String>> query,
                    HttpExchange exchange) throws Exception;
    }

    public ApiServer(Auth auth, StaticHandler staticHandler) {
        this.auth = auth;
        this.staticHandler = staticHandler;
    }

    /** Регистрирует API-маршрут (точное совпадение пути). */
    public void route(String path, ApiRoute handler) {
        routes.put(path, handler);
    }

    /** Регистрирует стриминговый (SSE) маршрут. */
    public void streamRoute(String path, StreamRoute handler) {
        streamRoutes.put(path, handler);
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return server != null;
    }

    /** Адрес для открытия в браузере (с токеном). */
    public String browsableUrl() {
        return "http://" + HOST + ":" + port + "/?token=" + auth.getToken();
    }

    /**
     * Запускает сервер, перебирая порты из {@link #DEFAULT_PORTS}.
     *
     * @return выбранный порт
     * @throws IOException если ни один порт не удалось занять
     */
    public int start() throws IOException {
        return start(DEFAULT_PORTS);
    }

    /** Запуск с явным списком портов (для тестов/конфигурации). */
    public int start(int[] ports) throws IOException {
        IOException last = null;
        for (int candidate : ports) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(HOST, candidate), 0);
                s.createContext("/", new RootHandler());
                executor = Executors.newFixedThreadPool(8);
                s.setExecutor(executor);
                s.start();
                this.server = s;
                this.port = s.getAddress().getPort();
                return this.port;
            } catch (IOException e) {
                last = e; // порт занят — пробуем следующий
            }
        }
        throw last == null ? new IOException("Нет доступных портов") : last;
    }

    /** Останавливает сервер (немедленно). */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            if (executor != null) executor.shutdownNow();
        }
    }

    /** Корневой обработчик: роутит /api/* и статику. */
    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            try {
                if (path.startsWith("/api/")) {
                    handleApi(exchange, path);
                } else {
                    handleStatic(exchange, path);
                }
            } catch (Exception e) {
                send(exchange, ApiResponse.serverError(e.getMessage()));
            } finally {
                exchange.close();
            }
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        // Авторизация Bearer-токеном (кроме health-проверки).
        if (!"/api/system/health".equals(path)) {
            String header = firstHeader(exchange, Auth.HEADER);
            if (!auth.isValid(header)) {
                send(exchange, ApiResponse.unauthorized());
                return;
            }
        }
        String body = "/api/files/upload".equals(path) ? "" : readBody(exchange);
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());

        // Стриминговые маршруты (SSE): обработчик сам пишет ответ в exchange.
        StreamRoute streamRoute = streamRoutes.get(path);
        if (streamRoute != null) {
            try {
                streamRoute.stream(exchange.getRequestMethod(), body, query, exchange);
            } catch (Exception e) {
                // Если заголовки ещё не отправлены — вернём JSON-ошибку.
                try {
                    send(exchange, ApiResponse.serverError(e.getMessage()));
                } catch (Exception ignored) {
                    // ответ уже начал отправляться в потоковом режиме — не дублируем
                }
            }
            return;
        }

        ApiRoute route = routes.get(path);
        if (route == null) {
            send(exchange, ApiResponse.notFound("Маршрут не найден: " + path));
            return;
        }
        ApiResponse response;
        try {
            response = route.handle(exchange.getRequestMethod(), body, query);
        } catch (Exception e) {
            response = ApiResponse.serverError(e.getMessage());
        }
        send(exchange, response);
    }

    private void handleStatic(HttpExchange exchange, String path) throws IOException {
        String relative = StaticHandler.normalizePath(path).replace('\\', '/');
        boolean safe = relative.indexOf(':') < 0 && relative.indexOf('\0') < 0;
        for (String part : relative.split("/")) if ("..".equals(part) || ".".equals(part)) safe = false;
        byte[] bytes = null; String mime = StaticHandler.mimeType(relative);
        if (safe) {
            java.io.File file = staticHandler.resolveSafeFile(path);
            if (file != null) bytes = java.nio.file.Files.readAllBytes(file.toPath());
            else bytes = resourceBytes(relative);
            if (bytes == null && relative.indexOf('.') < 0) {
                file = staticHandler.resolveSafeFile("/index.html");
                bytes = file == null ? resourceBytes("index.html") : java.nio.file.Files.readAllBytes(file.toPath());
                mime = StaticHandler.mimeType("index.html");
            }
        }
        int status = bytes == null ? 404 : 200;
        if (bytes == null) { bytes = "404 Not Found".getBytes(ApiResponse.UTF8); mime = "text/plain; charset=utf-8"; }
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private byte[] resourceBytes(String relative) throws IOException {
        try (java.io.InputStream in = ApiServer.class.getResourceAsStream("/web/" + relative)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int n;
            while ((n = in.read(chunk)) >= 0) buffer.write(chunk, 0, n);
            return buffer.toByteArray();
        }
    }

    private void send(HttpExchange exchange, ApiResponse response) throws IOException {
        byte[] body = response.toBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.getHttpStatus(), body.length);
        OutputStream os = exchange.getResponseBody();
        os.write(body);
        os.close();
    }

    // ==================== ХЕЛПЕРЫ (чистые, тестируемые) ====================

    static String firstHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    static String readBody(HttpExchange exchange) throws IOException {
        java.io.InputStream in = exchange.getRequestBody();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) {
            if (buf.size() + n > 25 * 1024 * 1024) throw new IOException("Request exceeds 25 MB");
            buf.write(tmp, 0, n);
        }
        return new String(buf.toByteArray(), ApiResponse.UTF8);
    }

    /** Разбирает query-строку в карту параметр → список значений (URL-decoded). */
    public static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            key = urlDecode(key);
            val = urlDecode(val);
            List<String> list = out.get(key);
            if (list == null) {
                list = new ArrayList<String>();
                out.put(key, list);
            }
            list.add(val);
        }
        return out;
    }

    static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
