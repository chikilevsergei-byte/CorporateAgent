package corpagent.data;

import corpagent.util.Json;
import corpagent.util.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Резервное копирование и восстановление данных (R-P1.7).
 * Бэкапит JSON-файлы данных + .db-файлы в ZIP-архив в папку backups/.
 */
public final class BackupManager {

    private BackupManager() {
    }

    /** Создаёт бэкап всех данных (чаты, навыки, инструменты, БД, логи) в backups/. */
    public static String createBackup(String baseDir, String backupsDir) {
        String ts = Utils.timestamp();
        String backupName = "backup_" + ts;
        String backupPath = backupsDir + java.io.File.separator + backupName + ".zip";
        try {
            File backupFile = new File(backupPath);
            File parent = backupFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // Собираем файлы для бэкапа
            List<File> files = new ArrayList<File>();
            String[] jsonFiles = {"chats", "skills", "tools", "databases", "db_logs", "projects", "config", "settings"};
            for (String name : jsonFiles) {
                String path = baseDir + java.io.File.separator + name + ".json";
                File f = new File(path);
                if (f.exists()) {
                    files.add(f);
                }
            }
            // Добавляем .db-файлы
            Map<String, Object> dbs = Json.parseObject(Utils.readFile(baseDir + java.io.File.separator + "databases.json"));
            if (dbs != null) {
                for (Map.Entry<String, Object> e : dbs.entrySet()) {
                    Map<String, Object> db = Json.asMap(e.getValue());
                    if (db != null) {
                        String dbPath = Json.str(db, "file", Json.str(db, "path"));
                        if (!dbPath.isEmpty() && new File(dbPath).exists()) {
                            files.add(new File(dbPath));
                        }
                    }
                }
            }
            // Создаём ZIP
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(backupFile))) {
                byte[] buffer = new byte[4096];
                for (File f : files) {
                    ZipEntry ze = new ZipEntry(f.getName());
                    zos.putNextEntry(ze);
                    try (InputStream is = new FileInputStream(f)) {
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
            return backupPath;
        } catch (Exception e) {
            throw new RuntimeException("Ошибка создания бэкапа: " + e.getMessage(), e);
        }
    }

    /** Восстанавливает данные из ZIP-бэкапа. */
    public static void restoreBackup(String baseDir, String backupPath) throws Exception {
        File backupFile = new File(backupPath);
        if (!backupFile.exists()) {
            throw new RuntimeException("Файл бэкапа не найден: " + backupPath);
        }
        // Создаём временную директорию
        String tempDir = Utils.getTempDir() + java.io.File.separator + "corpagent_restore_" + System.currentTimeMillis();
        Utils.mkdirs(tempDir);
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(backupFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(tempDir, entry.getName());
                if (entry.isDirectory()) {
                    Utils.mkdirs(outFile.getAbsolutePath());
                } else {
                    Utils.mkdirs(outFile.getParent());
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        // Копируем JSON-файлы в baseDir
        String[] jsonFiles = {"chats", "skills", "tools", "databases", "db_logs", "projects", "config", "settings"};
        for (String name : jsonFiles) {
            File src = new File(tempDir, name + ".json");
            if (src.exists()) {
                File dest = new File(baseDir, name + ".json");
                Utils.copyFile(src.getAbsolutePath(), dest.getAbsolutePath());
            }
        }
        // Копируем .db-файлы в databases/
        File dbDir = new File(baseDir, "databases");
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }
        File[] tempFiles = new File(tempDir).listFiles();
        if (tempFiles != null) {
            for (File f : tempFiles) {
                if (f.getName().endsWith(".db")) {
                    File dest = new File(dbDir, f.getName());
                    Utils.copyFile(f.getAbsolutePath(), dest.getAbsolutePath());
                }
            }
        }
    }

    /** Возвращает список доступных бэкапов в backups/. */
    public static List<Map<String, Object>> listBackups(String backupsDir) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        File dir = new File(backupsDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".zip"));
        if (files == null) return result;
        for (File f : files) {
            Map<String, Object> info = Json.newObject();
            info.put("name", f.getName().replace(".zip", ""));
            info.put("path", f.getAbsolutePath());
            info.put("size", f.length());
            info.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(f.lastModified()));
            result.add(info);
        }
        return result;
    }
}
