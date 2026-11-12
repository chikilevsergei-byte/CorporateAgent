package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.data.PresetLoader;
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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;

/**
 * Вкладка «Навыки» в модальных настройках.
 * Двухколоночный менеджер: слева список навыков (40%), справа форма
 * создания/редактирования (60%).
 */
public class SettingsPanelSkills extends JPanel {

    private final MainFrame frame;
    private final DataStore store;

    // Форма
    private final JTextField nameField = new JTextField();
    private final JTextArea descArea = new JTextArea(4, 20);
    private final JTextArea codeArea = new JTextArea(6, 20);
    private final JCheckBox enabledCheck = new JCheckBox("Включён");
    private String editingName = null;

    public SettingsPanelSkills(MainFrame frame) {
        this.frame = frame;
        this.store = frame.getStore();
        setLayout(new BorderLayout());
        setBackground(Ui.SURFACE);
    }

    public void refresh() {
        removeAll();

        // Заголовок
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(16, 20, 8, 20));
        JLabel title = new JLabel("🧠 Навыки");
        title.setFont(Ui.FONT_TITLE);
        title.setForeground(Ui.TEXT_DARK);
        header.add(title, BorderLayout.WEST);
        add(header, BorderLayout.NORTH);

        // Двухколоночная область
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(4, 20, 16, 20));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;

        // Левая колонка: список (40%)
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.4;
        gbc.insets = new Insets(0, 0, 0, 12);
        body.add(buildListPanel(), gbc);

        // Правая колонка: форма (60%)
        gbc.gridx = 1;
        gbc.weightx = 0.6;
        gbc.insets = new Insets(0, 0, 0, 0);
        body.add(buildFormPanel(), gbc);

        add(body, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ==================== ЛЕВАЯ КОЛОНКА: СПИСОК ====================

    private JPanel buildListPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Ui.SURFACE);
        wrapper.setBorder(new Ui.RoundedBorder(Ui.BORDER, Ui.RADIUS_CARD, 1));
        wrapper.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(12, 14, 8, 14));
        JLabel listTitle = new JLabel("Список навыков");
        listTitle.setFont(Ui.FONT_SMALL_BOLD);
        listTitle.setForeground(Ui.TEXT_SECONDARY);
        top.add(listTitle, BorderLayout.WEST);
        wrapper.add(top, BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);

        Map<String, Object> skills = store.getSkills();
                if (skills.isEmpty()) {
            // UI-3.5: пустое состояние списка навыков.
            list.add(new EmptyState("🧠", "Навыков пока нет",
                    "Нажмите «Новый навык», чтобы добавить первый"));
        } else {
            for (Map.Entry<String, Object> e : skills.entrySet()) {
                list.add(skillRow(e.getKey(), Json.asMap(e.getValue())));
                list.add(Box.createVerticalStrut(4));
            }
        }

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        wrapper.add(scroll, BorderLayout.CENTER);

        // Кнопка добавления внизу списка
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        foot.setOpaque(false);
        foot.setBorder(new EmptyBorder(8, 14, 12, 14));
        JButton addBtn = new JButton("➕ Добавить навык");
        Ui.stylePrimary(addBtn);
        addBtn.addActionListener(e -> startNew());
        foot.add(addBtn);
        wrapper.add(foot, BorderLayout.SOUTH);

        return wrapper;
    }

    private JPanel skillRow(String name, Map<String, Object> skill) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 14, 6, 14));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        row.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

        String icon = skill == null ? "🧠" : Json.str(skill, "icon", "🧠");
        JLabel iconL = Ui.icon(icon, 18);
        JLabel nameL = new JLabel(name);
        nameL.setFont(Ui.FONT_PLAIN);
        nameL.setForeground(Ui.TEXT);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(iconL);
        left.add(nameL);

        boolean enabled = skill == null || Json.bool(skill, "enabled", true);
        JLabel stateL = new JLabel(enabled ? "●" : "○");
        stateL.setFont(Ui.FONT_SMALL);
        stateL.setForeground(enabled ? Ui.SUCCESS : Ui.TEXT_MUTED);

        // Индикатор preset (P1.1)
        boolean isPreset = skill != null && PresetLoader.isPresetSkill(skill);
        if (isPreset) {
            JLabel presetL = Ui.icon("📌", 14);
            presetL.setFont(Ui.FONT_SMALL);
            presetL.setForeground(Ui.TEXT_MUTED);
            left.add(presetL);
        }

        row.add(left, BorderLayout.CENTER);
        row.add(stateL, BorderLayout.EAST);

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                loadForEdit(name);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                row.setBackground(Ui.HOVER);
                row.setOpaque(true);
                row.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                row.setOpaque(false);
                row.repaint();
            }
        });
        return row;
    }

    // ==================== ПРАВАЯ КОЛОНКА: ФОРМА ====================

    private JPanel buildFormPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Ui.SURFACE);
        wrapper.setBorder(new Ui.RoundedBorder(Ui.BORDER, Ui.RADIUS_CARD, 1));
        wrapper.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(new EmptyBorder(12, 16, 8, 16));
        JLabel formTitle = new JLabel("Добавить / редактировать навык");
        formTitle.setFont(Ui.FONT_HEADER);
        formTitle.setForeground(Ui.TEXT_DARK);
        top.add(formTitle, BorderLayout.WEST);
        wrapper.add(top, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(4, 16, 8, 16));

        form.add(fieldLabel("Название навыка"));
        form.add(Box.createVerticalStrut(5));
        Ui.styleField(nameField);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        form.add(nameField);
        form.add(Box.createVerticalStrut(12));

        form.add(fieldLabel("Описание"));
        form.add(Box.createVerticalStrut(5));
        Ui.styleArea(descArea);
        descArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        form.add(new JScrollPane(descArea));
        form.add(Box.createVerticalStrut(12));

        form.add(fieldLabel("Код/шаблон (опционально)"));
        form.add(Box.createVerticalStrut(5));
        Ui.styleArea(codeArea);
        codeArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        form.add(new JScrollPane(codeArea));
        form.add(Box.createVerticalStrut(12));

        enabledCheck.setFont(Ui.FONT_PLAIN);
        enabledCheck.setOpaque(false);
        enabledCheck.setSelected(true);
        form.add(enabledCheck);

        wrapper.add(form, BorderLayout.CENTER);

        // Кнопки
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.setBorder(new EmptyBorder(4, 16, 14, 16));

        JButton clear = new JButton("Очистить");
        Ui.styleSecondary(clear);
        clear.addActionListener(e -> startNew());
        buttons.add(clear);

        JButton del = new JButton("Удалить");
        Ui.styleDanger(del);
        del.addActionListener(e -> deleteCurrent());
        buttons.add(del);

        JButton save = new JButton("Сохранить");
        Ui.stylePrimary(save);
        save.addActionListener(e -> saveSkill());
        buttons.add(save);

        wrapper.add(buttons, BorderLayout.SOUTH);
        return wrapper;
    }

    // ==================== ДЕЙСТВИЯ ====================

    private void startNew() {
        editingName = null;
        nameField.setText("");
        descArea.setText("");
        codeArea.setText("");
        enabledCheck.setSelected(true);
        nameField.requestFocus();
    }

    private void loadForEdit(String name) {
        Map<String, Object> skill = Json.asMap(store.getSkills().get(name));
        editingName = name;
        nameField.setText(name);
        descArea.setText(skill == null ? "" : Json.str(skill, "description"));
        codeArea.setText(skill == null ? "" : Json.str(skill, "code"));
        enabledCheck.setSelected(skill == null || Json.bool(skill, "enabled", true));
        nameField.requestFocus();
    }

    private void deleteCurrent() {
        if (editingName == null) {
            JOptionPane.showMessageDialog(this, "Выберите навык в списке слева.");
            return;
        }
        Map<String, Object> skill = Json.asMap(store.getSkills().get(editingName));
        // P1.1: защита пресет-навыков от удаления
        if (PresetLoader.isPresetSkill(skill)) {
            Toast.warning(javax.swing.SwingUtilities.getWindowAncestor(this),
                    "Пресет", "Этот навык предустановлен и не может быть удалён.");
            return;
        }
        int c = JOptionPane.showConfirmDialog(this, "Удалить навык '" + editingName + "'?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);
        if (c == JOptionPane.YES_OPTION) {
            store.removeSkill(editingName);
            store.saveAll();
            startNew();
            refresh();
            frame.refreshSidebar();
        }
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
        skill.put("description", desc);
        skill.put("code", codeArea.getText());
        skill.put("enabled", enabledCheck.isSelected());
        skill.put("created", old == null ? Utils.nowIso() : Json.str(old, "created", Utils.nowIso()));
        skill.put("updated", Utils.nowIso());
        store.putSkill(name, skill);
        store.saveAll();
        startNew();
        refresh();
        frame.refreshSidebar();
    }

    // ==================== ХЕЛПЕРЫ ====================

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL_BOLD);
        l.setForeground(Ui.TEXT_SECONDARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
