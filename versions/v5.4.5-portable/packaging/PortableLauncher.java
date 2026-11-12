import corpagent.util.Json;
import java.nio.file.*;
import java.nio.channels.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Uses only the bundled runtime; data stays beside the application. */
public class PortableLauncher {
    static Map<String,Object> instance(Path root) {
        try {
            Path state=root.resolve("data/app-instance.json");
            if(Files.size(state)>4096) return null;
            Map<String,Object> info=Json.parseObject(Files.readString(state));
            URI uri=URI.create((String)info.get("url"));
            if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) ||
                uri.getPort()<1 || !"/".equals(uri.getPath()) || uri.getFragment()!=null ||
                uri.getUserInfo()!=null || uri.getRawQuery()==null ||
                !uri.getRawQuery().matches("token=[A-Za-z0-9]{32}")) return null;
            long pid=((Number)info.get("pid")).longValue();
            if(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return null;
            HttpURLConnection conn=(HttpURLConnection)uri.resolve("/api/system/health").toURL().openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(1500); conn.setReadTimeout(1500);
            try(InputStream stream=conn.getInputStream()) {
                Map<String,Object> response=Json.parseObject(new String(stream.readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8));
                Map<?,?> health=(Map<?,?>)response.get("data");
                if(!Boolean.TRUE.equals(response.get("ok")) || !"5.4.5-fixes".equals(health.get("version")) ||
                    !Objects.equals(info.get("instance_id"),health.get("instance_id"))) return null;
            } finally {conn.disconnect();}
            return info;
        } catch(Exception ignored) { return null; }
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toRealPath();
        boolean noBrowser=Arrays.asList(args).contains("--no-browser");
        Files.createDirectories(root.resolve("data"));
        try(FileChannel channel=FileChannel.open(root.resolve("data/.launcher.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
            FileLock lock=null;
            for(int n=0;n<300 && lock==null;n++) { lock=channel.tryLock(); if(lock==null) Thread.sleep(100); }
            if(lock==null) throw new IOException("Another launch is in progress");
            try {
                Map<String,Object> info=instance(root);
                if(Arrays.asList(args).contains("--stop")) {
                    if(info==null) {System.out.println("CorporateAgent is already stopped.");return;}
                    URI uri=URI.create((String)info.get("url"));
                    HttpURLConnection conn=(HttpURLConnection)uri.resolve("/api/system/shutdown").toURL().openConnection(Proxy.NO_PROXY);
                    conn.setConnectTimeout(2000);conn.setReadTimeout(5000);conn.setRequestMethod("POST");
                    conn.setRequestProperty("Authorization","Bearer "+uri.getRawQuery().substring(6));
                    conn.setRequestProperty("Content-Type","application/json");conn.setDoOutput(true);
                    try {try(OutputStream out=conn.getOutputStream()){out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                        if(conn.getResponseCode()!=200) throw new IOException("Shutdown request failed");
                    } finally {conn.disconnect();}
                    long pid=((Number)info.get("pid")).longValue();
                    for(int n=0;n<100 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);n++)Thread.sleep(100);
                    if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))throw new IOException("Shutdown timed out");
                    System.out.println("CorporateAgent stopped.");return;
                }
                if(info==null) {
                    boolean windows=System.getProperty("os.name").startsWith("Windows");
                    Path java=root.resolve(windows?"jre/bin/java.exe":"jre/Contents/Home/bin/java");
                    Files.createDirectories(root.resolve("launch-logs"));
                    Path log=root.resolve("launch-logs/launch-"+System.currentTimeMillis()+".log");
                    List<String> command=new ArrayList<>(List.of(java.toString(),"-Dfile.encoding=UTF-8",
                        "-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory",
                        "-Dcorpagent.port=0","-Dcorpagent.install="+root,"-Dcorpagent.base="+root.resolve("data"),
                        "-cp",root.resolve("dist/CorporateAgent.jar")+File.pathSeparator+root.resolve("lib")+File.separator+"*","corpagent.App"));
                    if(!windows) command.add(0,"/usr/bin/nohup");
                    Process child=new ProcessBuilder(command)
                        .directory(root.toFile()).redirectOutput(log.toFile()).redirectErrorStream(true)
                        .redirectInput(ProcessBuilder.Redirect.from(new File(windows?"NUL":"/dev/null"))).start();
                    for(int n=0;n<300;n++) {
                        info=instance(root); if(info!=null) break;
                        if(!child.isAlive()) throw new IOException("Application exited; see "+log);
                        Thread.sleep(100);
                    }
                    if(info==null) {
                        child.destroy();
                        throw new IOException("Startup timed out; see "+log);
                    }
                }
                if(!noBrowser) new ProcessBuilder("/usr/bin/open",(String)info.get("url")).start().waitFor();
                System.out.println("CorporateAgent is ready. Data: "+root.resolve("data"));
            } finally {lock.release();}
        }
    }
}
