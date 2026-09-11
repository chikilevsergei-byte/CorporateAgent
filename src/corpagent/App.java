package corpagent;

import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.files.FileCreator;
import corpagent.gui.MainFrame;
import corpagent.util.Config;
import corpagent.util.Settings;
import corpagent.util.TokenBudget;
import corpagent.util.Utils;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Точка входа в приложение CorporateAgent v3.
 * Инициализирует конфигурацию, хранилище данных, менеджер БД и запускает GUI.
 */
public class App {

    public static void main(String[] args) {
        // Базовая папка приложения — там, где лежит запускаемый jar/классы.
        String baseDir = System.getProperty("corpagent.base", ".");
        Utils.mkdirs(baseDir + java.io.File.separator + "databases");
        Utils.mkdirs(baseDir + java.io.File.separator + "backups");
        Utils.mkdirs(baseDir + java.io.File.separator + "output");

        try {
                        Config config = new Config(baseDir + java.io.File.separator + "config.json");
            Settings settings = new Settings(baseDir);
            // Авторизуем суточный лимит токенов в глобальном учёте.
            TokenBudget.setDailyLimit(settings.getDailyLimitTokens());
            DataStore store = new DataStore(baseDir);
            DatabaseManager dbManager = new DatabaseManager(
                    baseDir + java.io.File.separator + "databases",
                    baseDir + java.io.File.separator + "backups",
                    store);
            FileCreator fileCreator = new FileCreator(baseDir + java.io.File.separator + "output", baseDir);
            Agent agent = new Agent(config, store, dbManager, fileCreator);

            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    try {
                        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    } catch (Exception e) {
                        // используем стандартный вид
                    }
                    // Единый шрифт для стандартных компонентов Swing (без перебора всех
                    // ключей UIManager, что могло ломать отрисовку текста на некоторых системах).
                    try {
                        UIManager.put("defaultFont",
                                new javax.swing.plaf.FontUIResource("Segoe UI", java.awt.Font.PLAIN, 14));
                    } catch (Exception ignored) {
                        // не критично — компоненты задают шрифты сами через Ui.FONT_*
                    }
                                        MainFrame frame = new MainFrame(agent, store, dbManager, fileCreator, baseDir, config, settings);
                    frame.setVisible(true);
                    // UI-3.1: приветственный тост после показа окна.
                    frame.showWelcomeToast();
                }
            });
        } catch (Exception e) {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "Ошибка запуска: " + e.getMessage(),
                    "CorporateAgent v3",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }
}
