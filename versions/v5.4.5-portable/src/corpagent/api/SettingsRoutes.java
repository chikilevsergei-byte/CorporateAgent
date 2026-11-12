package corpagent.api;

import corpagent.util.Config;
import corpagent.util.Json;
import corpagent.util.Settings;

import java.util.List;
import java.util.Map;

/**
 * Маршруты раздела «Настройки» (Этап 6.1 миграции v3 → v4).
 *
 * <p>Перенос окна {@code SettingsDialog} (вкладка «Модель» + «Интерфейс») в HTTP-API:</p>
 * <ul>
 *   <li>{@code GET /api/settings} — текущие настройки модели ({@code config.json}) и
 *       интерфейса ({@code settings.json}) в одном ответе;</li>
 *   <li>{@code PUT /api/settings} — сохранение (тело: поля модели и интерфейса; присутствуют
 *       только изменяемые поля);</li>
 *   <li>{@code POST /api/settings/test-llm} — проверка подключения к LLM (пробный запрос).</li>
 * </ul>
 *
 * <p>Файл {@code config.json} — источник API-ключа/провайдера/модели; {@code settings.json} —
 * тема/лимиты/флаги. Классы {@link Config}/{@link Settings} инкапсулируют запись, здесь — только
 * HTTP-контракт и валидация. Логика провайдеров (маппинг подпись↔код) совпадает с
 * {@code SettingsPanelModel}.</p>
 */
public final class SettingsRoutes {

    private final Config config;
    private final Settings settings;
    private final LlmTester llmTester;

    /**
     * Функциональный интерфейс пробного запроса к LLM. Абстрагирует реальную сеть,
     * чтобы контракт {@code test-llm} тестировался без обращения к провайдеру.
     */
    public interface LlmTester {
        /** @return результат проверки: ok = успех, detail = текст ответа/ошибки. */
        TestResult test(Config config);
    }

    /** Результат проверки подключения к LLM. */
    public static final class TestResult {
        public final boolean ok;
        public final String detail;

        public TestResult(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    public SettingsRoutes(Config config, Settings settings) {
        this(config, settings, null);
    }

    public SettingsRoutes(Config config, Settings settings, LlmTester llmTester) {
        this.config = config;
        this.settings = settings;
        this.llmTester = llmTester;
    }

    /** Регистрирует маршруты настроек. */
    public void register(ApiServer server) {
        server.route("/api/settings/models",(method,body,query)->{
            Map<String,Object> req=parse(body);
            if(!"POST".equals(method)||!modelPasswordValid(req))return ApiResponse.badRequest("Введите пароль раздела «Модель»");
            Config effective=effectiveConfig(req);
            if(effective.getApiKey().isBlank())return ApiResponse.badRequest("Введите API-ключ или IAM-токен");
            if(Config.PROVIDER_YANDEX.equals(effective.getProvider())&&effective.getFolderId().isBlank())return ApiResponse.badRequest("Укажите Folder ID");
            try{return ApiResponse.ok(java.util.Collections.singletonMap("models",new corpagent.llm.DeepSeekClient(effective).listModels()));}
            catch(Exception e){return ApiResponse.badRequest("Не удалось загрузить модели: "+e.getMessage());}
        });
        server.route("/api/settings/model/unlock",(method,body,query)->{
            if(!"POST".equals(method))return ApiResponse.badRequest("Используйте POST");
            return modelPasswordValid(parse(body))?ApiResponse.ok(java.util.Collections.singletonMap("unlocked",true)):ApiResponse.badRequest("Неверный пароль раздела «Модель»");
        });
        server.route("/api/settings/backups",new ApiServer.ApiRoute(){public ApiResponse handle(String method,String body,Map<String,List<String>> query)throws Exception{return backups(method,body);}});
        server.route("/api/settings", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                Map<String,Object> req=parse(body);
                if(!"GET".equals(method)&&hasProtectedFields(req)&&!modelPasswordValid(req))return ApiResponse.badRequest("Для изменения модели введите пароль раздела «Модель»");
                return dispatch(method, body, query);
            }
        });
        server.route("/api/settings/test-llm", new ApiServer.ApiRoute() {
            @Override
            public ApiResponse handle(String method, String body, Map<String, List<String>> query) {
                if(!"POST".equals(method))return ApiResponse.badRequest("Используйте POST");
                if(!modelPasswordValid(parse(body)))return ApiResponse.badRequest("Введите пароль раздела «Модель»");
                return handleTestLlm(body);
            }
        });
    }

