package corpagent.llm;

import corpagent.util.Config;
import corpagent.util.Json;
import corpagent.util.TokenBudget;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Клиент для DeepSeek API (OpenAI-совместимый формат).
 * Использует HttpURLConnection из JDK — без внешних зависимостей.
 * Легко адаптируется под Qwen, OpenRouter и другие совместимые API.
 */
public class DeepSeekClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final int MAX_TOOL_ITERATIONS = 5;

    private final Config config;

    public DeepSeekClient(Config config) {
        this.config = config;
    }

    /**
     * Обработчик вызова инструмента (function calling).
     * Вызывается для каждого tool_call из ответа модели.
     */
    public interface ToolCallHandler {
        /**
         * Выполняет инструмент.
         *
         * @param name      имя функции (нормализованное)
         * @param arguments аргументы (Map имя → значение)
         * @return текстовый результат для передачи модели (role "tool")
         */
        String execute(String name, Map<String, Object> arguments);
    }

    /**
     * Универсальный вызов LLM.
     *
     * @param messages      список сообщений (Map с role и content/text)
     * @param systemPrompt  системный промпт (может быть пустым)
     * @return текст ответа или сообщение об ошибке (начинается с "❌")
     */
    public String call(List<Map<String, Object>> messages, String systemPrompt) {
        List<Map<String, Object>> allMessages = buildMessages(messages, systemPrompt);

        Map<String, Object> data = Json.newObject();
        data.put("model", config.getModel());
        data.put("messages", allMessages);
        data.put("temperature", config.getTemperature());
        data.put("max_tokens", config.getMaxTokens());

                Map<String, Object> respJson = postRequest(data);
        if (respJson == null) {
            return "❌ Ошибка API: пустой ответ.";
        }
        recordUsage(respJson);
        Map<String, Object> message = firstMessage(respJson);
        if (message == null) {
            return "❌ Ошибка API: Неверная структура ответа. " + Json.stringify(respJson);
        }
        Object content = message.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    /** Учитывает расход токенов ответа API в общем учёте. */
    private static void recordUsage(Map<String, Object> respJson) {
        if (respJson == null) {
            return;
        }
        Map<String, Object> usage = Json.asMap(respJson.get("usage"));
        if (usage == null) {
            return;
        }
        long prompt = Json.intVal(usage, "prompt_tokens", 0);
        long completion = Json.intVal(usage, "completion_tokens", 0);
        TokenBudget.record(prompt, completion);
    }

    /**
     * Вызов LLM с поддержкой function calling (инструментов).
     * Отправляет массив {@code tools} в запросе и обрабатывает {@code tool_calls}
     * в ответе: цикл «модель → вызов инструмента → результат → модель» до тех пор,
     * пока модель не вернёт финальный текст (с ограничением числа итераций).
     *
     * @param messages      список сообщений (Map с role и content/text)
     * @param systemPrompt  системный промпт (может быть пустым)
     * @param tools         массив tools (см. ToolRegistry.buildToolsArray)
     * @param handler       обработчик вызова инструмента
     * @return финальный текст ответа или сообщение об ошибке (начинается с "❌")
     */
    public String callWithTools(List<Map<String, Object>> messages, String systemPrompt,
                                List<Object> tools, ToolCallHandler handler) {
        List<Map<String, Object>> allMessages = buildMessages(messages, systemPrompt);

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            Map<String, Object> data = Json.newObject();
            data.put("model", config.getModel());
            data.put("messages", allMessages);
            data.put("temperature", config.getTemperature());
            data.put("max_tokens", config.getMaxTokens());
            if (tools != null && !tools.isEmpty()) {
                data.put("tools", tools);
            }

                        Map<String, Object> respJson = postRequest(data);
            if (respJson == null) {
                return "❌ Ошибка API: пустой ответ.";
            }
            recordUsage(respJson);
            Map<String, Object> message = firstMessage(respJson);
            if (message == null) {
                return "❌ Ошибка API: Неверная структура ответа. " + Json.stringify(respJson);
            }

            // Добавляем сообщение ассистента в историю (важно для tool_calls).
            Map<String, Object> assistantMsg = Json.newObject();
            assistantMsg.put("role", "assistant");
            Object content = message.get("content");
            assistantMsg.put("content", content == null ? "" : String.valueOf(content));
            if (message.containsKey("tool_calls")) {
                assistantMsg.put("tool_calls", message.get("tool_calls"));
            }
            allMessages.add(assistantMsg);

            List<Object> toolCalls = Json.arr(message, "tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) {
                // Модель не запросила инструменты — это финальный ответ.
                return content == null ? "" : String.valueOf(content);
            }

            // Выполняем каждый tool_call и добавляем результат в историю.
            boolean anyExecuted = false;
            for (Object tcObj : toolCalls) {
                Map<String, Object> tc = Json.asMap(tcObj);
                if (tc == null) {
                    continue;
                }
                String id = Json.str(tc, "id");
                if (id.isEmpty()) {
                    id = "call_" + iteration + "_" + toolCalls.indexOf(tcObj);
                }
                Map<String, Object> fn = Json.obj(tc, "function");
                String fnName = fn == null ? "" : Json.str(fn, "name");
                Map<String, Object> fnArgs = new java.util.LinkedHashMap<String, Object>();
                if (fn != null) {
                    String argsText = Json.str(fn, "arguments");
                    if (argsText != null && !argsText.trim().isEmpty()) {
                        try {
                            Object parsed = Json.parse(argsText);
                            Map<String, Object> parsedMap = Json.asMap(parsed);
                            if (parsedMap != null) {
                                fnArgs.putAll(parsedMap);
                            }
                        } catch (Exception e) {
                            // невалидный JSON аргументов — передаём как есть
                        }
                    }
                }

                String toolResult;
                if (handler != null && !fnName.isEmpty()) {
                    try {
                        toolResult = handler.execute(fnName, fnArgs);
                    } catch (Exception e) {
                        toolResult = "Ошибка выполнения инструмента: " + e.getMessage();
                    }
                } else {
                    toolResult = "Инструмент '" + fnName + "' не может быть выполнен (нет обработчика).";
                }

                Map<String, Object> toolMsg = Json.newObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", toolResult);
                allMessages.add(toolMsg);
                anyExecuted = true;
            }

            if (!anyExecuted) {
                // Не удалось распознать tool_calls — возвращаем текущий текст.
                return content == null ? "" : String.valueOf(content);
            }
        }
        return "❌ Превышено число итераций function calling (" + MAX_TOOL_ITERATIONS + ").";
    }

    /** Строит список сообщений: системный промпт + история. */
    private List<Map<String, Object>> buildMessages(List<Map<String, Object>> messages, String systemPrompt) {
        List<Map<String, Object>> allMessages = new ArrayList<Map<String, Object>>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, Object> sys = Json.newObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            allMessages.add(sys);
        }
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                Object content = msg.get("content");
                if (content == null) {
                    content = msg.get("text");
                }
                String role = Json.str(msg, "role", "user");
                Map<String, Object> m = Json.newObject();
                m.put("role", role);
                m.put("content", content == null ? "" : String.valueOf(content));
                allMessages.add(m);
            }
        }
        return allMessages;
    }

    /** Извлекает первое сообщение из choices[0].message. */
    private Map<String, Object> firstMessage(Map<String, Object> respJson) {
        Object choices = respJson.get("choices");
        if (choices instanceof List) {
            List<?> list = (List<?>) choices;
            if (!list.isEmpty()) {
                Map<String, Object> firstMap = Json.asMap(list.get(0));
                if (firstMap != null) {
                    return Json.obj(firstMap, "message");
                }
            }
        }
        return null;
    }

    /** Отправляет POST-запрос к API. Возвращает распарсенный JSON или null при ошибке/сетевом сбое. */
    private Map<String, Object> postRequest(Map<String, Object> data) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            // Устраняем ошибку PKIX path building failed: доверяем всем сертификатам
            // (необходимо для встроенной JRE и корпоративных прокси/MITM).
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(createTrustAllSslContext().getSocketFactory());
                https.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            byte[] body = Json.stringify(data).getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String respBody = readStream(conn.getInputStream());
                return Json.parseObject(respBody);
            } else {
                String errBody = readStream(conn.getErrorStream());
                return errorResponse("Ошибка HTTP " + code + ": " + errBody);
            }
        } catch (java.io.IOException e) {
            return errorResponse("Ошибка сети API: " + e.getMessage());
        } catch (Exception e) {
            return errorResponse("Ошибка API: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Возвращает JSON-объект, который firstMessage() распознает как ошибку. */
    private static Map<String, Object> errorResponse(String message) {
        Map<String, Object> m = Json.newObject();
        List<Object> choices = new ArrayList<Object>();
        Map<String, Object> choice = Json.newObject();
        Map<String, Object> msg = Json.newObject();
        msg.put("content", "❌ " + message);
        choice.put("message", msg);
        choices.add(choice);
        m.put("choices", choices);
        return m;
    }

    private String readStream(InputStream in) throws java.io.IOException {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /**
     * Создаёт SSLContext, который доверяет всем сертификатам.
     * Используется для обхода ошибки "PKIX path building failed",
     * возникающей при работе через встроенную JRE или корпоративный прокси.
     */
    private static SSLContext createTrustAllSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            return sc;
        } catch (Exception e) {
            // Если не удалось создать контекст — используем стандартный
            try {
                return SSLContext.getDefault();
            } catch (Exception ex) {
                throw new RuntimeException("Не удалось инициализировать SSL", ex);
            }
        }
    }
}
