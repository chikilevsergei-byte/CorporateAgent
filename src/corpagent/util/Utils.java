package corpagent.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * Вспомогательные утилиты: чтение/запись файлов, работа с датами, пути.
 */
public final class Utils {

    private Utils() {
    }

    /** Читает весь файл как строку UTF-8. Возвращает null, если файл не существует. */
    public static String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Записывает строку в файл UTF-8, создавая родительские каталоги при необходимости. */
    public static void writeFile(String path, String content) {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.write(Paths.get(path), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Не удалось записать файл: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    public static boolean exists(String path) {
        return new File(path).exists();
    }

    public static void mkdirs(String path) {
        new File(path).mkdirs();
    }

    public static boolean delete(String path) {
        File f = new File(path);
        return f.exists() && f.delete();
    }

    /** Копирует файл. Возвращает путь назначения или null при ошибке. */
    public static String copyFile(String src, String dest) {
        try {
            File sf = new File(src);
            if (!sf.exists()) {
                return null;
            }
            File df = new File(dest);
            File parent = df.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.copy(sf.toPath(), df.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            return null;
        }
    }

    /** Текущее время в формате ISO (yyyy-MM-dd'T'HH:mm:ss.SSSSSS) как в Python datetime.isoformat(). */
    public static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").format(new Date());
    }

    /** Текущее время в формате для имён файлов (yyyyMMdd_HHmmss). */
    public static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    }

    /** Безопасно экранирует имя таблицы/колонки для SQL (защита от инъекций). */
    public static String quoteIdent(String name) {
        return "\"" + String.valueOf(name).replace("\"", "\"\"") + "\"";
    }

    /** Проверяет, что имя БД содержит только буквы, цифры, '_' и '-'. */
    public static boolean isValidDbName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** Обрезает строку до max символов, добавляя многоточие. */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    /** Преобразует значение в строку (аналог to_str в Python). */
    public static String toStr(Object val) {
        if (val == null) {
            return "";
        }
        if (val instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) val;
            return list.isEmpty() ? "" : String.valueOf(list.get(0));
        }
        return String.valueOf(val);
    }

    /** Проверяет, что строка начинается с одного из ключевых слов (без учёта регистра). */
    public static boolean startsWithAny(String upper, String[] keywords) {
        for (String kw : keywords) {
            if (upper.startsWith(kw)) {
                return true;
            }
        }
        return false;
    }

    /** Возвращает значение из вложенной структуры Map по ключу, либо null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }
}
