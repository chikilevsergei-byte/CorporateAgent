package corpagent.gui;

import corpagent.Agent;
import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.files.FileCreator;
import corpagent.util.Config;
import corpagent.util.Json;
import corpagent.util.Settings;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.Map;

/**
 * Главное окно приложения. Содержит боковую панель (чаты/навыки/инструменты/БД)
 * и область контента, которая переключается между представлениями:
 * чат, менеджер навыков, менеджер инструментов, создание БД, просмотр БД.
 */
public class MainFrame extends JFrame {

    public static final String VIEW_CHAT = "chat";
    public static final String VIEW_SKILLS = "skills";
    public static final String VIEW_TOOLS = "tools";
        public static final String VIEW_DB_MANAGER = "db_manager";
    public static final String VIEW_DB_VIEWER = "db_viewer";

    /** Версия сборки (используется в заголовке окна и для git-тега релиза). */
    public static final String VERSION = "v3.1";


    private final Agent agent;
    private final DataStore store;
    private final DatabaseManager dbManager;
    private final FileCreator fileCreator;
    private final String baseDir;
    private final Config config;
    private final Settings settings;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);

    private SidebarPanel sidebar;
    private ChatView chatView;
    private SkillManagerView skillManagerView;
    private ToolManagerView toolManagerView;
    private DbManagerView dbManagerView;
    private DbViewerView dbViewerView;

    // Текущее состояние (аналог session_state в v2)
    private String currentChatId = null;
    private String viewingDb = null;
    private String editingDb = null; // null | "import" | "visualize" | "export"
    private String currentView = VIEW_CHAT;

    public MainFrame(Agent agent, DataStore store, DatabaseManager dbManager, FileCreator fileCreator,
                     String baseDir, Config config, Settings settings) {
        this.agent = agent;
        this.store = store;
        this.dbManager = dbManager;
        this.fileCreator = fileCreator;
        this.baseDir = baseDir;
        this.config = config;
        this.settings = settings;

                setTitle("🤖 AI ассистент руководителя проектов — CorporateAgent v3 (" + VERSION + ")");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setMinimumSize(new Dimension(1000, 650));
        setLocationRelativeTo(null);

        // Применяем сохранённую тему (UI-0.2) до сборки дерева UI.
        Ui.applyTheme(settings.getTheme());

        buildUI();
        showView(currentView);
        sidebar.refresh();
    }

    /**
     * Собирает (или пересобирает) дерево UI: заголовок, сайдбар и представления.
     * Вызывается из конструктора и повторно из {@link #applyTheme} при смене темы,
     * чтобы компоненты перечитали текущую палитру {@link Ui}.
     */
    private void buildUI() {
        // Верхняя панель-заголовок
        JPanel header = createHeader();

        // Боковая панель
        sidebar = new SidebarPanel(this);

        // Представления
        chatView = new ChatView(this);
        skillManagerView = new SkillManagerView(this);
        toolManagerView = new ToolManagerView(this);
        dbManagerView = new DbManagerView(this);
        dbViewerView = new DbViewerView(this);

        contentPanel.removeAll();
        contentPanel.setBackground(Ui.BG);
        contentPanel.add(chatView, VIEW_CHAT);
        contentPanel.add(skillManagerView, VIEW_SKILLS);
        contentPanel.add(toolManagerView, VIEW_TOOLS);
        contentPanel.add(dbManagerView, VIEW_DB_MANAGER);
        contentPanel.add(dbViewerView, VIEW_DB_VIEWER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, contentPanel);
        split.setDividerLocation(300);
        split.setResizeWeight(0.0);
        split.setContinuousLayout(true);
        split.setBorder(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);
        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

        setContentPane(root);
    }

    /**
     * UI-0.2: применяет тему без перезапуска приложения — меняет палитру
     * {@link Ui} и пересобирает дерево UI, сохраняя текущее представление.
     */
    public void applyTheme(String themeName) {
        Ui.applyTheme(themeName);
        String view = currentView;
        buildUI();
        showView(view);
        sidebar.refresh();
        revalidate();
        repaint();
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Ui.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.BORDER),
                new javax.swing.border.EmptyBorder(10, 24, 0, 20)));
        header.setPreferredSize(new Dimension(0, 64));

        JPanel titleRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 12, 0));
        titleRow.setOpaque(false);
        titleRow.setBorder(new javax.swing.border.EmptyBorder(0, 0, 0, 0));
        JLabel logo = Ui.icon("🤖", 26);
        logo.setAlignmentY(Component.CENTER_ALIGNMENT);
        titleRow.add(logo);
        JLabel title = new JLabel("AI ассистент руководителя проектов");
                title.setForeground(Ui.TEXT);
        title.setFont(Ui.FONT_TITLE);
        title.setAlignmentY(Component.CENTER_ALIGNMENT);
        titleRow.add(title);
        header.add(titleRow, BorderLayout.WEST);

        JPanel modelRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 16, 0));
        modelRow.setOpaque(false);

                // iOS-совместимая кнопка «Настройки»: компактная filled tint-кнопка (белый текст).
        JButton settingsBtn = new JButton("Настройки");
        Ui.stylePrimary(settingsBtn);
        settingsBtn.setFont(Ui.FONT_BOLD);
        settingsBtn.setPreferredSize(new Dimension(132, 38));
        settingsBtn.setMinimumSize(new Dimension(132, 38));
        settingsBtn.addActionListener(e -> openSettings());
        modelRow.add(settingsBtn);

        header.add(modelRow, BorderLayout.EAST);
        return header;
    }

    /** Открывает модальное окно настроек. */
    public void openSettings() {
        SettingsDialog dialog = new SettingsDialog(this);
        dialog.setVisible(true);
    }

    // ==================== НАВИГАЦИЯ ====================

    public void showView(String view) {
        this.currentView = view;
        cardLayout.show(contentPanel, view);
        if (VIEW_CHAT.equals(view)) {
            chatView.refresh();
        } else if (VIEW_SKILLS.equals(view)) {
            skillManagerView.refresh();
        } else if (VIEW_TOOLS.equals(view)) {
            toolManagerView.refresh();
        } else if (VIEW_DB_MANAGER.equals(view)) {
            dbManagerView.refresh();
        } else if (VIEW_DB_VIEWER.equals(view)) {
            dbViewerView.refresh();
        }
        sidebar.refresh();
    }

    public void showChat() {
        showView(VIEW_CHAT);
    }

    public void showSkillManager() {
        showView(VIEW_SKILLS);
    }

    public void showToolManager() {
        showView(VIEW_TOOLS);
    }

    public void showDbManager() {
        showView(VIEW_DB_MANAGER);
    }

    public void showDbViewer(String dbName) {
        this.viewingDb = dbName;
        this.editingDb = null;
        showView(VIEW_DB_VIEWER);
    }

    // ==================== ГЕТТЕРЫ ====================

    public Agent getAgent() {
        return agent;
    }

    public DataStore getStore() {
        return store;
    }

    public DatabaseManager getDbManager() {
        return dbManager;
    }

    public FileCreator getFileCreator() {
        return fileCreator;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public String getCurrentChatId() {
        return currentChatId;
    }

    public void setCurrentChatId(String id) {
        this.currentChatId = id;
    }

    public String getViewingDb() {
        return viewingDb;
    }

    public void setViewingDb(String viewingDb) {
        this.viewingDb = viewingDb;
    }

    public String getEditingDb() {
        return editingDb;
    }

    public void setEditingDb(String editingDb) {
        this.editingDb = editingDb;
    }

    public boolean isAutoUpdateDb() {
        return settings.isAutoUpdateDb();
    }

    public void setAutoUpdateDb(boolean autoUpdateDb) {
        settings.setAutoUpdateDb(autoUpdateDb);
        settings.save();
    }

    public Config getConfig() {
        return config;
    }

    public Settings getSettings() {
        return settings;
    }

    public ChatView getChatView() {
        return chatView;
    }

    public DbViewerView getDbViewerView() {
        return dbViewerView;
    }

        /**
     * Открывает мастер «Создать проект» (R-P0.2). При успехе обновляет сайдбар
     * и выводит пользователя в первый чат-роль проекта.
     */
    public void createProject() {
        CreateProjectDialog dialog = new CreateProjectDialog(this, store);
        dialog.setVisible(true);
                if (dialog.isCreated()) {
            sidebar.refresh();
            Map<String, Object> project = store.getProject(dialog.getCreatedProjectId());
            String db = project == null ? "" : Json.str(project, "db", "");
            int roles = (project == null || Json.arr(project, "chat_roles") == null)
                    ? 0 : Json.arr(project, "chat_roles").size();
            // UI-3.1: итог создания проекта показываем тостом вместо модального диалога.
            Toast.success(this, "Проект создан",
                    "БД: " + db + " · рабочих чатов: " + roles
                            + ". Выберите чат в списке «ЧАТЫ» слева.");
        }
    }

        /**
     * R-P1.4: создаёт чат «Вопрос по методологии» — только на чтение указанной
     * методологической БД ({@code db}). Дополнительные чипы (навыки/инструменты) не выбираются;
     * пользователь может переопределить выбор вручную.
     *
     * @param db   имя методологической БД (например, «Методология LPG»)
     * @param tag  короткая метка для заголовка чата (LPG / ИТ)
     */
    public void newMethodologyChat(String db, String tag) {
        String chatId = "chat_" + Utils.timestamp() + "_" + Math.abs(db.hashCode());
        Map<String, Object> chat = Json.newObject();
        chat.put("title", "Вопрос по методологии: " + tag);
        chat.put("icon", "📚");
        chat.put("created", Utils.nowIso());
        chat.put("updated", Utils.nowIso());
        chat.put("messages", Json.newArray());
        chat.put("pinned", Boolean.FALSE);
        chat.put("methodology_readonly", Boolean.TRUE);
        // Чат только на чтение методологической БД: единственный выбранный чип-БД.
        java.util.List<Object> dbs = new java.util.ArrayList<Object>();
        if (store.getDatabase(db) != null) {
            dbs.add(db);
        }
        chat.put("selected_databases", dbs);
        chat.put("selected_skills", Json.newArray());
        chat.put("selected_tools", Json.newArray());
        store.putChat(chatId, chat);
        store.saveAll();
        currentChatId = chatId;
        viewingDb = null;
        editingDb = null;
        showChat();
    }

    /** Создаёт новый чат и переходит к нему. */
    public void newChat() {
        String chatId = "chat_" + Utils.timestamp();
        Map<String, Object> chat = Json.newObject();
        chat.put("title", "Новый чат");
        chat.put("created", Utils.nowIso());
        chat.put("updated", Utils.nowIso());
        chat.put("messages", Json.newArray());
        chat.put("pinned", Boolean.FALSE);
        store.putChat(chatId, chat);
        store.saveAll();
        currentChatId = chatId;
        viewingDb = null;
        editingDb = null;
        showChat();
    }

        public void refreshSidebar() {
        sidebar.refresh();
    }

    /**
     * UI-3.1: приветственный тост при старте (перенос из прототипа:
     * {@code setTimeout(showToast(...), 500)}). Вызывать после {@code setVisible(true)},
     * чтобы позиция окна уже была известна.
     */
    public void showWelcomeToast() {
        Toast.info(this, "Добро пожаловать!",
                "CorporateAgent v3 " + VERSION + " в стиле macOS готов к работе");
    }
}
