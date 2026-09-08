package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
        setBackground(Color.WHITE);
    }

    public void refresh() {
        removeAll();

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Color.WHITE);
        content.setBorder(new EmptyBorder(16, 16, 16, 16));

                JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        JLabel title = new JLabel("🗄️ Базы данных");
        title.setFont(Ui.FONT_TITLE);
        title.setForeground(Ui.TEXT_DARK);
        topRow.add(title, BorderLayout.WEST);
        JButton createBtn = new JButton("✨ Создать БД из файлов");
        Ui.styleButton(createBtn, true);
        createBtn.addActionListener(e -> openCreateFromFiles());
        topRow.add(createBtn, BorderLayout.EAST);
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
            JLabel empty = new JLabel("Пока нет подключённых БД. Нажмите «Добавить БД», чтобы подключить первую.");
            empty.setFont(Ui.FONT_PLAIN);
            empty.setForeground(Ui.TEXT_GRAY);
            content.add(empty);
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
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        card.setOpaque(false);

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
        nameL.setFont(new Font("Segoe UI", Font.BOLD, 14));
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

        left.add(text);
        card.add(left, BorderLayout.CENTER);

        // Правая часть: toggle + кнопки
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        boolean enabled = db == null || Json.bool(db, "enabled", true);
        JCheckBox toggle = new JCheckBox("вкл", enabled);
        toggle.setOpaque(false);
        toggle.setFont(Ui.FONT_SMALL);
        toggle.addActionListener(ev -> {
            if (db != null) {
                db.put("enabled", toggle.isSelected());
                store.saveAll();
            }
        });
        right.add(toggle);

        JButton edit = new JButton("✏️");
        Ui.styleButton(edit, false);
        edit.setToolTipText("Редактировать");
        edit.addActionListener(e -> editDatabase(name));
        right.add(edit);

        JButton del = new JButton("🗑️");
        Ui.styleButton(del, false);
        del.setToolTipText("Удалить");
        del.addActionListener(e -> deleteDatabase(name));
        right.add(del);

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
        return s == null ? "" : s.replace("&", "&").replace("<", "<").replace(">", ">");
    }
}
