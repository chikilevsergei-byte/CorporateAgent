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
import java.util.Map;

/**
 * Диалог создания/редактирования навыка.
 * Поля: название, иконка (выбор эмодзи), описание, системный промпт,
 * пример запроса, параметры (имя/тип/обязательность).
 */
public class SkillEditDialog extends JDialog {

    private static final String[] EMOJIS = {
            "🧠", "📊", "📈", "📉", "🗂️", "📋", "✅", "⚠️", "🚀", "💡",
            "🛡️", "💰", "👥", "⏱️", "🎯", "📅", "🔍", "🧮", "📝", "🏗️"
    };

    private final DataStore store;
    private final String editingName;

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<String> iconCombo = new JComboBox<String>(EMOJIS);
    private final JTextArea descArea = new JTextArea(3, 40);
    private final JTextArea promptArea = new JTextArea(6, 40);
    private final JTextArea exampleArea = new JTextArea(2, 40);
    private final JTextField paramsField = new JTextField(40);

    private boolean saved = false;

    public SkillEditDialog(Frame owner, DataStore store, String editingName) {
        super(owner, editingName == null ? "➕ Новый навык" : "✏️ Редактировать навык", true);
        this.store = store;
        this.editingName = editingName;

        setSize(560, 560);
        setLocationRelativeTo(owner);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(16, 16, 16, 16));

        form.add(fieldLabel("Название навыка"));
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(nameField);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Иконка"));
        iconCombo.setMaximumSize(new Dimension(120, 30));
        form.add(iconCombo);
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Описание (для списка)"));
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        form.add(scroll(descArea, 70));
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Системный промпт (инструкция для агента)"));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        form.add(scroll(promptArea, 120));
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Пример запроса (опционально)"));
        exampleArea.setLineWrap(true);
        exampleArea.setWrapStyleWord(true);
        form.add(scroll(exampleArea, 60));
        form.add(Box.createVerticalStrut(10));

        form.add(fieldLabel("Параметры (имя:тип:обязательность, через запятую), напр. scope:text:yes"));
        paramsField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(paramsField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(229, 231, 235)));
        JButton cancel = new JButton("Отмена");
        Ui.styleButton(cancel, false);
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        JButton save = new JButton("Сохранить");
        Ui.styleButton(save, true);
        save.setIcon(Ui.iconImage("💾", 16));
        save.setIconTextGap(6);
        save.addActionListener(e -> saveSkill());
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
        Map<String, Object> skill = Json.asMap(store.getSkills().get(editingName));
        if (skill == null) {
            return;
        }
        nameField.setText(editingName);
        iconCombo.setSelectedItem(Json.str(skill, "icon", "🧠"));
        descArea.setText(Json.str(skill, "description"));
        promptArea.setText(Json.str(skill, "prompt", Json.str(skill, "code")));
        exampleArea.setText(Json.str(skill, "example"));
        paramsField.setText(formatParams(Json.arr(skill, "params")));
    }

    private void saveSkill() {
        String name = nameField.getText().trim();
        String desc = descArea.getText().trim();
        if (name.isEmpty() || desc.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Название и описание обязательны.");
            return;
        }
        Map<String, Object> old = null;
        if (editingName != null && !editingName.equals(name)) {
            old = Json.asMap(store.getSkills().get(editingName));
            store.removeSkill(editingName);
        } else if (editingName != null) {
            old = Json.asMap(store.getSkills().get(editingName));
        }

        Map<String, Object> skill = Json.newObject();
        skill.put("icon", iconCombo.getSelectedItem() == null ? "🧠" : iconCombo.getSelectedItem().toString());
        skill.put("description", desc);
        skill.put("prompt", promptArea.getText());
        skill.put("example", exampleArea.getText());
        skill.put("params", parseParams(paramsField.getText()));
        skill.put("enabled", old == null ? true : Json.bool(old, "enabled", true));
        skill.put("usage_count", old == null ? 0 : Json.intVal(old, "usage_count", 0));
        skill.put("created", old == null ? Utils.nowIso() : Json.str(old, "created", Utils.nowIso()));
        skill.put("updated", Utils.nowIso());

        store.putSkill(name, skill);
        store.saveAll();
        saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }

    // ==================== ПАРАМЕТРЫ ====================

    /** Преобразует список параметров [{name,type,required}] в строку "name:type:yes". */
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

    /** Преобразует строку "name:type:yes, name2:text:no" в список параметров. */
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

    // ==================== ХЕЛПЕРЫ ====================

    private JScrollPane scroll(JTextArea area, int height) {
        area.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(500, height));
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
