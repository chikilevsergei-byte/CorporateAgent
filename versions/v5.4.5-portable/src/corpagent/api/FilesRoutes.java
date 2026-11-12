package corpagent.api;

import com.sun.net.httpserver.HttpExchange;

import corpagent.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Маршруты работы с файлами (Этап 6.2 миграции v3 → v4).
 *
 * <p>Браузер не может писать на диск напрямую, поэтому файловые операции отдаются
 * HTTP-слою:</p>
 * <ul>
 *   <li>{@code GET /api/files/download?id=<имя>} — скачивание файла из {@code output/}
 *       (созданные pptx/xlsx) с заголовком {@code Content-Disposition: attachment};</li>
 *   <li>{@code GET /api/files/list} — список файлов {@code output/} (имя, размер, дата)
 *       для отрисовки кнопок скачивания;</li>
 *   <li>{@code POST /api/files/upload} — загрузка файлов для анализа во временную папку
 *       {@code uploads/} (multipart/form-data либо сырое тело с {@code ?name=});</li>
 *   <li>{@code GET /api/files/open-folder?path=output} — открытие папки в системном
 *       файловом менеджере (делегируется {@link SystemRoutes#openFolder(String)}).</li>
 * </ul>
 *
 * <p>Скачивание/загрузка реализованы как стриминговые маршруты ({@link ApiServer.StreamRoute}):
 * обработчик сам пишет бинарное тело/заголовки в {@link HttpExchange}, минуя JSON-обёртку
 * {@link ApiResponse}. Логика резолвинга имён вынесена в чистые статические методы и
 * покрывается smoke-тестом без поднятия сервера.</p>
 */
public final class FilesRoutes {

    /** Разрешённые для скачивания/листинга папки (относительно базы приложения). */
    private static final String OUTPUT_DIR = "output";

    /** Временная папка для загруженных на анализ файлов. */
    private static final String UPLOAD_DIR = "uploads";

    private final String baseDir;
    private final corpagent.data.DataStore store;

    public FilesRoutes(String baseDir) {
        this(baseDir,null);
    }
    public FilesRoutes(String baseDir, corpagent.data.DataStore store) {
        this.baseDir = baseDir == null ? "." : baseDir; this.store = store;
    }

    /** Регистрирует файловые маршруты в сервере. */
    public void register(ApiServer server) {
        // Скачивание — бинарный ответ, поэтому стриминговый маршрут (мимо JSON-обёртки).
        server.streamRoute("/api/files/download", new ApiServer.StreamRoute() {
            @Override
            public void stream(String method, String body, Map<String, List<String>> query,
                               HttpExchange exchange) throws Exception {
                handleDownload(query, exchange);
            }
        });
        // Загрузка — читаем тело запроса и сохраняем файл(ы).
        server.streamRoute("/api/files/upload", new ApiServer.StreamRoute() {
            @Override
            public void stream(String method, String body, Map<String, List<String>> query,
                               HttpExchange exchange) throws Exception {
                handleUpload(query, exchange);
            }
        });
        // Листинг output/ для UI.
        server.route("/api/files/list", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                return handleList();
            }
        });
        // Открытие папки делегируется SystemRoutes (единая точка безопасности путей).
        server.route("/api/files/open-folder", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                List<String> paths = query.get("path");
                String rel = (paths == null || paths.isEmpty()) ? "" : paths.get(0);
                return SystemRoutes.openFolder(rel);
            }
        });
    }

    // ==================== СКАЧИВАНИЕ ====================

    void handleDownload(Map<String, List<String>> query, HttpExchange exchange) throws IOException {
        String id = first(query, "id");
        if (id == null || id.trim().isEmpty()) {
            id = first(query, "name");
        }
        File file = resolveDownloadFile(outputDir(), id);
        if (file == null) {
            writeJson(exchange, ApiResponse.notFound("Файл не найден: " + id));
            return;
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        String name = file.getName();
        exchange.getResponseHeaders().set("Content-Type", StaticHandler.mimeType(name));
        exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + asciiFallback(name) + "\"; filename*=UTF-8''"
                        + urlEncodePath(name));
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    File outputDir() {
        return new File(baseDir, OUTPUT_DIR);
    }

    // ==================== ЗАГРУЗКА ====================

    void handleUpload(Map<String, List<String>> query, HttpExchange exchange) throws IOException {
        String chatId = first(query,"chat_id");
        if (chatId != null && (store == null || store.getChat(chatId) == null)) {
            writeJson(exchange,ApiResponse.notFound("Чат не найден")); return;
        }
        if (chatId != null && corpagent.util.TaskControl.busy(chatId)) {
            writeJson(exchange,ApiResponse.badRequest("Дождитесь завершения ответа в этом чате")); return;
        }
        String contentType = header(exchange, "Content-Type");
        byte[] body = readAll(exchange.getRequestBody());
        File uploadDir = new File(baseDir, UPLOAD_DIR);
        if (!uploadDir.isDirectory() && !uploadDir.mkdirs()) {
            writeJson(exchange, ApiResponse.serverError("Не удалось создать папку загрузок"));
            return;
        }

        List<Object> saved = Json.newArray();
        if (contentType != null && contentType.toLowerCase().contains("multipart/form-data")) {
            String boundary = boundaryOf(contentType);
            if (boundary == null) {
                writeJson(exchange, ApiResponse.badRequest("Не найден boundary multipart-запроса."));
                return;
            }
            List<UploadedFile> parts = parseMultipart(body, boundary);
            if (parts.isEmpty()) {
                writeJson(exchange, ApiResponse.badRequest("В запросе нет файлов."));
                return;
            }
            for (UploadedFile part : parts) {
                File dest = writeUploaded(uploadDir, part.filename, part.bytes);
                Map<String, Object> dto = fileDto(dest);
                dto.put("text", corpagent.files.FileExtractor.extractText(dest));
                dto.put("id",java.util.UUID.randomUUID().toString());
                saved.add(dto);
            }
        } else {
            // Сырое бинарное тело: имя берём из query (?name=) или из заголовка.
            String name = first(query, "name");
            if (name == null || name.trim().isEmpty()) {
                name = header(exchange, "X-Filename");
            }
            File dest = writeUploaded(uploadDir, name, body);
            if (dest == null) {
                writeJson(exchange, ApiResponse.badRequest("Не удалось определить имя файла."));
                return;
            }
            Map<String, Object> dto = fileDto(dest);
                dto.put("text", corpagent.files.FileExtractor.extractText(dest));
                dto.put("id",java.util.UUID.randomUUID().toString());
                saved.add(dto);
        }

        Map<String, Object> data = Json.newObject();
        if (chatId != null) synchronized (store) {
            Map<String,Object> chat = store.getChat(chatId);
            if (chat == null) { writeJson(exchange,ApiResponse.notFound("Чат был удалён во время загрузки")); return; }
            List<Object> attachments = Json.arr(chat,"attachments");
            if (attachments == null) { attachments=Json.newArray(); chat.put("attachments",attachments); }
            attachments.addAll(saved); chat.put("updated",corpagent.util.Utils.nowIso()); store.saveAll();
            data.put("chat",ChatRoutes.chatDto(chat));
        }
        data.put("files", saved);
        data.put("count", saved.size());
        writeJson(exchange, ApiResponse.ok(data));
    }

    /** Сохраняет байты во {@code dir} под безопасным именем. Возвращает записанный файл. */
    static File writeUploaded(File dir, String rawName, byte[] bytes) throws IOException {
        String safe = sanitizeName(rawName);
        if (safe == null) {
            safe = "upload.bin";
        }
        File dest = uniqueFile(dir, safe);
        Files.write(dest.toPath(), bytes == null ? new byte[0] : bytes);
        return dest;
    }

    // ==================== ЛИСТИНГ ====================

    ApiResponse handleList() {
        File dir = outputDir();
        List<Object> files = Json.newArray();
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children, new java.util.Comparator<File>() {
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (File f : children) {
                    if (f.isFile()) {
                        files.add(fileDto(f));
                    }
                }
            }
        }
        Map<String, Object> data = Json.newObject();
        data.put("files", files);
        data.put("count", files.size());
        return ApiResponse.ok(data);
    }

    // ==================== ЧИСТЫЕ ХЕЛПЕРЫ (тестируемые) ====================

    /**
     * Резолвит запрошенное имя в файл внутри {@code dir}. Защищает от path traversal:
     * принимается только базовое имя (без разделителей и {@code ..}). Возвращает null,
     * если файла нет или имя небезопасно.
     */
    static File resolveDownloadFile(File dir, String id) {
        String safe = sanitizeName(id);
        if (safe == null) {
            return null;
        }
        File f = new File(dir, safe);
        return f.isFile() ? f : null;
    }

    /**
     * Приводит произвольное имя к безопасному базовому имени файла. Убирает пути,
     * отсеивает {@code ..} и управляющие символы. Возвращает null, если имя пустое
     * или полностью недопустимо.
     */
    static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // Берём только часть после последнего разделителя (и / и \).
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        // Запрещаем ".." и пустые имена.
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
            return null;
        }
        // Убираем управляющие символы.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty() || ".".equals(out) || "..".equals(out)) {
            return null;
        }
        return out;
    }

    /** Возвращает файл в {@code dir} с уникальным именем (добавляет _1, _2 … при коллизии). */
    static File uniqueFile(File dir, String safeName) {
        File f = new File(dir, safeName);
        if (!f.exists()) {
            return f;
        }
        String base = safeName;
        String ext = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            base = safeName.substring(0, dot);
            ext = safeName.substring(dot);
        }
        int n = 1;
        while (f.exists()) {
            f = new File(dir, base + "_" + n + ext);
            n++;
        }
        return f;
    }

    /** Извлекает boundary из заголовка {@code Content-Type: multipart/form-data; boundary=...}. */
    static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        int i = contentType.toLowerCase().indexOf("boundary=");
        if (i < 0) {
            return null;
        }
        String b = contentType.substring(i + "boundary=".length()).trim();
        // Снимаем кавычки, если есть.
        if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
            b = b.substring(1, b.length() - 1);
        }
        int semi = b.indexOf(';');
        if (semi >= 0) {
            b = b.substring(0, semi).trim();
        }
        return b.isEmpty() ? null : b;
    }

    /** Один файл из multipart-запроса. */
    static final class UploadedFile {
        final String filename;
        final byte[] bytes;

        UploadedFile(String filename, byte[] bytes) {
            this.filename = filename;
            this.bytes = bytes;
        }
    }

    /**
     * Разбирает multipart/form-data тело, возвращая только части, содержащие файл
     * (у которых в Content-Disposition есть {@code filename=}). Границы разделов —
     * по строке {@code --boundary}. Реализовано на byte-уровне (без внешних библиотек).
     */
    static List<UploadedFile> parseMultipart(byte[] body, String boundary) {
        List<UploadedFile> out = new ArrayList<UploadedFile>();
        if (body == null || body.length == 0 || boundary == null || boundary.isEmpty()) {
            return out;
        }
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<int[]> ranges = splitByDelimiter(body, delim);
        for (int[] range : ranges) {
            int start = range[0];
            int end = range[1];
            // Пропускаем CRLF сразу после границы.
            while (start < end && (body[start] == '\r' || body[start] == '\n')) {
                start++;
            }
            // Находим конец заголовков части (пустая строка \r\n\r\n).
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), start, end);
            int sepLen = 4;
            if (headerEnd < 0) {
                headerEnd = indexOf(body, "\n\n".getBytes(StandardCharsets.ISO_8859_1), start, end);
                sepLen = 2;
            }
            if (headerEnd < 0) {
                continue; // нет заголовков — не файловая часть (например, хвостовой "--)
            }
            String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
            String filename = filenameOf(headers);
            if (filename == null) {
                continue; // обычное поле формы — пропускаем
            }
            int dataStart = headerEnd + sepLen;
            // Данные заканчиваются перед CRLF, идущим перед следующей границей.
            int dataEnd = end;
            if (dataEnd >= dataStart + 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') dataEnd -= 2;
            else if (dataEnd > dataStart && body[dataEnd - 1] == '\n') dataEnd--;
            byte[] data = new byte[Math.max(0, dataEnd - dataStart)];
            System.arraycopy(body, dataStart, data, 0, data.length);
            out.add(new UploadedFile(filename, data));
        }
        return out;
    }

    // ---- низкоуровневые байтовые хелперы ----

    /** Делит тело на диапазоны между вхождениями {@code delim}. */
    private static List<int[]> splitByDelimiter(byte[] body, byte[] delim) {
        List<int[]> out = new ArrayList<int[]>();
        int from = 0;
        int pos;
        while ((pos = indexOf(body, delim, from, body.length)) >= 0) {
            // Диапазон от конца предыдущего разделителя до начала текущего.
            // Первый диапазон начинается с 0 (заголовок до первой границы обычно пуст).
            out.add(new int[]{from, pos});
            from = pos + delim.length;
        }
        // Хвост после последней границы обрабатываем как часть (если не только "--").
        // Обычно это конец тела — игнорируем, т.к. реальные части уже до последней границы.
        // Первый пустой диапазон (0..первая граница) отбрасываем.
        List<int[]> result = new ArrayList<int[]>();
        for (int i = 1; i < out.size(); i++) {
            result.add(out.get(i));
        }
        return result;
    }

    /** Поиск байтовой подпоследовательности {@code needle} в {@code hay} в диапазоне [from, to). */
    static int indexOf(byte[] hay, byte[] needle, int from, int to) {
        if (needle.length == 0 || from < 0) {
            return -1;
        }
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Извлекает значение filename из блока заголовков части (или null, если его нет). */
    static String filenameOf(String headers) {
        if (headers == null) {
            return null;
        }
        for (String line : headers.split("\r?\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-disposition:")) {
                int i = lower.indexOf("filename=");
                if (i < 0) {
                    return null;
                }
                String v = line.substring(i + "filename=".length()).trim();
                if (v.startsWith("\"")) {
                    int q = v.indexOf('"', 1);
                    v = q > 0 ? v.substring(1, q) : v.substring(1);
                } else {
                    int semi = v.indexOf(';');
                    if (semi >= 0) {
                        v = v.substring(0, semi);
                    }
                    v = v.trim();
                }
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    // ==================== DTO / IO ====================

    static Map<String, Object> fileDto(File f) {
        Map<String, Object> m = Json.newObject();
        m.put("name", f.getName());
        m.put("size", f.length());
        m.put("modified", f.lastModified());
        return m;
    }

    private void writeJson(HttpExchange exchange, ApiResponse resp) throws IOException {
        byte[] bytes = resp.toBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(resp.getHttpStatus(), bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) > 0) {
            if (buf.size() + n > 25 * 1024 * 1024) throw new IOException("Upload exceeds 25 MB");
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static String first(Map<String, List<String>> query, String key) {
        if (query == null) {
            return null;
        }
        List<String> vals = query.get(key);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }

    private static String header(HttpExchange exchange, String name) {
        List<String> vals = exchange.getRequestHeaders().get(name);
        return (vals == null || vals.isEmpty()) ? null : vals.get(0);
    }

    /** ASCII-фолбэк имени файла для старого формата Content-Disposition. */
    static String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c < 128 && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }

    /** Процентное кодирование имени для {@code filename*=UTF-8''...}. */
    static String urlEncodePath(String name) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }
}
