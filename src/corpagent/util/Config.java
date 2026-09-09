package corpagent.util;

import java.util.Map;

/**
 * Загрузка, хранение и сохранение настроек из config.json.
 * Поля: api_key, model, temperature, max_tokens, provider, base_url.
 * Поддерживает чтение и запись (для вкладки «Модель» в настройках).
 *
 * <p>Начиная с R-P0.1 поддерживается выбор провайдера (DeepSeek / Яндекс ИИ студия /
 * пользовательский OpenAI-совместимый endpoint): адрес API берётся из конфига (base_url),
 * а не захардкожен в клиенте.</p>
 */
public class Config {

    /** Имя провайдера по умолчанию (DeepSeek). */
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    /** Имя провайдера Яндекс AI Studio (совместимый endpoint). */
    public static final String PROVIDER_YANDEX = "yandex";
    /** Произвольный OpenAI-совместимый провайдер. */
    public static final String PROVIDER_CUSTOM = "custom";

    /** Базовый URL DeepSeek (без завершающего "/chat/completions"). */
    public static final String BASE_URL_DEEPSEEK = "https://api.deepseek.com";
    /** Базовый URL Яндекс AI Studio (облачный OpenAI-совместимый endpoint). */
    public static final String BASE_URL_YANDEX = "https://llm.api.cloud.yandex.net/foundationModels/v1";

    /** Относительный путь chat/completions поверх base_url. */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final String configPath;
    private final Map<String, Object> data;

    public Config(String configPath) {
        this.configPath = configPath;
        String content = Utils.readFile(configPath);
        if (content == null) {
            throw new IllegalStateException("Файл config.json не найден: " + configPath);
        }
        this.data = Json.parseObject(content);
    }

    public String getApiKey() {
        return Json.str(data, "api_key", "");
    }

    public String getModel() {
        return Json.str(data, "model", "deepseek-chat");
    }

    public double getTemperature() {
        return Json.doubleVal(data, "temperature", 0.7);
    }

    public int getMaxTokens() {
        return Json.intVal(data, "max_tokens", 4000);
    }

    // ==================== ПРОВАЙДЕР / URL (R-P0.1) ====================

    /** Идентификатор провайдера: deepseek / yandex / custom. */
    public String getProvider() {
        String p = Json.str(data, "provider", "");
        if (p.isEmpty()) {
            return PROVIDER_DEEPSEEK;
        }
        return p;
    }

    /** Базовый URL API (без пути /chat/completions). */
    public String getBaseUrl() {
        String p = getProvider();
        String url = Json.str(data, "base_url", "");
        if (url == null || url.trim().isEmpty()) {
            return defaultBaseUrl(p);
        }
        String trimmed = trimTrailingSlash(url.trim());
        if (trimmed.endsWith("/chat/completions")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        return trimmed;
    }

    /** Полный endpoint для chat/completions. */
    public String getChatCompletionUrl() {
        return trimTrailingSlash(getBaseUrl()) + CHAT_COMPLETIONS_PATH;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Базовый URL по умолчанию для заданного провайдера. */
    public static String defaultBaseUrl(String provider) {
        if (PROVIDER_YANDEX.equals(provider)) {
            return BASE_URL_YANDEX;
        }
        if (PROVIDER_CUSTOM.equals(provider)) {
            return "";
        }
        return BASE_URL_DEEPSEEK;
    }

    public Map<String, Object> getRaw() {
        return data;
    }

    // ==================== ЗАПИСЬ ====================

    public void setApiKey(String value) {
        data.put("api_key", value == null ? "" : value);
    }

    public void setModel(String value) {
        data.put("model", value == null ? "deepseek-chat" : value);
    }

    public void setTemperature(double value) {
        data.put("temperature", value);
    }

    public void setMaxTokens(int value) {
        data.put("max_tokens", value);
    }

    public void setProvider(String value) {
        String p = value == null ? "" : value.trim();
        if (p.isEmpty()) {
            p = PROVIDER_DEEPSEEK;
        }
        String old = data.get("provider") == null ? null : String.valueOf(data.get("provider"));
        data.put("provider", p);
        // При смене провайдера, если base_url не задан явно или был задан по умолчанию для
        // старого провайдера — подставляем базовый URL нового провайдера.
        String existing = data.get("base_url") == null ? "" : String.valueOf(data.get("base_url"));
        if (existing.isEmpty() || defaultBaseUrl(old == null || old.isEmpty() ? "" : old).equals(existing)) {
            data.put("base_url", defaultBaseUrl(p));
        }
    }

    public void setBaseUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            data.put("base_url", defaultBaseUrl(getProvider()));
        } else {
            String v = value.trim();
            if (v.endsWith("/chat/completions")) {
                v = v.substring(0, v.length() - "/chat/completions".length());
            }
            data.put("base_url", trimTrailingSlash(v));
        }
    }

    /** Сохраняет текущие настройки в config.json (создавая резервную копию при первом сохранении). */
    public void save() {
        // Гарантируем, что обязательные поля провайдера присутствуют при сохранении.
        if (data.get("provider") == null) {
            data.put("provider", PROVIDER_DEEPSEEK);
        }
        if (data.get("base_url") == null || String.valueOf(data.get("base_url")).isEmpty()) {
            data.put("base_url", defaultBaseUrl(getProvider()));
        }
        Utils.writeFile(configPath, Json.stringify(data, true));
    }
}