    ApiResponse dispatch(String method, String body, Map<String, List<String>> query) {
        String m = method == null ? "" : method.toUpperCase();
        if ("GET".equals(m)) {
            return handleGet();
        }
        if ("PUT".equals(m) || "POST".equals(m)) {
            return handlePut(body);
        }
        return ApiResponse.badRequest("Неподдерживаемый метод: " + method);
    }

    /** GET /api/settings — текущие значения модели и интерфейса. */
    ApiResponse handleGet() {
        return ApiResponse.ok(settingsDto());
    }

    /** PUT /api/settings — сохранение пришедших полей в config.json/settings.json. */
    synchronized ApiResponse handlePut(String body) {
        Map<String, Object> req = parse(body);
        if (req == null) {
            return ApiResponse.badRequest("Ожидался JSON-объект.");
        }
        if(req.containsKey("theme")&&!java.util.Arrays.asList("green","dark","light","blue","purple").contains(Json.str(req,"theme","")))return ApiResponse.badRequest("Неизвестная тема оформления");
        Config proposed=effectiveConfig(req);
        String validation=validateYandex(proposed);
        if(validation!=null&&hasLlmOverrides(req))return ApiResponse.badRequest(validation);

        // ---- Модель (config.json) ----
        if(req.containsKey("folder_id"))config.setFolderId(Json.str(req,"folder_id",""));
        if(req.containsKey("auth_type"))config.setAuthType(Json.str(req,"auth_type","api_key"));
        if (req.containsKey("provider")) {
            if(!config.getProvider().equals(providerCodeFromRequest(req)))config.setApiKey("");
            config.setProvider(providerCodeFromRequest(req));
        }
        if (req.containsKey("base_url")) {
            config.setBaseUrl(Json.str(req, "base_url", ""));
        }
        if (req.containsKey("model")) {
            config.setModel(Json.str(req, "model", config.getModel()));
        }
        if (req.containsKey("api_key") && !Json.str(req, "api_key", "").isEmpty()) {
            config.setApiKey(Json.str(req, "api_key", ""));
        }
        if (req.containsKey("temperature")) {
            config.setTemperature(Json.doubleVal(req, "temperature", config.getTemperature()));
        }
        if (req.containsKey("max_tokens")) {
            int mt = Json.intVal(req, "max_tokens", config.getMaxTokens());
            if (mt <= 0) {
                return ApiResponse.badRequest("Поле 'max_tokens' должно быть положительным.");
            }
            config.setMaxTokens(mt);
        }
        config.save();

        // ---- Интерфейс (settings.json) ----
        if (req.containsKey("theme")) {
            settings.setTheme(Json.str(req, "theme", settings.getTheme()));
        }
        if (req.containsKey("auto_update_db")) {
            settings.setAutoUpdateDb(Json.bool(req, "auto_update_db", settings.isAutoUpdateDb()));
        }
        if (req.containsKey("show_usage_in_chat")) {
            settings.setShowUsageInChat(
                    Json.bool(req, "show_usage_in_chat", settings.isShowUsageInChat()));
        }
        if (req.containsKey("confirm_db_write")) {
            settings.setConfirmDbWrite(
                    Json.bool(req, "confirm_db_write", settings.isConfirmDbWrite()));
        }
        if (req.containsKey("daily_limit_tokens")) {
            settings.setDailyLimitTokens(
                    (long) Json.doubleVal(req, "daily_limit_tokens", settings.getDailyLimitTokens()));
        }
        settings.save();
        corpagent.util.TokenBudget.setDailyLimit(settings.getDailyLimitTokens());

        return ApiResponse.ok(settingsDto());
    }

