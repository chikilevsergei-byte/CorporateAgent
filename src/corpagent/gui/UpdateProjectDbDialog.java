package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.db.SqliteHelper;
import corpagent.files.FileExtractor;
import corpagent.util.Json;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * R-P1.9.6: окно «Обновить БД проекта».
 *
 * <p>Пользователь выбирает источник (другая зарегистрированная БД или файлы). ИИ строит
 * план синхронизации (upsert): INSERT для новых записей и UPDATE для изменившихся.
 * Записи, которые есть в БД проекта, но отсутствуют в источнике, попадают в секцию
 * «КОНФЛИКТЫ» — пользователь решает, удалять их или нет.</p>
 */
public class UpdateProjectDbDialog extends BaseDialog {

    private final MainFrame frame;
    private final DataStore store;
    private final String projectId;
    private final String projectName;
    private final String projectDb;

    private final JRadioButton existingDbRadio = new JRadioButton("Источник — БД из списка");
    private final JRadioButton filesRadio = new JRadioButton("Источник — файлы");
    private final JComboBox<String> sourceDbCombo = new JComboBox<String>();
    private final JLabel filesStatus = new JLabel("Файлы не выбраны.");
    private final List<File> selectedFiles = new ArrayList<File>();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton runBtn = new JButton("Обновить БД проекта");

    private boolean updated = false;

    public UpdateProjectDbDialog(MainFrame frame, DataStore store, String projectId,
            String projectName, String projectDb) {
        super(frame, "Обновить БД проекта: " + projectName, true);
        this.frame = frame;
        this.store = store;
        this.projectId = projectId;
        this.projectName = projectName;
        this.projectDb = projectDb;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(640, 460);
        setLocationRelativeTo(frame);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(Ui.SURFACE);
        root.setBorder(new EmptyBorder(18, 20, 16, 20));

        root.add(Ui.header("🔄", "Обновить БД проекта", Ui.FONT_TITLE));
        root.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel("<html>ИИ сопоставит данные источника с БД проекта "
                + "(<b>" + projectDb + "</b>):<br>"
                + "новые — добавит, изменившиеся — обновит. Удаление отсутствующих записей — "
                + "по вашему решению.</html>");
        hint.setFont(Ui.FONT_SMALL);
        hint.setForeground(Ui.TEXT_GRAY);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(hint);
        root.add(Box.createVerticalStrut(14));

        // Источник
        root.add(fieldLabel("Источник данных"));
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        existingDbRadio.setSelected(true);
        existingDbRadio.setOpaque(false);
        filesRadio.setOpaque(false);
        existingDbRadio.setFont(Ui.FONT_SMALL);
        filesRadio.setFont(Ui.FONT_SMALL);
        existingDbRadio.setAlignmentX(Component.LEFT_ALIGNMENT);
        filesRadio.setAlignmentX(Component.LEFT_ALIGNMENT);
        g.add(existingDbRadio);
        g.add(filesRadio);
        root.add(existingDbRadio);
        root.add(Box.createVerticalStrut(4));

        JPanel dbRow = new JPanel();
        dbRow.setLayout(new BoxLayout(dbRow, BoxLayout.X_AXIS));
        dbRow.setOpaque(false);
        dbRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        dbRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        sourceDbCombo.setFont(Ui.FONT_SMALL);
        sourceDbCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        dbRow.add(sourceDbCombo);
        root.add(dbRow);
        root.add(Box.createVerticalStrut(4));

        root.add(filesRadio);
        JPanel filesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filesRow.setOpaque(false);
        filesRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        filesRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        JButton chooseFiles = new JButton("Выбрать файлы…");
        Ui.styleSecondary(chooseFiles);
        chooseFiles.addActionListener(e -> chooseFiles());
        filesRow.add(chooseFiles);
        filesStatus.setFont(Ui.FONT_SMALL);
        filesStatus.setForeground(Ui.TEXT_MUTED);
        filesRow.add(filesStatus);
        root.add(filesRow);
        root.add(Box.createVerticalStrut(16));

        // Кнопки
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        Ui.styleButton(runBtn, true);
        runBtn.addActionListener(e -> runUpdate());
        buttons.add(runBtn);
        JButton cancel = new JButton("Отмена");
        Ui.styleSecondary(cancel);
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        root.add(buttons);

        statusLabel.setFont(Ui.FONT_SMALL);
        statusLabel.setForeground(Ui.PRIMARY);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(Box.createVerticalStrut(6));
        root.add(statusLabel);
        root.add(Box.createVerticalGlue());
        // UI-2.4: единая скруглённая карточка диалога (BaseDialog) + закрытие по ESC.
        JScrollPane dlgScroll = new JScrollPane(root);
        dlgScroll.setBorder(null);
        dlgScroll.getVerticalScrollBar().setUnitIncrement(16);
        setCardContent(dlgScroll, null);

        // Заполняем список БД-источников (за исключением самой БД проекта и методологий).
        for (String db : store.enabledDatabases()) {
            if (db.equals(projectDb)) {
                continue;
            }
            sourceDbCombo.addItem(db);
        }
    }

