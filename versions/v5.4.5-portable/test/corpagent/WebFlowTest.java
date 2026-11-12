package corpagent.api;

import corpagent.*;
import corpagent.data.*;
import corpagent.db.*;
import corpagent.files.*;
import corpagent.util.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local integration test. Never uses production data or external LLM credentials. */
public class WebFlowTest {
    static int checks;
    static volatile String received;
    static boolean fail;
    static int toolStage = -1;
    static void check(boolean yes, String name) {
        checks++; if (!yes) throw new AssertionError(name);
    }
    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close(); return new String(out.toByteArray(), "UTF-8");
    }
    static String request(int port, String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method); c.setRequestProperty("Authorization", "Bearer test-token");
        c.setReadTimeout(15000);
        if (body != null) { c.setDoOutput(true); c.getOutputStream().write(body.getBytes("UTF-8")); c.getOutputStream().close(); }
        int status = c.getResponseCode();
        String text = read(status < 400 ? c.getInputStream() : c.getErrorStream()); c.disconnect(); return text;
    }
    static Map<String,Object> data(String json) { return Json.obj(Json.parseObject(json), "data"); }
    static Map<String,Object> done(String sse) {
        for (String frame : sse.split("\n\n")) if (frame.contains("event: done"))
            return Json.parseObject(frame.substring(frame.indexOf("data: ") + 6).trim());
        throw new AssertionError("No done event: " + sse);
    }
    public static void main(String[] args) throws Exception {
        HttpServer llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService fixturePool = java.util.concurrent.Executors.newCachedThreadPool();
        llm.setExecutor(fixturePool);
        llm.createContext("/chat/completions", x -> {
            try {
                received = read(x.getRequestBody());
                boolean stream = Json.bool(Json.parseObject(received), "stream", false);
                List<Object> sentMessages = Json.arr(Json.parseObject(received),"messages");
                String lastInput = Json.str(Json.asMap(sentMessages.get(sentMessages.size()-1)),"content","");
                if (lastInput.contains("CANCEL_FIXTURE")) {
                    if (stream) { x.getResponseHeaders().set("Content-Type","text/event-stream"); x.sendResponseHeaders(200,0);
                    x.getResponseBody().write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes("UTF-8"));
                    x.getResponseBody().flush(); } Thread.sleep(30000); return;
                }
                String response;
                if (stream) response = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n";
                else response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Title\\nBody\\n---SLIDE---\\nSecond\\nText\"}}]}";
                if(!stream&&received.contains("json_object")&&received.contains("slides")){
                    List<Object> slides=Json.newArray();for(String title:java.util.Arrays.asList("Title","Second","Third","Fourth","Fifth")){Map<String,Object> slide=Json.newObject();slide.put("title",title);slide.put("body","Body");slides.add(slide);}Map<String,Object> plan=Json.newObject();plan.put("slides",slides);Map<String,Object> message=Json.newObject();message.put("content",Json.stringify(plan));Map<String,Object> choice=Json.newObject();choice.put("message",message);Map<String,Object> wrapper=Json.newObject();wrapper.put("choices",java.util.Collections.singletonList(choice));response=Json.stringify(wrapper);
                }
                if (!stream && (received.contains("XLSX_FIXTURE") || received.contains("DATABASE_FIXTURE"))) {
                    String text = received.contains("XLSX_FIXTURE") ? "Name;Value\nItem;42" : "CREATE TABLE fixture(id INTEGER PRIMARY KEY,value TEXT);\nINSERT INTO fixture VALUES(1,'sample');";
                    Map<String,Object> msg = Json.newObject(); msg.put("role", "assistant"); msg.put("content", text);
                    Map<String,Object> ch = Json.newObject(); ch.put("message", msg);
                    List<Object> choices = Json.newArray(); choices.add(ch);
                    Map<String,Object> responseMap = Json.newObject(); responseMap.put("choices", choices); response = Json.stringify(responseMap);
                }
                if (toolStage >= 0 && !stream) {
                    if (toolStage < 2) {
                        String name = toolStage == 0 ? "db_query" : "db_execute";
                        String sql = toolStage == 0 ? "SELECT * FROM items" : "INSERT INTO items(id,value) VALUES(1,'written')";
                        Map<String,Object> arguments = Json.newObject(); arguments.put("sql", sql);
                        Map<String,Object> function = Json.newObject(); function.put("name", name); function.put("arguments", Json.stringify(arguments));
                        Map<String,Object> call = Json.newObject(); call.put("id", "call_"+toolStage); call.put("type", "function"); call.put("function", function);
                        List<Object> calls = Json.newArray(); calls.add(call);
                        Map<String,Object> message = Json.newObject(); message.put("role", "assistant"); message.put("content", ""); message.put("tool_calls", calls);
                        Map<String,Object> choice = Json.newObject(); choice.put("message", message);
                        List<Object> choices = Json.newArray(); choices.add(choice);
                        Map<String,Object> responseMap = Json.newObject(); responseMap.put("choices", choices); response = Json.stringify(responseMap);
                    }
                    toolStage++;
                }
                byte[] bytes = response.getBytes("UTF-8");
                x.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                x.sendResponseHeaders(fail ? 500 : 200, bytes.length); x.getResponseBody().write(bytes);
            } catch (Exception e) { if (!(e instanceof InterruptedException) && !(e instanceof java.io.IOException)) throw new RuntimeException(e); } finally { x.close(); }
        });
        llm.createContext("/models",x->{byte[] bytes="{\"data\":[{\"id\":\"gpt://test-folder/yandexgpt/latest\"},{\"id\":\"gpt://test-folder/deepseek/latest\"},{\"id\":\"gpt://test-folder/qwen/latest\"},{\"id\":\"emb://test-folder/embedding/latest\"}]}".getBytes("UTF-8");x.sendResponseHeaders(200,bytes.length);try(var stream=x.getResponseBody()){stream.write(bytes);}});
        llm.start();
        Path base = Files.createTempDirectory("ca-web-flow");
        Map<String,Object> raw = Json.newObject(); raw.put("provider", "custom"); raw.put("api_key", "local-test");
        raw.put("base_url", "http://127.0.0.1:" + llm.getAddress().getPort());
        Config config = Config.fromRaw(raw); DataStore store = new DataStore(base.toString());
        DatabaseManager db = new DatabaseManager(base.resolve("databases").toString(), base.resolve("backups").toString(), store);
        Agent agent = new Agent(config, store, db, new FileCreator(base.resolve("output").toString(), base.toString()));
        ApiServer server = new ApiServer(Auth.withToken("test-token"), new StaticHandler("web"));
        new ChatRoutes(agent, store).register(server); new FilesRoutes(base.toString(), store).register(server);
        new CatalogRoutes(store).register(server); new NotesRoutes(store).register(server);
        new ProjectsRoutes(store, db).register(server); new SkillsRoutes(store).register(server);
        new ToolsRoutes(store).register(server); new DatabasesRoutes(store, db).register(server);
        new SettingsRoutes(config, new Settings(base.toString()), new DeepSeekLlmTester()).register(server);
        SystemRoutes.register(server);
        int port = server.start(new int[]{0});
        if (args.length > 0 && "--serve".equals(args[0])) {
            System.out.println("TEST_URL=http://127.0.0.1:" + port + "/?token=test-token");
            try { System.in.read(); } finally { server.stop(); llm.stop(0); fixturePool.shutdownNow(); } return;
        }
        try {
            Map<String,Object> first = done(request(port, "POST", "/api/chat/stream", "{\"chat_id\":\"flow\",\"message\":\"hello\",\"skills\":[],\"tools\":[],\"databases\":[]}"));
            Map<String,Object> chat = Json.obj(first, "chat");
            List<Object> messages = Json.arr(chat, "messages");
            check(messages.size() == 2, "persisted both messages");
            String userId = Json.str(Json.asMap(messages.get(0)), "id", "");
            check(userId.startsWith("m_"), "server message id");
            check(data(request(port, "PUT", "/api/chat/message", "{\"chat_id\":\"flow\",\"message_id\":\""+userId+"\",\"text\":\"edited\"}")) != null, "edit with server id");
            check(data(request(port, "GET", "/api/chat/context?chat_id=flow", null)) != null, "GET query context");
            DataStore restored = new DataStore(base.toString());
            check(Json.arr(restored.getChat("flow"), "messages").size() == 2, "history survives reopening");
            check(Json.arr(data(request(port, "GET", "/api/chats", null)), "chats").size() >= 1, "list saved chats");
            Map<String,Object> upload = data(request(port, "POST", "/api/files/upload?name=sample.txt", "ATTACHMENT_MARKER"));
            check(Json.str(Json.asMap(Json.arr(upload, "files").get(0)), "text", "").equals("ATTACHMENT_MARKER"), "binary upload body is extracted");
            Map<String,Object> report = done(request(port, "POST", "/api/chat/stream", "{\"chat_id\":\"flow\",\"message\":\"\u0421\u043e\u0437\u0434\u0430\u0439 \u043f\u0440\u0435\u0437\u0435\u043d\u0442\u0430\u0446\u0438\u044e\",\"file_content\":\"ATTACHMENT_MARKER\"}"));
            String reportPath = Json.str(report, "file_path", "");
            HttpURLConnection download = (HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/files/download?id="+URLEncoder.encode(new File(reportPath).getName(), "UTF-8")).openConnection();
            download.setRequestProperty("Authorization", "Bearer test-token");
            check(download.getResponseCode() == 200 && download.getHeaderField("Content-Disposition").contains("attachment"), "download response");
            check(download.getInputStream().read() == 'P', "download is ZIP/PPTX binary"); download.disconnect();
            check(new File(reportPath).isFile(), "PPTX created through stream endpoint");
            check(received.contains("ATTACHMENT_MARKER"), "attachment reaches LLM");
            try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(new FileInputStream(reportPath))) {
                check(ppt.getSlides().size() == 5, "generated PPTX opens with the requested five slides");
            }
            List<Object> before = Json.arr(store.getChat("flow"), "messages");
            String saved = Json.stringify(before);
            String answerId = Json.str(Json.asMap(before.get(1)), "id", ""); fail = true;
            request(port, "POST", "/api/chat/message", "{\"chat_id\":\"flow\",\"message_id\":\""+answerId+"\"}");
            check(saved.equals(Json.stringify(Json.arr(store.getChat("flow"), "messages"))), "failed regenerate preserves entire branch");
            fail = false;
            String dbPath = base.resolve("databases").resolve("write.db").toString();
            SqliteHelper.executeQuery(dbPath, "CREATE TABLE items(id INTEGER PRIMARY KEY,value TEXT)");
            Map<String,Object> dbInfo = Json.newObject(); dbInfo.put("path", dbPath); dbInfo.put("enabled", true); store.putDatabase("write", dbInfo);
            toolStage = 0;
            done(request(port, "POST", "/api/chat/stream", "{\"chat_id\":\"tools\",\"confirm_writes\":true,\"message\":\"hello\",\"databases\":[\"write\"]}"));
            check(toolStage == 3, "tool calling query, execute, final response");
            check(SqliteHelper.getTableRows(dbPath, "items", 10).size() == 1, "LLM tool actually wrote SQLite row");
            toolStage = -1;
            List<Object> toolMessages = Json.arr(store.getChat("tools"), "messages");
            String toolMessageId = Json.str(Json.asMap(toolMessages.get(1)), "id", "");
            String saveBody = "{\"chat_id\":\"tools\",\"message_id\":\"" + toolMessageId + "\"}";
            request(port, "POST", "/api/chat/save-db", saveBody); request(port, "POST", "/api/chat/save-db", saveBody);
            check(SqliteHelper.getTableRows(dbPath, "agent_notes", 10).size() == 1, "save to database is persistent and idempotent");
            Map<String,Object> action = Json.newObject(); action.put("name", "write"); action.put("action", "sql"); action.put("sql", "SELECT value FROM items");
            check(Json.arr(data(request(port,"POST","/api/databases/action",Json.stringify(action))),"rows").size()==1,"manual SQL endpoint");
            action.put("action", "backup");
            check(new File(Json.str(data(request(port,"POST","/api/databases/action",Json.stringify(action))),"path", "")).isFile(),"backup exists");
            action.put("action", "export");
            check(Json.str(data(request(port,"POST","/api/databases/action",Json.stringify(action))),"text", "").contains("CREATE TABLE"),"SQL export");
            int imported = DatabasesRoutes.importCsv(dbPath, "items", "value,id\r\n\"multi\nline,quoted\",2\r\n", "insert");
            check(imported==1,"CSV multiline and reordered header");
            List<List<Object>> csvRows = SqliteHelper.getTableRows(dbPath,"items",10);
            check(csvRows.get(1).get(1).equals("multi\nline,quoted"),"CSV data preserved");
            DatabasesRoutes.importCsv(dbPath,"items","id,value\n2,updated\n","update");
            check(SqliteHelper.getTableRows(dbPath,"items",10).get(1).get(1).equals("updated"),"CSV update by first key");
            try { DatabasesRoutes.importCsv(dbPath,"items","id,value\n3,ok\n4,too,many\n","insert"); throw new AssertionError("bad CSV accepted"); }
            catch (IllegalArgumentException expected) { }
            check(SqliteHelper.getTableRows(dbPath,"items",10).size()==2,"CSV failure rolls back entire import");
            String note1 = Json.str(data(request(port,"POST","/api/notes","{\"title\":\"one\",\"text\":\"a\"}")),"id", "");
            String note2 = Json.str(data(request(port,"POST","/api/notes","{\"title\":\"two\",\"text\":\"b\"}")),"id", "");
            check(!note1.equals(note2),"rapid notes have distinct IDs");
            check(!Auth.withToken("CaseToken").isValid("Bearer casetoken"),"token is case sensitive");
            Map<String,Object> settingsData = data(request(port,"GET","/api/settings",null));
            check(!settingsData.containsKey("api_key"),"settings never return secret");
            check(config.getApiKey().equals("local-test"),"configured key intact");
            request(port,"PUT","/api/settings","{\"api_key\":\"\",\"_model_password\":\"1234\"}");
            check(config.getApiKey().equals("local-test"),"empty password field preserves saved key");
            check(!db.createDatabase("write", "CREATE TABLE x(id INTEGER)", "").success,"existing database is not overwritten");
            check(SqliteHelper.getTableRows(dbPath,"items",10).size()==2,"existing data preserved");
            Map<String,Object> excel = done(request(port,"POST","/api/chat/stream","{\"message\":\"Создай Excel таблицу\",\"file_content\":\"XLSX_FIXTURE\"}"));
            check(!Json.str(excel,"file_path", "").isEmpty(), "Excel result: " + Json.stringify(excel));
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new FileInputStream(Json.str(excel,"file_path", "")))) {
                check(workbook.getNumberOfSheets()==1,"XLSX opens after creation through chat");
                check(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue().equals("42"),"XLSX contains generated data");
            }
            int dbCount = store.getDatabases().size();
            Map<String,Object> created = done(request(port,"POST","/api/chat/stream","{\"message\":\"Создай базу данных из загруженных файлов\",\"file_content\":\"DATABASE_FIXTURE\"}"));
            check(!Json.str(created,"text", "").startsWith("\u274c"),"database from files completes");
            check(store.getDatabases().size()==dbCount+1,"database from files registered");
            Map<String,Object> draft = data(request(port,"POST","/api/chats","{\"chat_id\":\"manage\",\"title\":\"original\"}"));
            check(Json.str(draft,"id","").equals("manage"),"persist draft before first message");
            request(port,"PUT","/api/chats","{\"chat_id\":\"manage\",\"title\":\"renamed\",\"pinned\":true}");
            check(Json.str(store.getChat("manage"),"title","").equals("renamed") && Json.bool(store.getChat("manage"),"pinned",false),"rename and pin persisted");
            request(port,"PUT","/api/chats","{\"chat_id\":\"manage\",\"title\":\"should-not-stick\",\"project_id\":\"missing\"}");
            check(Json.str(store.getChat("manage"),"title","").equals("renamed"),"invalid move leaves metadata untouched");
            Map<String,Object> project = Json.newObject();project.put("id","target");project.put("name","Target");project.put("db","write");store.getProjects().put("target",project);
            request(port,"PUT","/api/chats","{\"chat_id\":\"manage\",\"project_id\":\"target\"}");
            check(Json.str(store.getChat("manage"),"project","").equals("target") && Json.str(store.getChat("manage"),"project_db","").equals("write"),"move applies project context");
            request(port,"PUT","/api/chats","{\"chat_id\":\"manage\",\"project_id\":\"\"}");
            check(!store.getChat("manage").containsKey("project") && !Json.arr(store.getChat("manage"),"selected_databases").contains("write"),"detach removes old working database");
            Map<String,Object> attached = data(request(port,"POST","/api/files/upload?chat_id=manage&name=scope.txt","SCOPED_ATTACHMENT"));
            String fileId = Json.str(Json.asMap(Json.arr(attached,"files").get(0)),"id","");
            check(!fileId.isEmpty() && Json.arr(store.getChat("manage"),"attachments").size()==1,"upload belongs to chat");
            Map<String,Object> another = data(request(port,"POST","/api/chats","{\"chat_id\":\"other\",\"title\":\"other\"}"));
            check(Json.arr(another,"attachments").isEmpty(),"another chat has no shared files");
            String foreign = request(port,"POST","/api/chat/stream","{\"chat_id\":\"other\",\"message\":\"hello\",\"attachment_ids\":[\""+fileId+"\"]}");
            check(!Json.bool(Json.parseObject(foreign),"ok",true),"foreign attachment rejected");
            done(request(port,"POST","/api/chat/stream","{\"chat_id\":\"manage\",\"message\":\"hello\",\"attachment_ids\":[\""+fileId+"\"]}"));
            check(received.contains("SCOPED_ATTACHMENT"),"owned attachment resolved on server");
            check(Json.arr(new DataStore(base.toString()).getChat("manage"),"attachments").size()==1,"attachments survive reopening");
            request(port,"DELETE","/api/chat/attachment","{\"chat_id\":\"other\",\"attachment_id\":\""+fileId+"\"}");
            check(Json.arr(store.getChat("manage"),"attachments").size()==1,"cross-chat detach rejected");
            request(port,"DELETE","/api/chat/attachment","{\"chat_id\":\"manage\",\"attachment_id\":\""+fileId+"\"}");
            check(Json.arr(store.getChat("manage"),"attachments").isEmpty(),"detach persists");
            check(base.resolve("uploads").resolve("scope.txt").toFile().isFile(),"detaching preserves original uploaded file");
            java.util.concurrent.CompletableFuture<String> slow = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return request(port,"POST","/api/chat/stream","{\"request_id\":\"cancel-test\",\"chat_id\":\"cancel-chat\",\"message\":\"CANCEL_FIXTURE\"}"); }
                catch(Exception e){throw new RuntimeException(e);}
            });
            long deadline=System.currentTimeMillis()+5000;
            while((!TaskControl.busy("cancel-chat") || !received.contains("CANCEL_FIXTURE")) && System.currentTimeMillis()<deadline) Thread.sleep(10);
            String denied = request(port,"DELETE","/api/chats","{\"chat_id\":\"cancel-chat\"}");
            check(!Json.bool(Json.parseObject(denied),"ok",true),"running chat cannot be deleted");
            Map<String,Object> wrongCancel=data(request(port,"POST","/api/chat/cancel","{\"request_id\":\"cancel-test\",\"chat_id\":\"other\"}"));
            check(!Json.bool(wrongCancel,"active",true) && TaskControl.busy("cancel-chat"),"another chat cannot cancel this request");
            Map<String,Object> progress=null; deadline=System.currentTimeMillis()+1500;
            do { progress=data(request(port,"GET","/api/chat/status?request_id=cancel-test&chat_id=cancel-chat",null));if(!Json.str(progress,"partial","").isEmpty())break;Thread.sleep(10); } while(System.currentTimeMillis()<deadline);
            check(Json.bool(progress,"started",false) && Json.str(progress,"partial","").equals("partial"),"polling exposes incremental text before completion");
            Map<String,Object> cancel = data(request(port,"POST","/api/chat/cancel","{\"request_id\":\"cancel-test\",\"chat_id\":\"cancel-chat\"}"));
            check(Json.bool(cancel,"active",false),"cancel request acknowledged");
            Map<String,Object> cancelled = done(slow.get(5,java.util.concurrent.TimeUnit.SECONDS));
            check(Json.bool(cancelled,"cancelled",false),"server stops waiting for blocked LLM read");
            check(Json.bool(Json.asMap(Json.arr(Json.obj(cancelled,"chat"),"messages").get(1)),"cancelled",false),"partial answer marked cancelled");
            check(!TaskControl.busy("cancel-chat"),"cancelled worker released");
            done(request(port,"POST","/api/chat/stream","{\"chat_id\":\"cancel-chat\",\"message\":\"after cancel\",\"history\":[{\"role\":\"user\",\"content\":\"clean\"}]}"));
            deadline=System.currentTimeMillis()+1000;while(TaskControl.busy("cancel-chat") && System.currentTimeMillis()<deadline)Thread.sleep(10);
            check(!TaskControl.busy("cancel-chat"),"next request works after cancellation");
            request(port,"DELETE","/api/chats","{\"chat_id\":\"manage\"}");
            check(store.getChat("manage")==null && new DataStore(base.toString()).getChat("manage")==null,"delete persists");
            check(java.nio.file.Files.list(base.resolve("backups")).anyMatch(path -> path.getFileName().toString().startsWith("deleted-chat-")),"deleted chat archived for recovery");
            System.out.println("WebFlowTest OK (" + checks + " checks)");
        } finally { server.stop(); llm.stop(0); fixturePool.shutdownNow(); }
    }
}
