package corpagent.api;

import corpagent.llm.DeepSeekClient;
import corpagent.util.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Реальная реализация {@link SettingsRoutes.LlmTester} (Этап 6.1).
 *
 * <p>Делает минимальный пробный запрос к LLM текущими настройками конфига
 * ({@link DeepSeekClient#call}) и классифицирует результат как успех/ошибку.
 * Клиент возвращает текст ответа или строку, начинающуюся с «❌» — при ошибке.
 * Это позволяет тестировать подключение без обращения к сетевому слою в тестах
 * (см. {@link SettingsRoutes.LlmTester}).</p>
 */
public final class DeepSeekLlmTester implements SettingsRoutes.LlmTester {

    /** Короткий пробный промпт, чтобы запрос был максимально дешёвым. */
    private static final String PING_PROMPT = "Reply with the single word: pong";

    @Override
    public SettingsRoutes.TestResult test(Config config) {
        try {
            java.util.Map<String,Object> raw=new java.util.LinkedHashMap<String,Object>(config.getRaw());
            raw.put("max_tokens",32);raw.put("temperature",0.1);
            DeepSeekClient client = new DeepSeekClient(Config.fromRaw(raw));
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            Map<String, Object> userMsg = corpagent.util.Json.newObject();
            userMsg.put("role", "user");
            userMsg.put("content", PING_PROMPT);
            messages.add(userMsg);

            String reply = client.call(messages, "");
            if (reply == null) {
                return new SettingsRoutes.TestResult(false, "Пустой ответ.");
            }
            if (reply.startsWith("❌")) {
                // Клиент заворачивает ошибку в «❌ ...» — возвращаем без префикса.
                String detail = reply.substring(1).trim();
                return new SettingsRoutes.TestResult(false, detail);
            }
            String trimmed = reply.trim();
            if (trimmed.length() > 200) {
                trimmed = trimmed.substring(0, 200) + "…";
            }
            return new SettingsRoutes.TestResult(true, trimmed);
        } catch (Exception e) {
            return new SettingsRoutes.TestResult(false, e.getMessage());
        }
    }
}
