package corpagent.api;

import java.security.SecureRandom;

/**
 * Токен-авторизация локального API (Этап 1 миграции v3 → v4).
 *
 * <p>Сервер слушает только {@code 127.0.0.1}, но API всё равно защищён случайным
 * токеном, который генерируется при старте процесса и передаётся в URL браузера
 * ({@code http://127.0.0.1:8080/?token=...}). Каждый запрос к {@code /api/*} должен
 * отправлять заголовок {@code Authorization: Bearer <token>}.</p>
 *
 * <p>Токен живёт ровно столько, сколько живёт процесс — персистентность не нужна.</p>
 *
 * <p>Класс изолирован от HTTP-слоя: проверяет только строки заголовков, что делает
 * его покрываемым smoke-тестом без поднятия сервера.</p>
 */
public final class Auth {

    /** Префикс схемы авторизации. */
    public static final String BEARER_PREFIX = "Bearer ";

    /** Заголовок, в котором передаётся токен. */
    public static final String HEADER = "Authorization";

    private final String token;

    private Auth(String token) {
        this.token = token;
    }

    /** Создаёт экземпляр со случайным URL-safe токеном (32 символа). */
    public static Auth withRandomToken() {
        return new Auth(generateToken());
    }

    /** Создаёт экземпляр с заданным токеном (для тестов). */
    public static Auth withToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token не может быть пустым");
        }
        return new Auth(token);
    }

    /** Генерирует криптостойкий токен из алфавита {@code [A-Za-z0-9]} длиной 32. */
    public static String generateToken() {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public String getToken() {
        return token;
    }

    /**
     * Проверяет значение заголовка {@code Authorization} на соответствие токену.
     * Принимает формат {@code Bearer <token>} (без учёта регистра схемы).
     *
     * @param authorizationHeader значение заголовка (может быть null)
     * @return true, если токен совпадает
     */
    public boolean isValid(String authorizationHeader) {
        if (authorizationHeader == null) {
            return false;
        }
        String value = authorizationHeader.trim();
        String expected = BEARER_PREFIX + token;
        // Быстрая проверка с точным совпадением (в т.ч. регистр схемы «bearer»).
        if (value.equalsIgnoreCase(BEARER_PREFIX + token)) {
            return true;
        }
        // Явный разбор: отрезаем префикс и сравниваем токен.
        int sp = value.indexOf(' ');
        if (sp < 0) {
            return false;
        }
        String scheme = value.substring(0, sp);
        String rest = value.substring(sp + 1).trim();
        return "Bearer".equalsIgnoreCase(scheme) && constantTimeEquals(expected, scheme + " " + rest);
    }

    /** Сравнение без раннего выхода (защита от timing-атак). */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Готовое значение заголовка для тестов/клиента. */
    public String headerValue() {
        return BEARER_PREFIX + token;
    }
}