    private synchronized ApiResponse backups(String method,String body)throws Exception{
        java.nio.file.Path dir=settings.getDirectory().resolve("backups");
        if(java.nio.file.Files.isSymbolicLink(dir))return ApiResponse.badRequest("Недопустимый каталог копий");
        java.nio.file.Files.createDirectories(dir);
        if("GET".equals(method)){
            java.util.List<Object> list=Json.newArray();
            try(java.nio.file.DirectoryStream<java.nio.file.Path> files=java.nio.file.Files.newDirectoryStream(dir,"settings-*.json")){
                for(java.nio.file.Path file:files)if(java.nio.file.Files.isRegularFile(file)&&!java.nio.file.Files.isSymbolicLink(file)){
                    Map<String,Object> item=Json.newObject();item.put("name",file.getFileName().toString());item.put("created",java.nio.file.Files.getLastModifiedTime(file).toMillis());list.add(item);
                }
            }
            java.util.Collections.sort(list,(a,b)->Long.compare(((Number)Json.asMap(b).get("created")).longValue(),((Number)Json.asMap(a).get("created")).longValue()));
            return ApiResponse.ok(java.util.Collections.singletonMap("backups",list));
        }
        if(!"POST".equals(method))return ApiResponse.badRequest("POST required");
        Map<String,Object> request=Json.parseObject(body);if(request==null)return ApiResponse.badRequest("Ожидался JSON-объект");
        if("create".equals(Json.str(request,"action",""))){String name=saveSettingsBackup(dir);return ApiResponse.ok(java.util.Collections.singletonMap("name",name));}
        if(!"restore".equals(Json.str(request,"action","")))return ApiResponse.badRequest("Неизвестное действие");
        String name=Json.str(request,"name","");if(!name.matches("settings-[a-f0-9-]{36}\\.json"))return ApiResponse.badRequest("Некорректное имя копии");
        java.nio.file.Path file=dir.resolve(name);
        if(java.nio.file.Files.isSymbolicLink(file)||!java.nio.file.Files.isRegularFile(file)||java.nio.file.Files.size(file)>65536)return ApiResponse.badRequest("Копия не найдена или повреждена");
        Map<String,Object> restored=Json.parseObject(new String(java.nio.file.Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8));
        if(restored==null||!java.util.Arrays.asList("green","dark","light","blue","purple").contains(Json.str(restored,"theme","")))return ApiResponse.badRequest("Некорректная копия настроек");
        Map<String,Object> safe=Json.newObject();for(String key:java.util.Arrays.asList("theme","auto_update_db","show_usage_in_chat","confirm_db_write","daily_limit_tokens"))if(restored.containsKey(key))safe.put(key,restored.get(key));
        safe.put("daily_limit_tokens",settings.getDailyLimitTokens());
        saveSettingsBackup(dir);
        Map<String,Object> previous=new java.util.LinkedHashMap<String,Object>(settings.getRaw());
        try{settings.getRaw().clear();settings.getRaw().putAll(safe);settings.save();corpagent.util.TokenBudget.setDailyLimit(settings.getDailyLimitTokens());}
        catch(RuntimeException failure){settings.getRaw().clear();settings.getRaw().putAll(previous);throw failure;}
        return handleGet();
    }
    private String saveSettingsBackup(java.nio.file.Path dir)throws Exception{
        String name="settings-"+java.util.UUID.randomUUID()+".json";
        Map<String,Object> data=new java.util.LinkedHashMap<String,Object>(settings.getRaw());data.put("theme",settings.getTheme());
        data.remove("daily_limit_tokens");
        java.nio.file.Files.write(dir.resolve(name),Json.stringify(data,true).getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE_NEW);
        return name;
    }

