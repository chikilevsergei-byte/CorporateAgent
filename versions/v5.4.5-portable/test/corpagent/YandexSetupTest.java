package corpagent.api;

import corpagent.util.*;
import corpagent.llm.DeepSeekClient;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real loopback HTTP checks; never calls Yandex or uses user credentials. */
public class YandexSetupTest {
    static int checks,requests; static String authorization,project;static Map<String,Object> payload;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static Map<String,Object> request(int port,String method,String path,Map<String,Object> body)throws Exception{
        java.net.HttpURLConnection c=(java.net.HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer test-token");c.setRequestProperty("Content-Type","application/json");
        if(body!=null){c.setDoOutput(true);try(var out=c.getOutputStream()){out.write(Json.stringify(body).getBytes(StandardCharsets.UTF_8));}}
        try(var in=c.getResponseCode()<400?c.getInputStream():c.getErrorStream()){return Json.parseObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}finally{c.disconnect();}
    }
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory("ca-yandex-setup");Config config=new Config(base.resolve("config.json").toString());Settings settings=new Settings(base.toString());
        HttpServer fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fixture.createContext("/v1/chat/completions",x->{
            requests++;authorization=x.getRequestHeaders().getFirst("Authorization");project=x.getRequestHeaders().getFirst("OpenAI-Project");payload=Json.parseObject(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            boolean stream=Json.bool(payload,"stream",false);
            String text=stream?"data: {\"choices\":[{\"delta\":{\"content\":\"pong\"}}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n":"{\"choices\":[{\"message\":{\"content\":\"pong\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}
        });fixture.createContext("/v1/models",x->{requests++;authorization=x.getRequestHeaders().getFirst("Authorization");project=x.getRequestHeaders().getFirst("OpenAI-Project");byte[] bytes="{\"data\":[{\"id\":\"gpt://test-folder/yandexgpt/latest\"},{\"id\":\"gpt://test-folder/deepseek/latest\"},{\"id\":\"gpt://test-folder/qwen/latest\"}]}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);try(var stream=x.getResponseBody()){stream.write(bytes);}});fixture.start();
        ApiServer server=new ApiServer(Auth.withToken("test-token"),new StaticHandler("web"));new SettingsRoutes(config,settings,new DeepSeekLlmTester()).register(server);int port=server.start(new int[]{0});
        try{
            check(!Json.bool(request(port,"POST","/api/settings/model/unlock",Map.of("_model_password","bad")),"ok",false),"wrong password rejected");
            check(Json.bool(request(port,"POST","/api/settings/model/unlock",Map.of("_model_password","1234")),"ok",false),"correct password accepted");
            check(!Json.bool(request(port,"PUT","/api/settings",Map.of("max_tokens",17)),"ok",true),"direct API mutation protected");
            check(config.getMaxTokens()==4000,"unauthorized mutation leaves config unchanged");
            check(Json.bool(request(port,"PUT","/api/settings",Map.of("theme","dark")),"ok",false),"interface needs no model password");
            check(!Json.bool(request(port,"POST","/api/settings/test-llm",Map.of()),"ok",true)&&requests==0,"connection test protected without network call");
            Map<String,Object> model=new LinkedHashMap<>(Map.of("provider","yandex","api_key","synthetic-yandex-key","folder_id","test-folder","model","yandexgpt/latest","base_url","http://127.0.0.1:"+fixture.getAddress().getPort()+"/v1","_model_password","1234","auth_type","api_key","daily_limit_tokens",12345));
            config.setApiKey("synthetic-old-provider-key");Map<String,Object> noNewKey=new LinkedHashMap<>(model);noNewKey.remove("api_key");
            check(!Json.bool(request(port,"POST","/api/settings/test-llm",noNewKey),"ok",true)&&requests==0,"other provider key never sent to Yandex");
            check(Json.bool(request(port,"PUT","/api/settings",model),"ok",false),"Yandex settings saved");
            check("gpt://test-folder/yandexgpt/latest".equals(config.getModel()),"model URI assembled");
            check(new Config(base.resolve("config.json").toString()).getFolderId().equals("test-folder"),"folder persisted");
            check(!config.getRaw().containsKey("_model_password"),"password not stored in config");
            check(settings.getDailyLimitTokens()==12345&&TokenBudget.getDailyLimit()==12345,"daily limit applied immediately");
            int beforeModels=requests;check(!Json.bool(request(port,"POST","/api/settings/models",Map.of()),"ok",true)&&requests==beforeModels,"model list password protected");
            Map<String,Object> catalog=Json.asMap(request(port,"POST","/api/settings/models",Map.of("_model_password","1234")).get("data"));check(Json.arr(catalog,"models").size()==3&&Json.arr(catalog,"models").contains("gpt://test-folder/qwen/latest"),"full live catalog returned");
            check(authorization.equals("Api-Key synthetic-yandex-key")&&project.equals("test-folder"),"model list uses provider auth and folder");
            Map<String,Object> response=request(port,"POST","/api/settings/test-llm",Map.of("_model_password","1234"));
            check(Json.bool(Json.asMap(response.get("data")),"ok",false),"actual client connection test succeeds");
            check("Api-Key synthetic-yandex-key".equals(authorization),"API key auth header");
            check("test-folder".equals(project),"OpenAI-Project header");
            check("gpt://test-folder/yandexgpt/latest".equals(payload.get("model")),"wire model URI");
            check(Json.intVal(payload,"max_tokens",0)==32,"connection test has small token cap");
            check(!response.toString().contains("synthetic-yandex-key"),"response does not expose key");
            long beforeStream=TokenBudget.tokensToday();final StringBuilder streamed=new StringBuilder();new DeepSeekClient(config).callStreaming(List.of(Map.of("role","user","content","hello")),"",new DeepSeekClient.StreamHandler(){public void onToken(String s){streamed.append(s);}public void onDone(String s){}public void onError(String s){throw new AssertionError(s);}});
            check(TokenBudget.tokensToday()==beforeStream+4,"stream usage accounted");check(Json.bool(Json.obj(payload,"stream_options"),"include_usage",false),"stream requests usage frame");
            check(streamed.toString().equals("pong")&&authorization.equals("Api-Key synthetic-yandex-key"),"streaming uses Yandex auth");
            model.put("auth_type","iam");model.put("model","gpt://test-folder/custom-model/latest");request(port,"POST","/api/settings/test-llm",model);
            check(authorization.equals("Bearer synthetic-yandex-key"),"IAM bearer header");check(payload.get("model").equals("gpt://test-folder/custom-model/latest"),"custom model URI sent");
            check(config.getAuthType().equals("api_key"),"test overrides not persisted");
            model.put("folder_id","different-folder");int before=requests;
            check(!Json.bool(request(port,"POST","/api/settings/test-llm",model),"ok",true)&&requests==before,"mismatched folder rejected before network");
            Map<String,Object> backup=Json.asMap(request(port,"POST","/api/settings/backups",Map.of("action","create")).get("data"));
            check(!Files.readString(base.resolve("backups").resolve(Json.str(backup,"name",""))).contains("daily_limit_tokens"),"interface backup excludes model limit");
            check(!Json.bool(request(port,"PUT","/api/settings",Map.of("daily_limit_tokens",0)),"ok",true),"daily limit password protected");
            System.out.println("YandexSetupTest OK ("+checks+" checks; local fixture only)");
        }finally{server.stop();fixture.stop(0);}
    }
}
