package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.data.PresetLoader;
import corpagent.util.Json;
import corpagent.util.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Map;

/**
 * Вкладка «Базы данных» в модальных настройках.
 * Список подключённых БД карточками: иконка, название, тип, host/файл,
 * статус, количество таблиц/записей, toggle вкл/выкл, кнопки ✏️/🗑️.
 * Кнопка «Добавить БД».
 */
public class SettingsPanelDatabases extends JPanel {

    private final MainFrame frame;
    private final DataStore store;

    public SettingsPanelDatabases(MainFrame frame) {
        this.frame = frame;
        this.store = frame.getStore();
        setLayout(new BorderLayout());
        setBackground(Ui.BG);
    }

    public void refresh() {
        removeAll();

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Ui.BG);
        content.setBorder(new EmptyBorder(16, 16, 16, 16));

                JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        JLabel title = new JLabel("🗄️ Базы данных");
        title.setFont(Ui.FONT_TITLE);
        title.setForeground(Ui.TEXT_DARK);
        topRow.add(title, BorderLayout.WEST);
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        topButtons.setOpaque(false);
        JButton createBtn = new JButton("✨ Создать БД из файлов");
        Ui.styleButton(createBtn, true);
        createBtn.addActionListener(e -> openCreateFromFiles());
        topButtons.add(createBtn);
        JButton exportBtn = new JButton("📤 Экспорт БД");
        Ui.styleSecondary(exportBtn);
        exportBtn.addActionListener(e -> exportDatabase());
        topButtons.add(exportBtn);
        JButton importBtn = new JButton("📥 Импорт БД");
        Ui.styleSecondary(importBtn);
        importBtn.addActionListener(e -> importDatabase());
        topButtons.add(importBtn);
        topRow.add(topButtons, BorderLayout.EAST);
        content.add(topRow);
        content.add(Box.createVerticalStrut(4));

                JLabel hint = new JLabel("Подключаются базы данных SQLite (файлы .db). "
                + "Серверные СУБД (PostgreSQL, MySQL и т.п.) не используются.");
        hint.setFont(Ui.FONT_SMALL);
        hint.setForeground(Ui.TEXT_GRAY);
        content.add(hint);
        content.add(Box.createVerticalStrut(6));

        // Вспомогательная кнопка подключения уже существующего файла .db.
        JButton connectBtn = new JButton("➕ Добавить (подключить) БД");
        Ui.styleSecondary(connectBtn);
        connectBtn.addActionListener(e -> addDatabase());
        JPanel connectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        connectRow.setOpaque(false);
        connectRow.add(connectBtn);
        content.add(connectRow);
        content.add(Box.createVerticalStrut(10));

