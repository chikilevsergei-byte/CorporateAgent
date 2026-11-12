package corpagent.util;

import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;

/** Cooperative cancellation; already committed tool actions are not rolled back. */
public final class TaskControl {
    private static final ConcurrentHashMap<String, Token> TASKS = new ConcurrentHashMap<String, Token>();
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<Token>();
    private static final java.util.concurrent.ThreadPoolExecutor NETWORK = new java.util.concurrent.ThreadPoolExecutor(
        0, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<Runnable>(),
        new java.util.concurrent.ThreadFactory() { public Thread newThread(Runnable job) { Thread t=new Thread(job,"cancellable-network");t.setDaemon(true);return t; } });
    private TaskControl() { }
    /** Stop waiting promptly even when Java 8's socket close is blocked in a read. The bounded
     * daemon pool owns lingering network calls until EOF/read timeout; cancelled results and
     * subsequent tool steps are discarded. Provider-side computation cannot be rolled back. */
    public static <T> T await(final java.util.concurrent.Callable<T> work) {
        final Token token=CURRENT.get();
        if(token==null) { try { return work.call(); } catch(RuntimeException e){throw e;} catch(Exception e){throw new RuntimeException(e);} }
        check();
        java.util.concurrent.Future<T> result;
        try { result=NETWORK.submit(new java.util.concurrent.Callable<T>() {
            public T call() throws Exception { CURRENT.set(token);try { check();return work.call(); } finally { CURRENT.remove();Thread.interrupted(); } }
        }); } catch(java.util.concurrent.RejectedExecutionException e) { throw new IllegalStateException("Предыдущие соединения ещё закрываются. Повторите запрос позже."); }
        boolean completed=false;
        try {
            while(true) {
                check();
                try { T value=result.get(100,java.util.concurrent.TimeUnit.MILLISECONDS);check();completed=true;return value; }
                catch(java.util.concurrent.TimeoutException waiting) { }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new CancellationException("Request cancelled"); }
                catch(java.util.concurrent.ExecutionException failed) { Throwable cause=failed.getCause();if(cause instanceof RuntimeException)throw (RuntimeException)cause;throw new RuntimeException(cause); }
            }
        } finally { if(!completed) result.cancel(true); }
    }
    public static final class Token {
        public final String id, chatId;
        private final Thread worker = Thread.currentThread();
        public volatile boolean cancelled, started;
        public volatile String partial = "";
        private volatile HttpURLConnection connection;
        private volatile Process process;
        private Token(String id, String chatId) { this.id = id; this.chatId = chatId; }
        public synchronized void cancel() {
            cancelled = true; worker.interrupt();
            final HttpURLConnection conn = connection;
            Process child = process;
            if (child != null) child.destroyForcibly();
            if (conn != null) {
                Thread closer = new Thread(new Runnable() { public void run() { conn.disconnect(); } }, "cancel-llm");
                closer.setDaemon(true); closer.start();
            }
        }
    }
    public static Token begin(String id, String chatId) {
        if (id == null || id.isEmpty() || id.length()>160) throw new IllegalArgumentException("Invalid request ID");
        Token token = new Token(id, chatId);
        if (TASKS.putIfAbsent(id, token) != null) throw new IllegalArgumentException("Request already running");
        CURRENT.set(token); return token;
    }
    public static void end(Token token) {
        TASKS.remove(token.id, token); CURRENT.remove(); Thread.interrupted();
    }
    public static void started() { Token token=CURRENT.get(); if(token!=null)token.started=true; }
    public static void progress(String text) { Token token=CURRENT.get();if(token!=null)token.partial=text; }
    public static Token find(String id) { return TASKS.get(id); }
    public static boolean busy(String chatId) {
        for (Token token : TASKS.values()) if (chatId != null && chatId.equals(token.chatId)) return true;
        return false;
    }
    public static boolean cancelled() {
        Token token = CURRENT.get(); return (token != null && token.cancelled) || Thread.currentThread().isInterrupted();
    }
    public static void check() { if (cancelled()) throw new CancellationException("Request cancelled"); }
    public static void attach(HttpURLConnection conn) {
        check(); Token token = CURRENT.get(); if (token != null) { token.connection = conn; if (token.cancelled) conn.disconnect(); } check();
    }
    public static void detach(HttpURLConnection conn) { Token token = CURRENT.get(); if (token != null && token.connection == conn) token.connection = null; }
    public static void attach(Process process) {
        check(); Token token = CURRENT.get(); if (token != null) { token.process = process; if (token.cancelled) process.destroyForcibly(); } check();
    }
}