    /** POST /api/settings/test-llm — проверка подключения к LLM (пробный запрос). */
    ApiResponse handleTestLlm(String body) {
        // Тело (опционально): временные override-поля провайдера/ключа/модели/base_url,
        // чтобы протестировать настройки до сохранения.
        Map<String, Object> req = parse(body);
        Config effective = config;
        if (req != null && !req.isEmpty() && hasLlmOverrides(req)) {
            effective = effectiveConfig(req);
        }
        String validation=validateYandex(effective);
        if(validation!=null)return ApiResponse.badRequest(validation);
        if(effective.getApiKey().trim().isEmpty())return ApiResponse.badRequest("Введите API-ключ или IAM-токен");
        if (llmTester == null) {
            return ApiResponse.serverError("Тест LLM недоступен (тестер не сконфигурирован).");
        }
        TestResult r = llmTester.test(effective);
        Map<String, Object> data = Json.newObject();
        data.put("ok", r.ok);
        data.put("detail", r.detail == null ? "" : r.detail);
        data.put("provider", effective.getProvider());
        data.put("model", effective.getModel());
        data.put("base_url", effective.getBaseUrl());
        return r.ok ? ApiResponse.ok(data) : ApiResponse.ok(data, 200);
    }

    // ==================== DTO ====================

    private Map<String, Object> settingsDto() {
        Map<String, Object> dto = Json.newObject();
        // Модель
        dto.put("api_key_configured", !config.getApiKey().isEmpty());
        dto.put("provider", config.getProvider());
        dto.put("provider_label", providerLabel(config.getProvider()));
        dto.put("base_url", config.getBaseUrl());
        dto.put("model", config.getModel());
        dto.put("folder_id",config.getFolderId());
        dto.put("auth_type",config.getAuthType());
        dto.put("temperature", config.getTemperature());
        dto.put("max_tokens", config.getMaxTokens());
        dto.put("models", modelsFor(config.getProvider()));
        dto.put("providers", providersCatalog());
        // Интерфейс
        dto.put("theme", settings.getTheme());
        dto.put("auto_update_db", settings.isAutoUpdateDb());
        dto.put("show_usage_in_chat", settings.isShowUsageInChat());
        dto.put("confirm_db_write", settings.isConfirmDbWrite());
        dto.put("daily_limit_tokens", settings.getDailyLimitTokens());
        dto.put("tokens_today", corpagent.util.TokenBudget.tokensToday());
        return dto;
    }

    private static List<Object> providersCatalog() {
        List<Object> out = Json.newArray();
        out.add(providerEntry(Config.PROVIDER_DEEPSEEK));
        out.add(providerEntry(Config.PROVIDER_YANDEX));
        out.add(providerEntry(Config.PROVIDER_QWEN));
        out.add(providerEntry(Config.PROVIDER_CUSTOM));
        return out;
    }

    private static Map<String, Object> providerEntry(String code) {
        Map<String, Object> m = Json.newObject();
        m.put("code", code);
        m.put("label", providerLabel(code));
        m.put("base_url", Config.defaultBaseUrl(code));
        m.put("models", modelsFor(code));
        return m;
    }

    // ==================== ХЕЛПЕРЫ ПРОВАЙДЕРОВ ====================

    /** Преобразует подпись или код провайдера из запроса в код ({@code PROVIDER_*}). */
    static String providerCodeFromRequest(Map<String, Object> req) {
        String raw = Json.str(req, "provider_code", "");
        if (raw.isEmpty()) {
            raw = Json.str(req, "provider", "");
        }
        return providerCode(raw);
    }

    /** Подпись/код провайдера → код (совместимо с {@code SettingsPanelModel.providerCode}). */
    static String providerCode(Object labelOrCode) {
        String s = labelOrCode == null ? "" : String.valueOf(labelOrCode);
        if (s.contains("Яндекс") || Config.PROVIDER_YANDEX.equalsIgnoreCase(s)) {
            return Config.PROVIDER_YANDEX;
        }
        if (s.contains("Alibaba") || s.contains("Qwen") || Config.PROVIDER_QWEN.equalsIgnoreCase(s)) {
            return Config.PROVIDER_QWEN;
        }
        if (s.contains("Друг") || Config.PROVIDER_CUSTOM.equalsIgnoreCase(s)) {
            return Config.PROVIDER_CUSTOM;
        }
        return Config.PROVIDER_DEEPSEEK;
    }