    public boolean isUpdated() {
        return updated;
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        Ui.styleFileChooser(chooser);
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Выберите файлы-источник");
        String[] exts = {"txt", "md", "csv", "html", "docx", "pdf", "xlsx", "pptx"};
        chooser.setFileFilter(new FileNameExtensionFilter("Поддерживаемые форматы", exts));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                if (!selectedFiles.contains(f)) {
                    selectedFiles.add(f);
                }
            }
            filesRadio.setSelected(true);
            filesStatus.setText("Выбрано файлов: " + selectedFiles.size());
        }
    }

    private void runUpdate() {
        final boolean useFiles = filesRadio.isSelected();
        if (useFiles && selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Выберите файлы-источник или укажите БД из списка.");
            return;
        }
        if (!useFiles && sourceDbCombo.getSelectedItem() == null) {
            JOptionPane.showMessageDialog(this, "Нет доступной БД-источника.");
            return;
        }
        final String sourceDb = useFiles ? null : String.valueOf(sourceDbCombo.getSelectedItem());

        runBtn.setEnabled(false);
        statusLabel.setForeground(Ui.TEXT_GRAY);
        statusLabel.setText("⏳ Построение плана синхронизации ИИ...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String projectPath = frame.getDbManager().dbPath(projectDb);
                // Текущее содержимое БД проекта: схема + все данные.
                String projectCtx = SqliteHelper.getDbSchema(projectPath) + "\n\n"
                        + SqliteHelper.exportToSql(projectPath);
                String sourceContent;
                if (useFiles) {
                    StringBuilder sb = new StringBuilder();
                    for (File f : selectedFiles) {
                        sb.append("Файл: ").append(f.getName()).append("\n")
                          .append(FileExtractor.extractText(f)).append("\n\n");
                    }
                    sourceContent = sb.toString();
                } else {
                    String srcPath = frame.getDbManager().dbPath(sourceDb);
                    sourceContent = SqliteHelper.getDbSchema(srcPath) + "\n\n"
                            + SqliteHelper.exportToSql(srcPath);
                }
                return frame.getAgent().generateDbUpsertPlan(projectCtx, sourceContent);
            }

            @Override
            protected void done() {
                runBtn.setEnabled(true);
                String plan;
                try {
                    plan = get();
                } catch (Exception ex) {
                    statusLabel.setForeground(Ui.ERROR);
                    statusLabel.setText("❌ " + ex.getMessage());
                    return;
                }
                if (plan == null || plan.startsWith("❌")) {
                    statusLabel.setForeground(Ui.ERROR);
                    statusLabel.setText("❌ Не удалось построить план");
                    JOptionPane.showMessageDialog(UpdateProjectDbDialog.this, plan);
                    return;
                }
                applyPlan(plan);
            }
        }.execute();
    }

    /** Показывает план, спрашивает про конфликты и выполняет SQL. */
    private void applyPlan(String plan) {
        String sql = SqliteHelper.cleanSqlFromMarkdown(extractSqlBlock(plan));
        String conflicts = extractConflicts(plan);

        // Диалог просмотра плана.
        JTextArea area = new JTextArea(plan.replace("```sql", "").replace("```", ""), 16, 70);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFont(Ui.FONT_PLAIN);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(680, 320));
        int ok = JOptionPane.showConfirmDialog(this, scroll,
                "План синхронизации — применить?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            statusLabel.setText("Отменено пользователем.");
            return;
        }

        // Конфликты: строки проекта, отсутствующие в источнике → спросить про удаление.
        String deleteSql = "";
        if (!conflicts.isEmpty()) {
            int del = JOptionPane.showConfirmDialog(this,
                    "Обнаружены записи, которые есть в БД проекта, но отсутствуют в источнике:\n\n"
                            + conflicts + "\n\nУдалить эти записи из БД проекта?",
                    "Конфликты синхронизации", JOptionPane.YES_NO_OPTION);
            if (del == JOptionPane.YES_OPTION) {
                deleteSql = buildDeleteSql(conflicts);
            }
        }

        final String execSql = (sql + "\n" + deleteSql).trim();
        if (execSql.isEmpty()) {
            statusLabel.setForeground(Ui.TEXT_GRAY);
            statusLabel.setText("Нет изменений для применения.");
            return;
        }
        statusLabel.setForeground(Ui.TEXT_GRAY);
        statusLabel.setText("⏳ Применение изменений...");
        runBtn.setEnabled(false);
        new SwingWorker<List<Map<String, Object>>, Void>() {
            @Override
            protected List<Map<String, Object>> doInBackground() {
                String path = frame.getDbManager().dbPath(projectDb);
                return frame.getDbManager().autoExecuteSqlUpdates(path, projectDb, execSql, "");
            }

            @Override
            protected void done() {
                runBtn.setEnabled(true);
                try {
                    List<Map<String, Object>> res = get();
                    updated = true;
                    statusLabel.setForeground(Ui.PRIMARY);
                    statusLabel.setText("✅ Применено операторов: " + res.size());
                    JOptionPane.showMessageDialog(UpdateProjectDbDialog.this,
                            "БД проекта обновлена. Выполнено операторов: " + res.size());
                    dispose();
                } catch (Exception ex) {
                    statusLabel.setForeground(Ui.ERROR);
                    statusLabel.setText("❌ " + ex.getMessage());
                }
            }
        }.execute();
    }

    /** Извлекает содержимое первого блока ```sql ... ``` или возвращает весь текст. */
    private static String extractSqlBlock(String text) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)```sql\\s*(.*?)```").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        int idx = text.indexOf("###КОНФЛИКТЫ###");
        return idx >= 0 ? text.substring(0, idx).trim() : text.trim();
    }

    /** Извлекает секцию конфликтов после «###КОНФЛИКТЫ###». */
    private static String extractConflicts(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf("###КОНФЛИКТЫ###");
        if (idx < 0) {
            return "";
        }
        String rest = text.substring(idx + "###КОНФЛИКТЫ###".length()).trim();
        // Обрезаем возможный закрывающий блок кода.
        rest = rest.replace("```", "").trim();
        return rest;
    }

    /**
     * Строит SQL-удаления из строк секции конфликтов формата
     * {@code <таблица> | id=<id> | <описание>}. Безопасно пропускает некорректные строки.
     */
    private static String buildDeleteSql(String conflicts) {
        StringBuilder sb = new StringBuilder();
        for (String line : conflicts.split("\\r?\\n")) {
            String t = line.trim().replaceAll("^[-*•]\\s*", "");
            if (t.isEmpty() || t.startsWith("Конфликт") || t.startsWith("(") || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\\|");
            if (parts.length < 2) {
                continue;
            }
            String table = parts[0].trim().replace('`', ' ');
            java.util.regex.Matcher idm = java.util.regex.Pattern
                    .compile("id\\s*=\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(t);
            if (idm.find() && table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                sb.append("DELETE FROM ").append(table)
                  .append(" WHERE id=").append(idm.group(1)).append(";\n");
            }
        }
        return sb.toString();
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL_BOLD);
        l.setForeground(Ui.TEXT_SECONDARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }
}
