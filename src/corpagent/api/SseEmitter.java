package corpagent.api;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import corpagent.util.Json;

/**
 * Отправитель Server-Sent Events (Этап 3 миграции v3 → v4).
 *
 * <p>Пишет поток SSE-кадров {@code event: <name>\ndata: <json>\n\n} в тело
 * {@link HttpExchange}. Заголовки выставляются один раз при {@link #start()}.
 * Потокобезопасен: кадры пишутся под общим монитором, а {@link #close()} идемпотентен.</p>
 *
 * <p>Протокол событий чата:</p>
 * <ul>
 *   <li>{@code token} — {@code {text: "..."}} фрагмент ответа;</li>
 *   <li>{@code done} — {@code {text: "полный ответ"}} завершение;</li>
 *   <li>{@code error} — {@code {message: "..."}} ошибка.</li>
 * </ul>
 */
public class SseEmitter {

    private final HttpExchange exchange;
    private final Object lock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private OutputStream out;

    public SseEmitter(HttpExchange exchange) {
        this.exchange = exchange;
    }

    /** Отправляет заголовки SSE и открывает поток. Вызывать один раз до первого кадра. */
    public void start() throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        // 0 → chunked (неизвестная длина): сервер не буферизует ответ целиком.
        exchange.sendResponseHeaders(200, 0);
        out = exchange.getResponseBody();
    }

    /** Отправляет именованное событие с JSON-полезной нагрузкой. */
    public void send(String event, Map<String, Object> data) {
        String json = Json.stringify(data == null ? Json.newObject() : data);
        sendRaw(event, json);
    }

    /** Отправляет событие с текстовой полезной нагрузкой (обёрнутой в JSON-объект text). */
    public void sendText(String event, String text) {
        Map<String, Object> data = Json.newObject();
        data.put("text", text == null ? "" : text);
        send(event, data);
    }

    private void sendRaw(String event, String json) {
        if (closed.get() || out == null) {
            return;
        }
        synchronized (lock) {
            if (closed.get() || out == null) {
                return;
            }
            try {
                StringBuilder sb = new StringBuilder();
                if (event != null && !event.isEmpty()) {
                    sb.append("event: ").append(event).append('\n');
                }
                // data не должен содержать переводов строк — JSON.stringify даёт одну строку.
                sb.append("data: ").append(json).append("\n\n");
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                // Клиент оборвал соединение — помечаем поток закрытым.
                closed.set(true);
            }
        }
    }

    /** Идемпотентно закрывает поток ответа. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
                // поток уже мог быть закрыт сервером
            }
        }
    }
}
