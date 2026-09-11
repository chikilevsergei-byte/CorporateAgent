package corpagent.gui;

import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
import java.util.Map;

/**
 * Менеджер инструментов: добавление, редактирование, удаление.
 */
public class ToolManagerView extends JPanel {

    private final MainFrame frame;
    private final JTextField nameField = new JTextField(20);
    private final JTextArea descArea = new JTextArea(5, 40);
    private final JTextArea codeArea = new JTextArea(5, 40);
    private String editingName = null;

    public ToolManagerView(MainFrame frame) {
        this.frame = frame;
                setLayout(new BorderLayout());
        setBackground(Ui.BG);
        setBorder(new EmptyBorder(20, 24, 20, 24));
    }

    public void refresh() {
        removeAll();
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Ui.BG);

        JPanel titleRow = Ui.header("🔧", "Менеджер инструментов", Ui.FONT_TITLE);
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(titleRow);
        content.add(Box.createVerticalStrut(12));

        // Список существующих инструментов
        Map<String, Object> tools = frame.getStore().getTools();
        if (!tools.isEmpty()) {
                        JLabel listHeader = new JLabel("Существующие инструменты:");
            listHeader.setFont(Ui.FONT_BOLD);
            listHeader.setForeground(Ui.TEXT);
            content.add(listHeader);
            content.add(Box.createVerticalStrut(6));
            for (String name : tools.keySet()) {
                content.add(toolRow(name));
            }
            content.add(Box.createVerticalStrut(12));
        }

                JLabel formTitle = new JLabel("Добавить / редактировать инструмент");
        formTitle.setFont(Ui.FONT_SMALL_BOLD);
        formTitle.setForeground(Ui.TEXT_SECONDARY);
        content.add(formTitle);
        content.add(Box.createVerticalStrut(6));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBackground(Ui.SURFACE);
        form.setBorder(BorderFactory.createCompoundBorder(
                new Ui.RoundedBorder(Ui.BORDER, Ui.RADIUS_CARD, 1),
                new EmptyBorder(14, 14, 14, 14)));
        form.setAlignmentX(Component.LEFT_ALIGNMENT);

        form.add(fieldLabel("Название инструмента"));
        Ui.styleField(nameField);
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        form.add(nameField);
        form.add(Box.createVerticalStrut(8));
        form.add(fieldLabel("Описание"));
        Ui.styleArea(descArea);
        form.add(new JScrollPane(descArea));
        form.add(Box.createVerticalStrut(8));
        form.add(fieldLabel("Код/шаблон (опционально)"));
        Ui.styleArea(codeArea);
        form.add(new JScrollPane(codeArea));

        content.add(form);
        content.add(Box.createVerticalStrut(12));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton save = new JButton("Сохранить");
        Ui.styleButton(save, true);
        save.setIcon(Ui.iconImage("💾", 16));
        save.setIconTextGap(6);
        save.addActionListener(e -> saveTool());
        buttons.add(save);
        JButton back = new JButton("Назад к чату");
        Ui.styleButton(back, false);
        back.setIcon(Ui.iconImage("←", 16));
        back.setIconTextGap(6);
        back.addActionListener(e -> frame.showChat());
        buttons.add(back);
        content.add(buttons);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private JPanel toolRow(String name) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setBackground(Ui.SURFACE);
        row.setBorder(new Ui.RoundedBorder(Ui.BORDER, Ui.RADIUS_CARD, 1));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        JLabel l = new JLabel(name);
        l.setFont(Ui.FONT_PLAIN);
        row.add(l);
        JButton edit = new JButton("Редактировать");
        Ui.styleButton(edit, false);
        edit.setIcon(Ui.iconImage("✏️", 14));
        edit.setIconTextGap(5);
        edit.addActionListener(e -> loadForEdit(name));
        row.add(edit);
        JButton del = new JButton("Удалить");
        Ui.styleButton(del, false);
        del.setIcon(Ui.iconImage("🗑️", 14));
        del.setIconTextGap(5);
        del.addActionListener(e -> {
            int c = JOptionPane.showConfirmDialog(this, "Удалить инструмент '" + name + "'?",
                    "Подтверждение", JOptionPane.YES_NO_OPTION);
            if (c == JOptionPane.YES_OPTION) {
                frame.getStore().removeTool(name);
                frame.getStore().saveAll();
                refresh();
                frame.refreshSidebar();
            }
        });
        row.add(del);
        return row;
    }

    private void loadForEdit(String name) {
        Map<String, Object> tool = Json.asMap(frame.getStore().getTools().get(name));
        editingName = name;
        nameField.setText(name);
        descArea.setText(tool == null ? "" : Json.str(tool, "description"));
        codeArea.setText(tool == null ? "" : Json.str(tool, "code"));
        nameField.requestFocus();
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
            old = Json.asMap(frame.getStore().getTools().get(editingName));
            frame.getStore().removeTool(editingName);
        } else if (editingName != null) {
            old = Json.asMap(frame.getStore().getTools().get(editingName));
        }
        Map<String, Object> tool = Json.newObject();
        tool.put("description", desc);
        tool.put("code", codeArea.getText());
        tool.put("created", old == null ? Utils.nowIso() : Json.str(old, "created", Utils.nowIso()));
        tool.put("updated", Utils.nowIso());
        frame.getStore().putTool(name, tool);
        frame.getStore().saveAll();
        editingName = null;
        nameField.setText("");
        descArea.setText("");
        codeArea.setText("");
        refresh();
        frame.refreshSidebar();
        JOptionPane.showMessageDialog(this, "Инструмент '" + name + "' сохранён!");
    }

        private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL);
        l.setForeground(Ui.TEXT_SECONDARY);
        return l;
    }
}
