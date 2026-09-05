package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.db.SqliteHelper;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Диалог подключения базы данных.
 * Поддерживает типы: SQLite, PostgreSQL, MySQL, MongoDB, MSSQL, Oracle.
 * Поля подключения зависят от типа. Кнопка «Тестировать» проверяет подключение.
 * Для SQLite выполняется реальное открытие файла; для остальных типов требуется
 * JDBC-драйвер в lib/ (если отсутствует — понятное сообщение).
 */
public class DbConnectDialog extends JDialog {

    private static final String[] TYPES = {
            "sqlite", "postgresql", "mysql", "mongodb", "mssql", "oracle"
    };

    private final DataStore store;
    private final String editingName;

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<String> typeCombo = new JComboBox<String>(TYPES);
    private final JTextField fileField = new JTextField(30);
    private final JButton browseBtn = new JButton("📂");
    private final JTextField hostField = new JTextField(20);
    private final JTextField portField = new JTextField("5432", 6);
    private final JTextField databaseField = new JTextField(20);
    private final JTextField userField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JTextField connStringField = new JTextField(30);
    private final JTextAreaDesc descArea = new JTextAreaDesc();
    private final JCheckBox autoUpdateCheck = new JCheckBox("Автообновление схемы", true);
    private final JCheckBox readOnlyCheck = new JCheckBox("Только чтение (read-only)", false);
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(30, 1, 300, 5));

    private boolean saved = false;

    /** Простая JTextArea-обёртка, чтобы не импортировать лишнее. */
    private static class JTextAreaDesc extends javax.swing.JTextArea {
        JTextAreaDesc() {
            super(2, 40);
            setLineWrap(true);
            setWrapStyleWord(true);
        }
    }

    public DbConnectDialog(Frame owner, DataStore store, String editingName) {
        super(owner, editingName == null ? "➕ Подключить БД" : "✏️ Настройки БД", true);
        this.store = store;
        this.editingName = editingName;

        setSize(620, 620);
        setLocationRelativeTo(owner);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(16, 16, 16, 16));

        form.add(fieldLabel("Название подключения (уникальное)"));
        Ui.styleField(nameField);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(nameField);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Тип базы данных"));
        typeCombo.setMaximumSize(new Dimension(200, 30));
        typeCombo.setFont(Ui.FONT_PLAIN);
        typeCombo.setBackground(Ui.SURFACE);
        typeCombo.addActionListener(e -> updateTypeFields());
        form.add(typeCombo);
        form.add(Box.createVerticalStrut(10));

        // --- SQLite: файл ---
        sqlitePanel = new JPanel();
        sqlitePanel.setLayout(new BoxLayout(sqlitePanel, BoxLayout.Y_AXIS));
        sqlitePanel.setOpaque(false);
        sqlitePanel.add(fieldLabel("Файл базы данных (.db)"));
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fileRow.setOpaque(false);
        Ui.styleField(fileField);
        fileField.setPreferredSize(new Dimension(420, 28));
        fileRow.add(fileField);
        Ui.styleSecondary(browseBtn);
        browseBtn.setText("Обзор");
        browseBtn.setIcon(Ui.iconImage("📂", 14));
        browseBtn.setIconTextGap(6);
        browseBtn.addActionListener(e -> chooseFile());
        fileRow.add(browseBtn);
        sqlitePanel.add(fileRow);
        form.add(sqlitePanel);
        form.add(Box.createVerticalStrut(10));

        // --- Серверные типы: host/port/db/user/password ---
        serverPanel = new JPanel();
        serverPanel.setLayout(new BoxLayout(serverPanel, BoxLayout.Y_AXIS));
        serverPanel.setOpaque(false);
        serverPanel.add(fieldLabel("Хост"));
        Ui.styleField(hostField);
        hostField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        serverPanel.add(hostField);
        serverPanel.add(Box.createVerticalStrut(8));
        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portRow.setOpaque(false);
        portRow.add(fieldLabel("Порт"));
        Ui.styleField(portField);
        portField.setPreferredSize(new Dimension(80, 28));
        portRow.add(portField);
        serverPanel.add(portRow);
        serverPanel.add(Box.createVerticalStrut(8));
        serverPanel.add(fieldLabel("Имя базы данных"));
        Ui.styleField(databaseField);
        databaseField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        serverPanel.add(databaseField);
        serverPanel.add(Box.createVerticalStrut(8));
        serverPanel.add(fieldLabel("Пользователь"));
        Ui.styleField(userField);
        userField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        serverPanel.add(userField);
        serverPanel.add(Box.createVerticalStrut(8));
        serverPanel.add(fieldLabel("Пароль"));
        Ui.stylePassword(passwordField);
        passwordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        serverPanel.add(passwordField);
        form.add(serverPanel);
        form.add(Box.createVerticalStrut(10));

        // --- MongoDB: connection string ---
        mongoPanel = new JPanel();
        mongoPanel.setLayout(new BoxLayout(mongoPanel, BoxLayout.Y_AXIS));
        mongoPanel.setOpaque(false);
        mongoPanel.add(fieldLabel("Connection string (mongodb://...)"));
        Ui.styleField(connStringField);
        connStringField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        mongoPanel.add(connStringField);
        form.add(mongoPanel);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Описание (для чего БД)"));
        Ui.styleArea(descArea);
        form.add(new JScrollPane(descArea));
        form.add(Box.createVerticalStrut(12));

        form.add(sectionLabel("Настройки подключения"));
        autoUpdateCheck.setOpaque(false);
        autoUpdateCheck.setFont(Ui.FONT_PLAIN);
        autoUpdateCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(autoUpdateCheck);
        readOnlyCheck.setOpaque(false);
        readOnlyCheck.setFont(Ui.FONT_PLAIN);
        readOnlyCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(readOnlyCheck);
        form.add(Box.createVerticalStrut(8));
        form.add(fieldLabel("Таймаут подключения (сек)"));
        timeoutSpinner.setMaximumSize(new Dimension(120, 28));
        form.add(timeoutSpinner);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(229, 231, 235)));
        JButton test = new JButton("🧪 Тестировать");
        Ui.styleButton(test, false);
        test.setToolTipText("Проверить подключение");
        test.addActionListener(e -> testConnection());
        buttons.add(test);
        JButton cancel = new JButton("Отмена");
        Ui.styleButton(cancel, false);
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        JButton save = new JButton("Сохранить");
        Ui.styleButton(save, true);
        save.addActionListener(e -> saveConnection());
        buttons.add(save);

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        if (editingName != null) {
            loadExisting();
        }
        updateTypeFields();
    }

    private JPanel sqlitePanel;
    private JPanel serverPanel;
    private JPanel mongoPanel;

    private void updateTypeFields() {
        String type = typeCombo.getSelectedItem() == null ? "sqlite" : typeCombo.getSelectedItem().toString();
        boolean sqlite = "sqlite".equals(type);
        boolean mongo = "mongodb".equals(type);
        sqlitePanel.setVisible(sqlite);
        serverPanel.setVisible(!sqlite && !mongo);
        mongoPanel.setVisible(mongo);
        // Порт по умолчанию в зависимости от типа
        if (!sqlite && !mongo) {
            String defPort = "postgresql".equals(type) ? "5432"
                    : "mysql".equals(type) ? "3306"
                    : "mssql".equals(type) ? "1433"
                    : "oracle".equals(type) ? "1521" : "5432";
            portField.setText(defPort);
        }
        revalidate();
        repaint();
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        Ui.styleFileChooser(fc);
        fc.setDialogTitle("Выберите файл базы данных SQLite");
        fc.setFileFilter(new FileNameExtensionFilter("SQLite (*.db)", "db", "sqlite", "sqlite3"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void loadExisting() {
        Map<String, Object> db = Json.asMap(store.getDatabases().get(editingName));
        if (db == null) {
            return;
        }
        nameField.setText(editingName);
        typeCombo.setSelectedItem(Json.str(db, "type", "sqlite"));
        fileField.setText(Json.str(db, "file", Json.str(db, "path")));
        hostField.setText(Json.str(db, "host"));
        portField.setText(String.valueOf(Json.intVal(db, "port", 5432)));
        databaseField.setText(Json.str(db, "database"));
        userField.setText(Json.str(db, "user"));
        passwordField.setText(Json.str(db, "password"));
        connStringField.setText(Json.str(db, "connection_string"));
        descArea.setText(Json.str(db, "description"));
        autoUpdateCheck.setSelected(Json.bool(db, "auto_update_schema", true));
        readOnlyCheck.setSelected(Json.bool(db, "read_only", false));
        timeoutSpinner.setValue(Json.intVal(db, "timeout_sec", 30));
        updateTypeFields();
    }

    private void saveConnection() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите название подключения.");
            return;
        }
        String type = typeCombo.getSelectedItem() == null ? "sqlite" : typeCombo.getSelectedItem().toString();
        if ("sqlite".equals(type) && fileField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите файл базы данных SQLite.");
            return;
        }
        if (!"sqlite".equals(type) && !"mongodb".equals(type)
                && hostField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите хост подключения.");
            return;
        }

        Map<String, Object> old = null;
        if (editingName != null && !editingName.equals(name)) {
            old = Json.asMap(store.getDatabases().get(editingName));
            store.removeDatabase(editingName);
        } else if (editingName != null) {
            old = Json.asMap(store.getDatabases().get(editingName));
        }

        Map<String, Object> db = Json.newObject();
        db.put("type", type);
        db.put("description", descArea.getText().trim());
        db.put("file", fileField.getText().trim());
        db.put("path", fileField.getText().trim());
        db.put("host", hostField.getText().trim());
        db.put("port", parseInt(portField.getText(), 5432));
        db.put("database", databaseField.getText().trim());
        db.put("user", userField.getText().trim());
        db.put("password", new String(passwordField.getPassword()));
        db.put("connection_string", connStringField.getText().trim());
        db.put("auto_update_schema", autoUpdateCheck.isSelected());
        db.put("read_only", readOnlyCheck.isSelected());
        db.put("timeout_sec", ((Number) timeoutSpinner.getValue()).intValue());
        db.put("enabled", old == null ? true : Json.bool(old, "enabled", true));
        db.put("usage_count", old == null ? 0 : Json.intVal(old, "usage_count", 0));
        db.put("created", old == null ? Utils.nowIso() : Json.str(old, "created", Utils.nowIso()));
        db.put("updated", Utils.nowIso());

        // Для SQLite — реально открыть и посчитать таблицы/записи
        if ("sqlite".equals(type)) {
            String path = fileField.getText().trim();
            List<String> tables = SqliteHelper.getTables(path);
            db.put("tables_count", tables.size());
            int records = 0;
            for (String t : tables) {
                try {
                    SqliteHelper.QueryResult r = SqliteHelper.executeQuery(path, "SELECT COUNT(*) FROM " + Utils.quoteIdent(t));
                    if (r.success && !r.rows.isEmpty() && !r.rows.get(0).isEmpty()) {
                        records += Integer.parseInt(String.valueOf(r.rows.get(0).get(0)));
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
            db.put("records_count", records);
            db.put("schema", SqliteHelper.getDbSchema(path));
        } else {
            db.put("tables_count", old == null ? 0 : Json.intVal(old, "tables_count", 0));
            db.put("records_count", old == null ? 0 : Json.intVal(old, "records_count", 0));
        }

        store.putDatabase(name, db);
        store.saveAll();
        saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }

    // ==================== ТЕСТИРОВАНИЕ ====================

    private void testConnection() {
        String type = typeCombo.getSelectedItem() == null ? "sqlite" : typeCombo.getSelectedItem().toString();
        if ("sqlite".equals(type)) {
            testSqlite();
        } else {
            JOptionPane.showMessageDialog(this,
                    "Подключение к '" + typeLabel(type) + "' требует JDBC-драйвер в папке lib/.\n"
                            + "Сейчас приложение поддерживает работу с SQLite. Драйверы для "
                            + typeLabel(type) + " будут добавлены на этапе расширения типов БД.");
        }
    }

    private void testSqlite() {
        String path = fileField.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите файл базы данных SQLite.");
            return;
        }
        if (!new File(path).exists()) {
            JOptionPane.showMessageDialog(this, "Файл не найден: " + path);
            return;
        }
        List<String> tables = SqliteHelper.getTables(path);
        JOptionPane.showMessageDialog(this,
                "✅ Подключение к SQLite успешно.\nФайл: " + path + "\nТаблиц: " + tables.size()
                        + (tables.isEmpty() ? "" : "\n" + String.join(", ", tables)));
    }

    private String typeLabel(String type) {
        if ("sqlite".equals(type)) {
            return "SQLite";
        }
        if ("postgresql".equals(type)) {
            return "PostgreSQL";
        }
        if ("mysql".equals(type)) {
            return "MySQL";
        }
        if ("mongodb".equals(type)) {
            return "MongoDB";
        }
        if ("mssql".equals(type)) {
            return "MSSQL";
        }
        if ("oracle".equals(type)) {
            return "Oracle";
        }
        return type;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ==================== ХЕЛПЕРЫ ====================

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_HEADER);
        l.setForeground(Ui.TEXT_DARK);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        l.setForeground(Ui.TEXT_GRAY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