    /** Код провайдера → подпись для UI (совместимо с {@code SettingsPanelModel.providerLabel}). */
    static String providerLabel(String code) {
        if (Config.PROVIDER_YANDEX.equals(code)) {
            return "Яндекс ИИ студия";
        }
        if (Config.PROVIDER_QWEN.equals(code)) {
            return "Alibaba Qwen";
        }
        if (Config.PROVIDER_CUSTOM.equals(code)) {
            return "Другой (OpenAI-совместимый)";
        }
        return "DeepSeek";
    }

    /** Список моделей провайдера (совместимо с {@code SettingsPanelModel.populateModelsFor}). */
    static List<Object> modelsFor(String provider) {
        List<Object> out = Json.newArray();
        if (Config.PROVIDER_YANDEX.equals(provider)) {
            out.add("yandexgpt/latest");
            out.add("yandexgpt-lite/latest");
        } else if (Config.PROVIDER_QWEN.equals(provider)) {
            out.add("qwen-max");
            out.add("qwen-plus");
            out.add("qwen-turbo");
        } else if (Config.PROVIDER_CUSTOM.equals(provider)) {
            out.add("gpt-4o");
            out.add("gpt-4o-mini");
            out.add("custom-model");
        } else {
            out.add("deepseek-chat");
            out.add("deepseek-reasoner");
        }
        return out;
    }

    private static boolean hasLlmOverrides(Map<String, Object> req) {
        return req.containsKey("api_key") || req.containsKey("provider")
                || req.containsKey("base_url") || req.containsKey("model")||req.containsKey("folder_id")||req.containsKey("auth_type");
    }

    /**
     * Собирает временный {@link Config} из raw-настроек с подстановкой override-полей.
     * Используется только для пробного запроса — на диск ничего не пишется.
     */
    private Config effectiveConfig(Map<String, Object> req) {
        // Config — обёртка над raw-данными; создаём независимую копию и строим из неё.
        Map<String, Object> raw = Json.newObject();
        raw.putAll(config.getRaw());
        for(String field:java.util.Arrays.asList("folder_id","auth_type","temperature","max_tokens"))if(req.containsKey(field))raw.put(field,req.get(field));
        if (req.containsKey("provider")) {
            if(!config.getProvider().equals(providerCodeFromRequest(req)))raw.put("api_key","");
            raw.put("provider", providerCodeFromRequest(req));
        }
        if (req.containsKey("api_key") && !Json.str(req, "api_key", "").isEmpty()) {
            raw.put("api_key", Json.str(req, "api_key", ""));
        }
        if (req.containsKey("base_url")) {
            raw.put("base_url", Json.str(req, "base_url", ""));
        }
        if (req.containsKey("model")) {
            raw.put("model", Json.str(req, "model", ""));
        }
        return Config.fromRaw(raw);
    }

    // ==================== ПАРСИНГ ====================
    private static boolean hasProtectedFields(Map<String,Object> req){return req!=null&&(hasLlmOverrides(req)||req.containsKey("temperature")||req.containsKey("max_tokens")||req.containsKey("daily_limit_tokens"));}
    private static boolean modelPasswordValid(Map<String,Object> req){
        if(req==null)return false;
        try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Json.str(req,"_model_password","").getBytes(java.nio.charset.StandardCharsets.UTF_8));return java.security.MessageDigest.isEqual(digest,java.util.HexFormat.of().parseHex("03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"));}catch(Exception e){return false;}
    }
    private static String validateYandex(Config c){
        if(!Config.PROVIDER_YANDEX.equals(c.getProvider()))return null;
        if(!c.getFolderId().matches("[A-Za-z0-9_-]{3,128}"))return "Укажите идентификатор каталога Яндекс (Folder ID)";
        if(!java.util.Arrays.asList("api_key","iam").contains(c.getAuthType()))return "Выберите API-ключ или IAM-токен";
        if(!c.getModel().startsWith("gpt://"+c.getFolderId()+"/")||!c.getModel().matches("gpt://[A-Za-z0-9_-]+/[A-Za-z0-9_./-]+"))return "Проверьте URI модели: gpt://<каталог>/<модель>";
        return null;
    }

    private static Map<String, Object> parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return Json.parseObject(body);
        } catch (Exception e) {
            return null;
        }
    }

}
