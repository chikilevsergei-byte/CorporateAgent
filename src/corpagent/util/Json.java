package corpagent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Компактный JSON-парсер и сериализатор на чистой Java (без внешних зависимостей).
 * Поддерживает объекты, массивы, строки, числа, boolean, null.
 * Используется для чтения/записи config.json, chats.json, skills.json, tools.json,
 * databases.json, db_logs.json и ответов DeepSeek API.
 */
public final class Json {

    private Json() {
    }

    // ==================== ПАРСИНГ ====================

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.eof()) {
            throw new JsonException("Лишние символы после JSON-значения");
        }
        return value;
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Ожидался JSON-объект");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) v;
        return map;
    }

    public static List<Object> parseArray(String text) {
        Object v = parse(text);
        if (!(v instanceof List)) {
            throw new JsonException("Ожидался JSON-массив");
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) v;
        return list;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (eof()) {
                throw new JsonException("Неожиданный конец JSON");
            }
            return s.charAt(i);
        }

        char next() {
            if (eof()) {
                throw new JsonException("Неожиданный конец JSON");
            }
            return s.charAt(i++);
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new JsonException("Недопустимый символ JSON: '" + c + "'");
            }
        }

        void expect(String word) {
            for (int k = 0; k < word.length(); k++) {
                if (eof() || next() != word.charAt(k)) {
                    throw new JsonException("Ошибка разбора литерала " + word);
                }
            }
        }

        Map<String, Object> parseObject() {
            next(); // '{'
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                next();
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (next() != ':') {
                    throw new JsonException("Ожидалось ':' в объекте");
                }
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw new JsonException("Ожидалось ',' или '}' в объекте");
            }
            return map;
        }

        List<Object> parseArray() {
            next(); // '['
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (peek() == ']') {
                next();
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw new JsonException("Ожидалось ',' или ']' в массиве");
            }
            return list;
        }

        String parseString() {
            next(); // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new JsonException("Незакрытая строка JSON");
                }
                char c = next();
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw new JsonException("Недопустимая escape-последовательность \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (!eof() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e'
                    || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            String num = s.substring(start, i);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.valueOf(num);
                }
                return Long.valueOf(num);
            } catch (NumberFormatException e) {
                throw new JsonException("Недопустимое число: " + num);
            }
        }
    }

    // ==================== СЕРИАЛИЗАЦИЯ ====================

    public static String stringify(Object value) {
        return stringify(value, false);
    }

    public static String stringify(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, pretty, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            writeObject(sb, (Map<String, Object>) value, pretty, depth);
        } else if (value instanceof List) {
            writeArray(sb, (List<Object>) value, pretty, depth);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, boolean pretty, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                newlineIndent(sb, depth + 1);
            }
            writeString(sb, e.getKey());
            sb.append(':');
            if (pretty) {
                sb.append(' ');
            }
            writeValue(sb, e.getValue(), pretty, depth + 1);
        }
        if (pretty) {
            newlineIndent(sb, depth);
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, boolean pretty, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                newlineIndent(sb, depth + 1);
            }
            writeValue(sb, o, pretty, depth + 1);
        }
        if (pretty) {
            newlineIndent(sb, depth);
        }
        sb.append(']');
    }

    private static void newlineIndent(StringBuilder sb, int depth) {
        sb.append('\n');
        for (int k = 0; k < depth; k++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ==================== УДОБНЫЕ ДОСТУПЫ ====================

    public static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int intVal(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static double doubleVal(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return null;
    }

    /** Приводит произвольный объект к Map<String, Object>, если это Map, иначе null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return null;
    }

    public static Map<String, Object> newObject() {
        return new LinkedHashMap<String, Object>();
    }

    public static List<Object> newArray() {
        return new ArrayList<Object>();
    }

    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }
}
