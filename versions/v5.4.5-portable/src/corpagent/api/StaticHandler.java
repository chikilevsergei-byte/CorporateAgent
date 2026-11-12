package corpagent.api;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Отдача статических файлов фронтенда из папки {@code web/} (Этап 1 миграции v3 → v4).
 *
 * <p>Логика намеренно отделена от {@code HttpExchange}: ключевые методы
 * ({@link #mimeType(String)}, {@link #resolveSafeFile(String)}, {@link #normalizePath(String)})
 * — чистые и покрываются smoke-тестом без поднятия сервера.</p>
 *
 * <p>Безопасность: нормализация пути исключает выход за пределы корня {@code web/}
 * ({@code ../} и абсолютные пути отклоняются).</p>
 */
public final class StaticHandler {

    /** Корень статики (обычно {baseDir}/web). */
    private final File webRoot;

    public StaticHandler(String webRoot) {
        this.webRoot = new File(webRoot == null ? "web" : webRoot);
    }

    public File getWebRoot() {
        return webRoot;
    }

    /** Есть ли папка статики (для диагностики при старте). */
    public boolean exists() {
        return webRoot.isDirectory();
    }

    /**
     * Приводит URL-путь к виду файла: убирает query, декодирует %xx,
     * "/" → "index.html".
     *
     * @param urlPath путь из запроса (например "/css/base.css?x=1")
     * @return нормализованный относительный путь
     */
    public static String normalizePath(String urlPath) {
        if (urlPath == null || urlPath.isEmpty()) {
            return "index.html";
        }
        String p = urlPath;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int hash = p.indexOf('#');
        if (hash >= 0) {
            p = p.substring(0, hash);
        }
        try {
            p = URLDecoder.decode(p, "UTF-8");
        } catch (Exception e) {
            // некорректный %xx — оставляем как есть
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty() || p.endsWith("/")) {
            p = p + "index.html";
        }
        return p;
    }

    /**
     * Безопасно резолвит файл внутри {@code web/}. Возвращает null, если путь
     * выходит за пределы корня (защита от path traversal) или файл не существует.
     */
    public File resolveSafeFile(String urlPath) {
        String rel = normalizePath(urlPath);
        try {
            File f = new File(webRoot, rel).getCanonicalFile();
            File root = webRoot.getCanonicalFile();
            String rootPath = root.getPath();
            String childPath = f.getPath();
            if (!childPath.equals(rootPath) && !childPath.startsWith(rootPath + File.separator)) {
                return null; // выход за пределы web/
            }
            return f.isFile() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** MIME-тип по расширению файла. */
    public static String mimeType(String path) {
        String ext = extensionOf(path);
        String m = EXT_TO_MIME.get(ext);
        return m == null ? "application/octet-stream" : m;
    }

    private static String extensionOf(String path) {
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot < 0 || dot < slash) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase();
    }

    private static final Map<String, String> EXT_TO_MIME = new LinkedHashMap<String, String>();
    static {
        EXT_TO_MIME.put("html", "text/html; charset=utf-8");
        EXT_TO_MIME.put("htm", "text/html; charset=utf-8");
        EXT_TO_MIME.put("css", "text/css; charset=utf-8");
        EXT_TO_MIME.put("js", "application/javascript; charset=utf-8");
        EXT_TO_MIME.put("mjs", "application/javascript; charset=utf-8");
        EXT_TO_MIME.put("json", "application/json; charset=utf-8");
        EXT_TO_MIME.put("svg", "image/svg+xml");
        EXT_TO_MIME.put("png", "image/png");
        EXT_TO_MIME.put("jpg", "image/jpeg");
        EXT_TO_MIME.put("jpeg", "image/jpeg");
        EXT_TO_MIME.put("gif", "image/gif");
        EXT_TO_MIME.put("webp", "image/webp");
        EXT_TO_MIME.put("ico", "image/x-icon");
        EXT_TO_MIME.put("woff", "font/woff");
        EXT_TO_MIME.put("woff2", "font/woff2");
        EXT_TO_MIME.put("ttf", "font/ttf");
        EXT_TO_MIME.put("otf", "font/otf");
        EXT_TO_MIME.put("txt", "text/plain; charset=utf-8");
        EXT_TO_MIME.put("map", "application/json; charset=utf-8");
        EXT_TO_MIME.put("wasm", "application/wasm");
    }

    /** Записывает файл в поток ответа (используется сервером). */
    public static void writeFile(File file, OutputStream out) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        out.write(bytes);
    }
}
