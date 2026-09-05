package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Диалог создания/редактирования инструмента.
 * Поля: название, иконка, тип (HTTP API / Python / Shell / SQL / Custom),
 * endpoint/команда, метод, headers, тело JSON, описание для LLM, параметры,
 * пример запроса. Кнопка «Тестировать» проверяет подключение (для HTTP — реальный запрос).
 */
public class ToolEditDialog extends JDialog {

    private static final String[] EMOJIS = {
            "🔧", "🌐", "📡", "🖥️", "🐍", "⚙️", "🔌", "📨", "🗄️", "🧩",
            "📊", "🔍", "🔄", "📤", "📥", "🛠️", "☁️", "🤖", "📁", "🔑"
    };

    private static final String[] TYPES = {"http", "python", "shell", "sql", "custom"};
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};

    private final DataStore store;
    private final String editingName;

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<String> iconCombo = new JComboBox<String>(EMOJIS);
    private final JComboBox<String> typeCombo = new JComboBox<String>(TYPES);
    private final JTextField endpointField = new JTextField(40);
    private final JComboBox<String> methodCombo = new JComboBox<String>(METHODS);
    private final JTextArea headersArea = new JTextArea(3, 40);
    private final JTextArea bodyArea = new JTextArea(3, 40);
    private final JTextArea descArea = new JTextArea(3, 40);
    private final JTextField paramsField = new JTextField(40);
    private final JTextArea exampleArea = new JTextArea(2, 40);

    private boolean saved = false;

    public ToolEditDialog(Frame owner, DataStore store, String editingName) {
        super(owner, editingName == null ? "➕ Новый инструмент" : "✏️ Редактировать инструмент", true);
        this.store = store;
        this.editingName = editingName;

        setSize(600, 640);
        setLocationRelativeTo(owner);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(16, 16, 16, 16));

        form.add(fieldLabel("Название инструмента"));
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(nameField);
        form.add(Box.createVerticalStrut(10));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row1.setOpaque(false);
        row1.add(labeled("Иконка", iconCombo, 120));
        row1.add(labeled("Тип", typeCombo, 120));
        form.add(row1);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Endpoint / команда (для http — URL, для python/shell — команда/скрипт)"));
        endpointField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(endpointField);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Метод HTTP (для типа http)"));
        methodCombo.setMaximumSize(new Dimension(120, 30));
        form.add(methodCombo);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Headers (формат: Имя: значение, по одному на строку)"));
        headersArea.setLineWrap(true);
        headersArea.setWrapStyleWord(true);
        form.add(scroll(headersArea, 60));
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Тело запроса JSON (для POST/PUT, опционально)"));
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        form.add(scroll(bodyArea, 60));
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Описание для LLM (что делает инструмент, когда его вызывать)"));
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        form.add(scroll(descArea, 60));
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Параметры (имя:тип:обязательность, через запятую), напр. project_key:text:yes"));
        paramsField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(paramsField);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Пример запроса (опционально)"));
        exampleArea.setLineWrap(true);
        exampleArea.setWrapStyleWord(true);
        form.add(scroll(exampleArea, 50));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(229, 231, 235)));
        JButton test = new JButton("🧪 Тестировать");
        Ui.styleButton(test, false);
        test.setToolTipText("Проверить подключение (для http — реальный запрос)");
        test.addActionListener(e -> testTool());
        buttons.add(test);
        JButton cancel = new JButton("Отмена");
        Ui.styleButton(cancel, false);
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        JButton save = new JButton("Сохранить");
        Ui.styleButton(save, true);
        save.setIcon(Ui.iconImage("💾", 16));
        save.setIconTextGap(6);
        save.addActionListener(e -> saveTool());
        buttons.add(save);

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        if (editingName != null) {
            loadExisting();
        }
    }

    private void loadExisting() {
        Map<String, Object> tool = Json.asMap(store.getTools().get(editingName));
        if (tool == null) {
            return;
        }
        nameField.setText(editingName);
        iconCombo.setSelectedItem(Json.str(tool, "icon", "🔧"));
        typeCombo.setSelectedItem(Json.str(tool, "type", "http"));
        endpointField.setText(Json.str(tool, "endpoint", Json.str(tool, "code")));
        methodCombo.setSelectedItem(Json.str(tool, "method", "GET"));
        headersArea.setText(formatHeaders(Json.arr(tool, "headers")));
        bodyArea.setText(Json.str(tool, "body"));
        descArea.setText(Json.str(tool, "description"));
        paramsField.setText(formatParams(Json.arr(tool, "params")));
        exampleArea.setText(Json.str(tool, "example"));
    }

    private void saveTool() {
        String name = nameField.getText().trim();
        String desc = descArea.getText().trim();
        if (name.isEmpty() || desc.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Название и описание обязательны.");
            return;
        }
        Map<String, Object> old = null;
        if (editingName != null && !editingName.equals(name)) {
            old = Json.asMap(store.getTools().get(editingName));
            store.removeTool(editingName);
        } else if (editingName != null) {
            old = Json.asMap(store.getTools().get(editingName));
        }

        Map<String, Object> tool = Json.newObject();
        tool.put("icon", iconCombo.getSelectedItem() == null ? "🔧" : iconCombo.getSelectedItem().toString());
        tool.put("type", typeCombo.getSelectedItem() == null ? "http" : typeCombo.getSelectedItem().toString());
        tool.put("endpoint", endpointField.getText().trim());
        tool.put("method", methodCombo.getSelectedItem() == null ? "GET" : methodCombo.getSelectedItem().toString());
        tool.put("headers", parseHeaders(headersArea.getText()));
        tool.put("body", bodyArea.getText());
        tool.put("description", desc);
        tool.put("params", parseParams(paramsField.getText()));
        tool.put("example", exampleArea.getText());
        tool.put("enabled", old == null ? true : Json.bool(old, "enabled", true));
        tool.put("usage_count", old == null ? 0 : Json.intVal(old, "usage_count", 0));
        tool.put("created", old == null ? Utils.nowIso() : Json.str(old, "created", Utils.nowIso()));
        tool.put("updated", Utils.nowIso());

        store.putTool(name, tool);
        store.saveAll();
        saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }

    // ==================== ТЕСТИРОВАНИЕ ====================

    private void testTool() {
        String type = typeCombo.getSelectedItem() == null ? "http" : typeCombo.getSelectedItem().toString();
        String endpoint = endpointField.getText().trim();
        if (endpoint.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите endpoint/команду для тестирования.");
            return;
        }
        if ("http".equals(type)) {
            testHttp(endpoint);
        } else {
            JOptionPane.showMessageDialog(this,
                    "Исполнение инструментов типа '" + type + "' будет доступно на этапе function calling.\n"
                            + "Сейчас можно проверить только HTTP-инструменты.");
        }
    }

    private void testHttp(String endpoint) {
        final String urlText = endpoint.startsWith("http") ? endpoint : "http://" + endpoint;
        final String method = methodCombo.getSelectedItem() == null ? "GET" : methodCombo.getSelectedItem().toString();
        final String body = bodyArea.getText().trim();
        final String headersText = headersArea.getText();

        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    result = httpCall(urlText, method, body, headersText);
                } catch (Exception ex) {
                    result = "Ошибка: " + ex.getMessage();
                }
                final String msg = result;
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        JOptionPane.showMessageDialog(ToolEditDialog.this,
                                "Результат теста (" + method + " " + urlText + "):\n\n" + msg);
                    }
                });
            }
        }).start();
    }

    private String httpCall(String urlText, String method, String body, String headersText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setInstanceFollowRedirects(true);

        if (headersText != null && !headersText.trim().isEmpty()) {
            for (String line : headersText.split("\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    conn.setRequestProperty(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
        }

        if (body != null && !body.isEmpty()
                && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
        }

        int code = conn.getResponseCode();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line).append('\n');
        }
        in.close();
        conn.disconnect();
        String text = sb.toString().trim();
        if (text.length() > 2000) {
            text = text.substring(0, 2000) + "\n... (обрезано)";
        }
        return "HTTP " + code + "\n" + text;
    }

    // ==================== ПАРАМЕТРЫ / HEADERS ====================

    private String formatParams(Object paramsObj) {
        if (!(paramsObj instanceof java.util.List)) {
            return "";
        }
        java.util.List<?> list = (java.util.List<?>) paramsObj;
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            Map<String, Object> p = Json.asMap(o);
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Json.str(p, "name", ""));
            sb.append(":").append(Json.str(p, "type", "text"));
            sb.append(":").append(Json.bool(p, "required", false) ? "yes" : "no");
        }
        return sb.toString();
    }

    private java.util.List<Object> parseParams(String text) {
        java.util.List<Object> result = new java.util.ArrayList<Object>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        String[] parts = text.split(",");
        for (String part : parts) {
            String[] seg = part.trim().split(":");
            if (seg.length == 0 || seg[0].trim().isEmpty()) {
                continue;
            }
            Map<String, Object> p = Json.newObject();
            p.put("name", seg[0].trim());
            p.put("type", seg.length > 1 && !seg[1].trim().isEmpty() ? seg[1].trim() : "text");
            p.put("required", seg.length > 2 && "yes".equalsIgnoreCase(seg[2].trim()));
            result.add(p);
        }
        return result;
    }

    private String formatHeaders(Object headersObj) {
        if (!(headersObj instanceof java.util.List)) {
            return "";
        }
        java.util.List<?> list = (java.util.List<?>) headersObj;
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            Map<String, Object> h = Json.asMap(o);
            if (h == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(Json.str(h, "name", "")).append(": ").append(Json.str(h, "value", ""));
        }
        return sb.toString();
    }

    private java.util.List<Object> parseHeaders(String text) {
        java.util.List<Object> result = new java.util.ArrayList<Object>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        for (String line : text.split("\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            Map<String, Object> h = Json.newObject();
            h.put("name", line.substring(0, idx).trim());
            h.put("value", line.substring(idx + 1).trim());
            result.add(h);
        }
        return result;
    }

    // ==================== ХЕЛПЕРЫ ====================

    private JPanel labeled(String text, JComboBox<String> combo, int width) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        l.setForeground(Ui.TEXT_GRAY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
        combo.setMaximumSize(new Dimension(width, 30));
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(combo);
        return p;
    }

    private JScrollPane scroll(JTextArea area, int height) {
        area.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(540, height));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        return sp;
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        l.setForeground(Ui.TEXT_GRAY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