        Map<String, Object> dbs = store.getDatabases();
        Map<String, Object> sqliteDbs = new java.util.LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : dbs.entrySet()) {
            Map<String, Object> v = Json.asMap(e.getValue());
            String type = v == null ? "sqlite" : Json.str(v, "type", "sqlite");
            if ("sqlite".equals(type)) {
                sqliteDbs.put(e.getKey(), e.getValue());
            }
        }
                if (sqliteDbs.isEmpty()) {
            // UI-3.5: пустое состояние списка БД.
            content.add(new EmptyState("🗄️", "Пока нет подключённых БД",
                    "Нажмите «Добавить БД», чтобы подключить первую"));
        } else {
            for (Map.Entry<String, Object> e : sqliteDbs.entrySet()) {
                content.add(dbCard(e.getKey(), Json.asMap(e.getValue())));
                content.add(Box.createVerticalStrut(8));
            }
        }

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private JPanel dbCard(String name, Map<String, Object> db) {
                JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Ui.SURFACE);
        card.setBorder(new Ui.RoundedBorder(Ui.BORDER, Ui.RADIUS_CARD, 1));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        card.setOpaque(true);
        // UI-2.2: мягкий hover-эффект (подсветка фона карточки).
        card.setCursor(new java.awt.Cursor(java.awt.Cursor.DEFAULT_CURSOR));
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                card.setBackground(Ui.HOVER);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                card.setBackground(Ui.SURFACE);
            }
        });

        // Левая часть: иконка + текст
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);
        String type = db == null ? "sqlite" : Json.str(db, "type", "sqlite");
        String icon = typeIcon(type);
        JLabel iconL = Ui.icon(icon, 26);
        iconL.setAlignmentY(Component.TOP_ALIGNMENT);
        left.add(iconL);
        left.add(Box.createHorizontalStrut(10));

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        JLabel nameL = new JLabel(name);
        nameL.setFont(Ui.FONT_BOLD);
        nameL.setForeground(Ui.TEXT_DARK);
        text.add(nameL);

        String typeLabel = typeLabel(type);
        String loc = location(db, type);
        JLabel typeL = new JLabel(typeLabel + (loc.isEmpty() ? "" : "  ·  " + escape(Utils.truncate(loc, 70))));
        typeL.setFont(Ui.FONT_SMALL);
        typeL.setForeground(Ui.TEXT_GRAY);
        text.add(typeL);

        int tables = db == null ? 0 : Json.intVal(db, "tables_count", 0);
        int records = db == null ? 0 : Json.intVal(db, "records_count", 0);
        String desc = db == null ? "" : Json.str(db, "description", "");
        StringBuilder info = new StringBuilder();
        if (!desc.isEmpty()) {
            info.append(escape(Utils.truncate(desc, 90)));
        }
        if (tables > 0 || records > 0) {
            if (info.length() > 0) {
                info.append("  ·  ");
            }
            info.append("Таблиц: ").append(tables).append(", записей: ").append(records);
        }
        if (info.length() > 0) {
            JLabel descL = new JLabel("<html>" + info + "</html>");
            descL.setFont(Ui.FONT_SMALL);
            descL.setForeground(Ui.TEXT_GRAY);
            text.add(descL);
        }

                int usage = db == null ? 0 : Json.intVal(db, "usage_count", 0);
        JLabel usageL = new JLabel("Использований: " + usage);
        usageL.setFont(Ui.FONT_SMALL);
        usageL.setForeground(Ui.PRIMARY_DARK);
        text.add(usageL);

        // UI-2.2: путь к БД отдельной строкой моноширинным шрифтом.
        if (loc != null && !loc.isEmpty()) {
            JLabel pathL = new JLabel(escape(Utils.truncate(loc, 90)));
            pathL.setFont(Ui.FONT_MONO);
            pathL.setForeground(Ui.TEXT_MUTED);
            text.add(pathL);
        }

        left.add(text);
        card.add(left, BorderLayout.CENTER);

        // Правая часть: toggle + кнопки
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
                boolean enabled = db == null || Json.bool(db, "enabled", true);
        // UI-1.2: modern toggle-свитч вместо JCheckBox.
        ToggleSwitch toggle = new ToggleSwitch(enabled);
        toggle.setToolTipText(enabled ? "Включена" : "Выключена");
        toggle.addChangeListener(() -> {
            if (db != null) {
                db.put("enabled", toggle.isSelected());
                store.saveAll();
            }
        });
        right.add(toggle);

        // P1.3: защита пресет-БД от удаления/редактирования
        boolean isPreset = PresetLoader.isPresetDatabase(db);

        JButton edit = new JButton("✏️");
        Ui.styleButton(edit, false);
        edit.setToolTipText("Редактировать");
        edit.addActionListener(e -> editDatabase(name));
        if (isPreset) {
            edit.setEnabled(false);
            edit.setToolTipText("Пресет — только чтение");
        }
        right.add(edit);

        JButton del = new JButton("🗑️");
        Ui.styleButton(del, false);
        del.setToolTipText("Удалить");
        del.addActionListener(e -> deleteDatabase(name));
        if (isPreset) {
            del.setEnabled(false);
            del.setToolTipText("Пресет — нельзя удалить");
        }
        right.add(del);

        // Индикатор preset
        if (isPreset) {
            JLabel presetL = Ui.icon("📌", 14);
            presetL.setFont(Ui.FONT_SMALL);
            presetL.setForeground(Ui.TEXT_MUTED);
            right.add(presetL);
        }

        card.add(right, BorderLayout.EAST);
        return card;
    }

    private String typeIcon(String type) {
        if ("sqlite".equals(type)) {
            return "🗄️";
        }
        if ("postgresql".equals(type)) {
            return "🐘";
        }
        if ("mysql".equals(type)) {
            return "🐬";
        }
        if ("mongodb".equals(type)) {
            return "🍃";
        }
        if ("mssql".equals(type)) {
            return "🪟";
        }
        if ("oracle".equals(type)) {
            return "🔶";
        }
        return "🗄️";
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

    /** Возвращает host:port для серверных БД или путь к файлу для SQLite. */
    private String location(Map<String, Object> db, String type) {
        if (db == null) {
            return "";
        }
        if ("sqlite".equals(type)) {
            return Json.str(db, "file", Json.str(db, "path"));
        }
        if ("mongodb".equals(type)) {
            return Json.str(db, "connection_string");
        }
        String host = Json.str(db, "host");
        if (host.isEmpty()) {
            return "";
        }
        int port = Json.intVal(db, "port", 0);
        return port > 0 ? host + ":" + port : host;
    }

        private void addDatabase() {
        DbConnectDialog dlg = new DbConnectDialog(frame, store, null);
        dlg.setVisible(true);
        if (dlg.isSaved()) {
            refresh();
            frame.refreshSidebar();
        }
    }

    /** Открывает окно создания новой БД из загруженных файлов. */
    private void openCreateFromFiles() {
        DbCreateFromFilesDialog dlg = new DbCreateFromFilesDialog(frame, store);
        dlg.setVisible(true);
        if (dlg.isSaved()) {
            refresh();
            frame.refreshSidebar();
        }
    }

    private void editDatabase(String name) {
        DbConnectDialog dlg = new DbConnectDialog(frame, store, name);
        dlg.setVisible(true);
        if (dlg.isSaved()) {
            refresh();
            frame.refreshSidebar();
        }
    }

        private void deleteDatabase(String name) {
            int c = JOptionPane.showConfirmDialog(this, "Удалить подключение '" + name + "'?\n"
                            + "Файл базы данных и журнал операций будут удалены безвозвратно.",
                    "Подтверждение", JOptionPane.YES_NO_OPTION);
            if (c != JOptionPane.YES_OPTION) {
                return;
            }
            Map<String, Object> db = Json.asMap(store.getDatabases().get(name));
            String file = db == null ? "" : Json.str(db, "file", Json.str(db, "path"));
            store.removeDatabase(name);
            store.removeDbLogs(name);
            store.saveAll();
            if (!file.trim().isEmpty()) {
                Utils.delete(file.trim());
            }
            refresh();
            frame.refreshSidebar();
        }

        private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ==================== ЭКСПОРТ/ИМПОРТ БД (R-P1.6) ====================

    /** Экспорт выбранной БД в ZIP-архив (.db + метаданные). */
    private void exportDatabase() {
        // Находим первую непресет-БД для экспорта
        String dbName = null;
        for (Map.Entry<String, Object> e : store.getDatabases().entrySet()) {
            Map<String, Object> db = Json.asMap(e.getValue());
            if (db != null && !PresetLoader.isPresetDatabase(db)) {
                dbName = e.getKey();
                break;
            }
        }
        if (dbName == null) {
            JOptionPane.showMessageDialog(this, "Нет доступных для экспорта БД.");
            return;
        }
        Map<String, Object> db = Json.asMap(store.getDatabases().get(dbName));
        String dbPath = Json.str(db, "file", Json.str(db, "path"));
        if (dbPath.isEmpty() || !Utils.exists(dbPath)) {
            JOptionPane.showMessageDialog(this, "Файл БД не найден: " + dbName);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Экспорт БД: " + dbName);
        chooser.setSelectedFile(new File(dbName + ".zip"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File outFile = chooser.getSelectedFile();
        try {
            exportDbToZip(dbPath, db, outFile);
            JOptionPane.showMessageDialog(this, "БД '" + dbName + "' экспортирована в:\n" + outFile.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Ошибка экспорта:\n" + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void exportDbToZip(String dbPath, Map<String, Object> db, File outFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {
            // Добавляем .db-файл
            File dbFile = new File(dbPath);
            byte[] buffer = new byte[4096];
            try (InputStream is = new FileInputStream(dbFile)) {
                ZipEntry ze = new ZipEntry(dbFile.getName());
                zos.putNextEntry(ze);
                int len;
                while ((len = is.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                zos.closeEntry();
            }
            // Добавляем метаданные
            Map<String, Object> meta = Json.newObject();
            meta.put("name", Json.str(db, "name", ""));
            meta.put("description", Json.str(db, "description", ""));
            meta.put("type", Json.str(db, "type", "sqlite"));
            meta.put("schema", Json.str(db, "schema", ""));
            meta.put("exported_at", Utils.nowIso());
            ZipEntry metaEntry = new ZipEntry("metadata.json");
            zos.putNextEntry(metaEntry);
            zos.write(Json.stringify(meta).getBytes("UTF-8"));
            zos.closeEntry();
        }
    }

    /** Импорт БД из ZIP-архива. */
    private void importDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Импорт БД из ZIP");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ZIP-архивы", "zip"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File zipFile = chooser.getSelectedFile();
        try {
            importDbFromZip(zipFile);
            refresh();
            frame.refreshSidebar();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Ошибка импорта:\n" + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    @SuppressWarnings("unchecked")
    private void importDbFromZip(File zipFile) throws Exception {
        String tempDir = Utils.getTempDir() + java.io.File.separator + "corpagent_import_" + System.currentTimeMillis();
        Utils.mkdirs(tempDir);
        String[] dbFileName = {null};
        String[] metadataJson = {null};
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(tempDir, entry.getName());
                if (entry.isDirectory()) {
                    Utils.mkdirs(outFile.getAbsolutePath());
                } else {
                    Utils.mkdirs(outFile.getParent());
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                    if (entry.getName().endsWith(".db")) {
                        dbFileName[0] = outFile.getAbsolutePath();
                    } else if ("metadata.json".equals(entry.getName())) {
                        metadataJson[0] = Utils.readFile(outFile.getAbsolutePath());
                    }
                }
                zis.closeEntry();
            }
        }
        if (dbFileName[0] == null) {
            throw new Exception("В архиве не найден .db-файл");
        }
        // Парсим метаданные
        Map<String, Object> meta = Json.newObject();
        if (metadataJson[0] != null && !metadataJson[0].trim().isEmpty()) {
            Object parsed = Json.parse(metadataJson[0]);
            meta = Json.asMap(parsed) != null ? Json.asMap(parsed) : Json.newObject();
        }
        String dbName = Json.str(meta, "name", zipFile.getName().replace(".zip", ""));
        String description = Json.str(meta, "description", "");
        // Копируем .db в databases/
        String destPath = frame.getDbManager().dbPath(dbName);
        Utils.copyFile(dbFileName[0], destPath);
        // Регистрируем в хранилище
        Map<String, Object> db = Json.newObject();
        db.put("path", destPath);
        db.put("file", destPath);
        db.put("description", description);
        db.put("type", "sqlite");
        db.put("enabled", true);
        db.put("created", Utils.nowIso());
        db.put("usage_count", 0);
        store.putDatabase(dbName, db);
        store.saveAll();
        Utils.deleteDir(tempDir);
    }
}
