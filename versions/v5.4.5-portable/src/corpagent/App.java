package corpagent;

import corpagent.api.ApiBootstrap;
import corpagent.api.ApiServer;
import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.files.FileCreator;
import corpagent.util.Config;
import corpagent.util.Settings;
import corpagent.util.TokenBudget;
import corpagent.util.Utils;


/**
 * Точка входа в приложение CorporateAgent v3.
 * Инициализирует конфигурацию, хранилище данных, менеджер БД и запускает GUI.
 */
public class App {
    private static java.nio.channels.FileChannel lease;
    private static java.nio.channels.FileLock lock;

    public static void main(String[] args) {
        // Базовая папка приложения — там, где лежит запускаемый jar/классы.
        String baseDir = System.getProperty("corpagent.base", ".");
        try{
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(baseDir));
            lease=java.nio.channels.FileChannel.open(java.nio.file.Paths.get(baseDir,".application.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
            lock=lease.tryLock();if(lock==null)throw new IllegalStateException("Application already running");
        }catch(Exception busy){System.err.println("Application data directory is already open or unavailable.");System.exit(3);}
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
                        FileCreator fileCreator = new FileCreator(baseDir + java.io.File.separator + "output",System.getProperty("corpagent.install",baseDir));
            Agent agent = new Agent(config, store, dbManager, fileCreator);

            // Этап 1 (v4): поднимаем локальный HTTP-сервер и открываем браузер.
            // Legacy Swing-окно пока работает параллельно — будет удалено на Этапе 6.
            try {
                ApiBootstrap.StartResult srv = ApiBootstrap.start(baseDir, agent, store, dbManager,
                        config, settings);
                System.out.println("[App] Web-UI запущен на порту " + srv.port);
                System.out.println("[App] URL: " + srv.server.browsableUrl());
                java.util.Map<String,Object> info=corpagent.util.Json.newObject();
                info.put("url",srv.server.browsableUrl());info.put("instance_id",srv.server.getInstanceId());info.put("pid",ProcessHandle.current().pid());info.put("mode","application");
                info.put("jar_file",java.nio.file.Paths.get(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString());
                java.nio.file.Path target=java.nio.file.Paths.get(baseDir,"app-instance.json"),temp=java.nio.file.Files.createTempFile(target.getParent(),"launch-",".tmp");
                java.nio.file.Files.write(temp,corpagent.util.Json.stringify(info).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.move(temp,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception webEx) {
                System.err.println("[App] Не удалось запустить HTTP-сервер: " + webEx.getMessage());
                System.exit(4);
            }


        } catch (Exception e) {
            e.printStackTrace();
            System.exit(5);
        }
    }
}

