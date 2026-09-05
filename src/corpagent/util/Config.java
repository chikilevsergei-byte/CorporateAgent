package corpagent.util;

import java.util.Map;

/**
 * Загрузка, хранение и сохранение настроек из config.json.
 * Поля: api_key, model, temperature, max_tokens.
 * Поддерживает чтение и запись (для вкладки «Модель» в настройках).
 */
public class Config {

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

    /** Сохраняет текущие настройки в config.json (создавая резервную копию при первом сохранении). */
    public void save() {
        Utils.writeFile(configPath, Json.stringify(data, true));
    }
}
