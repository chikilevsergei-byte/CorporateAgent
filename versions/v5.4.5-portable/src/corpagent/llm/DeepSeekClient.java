package corpagent.llm;

import corpagent.util.Config;
import corpagent.util.TaskControl;
import corpagent.util.Json;
import corpagent.util.TokenBudget;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * OpenAI-совместимый клиент LLM. Формат запроса/ответа — OpenAI-совместимый.
 * Адрес API берётся из {@link Config#getChatCompletionUrl()}, который задаётся в
 * настройках (провайдер + base_url) — см. R-P0.1. Так поддерживаются DeepSeek,
 * Яндекс ИИ студия и другие OpenAI-совместимые endpoints без правки кода.
 * Использует HttpURLConnection из JDK — без внешних зависимостей.
 *
 * <p>Историческое имя сохранено для совместимости; по роли класс — универсальный ChatClient.</p>
 */
public class DeepSeekClient {

    private static final int MAX_TOOL_ITERATIONS = 12;

    private final Config config;

    public DeepSeekClient(Config config) {
        this.config = config;
    }

    /** Current provider catalog. Credentials remain on the local backend. */
    public List<String> listModels() throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(config.getBaseUrl()+"/models").openConnection();
        try{
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(20000);conn.setReadTimeout(30000);
            conn.setRequestProperty("Authorization",config.getAuthorization());
            if(Config.PROVIDER_YANDEX.equals(config.getProvider()))conn.setRequestProperty("OpenAI-Project",config.getFolderId());
            int status=conn.getResponseCode();
            if(status!=200)throw new java.io.IOException("HTTP "+status+": "+maskSecrets(readStream(conn.getErrorStream())));
            Map<String,Object> response=Json.parseObject(readStream(conn.getInputStream()));
            List<Object> entries=Json.arr(response,"data");
            if(entries==null)throw new java.io.IOException("API did not return a model list");
            java.util.Set<String> models=new java.util.TreeSet<String>();
            for(Object entry:entries){String id=Json.str(Json.asMap(entry),"id","").trim();if(!id.isEmpty())models.add(id);}
            if(models.isEmpty())throw new java.io.IOException("API returned an empty model list");
            return new ArrayList<String>(models);
        }finally{conn.disconnect();}
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
     * Обработчик потокового ответа (SSE). Вызывается по мере поступления токенов,
     * чтобы отдавать ответ в браузер инкрементально, не дожидаясь полного текста.
     */
    public interface StreamHandler {
        /** Новый фрагмент ответа (delta.content). */
        void onToken(String delta);

        /** Стриминг завершён: полный накопленный текст ответа. */
        void onDone(String fullText);

        /** Ошибка сети/API (сообщение без эмодзи-префикса). */
        void onError(String message);
    }

    /**
     * Универсальный вызов LLM.
     *
     * @param messages      список сообщений (Map с role и content/text)
     * @param systemPrompt  системный промпт (может быть пустым)
     * @return текст ответа или сообщение об ошибке (начинается с "❌")
     */
    public String call(List<Map<String, Object>> messages, String systemPrompt) {
        return call(messages,systemPrompt,false);
    }
    public String callJson(List<Map<String,Object>> messages,String systemPrompt){return call(messages,systemPrompt,true);}
    private String call(List<Map<String,Object>> messages,String systemPrompt,boolean json){
        List<Map<String, Object>> allMessages = buildMessages(messages, systemPrompt);

        Map<String, Object> data = Json.newObject();
        data.put("model", config.getModel());
        data.put("messages", allMessages);
        data.put("temperature", config.getTemperature());
        data.put("max_tokens", config.getMaxTokens());
        if(json){data.put("response_format",java.util.Collections.singletonMap("type","json_object"));if(Config.PROVIDER_YANDEX.equals(config.getProvider())&&config.getModel().contains("/yandexgpt"))data.put("reasoning_effort","none");}

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
        if(content==null||String.valueOf(content).isBlank()){
            Map<String,Object> retryPrompt=Json.newObject();retryPrompt.put("role","user");retryPrompt.put("content","Предыдущий ответ оказался пустым. Верни непустой результат по исходному запросу, строго соблюдая указанный формат и фактические данные. Не добавляй новых условий.");allMessages.add(retryPrompt);
            Map<String,Object> retried=postRequest(data);recordUsage(retried);Map<String,Object> retriedMessage=firstMessage(retried);
            Object retriedContent=retriedMessage==null?null:retriedMessage.get("content");
            if(retriedContent!=null&&!String.valueOf(retriedContent).isBlank())return String.valueOf(retriedContent);
            String reason="";List<Object> choices=Json.arr(retried,"choices");if(choices!=null&&!choices.isEmpty())reason=Json.str(Json.asMap(choices.get(0)),"finish_reason","");
            return "length".equals(reason)?"❌ Модель исчерпала лимит ответа до выдачи результата. Увеличьте Max tokens во вкладке «Модель». Изменения не подтверждены.":"❌ Провайдер дважды вернул пустой ответ. Запрос не завершён.";
        }
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

        boolean retriedEmpty=false;
        StringBuilder executedEvidence=new StringBuilder();
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
                if(content==null||String.valueOf(content).isBlank()){
                    if(executedEvidence.length()>0)return "Фактические результаты вызовов инструментов (уже выполненные операции не повторяются):\n\n"+executedEvidence;
                    if(retriedEmpty)return "❌ Провайдер вернул пустой ответ без вызова инструмента. Повторите запрос.";
                    retriedEmpty=true;allMessages.remove(allMessages.size()-1);Map<String,Object> retry=Json.newObject();retry.put("role","user");retry.put("content","Предыдущий ответ был пустым. Выполни исходный запрос или явно объясни, чего не хватает. Используй доступные функции для действий. Не повторяй уже успешно выполненные записи; сначала проверь фактические данные.");allMessages.add(retry);continue;
                }
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
            TaskControl.check();
                            // невалидный JSON аргументов — передаём как есть
                        }
                    }
                }

                String toolResult;
                if (handler != null && !fnName.isEmpty()) {
                    try {
                        TaskControl.check();
                        toolResult = handler.execute(fnName, fnArgs);
                    } catch (Exception e) {
            TaskControl.check();
                        toolResult = "Ошибка выполнения инструмента: " + maskSecrets(e.getMessage());
                    }
                } else {
                    toolResult = "Инструмент '" + fnName + "' не может быть выполнен (нет обработчика).";
                }

                Map<String, Object> toolMsg = Json.newObject();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", toolResult);
                if(executedEvidence.length()>10000)executedEvidence.delete(0,executedEvidence.length()-6000);
                executedEvidence.append(fnName).append(":\n").append(toolResult).append("\n\n");
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

    /**
     * Потоковый вызов LLM (SSE, {@code stream=true}). Отправляет историю в модель и
     * дёргает {@code handler.onToken(...)} на каждый фрагмент {@code delta.content},
     * затем {@code handler.onDone(fullText)}. При сбое — {@code handler.onError(...)}
     * и возврат сообщения об ошибке (начинается с "❌").
     *
     * @param messages     список сообщений (Map с role и content/text)
     * @param systemPrompt системный промпт (может быть пустым)
     * @param handler      обработчик токенов (может быть null)
     * @return полный текст ответа или сообщение об ошибке
     */
    public String callStreaming(final List<Map<String,Object>> messages, final String systemPrompt, final StreamHandler handler) {
        return TaskControl.await(new java.util.concurrent.Callable<String>() { public String call() { return callStreamingNetwork(messages,systemPrompt,handler); } });
    }
    private String callStreamingNetwork(List<Map<String, Object>> messages, String systemPrompt,
                                StreamHandler handler) {
        List<Map<String, Object>> allMessages = buildMessages(messages, systemPrompt);

        Map<String, Object> data = Json.newObject();
        data.put("model", config.getModel());
        data.put("messages", allMessages);
        data.put("temperature", config.getTemperature());
        data.put("max_tokens", config.getMaxTokens());
        data.put("stream", Boolean.TRUE);
        data.put("stream_options",java.util.Collections.singletonMap("include_usage",Boolean.TRUE));

        HttpURLConnection conn = null;
        StringBuilder full = new StringBuilder();
        Map<String,Object> streamUsage=null;
        try {
            URL url = new URL(config.getChatCompletionUrl());
            conn = (HttpURLConnection) url.openConnection();
            TaskControl.attach(conn);

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Authorization", config.getAuthorization());
            if(Config.PROVIDER_YANDEX.equals(config.getProvider()))conn.setRequestProperty("OpenAI-Project",config.getFolderId());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setDoOutput(true);

            byte[] body = Json.stringify(data).getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String errBody = maskSecrets(readStream(conn.getErrorStream()));
                String msg = "❌ Ошибка HTTP " + code + ": " + errBody;
                if (handler != null) {
                    handler.onError("Ошибка HTTP " + code + ": " + errBody);
                }
                return msg;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                TaskControl.check();
                if(line.trim().startsWith("data:")){
                    try{Map<String,Object> frame=Json.parseObject(line.trim().substring(5).trim());if(frame!=null&&Json.asMap(frame.get("usage"))!=null)streamUsage=frame;}catch(Exception ignored){TaskControl.check();}
                }
                String delta = parseSseLine(line);
                if (delta == null) {
                    continue; // [DONE] / комментарий / служебный кадр
                }
                if (!delta.isEmpty()) {
                    full.append(delta);
                    if (handler != null) {
                        handler.onToken(delta);
                    }
                }
            }
            reader.close();
            recordUsage(streamUsage);
            if (handler != null) {
                handler.onDone(full.toString());
            }
            return full.toString();
        } catch (java.util.concurrent.CancellationException e) { throw e;
        } catch (java.io.IOException e) {
            TaskControl.check();
            if (handler != null) {
                handler.onError(maskSecrets(e.getMessage()));
            }
            return "❌ Ошибка сети API: " + maskSecrets(e.getMessage());
        } catch (Exception e) {
            TaskControl.check();
            if (handler != null) {
                handler.onError(maskSecrets(e.getMessage()));
            }
            return "❌ Ошибка API: " + maskSecrets(e.getMessage());
        } finally {
            if (conn != null) {
                TaskControl.detach(conn);
                conn.disconnect();
            }
        }
    }

    /**
     * Разбирает одну строку SSE-потока (OpenAI-совместимый формат).
     * Возвращает {@code delta.content} кадра ({@code data: {...}}), пустую строку —
     * если кадр без контента (например, только роль), {@code null} — если это не
     * контент-кадр ({@code [DONE]}, комментарий, поле {@code event:}, невалидный JSON).
     * Статический — тестируется без сети.
     */
    public static String parseSseLine(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")) {
            return null;
        }
        if (!trimmed.startsWith("data:")) {
            return null;
        }
        String payload = trimmed.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return null;
        }
        Map<String, Object> frame;
        try {
            frame = Json.parseObject(payload);
        } catch (Exception e) {
            TaskControl.check();
            return null; // невалидный JSON — пропускаем кадр
        }
        if (frame == null) {
            return null;
        }
        List<Object> choices = Json.arr(frame, "choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Map<String, Object> choice = Json.asMap(choices.get(0));
        if (choice == null) {
            return null;
        }
        Map<String, Object> delta = Json.obj(choice, "delta");
        if (delta == null) {
            // Некоторые провайдеры кладут текст в message.content (не потоково).
            Map<String, Object> message = Json.obj(choice, "message");
            if (message != null) {
                return Json.str(message, "content", "");
            }
            return null;
        }
        return Json.str(delta, "content", "");
    }

    /** Настраивает соединение на доверие всем сертификатам (встроенная JRE/прокси/MITM). */
    private String maskSecrets(String text) {
        if (text == null) return "";
        String key = config.getApiKey(); return key == null || key.isEmpty() ? text : text.replace(key, "[REDACTED]");
    }

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
        if(respJson==null)return null;
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
    private Map<String,Object> postRequest(final Map<String,Object> data) {
        return TaskControl.await(new java.util.concurrent.Callable<Map<String,Object>>() { public Map<String,Object> call() { return postRequestNetwork(data); } });
    }
    private Map<String, Object> postRequestNetwork(Map<String, Object> data) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(config.getChatCompletionUrl());
            conn = (HttpURLConnection) url.openConnection();
            TaskControl.attach(conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Authorization", config.getAuthorization());
            if(Config.PROVIDER_YANDEX.equals(config.getProvider()))conn.setRequestProperty("OpenAI-Project",config.getFolderId());
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
                TaskControl.check();
                return Json.parseObject(respBody);
            } else {
                String errBody = maskSecrets(readStream(conn.getErrorStream()));
                return errorResponse("Ошибка HTTP " + code + ": " + errBody);
            }
        } catch (java.util.concurrent.CancellationException e) { throw e;
        } catch (java.io.IOException e) {
            TaskControl.check();
            return errorResponse("Ошибка сети API: " + maskSecrets(e.getMessage()));
        } catch (Exception e) {
            TaskControl.check();
            return errorResponse("Ошибка API: " + maskSecrets(e.getMessage()));
        } finally {
            if (conn != null) {
                TaskControl.detach(conn);
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

}
