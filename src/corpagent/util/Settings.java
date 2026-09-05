package corpagent.util;

import java.util.Map;

/**
 * Настройки интерфейса (вкладка «Интерфейс» в настройках).
 * Хранятся в отдельном файле settings.json рядом с приложением.
 * Поля: auto_update_db, theme, show_usage_in_chat.
 */
public class Settings {

    private final String settingsPath;
    private final Map<String, Object> data;

    public Settings(String baseDir) {
        this.settingsPath = baseDir + java.io.File.separator + "settings.json";
        String content = Utils.readFile(settingsPath);
        if (content == null) {
            this.data = Json.newObject();
            // Значения по умолчанию
            this.data.put("auto_update_db", true);
            this.data.put("theme", "green");
            this.data.put("show_usage_in_chat", true);
        } else {
            this.data = Json.parseObject(content);
        }
    }

    /** Глобальный переключатель автообновления БД (ранее был в сайдбаре). */
    public boolean isAutoUpdateDb() {
        return Json.bool(data, "auto_update_db", true);
    }

    public void setAutoUpdateDb(boolean value) {
        data.put("auto_update_db", value);
    }

    /** Тема оформления (green / dark / blue). */
    public String getTheme() {
        return Json.str(data, "theme", "green");
    }

    public void setTheme(String value) {
        data.put("theme", value == null ? "green" : value);
    }

    /** Показывать ли в чате визуализацию использования навыков/инструментов/БД. */
    public boolean isShowUsageInChat() {
        return Json.bool(data, "show_usage_in_chat", true);
    }

    public void setShowUsageInChat(boolean value) {
        data.put("show_usage_in_chat", value);
    }

    public Map<String, Object> getRaw() {
        return data;
    }

    /** Сохраняет настройки интерфейса в settings.json. */
    public void save() {
        Utils.writeFile(settingsPath, Json.stringify(data, true));
    }
}
