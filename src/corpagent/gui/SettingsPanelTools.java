package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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
 * Вкладка «Инструменты» в модальных настройках.
 * Двухколоночный менеджер: слева список инструментов (40%), справа форма
 * создания/редактирования (60%).
 */
public class SettingsPanelTools extends JPanel {

    private final MainFrame frame;
    private final DataStore store;

    // Форма
    private final JTextField nameField = new JTextField();
    private final JComboBox<String> typeCombo = new JComboBox<String>(new String[]{
            "http", "python", "shell", "sql"});
    private final JTextArea endpointArea = new JTextArea(3, 20);
    private final JTextArea descArea = new JTextArea(4, 20);
    private final JCheckBox enabledCheck = new JCheckBox("Включён");
    private String editingName = null;

    public SettingsPanelTools(MainFrame frame) {
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
        JLabel title = new JLabel("🔧 Инструменты");
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
        JLabel listTitle = new JLabel("Список инструментов");
        listTitle.setFont(Ui.FONT_SMALL_BOLD);
        listTitle.setForeground(Ui.TEXT_SECONDARY);
        top.add(listTitle, BorderLayout.WEST);
        wrapper.add(top, BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);

        Map<String, Object> tools = store.getTools();
        if (tools.isEmpty()) {
            JLabel empty = new JLabel("Инструментов пока нет.");
            empty.setFont(Ui.FONT_SMALL);
            empty.setForeground(Ui.TEXT_MUTED);
            empty.setBorder(new EmptyBorder(8, 14, 8, 14));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(empty);
        } else {
            for (Map.Entry<String, Object> e : tools.entrySet()) {
                list.add(toolRow(e.getKey(), Json.asMap(e.getValue())));
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
        JButton addBtn = new JButton("➕ Добавить инструмент");
        Ui.stylePrimary(addBtn);
        addBtn.addActionListener(e -> startNew());
        foot.add(addBtn);
        wrapper.add(foot, BorderLayout.SOUTH);

        return wrapper;
    }

    private JPanel toolRow(String name, Map<String, Object> tool) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(6, 14, 6, 14));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        row.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

        String icon = tool == null ? "🔧" : Json.str(tool, "icon", "🔧");
        JLabel iconL = Ui.icon(icon, 18);
        JLabel nameL = new JLabel(name);
        nameL.setFont(Ui.FONT_PLAIN);
        nameL.setForeground(Ui.TEXT);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(iconL);
        left.add(nameL);

        boolean enabled = tool == null || Json.bool(tool, "enabled", true);
        JLabel stateL = new JLabel(enabled ? "●" : "○");
        stateL.setFont(Ui.FONT_SMALL);
        stateL.setForeground(enabled ? Ui.SUCCESS : Ui.TEXT_MUTED);

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
        JLabel formTitle = new JLabel("Добавить / редактировать инструмент");
        formTitle.setFont(Ui.FONT_HEADER);
        formTitle.setForeground(Ui.TEXT_DARK);
        top.add(formTitle, BorderLayout.WEST);
        wrapper.add(top, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(4, 16, 8, 16));

        form.add(fieldLabel("Название инструмента"));
        form.add(Box.createVerticalStrut(5));
        Ui.styleField(nameField);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        form.add(nameField);
        form.add(Box.createVerticalStrut(12));

        form.add(fieldLabel("Тип"));
        form.add(Box.createVerticalStrut(5));
        typeCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        typeCombo.setFont(Ui.FONT_PLAIN);
        form.add(typeCombo);
        form.add(Box.createVerticalStrut(12));

        form.add(fieldLabel("Endpoint / команда"));
        form.add(Box.createVerticalStrut(5));
        Ui.styleArea(endpointArea);
        endpointArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        form.add(new JScrollPane(endpointArea));
        form.add(Box.createVerticalStrut(12));

        form.add(fieldLabel("Описание"));
        form.add(Box.createVerticalStrut(5));
        Ui.styleArea(descArea);
        descArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        form.add(new JScrollPane(descArea));
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
        save.addActionListener(e -> saveTool());
        buttons.add(save);

        wrapper.add(buttons, BorderLayout.SOUTH);
        return wrapper;
    }

    // ==================== ДЕЙСТВИЯ ====================

    private void startNew() {
        editingName = null;
        nameField.setText("");
        typeCombo.setSelectedItem("http");
        endpointArea.setText("");
        descArea.setText("");
        enabledCheck.setSelected(true);
        nameField.requestFocus();
    }

    private void loadForEdit(String name) {
        Map<String, Object> tool = Json.asMap(store.getTools().get(name));
        editingName = name;
        nameField.setText(name);
        String type = tool == null ? "http" : Json.str(tool, "type", "http");
        typeCombo.setSelectedItem(type);
        String endpoint = tool == null ? "" : Json.str(tool, "endpoint", Json.str(tool, "code"));
        endpointArea.setText(endpoint);
        descArea.setText(tool == null ? "" : Json.str(tool, "description"));
        enabledCheck.setSelected(tool == null || Json.bool(tool, "enabled", true));
        nameField.requestFocus();
    }

    private void deleteCurrent() {
        if (editingName == null) {
            JOptionPane.showMessageDialog(this, "Выберите инструмент в списке слева.");
            return;
        }
        int c = JOptionPane.showConfirmDialog(this, "Удалить инструмент '" + editingName + "'?",
                "Подтверждение", JOptionPane.YES_NO_OPTION);
        if (c == JOptionPane.YES_OPTION) {
            store.removeTool(editingName);
            store.saveAll();
            startNew();
            refresh();
            frame.refreshSidebar();
        }
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
        tool.put("type", (String) typeCombo.getSelectedItem());
        tool.put("endpoint", endpointArea.getText());
        tool.put("description", desc);
        tool.put("enabled", enabledCheck.isSelected());
        tool.put("created", old == null ? Utils.nowIso() : Json.str(old, "created", Utils.nowIso()));
        tool.put("updated", Utils.nowIso());
        store.putTool(name, tool);
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
