package corpagent;

import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.files.FileCreator;
import corpagent.util.Config;

import java.io.File;
import java.nio.file.Files;

/**
 * Smoke-тест Этапа 0.2 миграции v3 → v4: ядро {@link Agent} инициализируется и
 * работает БЕЗ графического интерфейса (headless-совместимо, без {@code javax.swing.*}).
 *
 * <p>Цель: зафиксировать, что бизнес-слой не зависит от GUI — это обязательное условие
 * для переезда на веб-интерфейс (v4), где весь Swing-пакет {@code gui/} удаляется.</p>
 *
 * <p>Тест не обращается к LLM API и к SQLite-движку (проверяется только инстанцирование
 * и чистые методы контракта). Запуск обычным {@code main(String[])}:</p>
 *
 * <pre>
 * javac -encoding UTF-8 -cp "dist/classes;lib/*" -d build_test ^
 *       test/corpagent/AgentHeadlessTest.java
 * java  -cp "build_test;dist/classes;lib/*" corpagent.AgentHeadlessTest
 * </pre>
 *
 * Провал теста, если ядро требует GUI или падает при создании без окна.
 */
public class AgentHeadlessTest {

    private static int checks = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testHeadlessProperty();
        testAgentConstructsWithoutGui();
        testAgentCoreContract();
        if (failed == 0) {
            System.out.println("AgentHeadlessTest OK (" + checks + " проверок).");
        } else {
            System.out.println("AgentHeadlessTest FAILED: " + failed + " из " + checks);
            System.exit(1);
        }
    }

    private static void check(boolean cond, String label) {
        checks++;
        if (!cond) {
            failed++;
            System.out.println("  [FAIL] " + label);
        }
    }

    /** 1) JVM работает в headless-режиме: тест не поднимает и не требует дисплей. */
    private static void testHeadlessProperty() {
        // Ставим headless ДО любого обращения к GraphicsEnvironment — это гарантирует,
        // что инстанцирование ядра не тянет за собой AWT-дисплей.
        System.setProperty("java.awt.headless", "true");
        check("true".equals(System.getProperty("java.awt.headless")),
                "java.awt.headless установлен в true");
    }

    /** 2) Ядро Agent создаётся без GUI: Config → DataStore → DatabaseManager → FileCreator → Agent. */
    private static void testAgentConstructsWithoutGui() {
        File base = null;
        try {
            base = Files.createTempDirectory("ca_headless").toFile();

            // config.json — минимальный валидный файл (клиент LLM не будет вызван).
            File config = new File(base, "config.json");
            String cfg = "{\"api_key\":\"test\",\"model\":\"deepseek-chat\","
                    + "\"provider\":\"deepseek\",\"base_url\":\"https://api.deepseek.com\","
                    + "\"temperature\":0.1,\"max_tokens\":100}";
            Files.write(config.toPath(), cfg.getBytes("UTF-8"));

            Config configObj = new Config(config.getPath());
            check("deepseek".equals(configObj.getProvider()),
                    "Config: провайдер по умолчанию deepseek");
            check(configObj.getChatCompletionUrl().endsWith("/chat/completions"),
                    "Config: chat-url собирается из base_url");

            DataStore store = new DataStore(base.getPath());
            DatabaseManager dbManager = new DatabaseManager(
                    new File(base, "databases").getPath(),
                    new File(base, "backups").getPath(),
                    store);
            FileCreator fileCreator = new FileCreator(
                    new File(base, "output").getPath(), base.getPath());

            Agent agent = new Agent(configObj, store, dbManager, fileCreator);
            check(agent != null, "Agent создаётся без GUI (headless)");
            check(agent.getLlm() != null, "Agent.getLlm() доступен");
            check("deepseek-chat".equals(agent.getModel()),
                    "Agent.getModel() возвращает модель из Config");
        } catch (Exception e) {
            check(false, "testAgentConstructsWithoutGui: " + e);
        } finally {
            deleteRecursive(base);
        }
    }

    /** 3) Базовый контракт ядра не требует GUI: обрезка контекста и пустой результат. */
    private static void testAgentCoreContract() {
        File base = null;
        try {
            base = Files.createTempDirectory("ca_headless2").toFile();
            File config = new File(base, "config.json");
            Files.write(config.toPath(),
                    "{\"api_key\":\"test\",\"model\":\"deepseek-chat\"}".getBytes("UTF-8"));

            Config configObj = new Config(config.getPath());
            DataStore store = new DataStore(base.getPath());
            DatabaseManager dbManager = new DatabaseManager(
                    new File(base, "databases").getPath(),
                    new File(base, "backups").getPath(),
                    store);
            FileCreator fileCreator = new FileCreator(
                    new File(base, "output").getPath(), base.getPath());
            Agent agent = new Agent(configObj, store, dbManager, fileCreator);

            // buildDbContext с пустым списком БД не должен падать и не тянет GUI.
            String ctx = agent.buildDbContext(new java.util.ArrayList<String>(), "привет");
            check(ctx == null || ctx.isEmpty(),
                    "buildDbContext([]) → пустой контекст (без БД)");

            // TaskResult собирается независимо от GUI.
            Agent.TaskResult r = new Agent.TaskResult();
            check(r.response == null && r.dbUpdates != null && r.usage != null,
                    "TaskResult: инициализирует коллекции без GUI");
        } catch (Exception e) {
            check(false, "testAgentCoreContract: " + e);
        } finally {
            deleteRecursive(base);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
