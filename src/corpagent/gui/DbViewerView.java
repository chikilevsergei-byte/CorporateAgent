package corpagent.gui;

import corpagent.db.DatabaseManager;
import corpagent.db.SqliteHelper;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
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
 * Просмотр базы данных: обзор таблиц, данные, SQL-запросы, импорт CSV,
 * экспорт SQL, бэкап, журнал операций и простая визуализация (столбчатая диаграмма).
 */
public class DbViewerView extends JPanel {

    private final MainFrame frame;
    private final JLabel titleLabel = new JLabel(" ");
    private final JComboBox<String> tableCombo = new JComboBox<String>();
    private final JTable dataTable = new JTable();
    private final JTextArea sqlArea = new JTextArea(4, 60);
    private final JTextArea logArea = new JTextArea(8, 60);
    private final JLabel statusLabel = new JLabel(" ");

    public DbViewerView(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(Ui.BG);
        setBorder(new EmptyBorder(16, 20, 16, 20));
    }

    public void refresh() {
        removeAll();
        String dbName = frame.getViewingDb();
        if (dbName == null) {
                        JLabel empty = new JLabel("Выберите базу данных в боковой панели.");
            empty.setFont(Ui.FONT_PLAIN);
            empty.setForeground(Ui.TEXT_SECONDARY);
            empty.setHorizontalAlignment(JLabel.CENTER);
            add(empty, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        Map<String, Object> dbInfo = frame.getStore().getDatabase(dbName);
        if (dbInfo == null) {
                        JLabel empty = new JLabel("База данных '" + dbName + "' не найдена.");
            empty.setFont(Ui.FONT_PLAIN);
            empty.setForeground(Ui.TEXT_SECONDARY);
            empty.setHorizontalAlignment(JLabel.CENTER);
            add(empty, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        String dbPath = Json.str(dbInfo, "path");
        titleLabel.setText("База данных: " + dbName + "   |   " + dbPath);
        titleLabel.setFont(Ui.FONT_TITLE);
        titleLabel.setForeground(Ui.TEXT_DARK);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Ui.BG);
        JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleRow.setBackground(Ui.BG);
        titleRow.add(Ui.icon("🗄️", 22));
        titleRow.add(titleLabel);
        top.add(titleRow, BorderLayout.WEST);

        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        topButtons.setBackground(Ui.BG);
                JButton backup = new JButton("Бэкап");
        Ui.styleButton(backup, false);
        Ui.withActionIcon(backup, "🗄️");
        backup.addActionListener(e -> doBackup(dbName, dbPath));
        topButtons.add(backup);
        JButton export = new JButton("Экспорт SQL");
        Ui.styleButton(export, false);
        Ui.withActionIcon(export, "📤");
        export.addActionListener(e -> doExport(dbName, dbPath));
        topButtons.add(export);
        JButton back = new JButton("Назад");
        Ui.styleButton(back, false);
        Ui.withActionIcon(back, "←");
        back.addActionListener(e -> frame.showChat());
        topButtons.add(back);
        top.add(topButtons, BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Ui.BG);

        // --- Таблицы ---
        JPanel tablesPanel = new JPanel(new BorderLayout());
        tablesPanel.setBackground(Ui.BG);
        tablesPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        JLabel tablesTitle = new JLabel("Таблицы");
        tablesTitle.setFont(Ui.FONT_SMALL_BOLD);
        tablesTitle.setForeground(Ui.TEXT_SECONDARY);
        tablesTitle.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        JPanel tablesHeader = new JPanel(new BorderLayout());
        tablesHeader.setBackground(Ui.BG);
        tablesHeader.add(tablesTitle, BorderLayout.NORTH);
        JPanel comboRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        comboRow.setBackground(Ui.BG);
        JLabel tableLbl = new JLabel("Таблица:");
        tableLbl.setFont(Ui.FONT_PLAIN);
        tableLbl.setForeground(Ui.TEXT);
        comboRow.add(tableLbl);
        tableCombo.setFont(Ui.FONT_PLAIN);
        tableCombo.setBackground(Ui.SURFACE);
        comboRow.add(tableCombo);
        JButton loadBtn = new JButton("Показать данные");
        Ui.styleButton(loadBtn, true);
        loadBtn.addActionListener(e -> loadTable(dbPath));
        comboRow.add(loadBtn);
                JButton importBtn = new JButton("Импорт CSV");
        Ui.styleButton(importBtn, false);
        Ui.withActionIcon(importBtn, "📥");
        importBtn.addActionListener(e -> importCsv(dbName, dbPath));
        comboRow.add(importBtn);
        tablesHeader.add(comboRow, BorderLayout.CENTER);
        tablesPanel.add(tablesHeader, BorderLayout.NORTH);
        dataTable.setAutoCreateRowSorter(true);
        tablesPanel.add(new JScrollPane(dataTable), BorderLayout.CENTER);
        center.add(tablesPanel, BorderLayout.CENTER);

        // --- SQL + журнал ---
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBackground(Ui.BG);

        JPanel sqlPanel = new JPanel(new BorderLayout());
        sqlPanel.setBackground(Ui.BG);
        sqlPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        JLabel sqlTitle = new JLabel("SQL-запрос");
        sqlTitle.setFont(Ui.FONT_SMALL_BOLD);
        sqlTitle.setForeground(Ui.TEXT_SECONDARY);
        sqlTitle.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        sqlPanel.add(sqlTitle, BorderLayout.NORTH);
        Ui.styleArea(sqlArea);
        sqlArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        sqlArea.setLineWrap(false);
        sqlPanel.add(new JScrollPane(sqlArea), BorderLayout.CENTER);
                JButton runSql = new JButton("Выполнить");
        Ui.styleButton(runSql, true);
        Ui.withActionIcon(runSql, "▶");
        runSql.addActionListener(e -> runSql(dbName, dbPath));
        JPanel sqlBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        sqlBtnRow.setBackground(Ui.BG);
        sqlBtnRow.add(runSql);
                JButton visualize = new JButton("Визуализировать");
        Ui.styleButton(visualize, false);
        Ui.withActionIcon(visualize, "📊");
        visualize.addActionListener(e -> visualize(dbName, dbPath));
        sqlBtnRow.add(visualize);
        sqlPanel.add(sqlBtnRow, BorderLayout.SOUTH);
        bottom.add(sqlPanel);
        bottom.add(Box.createVerticalStrut(8));

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(Ui.BG);
        logPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        JLabel logTitle = new JLabel("Журнал операций");
        logTitle.setFont(Ui.FONT_SMALL_BOLD);
        logTitle.setForeground(Ui.TEXT_SECONDARY);
        logTitle.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        logPanel.add(logTitle, BorderLayout.NORTH);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        bottom.add(logPanel);

        center.add(bottom, BorderLayout.SOUTH);

        statusLabel.setFont(Ui.FONT_SMALL);
        statusLabel.setForeground(Ui.PRIMARY);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);
        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);

        add(root, BorderLayout.CENTER);

        // Заполняем комбобокс таблиц
        tableCombo.removeAllItems();
        List<String> tables = SqliteHelper.getTables(dbPath);
        for (String t : tables) {
            tableCombo.addItem(t);
        }
        if (!tables.isEmpty()) {
            tableCombo.setSelectedIndex(0);
            loadTable(dbPath);
        } else {
            setTableData(new ArrayList<String>(), new ArrayList<List<Object>>());
        }

        // Журнал
        StringBuilder log = new StringBuilder();
        List<Object> logs = frame.getStore().getDbLogList(dbName);
        for (int i = logs.size() - 1; i >= 0; i--) {
            Map<String, Object> entry = Json.asMap(logs.get(i));
            if (entry != null) {
                log.append(Json.str(entry, "timestamp", "")).append("  ")
                        .append(Json.str(entry, "action", "")).append("  ")
                        .append(Json.str(entry, "details", "")).append("\n");
            }
        }
        logArea.setText(log.length() == 0 ? "(пока нет операций)" : log.toString());

        revalidate();
        repaint();
    }

    private void loadTable(String dbPath) {
        String table = (String) tableCombo.getSelectedItem();
        if (table == null) {
            return;
        }
        List<String> columns = SqliteHelper.getTableColumns(dbPath, table);
        List<List<Object>> rows = SqliteHelper.getTableRows(dbPath, table, 500);
        setTableData(columns, rows);
        statusLabel.setText("Таблица '" + table + "': показано " + rows.size() + " строк.");
    }

    private void setTableData(List<String> columns, List<List<Object>> rows) {
        DefaultTableModel model = new DefaultTableModel(columns.toArray(), 0);
        for (List<Object> row : rows) {
            Object[] arr = new Object[row.size()];
            for (int i = 0; i < row.size(); i++) {
                arr[i] = row.get(i) == null ? "" : row.get(i);
            }
            model.addRow(arr);
        }
        dataTable.setModel(model);
    }

    private void runSql(String dbName, String dbPath) {
        String sql = sqlArea.getText().trim();
        if (sql.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите SQL-запрос.");
            return;
        }
        statusLabel.setText("⏳ Выполнение...");
        final String fSql = sql;
        new SwingWorker<SqliteHelper.QueryResult, Void>() {
            @Override
            protected SqliteHelper.QueryResult doInBackground() {
                return SqliteHelper.executeQuery(dbPath, fSql);
            }

            @Override
            protected void done() {
                try {
                    SqliteHelper.QueryResult r = get();
                    if (r.success) {
                        if (!r.columns.isEmpty()) {
                            setTableData(r.columns, r.rows);
                            statusLabel.setText("✅ Запрос выполнен. Строк: " + r.rows.size());
                        } else {
                            statusLabel.setText("✅ Запрос выполнен. Затронуто строк: " + r.rowsAffected);
                        }
                        frame.getStore().addDbLog(dbName, "SQL-запрос", Utils.truncate(fSql, 100));
                        refresh();
                    } else {
                        statusLabel.setText("❌ Ошибка: " + r.error);
                        JOptionPane.showMessageDialog(DbViewerView.this, "Ошибка SQL:\n" + r.error);
                    }
                } catch (Exception ex) {
                    statusLabel.setText("❌ Ошибка: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void importCsv(String dbName, String dbPath) {
        String table = (String) tableCombo.getSelectedItem();
        if (table == null) {
            JOptionPane.showMessageDialog(this, "Сначала выберите таблицу.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        Ui.styleFileChooser(chooser);
        chooser.setDialogTitle("Выберите CSV-файл для импорта в '" + table + "'");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV-файлы (*.csv)", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        String content = Utils.readFile(file.getAbsolutePath());
        if (content == null) {
            JOptionPane.showMessageDialog(this, "Не удалось прочитать файл.");
            return;
        }
        String[] options = {"Добавить записи (insert)", "Обновить по первому столбцу (update)"};
        int mode = JOptionPane.showOptionDialog(this, "Режим импорта:", "Импорт CSV",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (mode < 0) {
            return;
        }
        String modeStr = mode == 0 ? "insert" : "update";
        Object[] res = frame.getDbManager().importCsvToTable(dbPath, table, content, modeStr);
        int count = ((Number) res[0]).intValue();
        String msg = String.valueOf(res[1]);
        if ("OK".equals(msg)) {
            frame.getStore().addDbLog(dbName, "Импорт CSV", "Таблица '" + table + "', добавлено: " + count);
            statusLabel.setText("✅ Импортировано записей: " + count);
            loadTable(dbPath);
            refresh();
        } else {
            statusLabel.setText("❌ Ошибка импорта: " + msg);
            JOptionPane.showMessageDialog(this, "Ошибка импорта:\n" + msg);
        }
    }

    private void doBackup(String dbName, String dbPath) {
        String path = frame.getDbManager().backupDatabase(dbName, dbPath);
        if (path != null) {
            frame.getStore().addDbLog(dbName, "Бэкап", path);
            statusLabel.setText("✅ Бэкап создан: " + path);
            Toast.success(javax.swing.SwingUtilities.getWindowAncestor(this),
                    "Бэкап создан", path);
            refresh();
        } else {
            statusLabel.setText("❌ Не удалось создать бэкап.");
        }
    }

    private void doExport(String dbName, String dbPath) {
        String sql = SqliteHelper.exportToSql(dbPath);
        if (sql.startsWith("Ошибка")) {
            statusLabel.setText("❌ " + sql);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        Ui.styleFileChooser(chooser);
        chooser.setDialogTitle("Сохранить экспорт SQL");
        chooser.setSelectedFile(new File(dbName + "_export.sql"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            Utils.writeFile(f.getAbsolutePath(), sql);
            frame.getStore().addDbLog(dbName, "Экспорт SQL", f.getAbsolutePath());
            statusLabel.setText("✅ Экспорт сохранён: " + f.getAbsolutePath());
            JOptionPane.showMessageDialog(this, "Экспорт сохранён:\n" + f.getAbsolutePath());
            refresh();
        }
    }

    /**
     * Простая визуализация: пользователь выбирает таблицу и числовую колонку,
     * строится столбчатая диаграмма по первым значениям.
     */
    private void visualize(String dbName, String dbPath) {
        String table = (String) tableCombo.getSelectedItem();
        if (table == null) {
            JOptionPane.showMessageDialog(this, "Сначала выберите таблицу.");
            return;
        }
        List<String> columns = SqliteHelper.getTableColumns(dbPath, table);
        if (columns.isEmpty()) {
            JOptionPane.showMessageDialog(this, "В таблице нет колонок.");
            return;
        }
        String col = (String) JOptionPane.showInputDialog(this,
                "Выберите колонку для визуализации (числовую):", "Визуализация",
                JOptionPane.PLAIN_MESSAGE, null, columns.toArray(), columns.get(0));
        if (col == null) {
            return;
        }
        List<List<Object>> rows = SqliteHelper.getTableRows(dbPath, table, 50);
        List<Double> values = new ArrayList<Double>();
        List<String> labels = new ArrayList<String>();
        int colIdx = columns.indexOf(col);
        for (List<Object> row : rows) {
            if (colIdx >= 0 && colIdx < row.size() && row.get(colIdx) != null) {
                try {
                    double v = Double.parseDouble(String.valueOf(row.get(colIdx)).replace(",", "."));
                    values.add(v);
                    String lbl = row.size() > 0 && row.get(0) != null ? String.valueOf(row.get(0)) : String.valueOf(values.size());
                    labels.add(Utils.truncate(lbl, 12));
                } catch (NumberFormatException e) {
                    // пропускаем нечисловые
                }
            }
        }
        if (values.isEmpty()) {
            JOptionPane.showMessageDialog(this, "В колонке '" + col + "' нет числовых данных.");
            return;
        }
        frame.getStore().addDbLog(dbName, "Визуализация", "Таблица '" + table + "', колонка '" + col + "'");
        ChartDialog dialog = new ChartDialog(frame, "📊 " + table + " — " + col, labels, values);
        dialog.setVisible(true);
        refresh();
    }

    /** Простое диалоговое окно со столбчатой диаграммой (чистый Swing, без зависимостей). */
    private static class ChartDialog extends javax.swing.JDialog {
        ChartDialog(java.awt.Frame owner, String title, List<String> labels, List<Double> values) {
            super(owner, title, true);
            setSize(700, 500);
            setLocationRelativeTo(owner);
            JPanel chart = new JPanel() {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    super.paintComponent(g);
                    int w = getWidth();
                    int h = getHeight();
                    int padL = 60, padR = 20, padT = 30, padB = 60;
                    double max = 1;
                    for (double v : values) {
                        if (v > max) {
                            max = v;
                        }
                    }
                    int chartW = w - padL - padR;
                    int chartH = h - padT - padB;
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, w, h);
                    // сетка
                    g.setColor(new Color(220, 220, 220));
                    for (int i = 0; i <= 5; i++) {
                        int y = padT + chartH - (int) (chartH * (i / 5.0));
                        g.drawLine(padL, y, w - padR, y);
                        g.setColor(Color.DARK_GRAY);
                        g.drawString(String.format("%.0f", max * i / 5.0), 10, y + 4);
                        g.setColor(new Color(220, 220, 220));
                    }
                    int n = values.size();
                    double slot = (double) chartW / n;
                    double barW = slot * 0.6;
                    for (int i = 0; i < n; i++) {
                        double v = values.get(i);
                        int barH = (int) (chartH * (v / max));
                        int x = padL + (int) (i * slot + (slot - barW) / 2);
                        int y = padT + chartH - barH;
                                                g.setColor(Ui.PRIMARY);
                        g.fillRect(x, y, (int) barW, barH);
                        g.setColor(Color.DARK_GRAY);
                        String lbl = i < labels.size() ? labels.get(i) : String.valueOf(i + 1);
                        g.drawString(lbl, x, padT + chartH + 15);
                    }
                }
            };
            chart.setPreferredSize(new Dimension(650, 380));
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(new EmptyBorder(10, 10, 10, 10));
            content.add(chart, BorderLayout.CENTER);
            JButton close = new JButton("Закрыть");
            Ui.stylePrimary(close);
            close.addActionListener(e -> dispose());
            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
            btnRow.add(close);
            content.add(btnRow, BorderLayout.SOUTH);
            setContentPane(content);
        }
    }
}
