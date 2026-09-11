package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.db.DatabaseManager;
import corpagent.files.FileExtractor;
import corpagent.util.Utils;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
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

/**
 * Модальное окно «Создать БД из файлов» (вкладка Базы данных в настройках).
 * Пользователь вводит имя и описание БД, выбирает один или несколько файлов;
 * ИИ по содержимому строит SQLite-схему (+ стартовые INSERT для табличных данных)
 * и создаёт реальный файл баз данных databases/&lt;имя&gt;.db.
 */
public class DbCreateFromFilesDialog extends JDialog {

    private final MainFrame frame;
    private final DataStore store;

    private boolean saved = false;

    private final JTextField nameField = new JTextField(22);
    private final JTextArea descArea = new JTextArea(3, 40);
    private final List<File> selectedFiles = new ArrayList<File>();
    private final JLabel filesStatusLabel = new JLabel("Файлы не выбраны.");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton createBtn = new JButton("🗄️ Создать БД из файлов");

    public DbCreateFromFilesDialog(MainFrame frame, DataStore store) {
        super(frame, "Создать базу данных из файлов", true);
        this.frame = frame;
        this.store = store;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(640, 520);
        setLocationRelativeTo(frame);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.SURFACE);
        root.setBorder(new EmptyBorder(18, 20, 16, 20));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        root.add(Ui.header("🗄️", "Создать базу данных из файлов", Ui.FONT_TITLE));
        root.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel("<html>Выберите один или несколько файлов "
                + "(txt/md/csv/html/docx/pdf/xlsx/pptx).<br>ИИ разберёт содержимое и построит структуру БД.</html>");
        hint.setFont(Ui.FONT_SMALL);
        hint.setForeground(Ui.TEXT_SECONDARY);
        root.add(hint);
        root.add(Box.createVerticalStrut(14));

        // Имя + описание
        root.add(fieldLabel("Имя БД (латиница/цифры/_-)"));
        Ui.styleField(nameField);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        root.add(nameField);
        root.add(Box.createVerticalStrut(10));

        root.add(fieldLabel("Описание БД (для чего)"));
        Ui.styleArea(descArea);
        root.add(new JScrollPane(descArea));
        root.add(Box.createVerticalStrut(12));

        // Выбор файлов
        JPanel filesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filesRow.setOpaque(false);
        JButton choose = new JButton("📁 Выбрать файлы");
        Ui.styleSecondary(choose);
        choose.addActionListener(e -> chooseSourceFiles());
        filesRow.add(choose);

        filesStatusLabel.setForeground(Ui.PRIMARY);
        filesStatusLabel.setFont(Ui.FONT_SMALL);
        updateFilesLabelText();
        filesRow.add(filesStatusLabel);
        root.add(filesRow);
        root.add(Box.createVerticalStrut(14));

        Ui.styleButton(createBtn, true);
        createBtn.setIcon(Ui.iconImage("🗄️", 18));
        createBtn.addActionListener(e -> createFromFiles());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.add(createBtn);

        JButton cancel = new JButton("Отмена");
        Ui.styleSecondary(cancel);
        cancel.addActionListener(e -> dispose());
        row.add(cancel);
        root.add(row);

        statusLabel.setFont(Ui.FONT_SMALL);
        statusLabel.setForeground(Ui.PRIMARY);
        root.add(Box.createVerticalStrut(4));
        root.add(statusLabel);

        root.add(Box.createVerticalGlue());
        setContentPane(root);
    }

    public boolean isSaved() {
        return saved;
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL_BOLD);
        l.setForeground(Ui.TEXT_SECONDARY);
        l.setAlignmentX(0.0f);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }

    private void updateFilesLabelText() {
        if (selectedFiles.isEmpty()) {
            filesStatusLabel.setText("Файлы не выбраны.");
        } else {
            StringBuilder sb = new StringBuilder("Выбрано: ").append(selectedFiles.size()).append(" — ");
            for (File f : selectedFiles) {
                sb.append(f.getName());
                sb.append("  ");
                if (sb.length() > 90) {
                    sb.append("…");
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
        String[] exts = {"txt", "md", "csv", "html", "docx", "pdf", "xlsx", "pptx"};
        chooser.setFileFilter(new FileNameExtensionFilter("Поддерживаемые форматы", exts));
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
        // Запрещаем перезапись, используем имя как есть (без молчаливого перезаписывания).
        if (store.getDatabase(name) != null) {
            int c = JOptionPane.showConfirmDialog(this,
                    "БД '" + name + "' уже существует. Перезаписать?", "Подтверждение",
                    JOptionPane.YES_NO_OPTION);
            if (c != JOptionPane.YES_OPTION) {
                return;
            }
        }
        statusLabel.setForeground(Ui.SUCCESS);
        statusLabel.setText("⏳ Извлечение файлов и построение схемы ИИ...");
        createBtn.setEnabled(false);
        final String fName = name;
        final String fDesc = description;
        final java.util.List<File> files = new ArrayList<File>(selectedFiles);
        new SwingWorker<DatabaseManager.CreateDbResult, Void>() {
            @Override
            protected DatabaseManager.CreateDbResult doInBackground() {
                StringBuilder combined = new StringBuilder();
                for (File f : files) {
                    String text = FileExtractor.extractText(f);
                    combined.append("Файл: ").append(f.getName()).append("\n").append(text).append("\n\n");
                }
                return frame.getAgent().createDatabaseFromFiles(fName, fDesc, combined.toString());
            }

            @Override
            protected void done() {
                createBtn.setEnabled(true);
                try {
                    DatabaseManager.CreateDbResult r = get();
                    if (r.success) {
                        saved = true;
                        store.saveAll();
                        JOptionPane.showMessageDialog(DbCreateFromFilesDialog.this,
                                "База данных '" + fName + "' создана!\nТаблиц: " + r.tables.size()
                                        + "\nЗаписей: " + r.totalRecords);
                        dispose();
                    } else {
                        statusLabel.setForeground(Ui.ERROR);
                        statusLabel.setText("❌ Ошибка: " + r.error);
                        JOptionPane.showMessageDialog(DbCreateFromFilesDialog.this,
                                "Не удалось создать БД из файлов:\n" + r.error);
                    }
                } catch (Exception ex) {
                    statusLabel.setForeground(Ui.ERROR);
                    statusLabel.setText("❌ Ошибка: " + ex.getMessage());
                }
            }
        }.execute();
    }
}
