package corpagent.gui;

import corpagent.db.DatabaseManager;
import corpagent.files.FileExtractor;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Создание новой базы данных: ввод названия, описания и SQL-схемы.
 * Схему можно ввести вручную или сгенерировать с помощью ИИ по описанию.
 */
public class DbManagerView extends JPanel {

    private final MainFrame frame;
    private final JTextField nameField = new JTextField(20);
    private final JTextArea descArea = new JTextArea(3, 40);
        private final JTextArea schemaArea = new JTextArea(14, 60);
    private final JLabel statusLabel = new JLabel(" ");
    private final List<File> selectedFiles = new ArrayList<File>();
    private final JLabel filesStatusLabel = new JLabel("Файлы не выбраны.");

    public DbManagerView(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);
        setBorder(new EmptyBorder(20, 24, 20, 24));
    }

    public void refresh() {
        removeAll();
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);

        content.add(Ui.header("🗄️", "Создание базы данных", Ui.FONT_TITLE));
        content.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel("Опишите структуру БД словами, и ИИ сгенерирует SQL-схему. Или введите схему вручную.");
        hint.setFont(Ui.FONT_SMALL);
        hint.setForeground(Ui.TEXT_GRAY);
        content.add(hint);
        content.add(Box.createVerticalStrut(14));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createLineBorder(new Color(209, 250, 229)));

        form.add(fieldLabel("Название БД (латиница/цифры/_-)"));
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        form.add(nameField);
        form.add(Box.createVerticalStrut(8));

        form.add(fieldLabel("Описание (для чего БД)"));
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        form.add(new JScrollPane(descArea));
        form.add(Box.createVerticalStrut(8));

        form.add(fieldLabel("SQL-схема (CREATE TABLE ...) — можно сгенерировать ИИ"));
        schemaArea.setLineWrap(false);
        schemaArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        form.add(new JScrollPane(schemaArea));

        content.add(form);
        content.add(Box.createVerticalStrut(10));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);

        JButton generate = new JButton("Сгенерировать схему ИИ");
        generate.setIcon(Ui.iconImage("✨", 16));
        generate.setBackground(new Color(59, 130, 246));
        generate.setForeground(Color.WHITE);
        generate.setFocusPainted(false);
        generate.setFont(Ui.FONT_PLAIN);
        generate.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        generate.addActionListener(e -> generateSchema());
        buttons.add(generate);

        JButton create = new JButton("Создать БД");
        create.setIcon(Ui.iconImage("💾", 16));
        Ui.styleButton(create, true);
        create.addActionListener(e -> createDatabase());
        buttons.add(create);

        JButton back = new JButton("Назад");
        back.setIcon(Ui.iconImage("←", 16));
        Ui.styleButton(back, false);
        back.addActionListener(e -> frame.showChat());
                buttons.add(back);
        content.add(buttons);

        content.add(Box.createVerticalStrut(18));
        content.add(buildFilePanel());
        content.add(Box.createVerticalStrut(6));

        statusLabel.setFont(Ui.FONT_SMALL);
        statusLabel.setForeground(Ui.PRIMARY);
        content.add(Box.createVerticalStrut(6));
        content.add(statusLabel);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void generateSchema() {
        String description = descArea.getText().trim();
        if (description.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Сначала заполните описание БД.");
            return;
        }
        statusLabel.setForeground(new Color(59, 130, 246));
        statusLabel.setText("⏳ Генерация схемы ИИ...");
        final String desc = description;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String prompt = "Создай SQL-схему базы данных по описанию: " + desc
                        + "\nВерни ТОЛЬКО CREATE TABLE операторы для SQLite (без INSERT). "
                        + "Используй INTEGER PRIMARY KEY AUTOINCREMENT для id. Не используй FOREIGN KEY и REFERENCES.";
                List<Map<String, Object>> msgs = new ArrayList<Map<String, Object>>();
                Map<String, Object> m = Json.newObject();
                m.put("role", "user");
                m.put("text", prompt);
                msgs.add(m);
                return frame.getAgent().getLlm().call(msgs, "");
            }

            @Override
            protected void done() {
                try {
                    String schema = get();
                    if (schema.startsWith("❌")) {
                        statusLabel.setForeground(Color.RED);
                        statusLabel.setText(schema);
                        return;
                    }
                    schemaArea.setText(schema.trim());
                    statusLabel.setForeground(new Color(16, 185, 129));
                    statusLabel.setText("✅ Схема сгенерирована. Проверьте и нажмите «Создать БД».");
                } catch (Exception ex) {
                    statusLabel.setForeground(Color.RED);
                    statusLabel.setText("❌ Ошибка: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void createDatabase() {
        String name = nameField.getText().trim();
        String schema = schemaArea.getText().trim();
        String description = descArea.getText().trim();
        if (!Utils.isValidDbName(name)) {
            JOptionPane.showMessageDialog(this, "Некорректное имя БД. Используйте латиницу, цифры, '_' или '-'.");
            return;
        }
        if (schema.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите SQL-схему или сгенерируйте её ИИ.");
            return;
        }
        if (frame.getStore().getDatabase(name) != null) {
            int c = JOptionPane.showConfirmDialog(this,
                    "БД '" + name + "' уже существует. Перезаписать?", "Подтверждение",
                    JOptionPane.YES_NO_OPTION);
            if (c != JOptionPane.YES_OPTION) {
                return;
            }
        }
        statusLabel.setForeground(new Color(16, 185, 129));
        statusLabel.setText("⏳ Создание БД...");
        final String fName = name;
        final String fSchema = schema;
        final String fDesc = description;
        new SwingWorker<DatabaseManager.CreateDbResult, Void>() {
            @Override
            protected DatabaseManager.CreateDbResult doInBackground() {
                return frame.getDbManager().createDatabase(fName, fSchema, fDesc);
            }

            @Override
            protected void done() {
                try {
                    DatabaseManager.CreateDbResult r = get();
                    if (r.success) {
                        statusLabel.setForeground(new Color(16, 185, 129));
                        statusLabel.setText("✅ БД создана: таблиц " + r.tables.size()
                                + ", записей " + r.totalRecords + ".");
                        nameField.setText("");
                        descArea.setText("");
                        schemaArea.setText("");
                        frame.refreshSidebar();
                        JOptionPane.showMessageDialog(DbManagerView.this,
                                "База данных '" + fName + "' успешно создана!\nТаблиц: " + r.tables.size()
                                        + "\nЗаписей: " + r.totalRecords);
                        frame.showDbViewer(fName);
                    } else {
                        statusLabel.setForeground(Color.RED);
                        statusLabel.setText("❌ Ошибка: " + r.error);
                        JOptionPane.showMessageDialog(DbManagerView.this,
                                "Не удалось создать БД:\n" + r.error);
                    }
                } catch (Exception ex) {
                    statusLabel.setForeground(Color.RED);
                    statusLabel.setText("❌ Ошибка: " + ex.getMessage());
                }
            }
        }.execute();
    }

        private JPanel buildFilePanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(245, 250, 245));
        p.setBorder(BorderFactory.createLineBorder(new Color(167, 214, 195)));

        p.add(fieldLabel("Способ 2: создать БД из загруженных файлов"));
        p.add(Box.createVerticalStrut(6));
        JLabel h = new JLabel("<html>Выберите один или несколько файлов "
                + "(txt/md/csv/html/docx/pdf/xlsx/pptx).<br>ИИ изучит их содержимое и построит структуру БД. "
                + "Имя и описание БД возьмите из полей выше.</html>");
        h.setFont(Ui.FONT_SMALL);
        h.setForeground(Ui.TEXT_GRAY);
        p.add(h);
        p.add(Box.createVerticalStrut(8));

        filesStatusLabel.setForeground(new Color(59, 130, 246));
        filesStatusLabel.setFont(Ui.FONT_SMALL);
        updateFilesLabelText();
        p.add(filesStatusLabel);
        p.add(Box.createVerticalStrut(8));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        JButton choose = new JButton("Выбрать файлы");
        choose.setIcon(Ui.iconImage("📁", 16));
        choose.setBackground(new Color(59, 130, 246));
        choose.setForeground(Color.WHITE);
        choose.setFocusPainted(false);
        choose.setFont(Ui.FONT_PLAIN);
        choose.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        choose.addActionListener(e -> chooseSourceFiles());
        row.add(choose);

        JButton create = new JButton("Создать БД из файлов");
        create.setIcon(Ui.iconImage("🗄️", 16));
        Ui.styleButton(create, true);
        create.addActionListener(e -> createFromFiles());
        row.add(create);
        p.add(row);
        return p;
    }

    private void updateFilesLabelText() {
        if (selectedFiles.isEmpty()) {
            filesStatusLabel.setText("Файлы не выбраны.");
        } else {
            StringBuilder sb = new StringBuilder("Выбрано файлов: ").append(selectedFiles.size()).append(":");
            for (File f : selectedFiles) {
                sb.append(" ").append(f.getName());
                if (sb.length() > 90) {
                    sb.append(" …");
                    break;
                }
            }
            filesStatusLabel.setText(sb.toString());
        }
    }

    private void chooseSourceFiles() {
        JFileChooser chooser = new JFileChooser();
        Ui.styleFileChooser(chooser);
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Выберите файлы для создания БД");
        // Filter supported formats (extract text only).
        String[] exts = {"txt", "md", "csv", "html", "docx", "pdf", "xlsx", "pptx"};
        chooser.setFileFilter(new FileNameExtensionFilter("Поддерживаемые ИИ форматы", exts));
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                if (!selectedFiles.contains(f)) {
                    selectedFiles.add(f);
                }
            }
            updateFilesLabelText();
        }
    }

    private void createFromFiles() {
        String name = nameField.getText().trim();
        String description = descArea.getText().trim();
        if (!Utils.isValidDbName(name)) {
            JOptionPane.showMessageDialog(this,
                    "Некорректное имя БД. Используйте латиницу, цифры, '_' или '-'.");
            return;
        }
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Сначала выберите файлы (кнопка «Выбрать файлы»).");
            return;
        }
        if (frame.getStore().getDatabase(name) != null) {
            int c = JOptionPane.showConfirmDialog(this,
                    "БД '" + name + "' уже существует. Перезаписать?", "Подтверждение",
                    JOptionPane.YES_NO_OPTION);
            if (c != JOptionPane.YES_OPTION) {
                return;
            }
        }
        statusLabel.setForeground(new Color(16, 185, 129));
        statusLabel.setText("⏳ Извлечение и анализ файлов...");
        final String fName = name;
        final String fDesc = description;
        final java.util.List<File> files = new ArrayList<File>(selectedFiles);
        new SwingWorker<DatabaseManager.CreateDbResult, Void>() {
            @Override
            protected DatabaseManager.CreateDbResult doInBackground() {
                // Читаем файлы в фоне (без блокировки UI).
                StringBuilder combined = new StringBuilder();
                for (File f : files) {
                    String text = FileExtractor.extractText(f);
                    combined.append("Файл: ").append(f.getName()).append("\n").append(text).append("\n\n");
                }
                return frame.getAgent().createDatabaseFromFiles(fName, fDesc, combined.toString());
            }

            @Override
            protected void done() {
                try {
                    DatabaseManager.CreateDbResult r = get();
                    if (r.success) {
                        statusLabel.setForeground(new Color(16, 185, 129));
                        statusLabel.setText("✅ БД создана из файлов: таблиц " + r.tables.size()
                                + ", записей " + r.totalRecords + ".");
                        nameField.setText("");
                        descArea.setText("");
                        schemaArea.setText("");
                        selectedFiles.clear();
                        updateFilesLabelText();
                        frame.refreshSidebar();
                        JOptionPane.showMessageDialog(DbManagerView.this,
                                "База данных '" + fName + "' создана из файлов!\nТаблиц: " + r.tables.size()
                                        + "\nЗаписей: " + r.totalRecords);
                        frame.showDbViewer(fName);
                    } else {
                        statusLabel.setForeground(Color.RED);
                        statusLabel.setText("❌ Ошибка: " + r.error);
                        JOptionPane.showMessageDialog(DbManagerView.this,
                                "Не удалось создать БД из файлов:\n" + r.error);
                    }
                } catch (Exception ex) {
                    statusLabel.setForeground(Color.RED);
                    statusLabel.setText("❌ Ошибка: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_BOLD);
        l.setForeground(Ui.TEXT_DARK);
        return l;
    }
}
