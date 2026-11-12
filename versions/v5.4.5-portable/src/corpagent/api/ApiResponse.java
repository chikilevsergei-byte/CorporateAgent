package corpagent.api;

import corpagent.util.Json;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Унифицированный ответ HTTP-API (Этап 1 миграции v3 → v4).
 *
 * <p>Все эндпоинты возвращают JSON единого формата:</p>
 * <pre>
 * { "ok": true,  "data": { ... } }
 * { "ok": false, "error": "текст ошибки" }
 * </pre>
 *
 * <p>Класс не зависит от {@code com.sun.net.httpserver} — только формат + сериализация
 * через собственный {@link Json}. Это упрощает тестирование контракта без поднятия сервера.</p>
 */
public final class ApiResponse {

    /** UTF-8 — единая кодировка тела ответов. */
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private final boolean ok;
    private final Object data;
    private final String error;
    private final int httpStatus;

    private ApiResponse(boolean ok, Object data, String error, int httpStatus) {
        this.ok = ok;
        this.data = data;
        this.error = error;
        this.httpStatus = httpStatus;
    }

    // ==================== ФАБРИКИ ====================

    /** Успешный ответ 200 с произвольной полезной нагрузкой (Map/List/строка/число). */
    public static ApiResponse ok(Object data) {
        return new ApiResponse(true, data, null, 200);
    }

    /** Успешный ответ с числовым HTTP-статусом (например 201 Created). */
    public static ApiResponse ok(Object data, int httpStatus) {
        return new ApiResponse(true, data, null, httpStatus);
    }

    /** Ошибка 400 (некорректный запрос) с текстовым описанием. */
    public static ApiResponse badRequest(String error) {
        return new ApiResponse(false, null, error, 400);
    }

    /** Ошибка 401 (нет/неверный токен). */
    public static ApiResponse unauthorized() {
        return new ApiResponse(false, null, "Unauthorized", 401);
    }

    /** Ошибка 404 (маршрут/ресурс не найден). */
    public static ApiResponse notFound(String error) {
        return new ApiResponse(false, null,
                error == null || error.isEmpty() ? "Not found" : error, 404);
    }

    /** Ошибка 500 (внутренняя). */
    public static ApiResponse serverError(String error) {
        return new ApiResponse(false, null,
                error == null || error.isEmpty() ? "Internal error" : error, 500);
    }

    // ==================== ДОСТУП ====================

    public boolean isOk() {
        return ok;
    }

    public Object getData() {
        return data;
    }

    public String getError() {
        return error;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Сериализует ответ в JSON-строку формата {@code {ok, data|error}}.
     * Поле {@code data} включается только при успехе, {@code error} — только при ошибке.
     */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("ok", ok);
        if (ok) {
            root.put("data", data);
        } else {
            root.put("error", error == null ? "" : error);
        }
        return Json.stringify(root, false);
    }

    /** Тело ответа в байтах (готово к записи в {@code HttpExchange}). */
    public byte[] toBytes() {
        return toJson().getBytes(UTF8);
    }
}
