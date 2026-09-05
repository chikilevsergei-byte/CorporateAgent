package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.util.Json;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Панель chips над полем ввода чата.
 * Три чипа: «🧠 Навыки», «🔧 Инструменты», «🗄️ БД». По клику на чип открывается
 * выпадающий список с чекбоксами для выбора конкретных элементов.
 *
 * <p>Заменяет прежние три пары «чекбокс + дропдаун» в {@link ChatView}.</p>
 */
public class ChatChipsBar extends JPanel {

    private final DataStore store;

    private final ChipButton dbChip;
    private final ChipButton skillsChip;
    private final ChipButton toolsChip;

    private final Set<String> selectedDatabases = new LinkedHashSet<String>();
    private final Set<String> selectedSkills = new LinkedHashSet<String>();
    private final Set<String> selectedTools = new LinkedHashSet<String>();

    public ChatChipsBar(DataStore store) {
        this.store = store;
        setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);

        dbChip = new ChipButton("🗄️", "БД");
        skillsChip = new ChipButton("🧠", "Навыки");
        toolsChip = new ChipButton("🔧", "Инструменты");

        dbChip.addActionListener(e -> showPicker(dbChip, "🗄️ Базы данных",
                store.getDatabases(), selectedDatabases, "БД"));
        skillsChip.addActionListener(e -> showPicker(skillsChip, "🧠 Навыки",
                store.getSkills(), selectedSkills, "навык"));
        toolsChip.addActionListener(e -> showPicker(toolsChip, "🔧 Инструменты",
                store.getTools(), selectedTools, "инструмент"));

        add(dbChip);
        add(skillsChip);
        add(toolsChip);
    }

    /** Обновляет счётчики и убирает выбранные элементы, которых больше нет. */
    public void refresh() {
        prune(selectedDatabases, store.getDatabases());
        prune(selectedSkills, store.getSkills());
        prune(selectedTools, store.getTools());
        dbChip.setCount(selectedDatabases.size());
        skillsChip.setCount(selectedSkills.size());
        toolsChip.setCount(selectedTools.size());
        revalidate();
        repaint();
    }

    private void prune(Set<String> selected, Map<String, Object> available) {
        if (selected == null || available == null) {
            return;
        }
        List<String> gone = new ArrayList<String>();
        for (String s : selected) {
            if (!available.containsKey(s)) {
                gone.add(s);
            }
        }
        selected.removeAll(gone);
    }

    public Set<String> getSelectedDatabases() {
        return selectedDatabases;
    }

    public Set<String> getSelectedSkills() {
        return selectedSkills;
    }

    public Set<String> getSelectedTools() {
        return selectedTools;
    }

    public boolean hasDatabases() {
        return !selectedDatabases.isEmpty();
    }

    public boolean hasSkills() {
        return !selectedSkills.isEmpty();
    }

    public boolean hasTools() {
        return !selectedTools.isEmpty();
    }

    // ==================== ПИКЕР ====================

    private void showPicker(ChipButton anchor, String title, Map<String, Object> items,
                            Set<String> selected, String noun) {
        if (items == null || items.isEmpty()) {
            JPopupMenu empty = new JPopupMenu();
            JLabel l = new JLabel("  Нет доступных " + noun + "ов. Добавьте в настройках (⚙️).  ");
            l.setFont(Ui.FONT_SMALL);
            l.setForeground(Ui.TEXT_GRAY);
            empty.add(l);
            empty.show(anchor, 0, anchor.getHeight());
            return;
        }

        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(new Color(209, 213, 219)));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);

        JLabel titleL = new JLabel("  " + title);
        titleL.setFont(Ui.FONT_SMALL_BOLD);
        titleL.setForeground(Ui.TEXT_DARK);
        titleL.setBorder(new EmptyBorder(6, 4, 6, 4));
        root.add(titleL, BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Color.WHITE);

        for (Map.Entry<String, Object> e : items.entrySet()) {
            String name = e.getKey();
            Map<String, Object> data = Json.asMap(e.getValue());
            String icon = data == null ? "" : Json.str(data, "icon", "");
            JCheckBox cb = new JCheckBox((icon.isEmpty() ? "" : icon + " ") + name);
            cb.setOpaque(false);
            cb.setFont(Ui.FONT_PLAIN);
            cb.setSelected(selected.contains(name));
            cb.addActionListener(ev -> {
                if (cb.isSelected()) {
                    selected.add(name);
                } else {
                    selected.remove(name);
                }
                refresh();
            });
            list.add(cb);
        }

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(260, Math.min(40 + items.size() * 26, 240)));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        footer.setBackground(Color.WHITE);
        JButton clear = new JButton("Сбросить");
        Ui.styleButton(clear, false);
        clear.addActionListener(ev -> {
            selected.clear();
            refresh();
            popup.setVisible(false);
        });
        footer.add(clear);
        JButton done = new JButton("Готово");
        Ui.styleButton(done, true);
        done.addActionListener(ev -> popup.setVisible(false));
        footer.add(done);
        root.add(footer, BorderLayout.SOUTH);

        popup.add(root);
        popup.show(anchor, 0, anchor.getHeight());
    }

    // ==================== ЧИП ====================

    /** Кнопка-чип с иконкой, подписью и счётчиком выбранных элементов. */
    private static class ChipButton extends JButton {
        private final JLabel iconL;
        private final JLabel textL;
        private final JLabel countL;
        private int count = 0;

        ChipButton(String emoji, String label) {
            setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setBackground(Ui.PRIMARY_SOFT);
            setBorder(new Ui.RoundedBorder(Ui.BORDER_USER, 999, 1));
            setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));

            iconL = Ui.icon(emoji, 14);
            textL = new JLabel(label);
            textL.setFont(Ui.FONT_SMALL);
            textL.setForeground(Ui.TEXT_DARK);
            countL = new JLabel("");
            countL.setFont(Ui.FONT_SMALL_BOLD);
            countL.setForeground(Ui.PRIMARY_DARK);

            add(iconL);
            add(textL);
            add(countL);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    setBackground(Ui.PRIMARY_SOFT.darker());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setBackground(Ui.PRIMARY_SOFT);
                }
            });
        }

        /**
         * Вычисляет компактный размер чипа по фактическим размерам дочерних
         * меток. Переопределяем, потому что стандартный расчёт JButton с
         * кастомным FlowLayout даёт ошибочно огромный размер (наблюдалось
         * ~1070x1014px), который раздувает панель ввода и вытесняет область
         * чата (JScrollPane в CENTER получает отрицательную высоту).
         */
        private Dimension compactSize() {
            int w = 0;
            int h = 0;
            for (Component c : getComponents()) {
                Dimension d = c.getPreferredSize();
                w += d.width;
                h = Math.max(h, d.height);
            }
            // зазоры между элементами (FlowLayout hgap=4) + внутренние отступы
            int n = getComponentCount();
            w += (n > 0 ? (n - 1) * 4 : 0) + 16;
            h += 8;
            return new Dimension(w, Math.max(h, 30));
        }

        @Override
        public Dimension getPreferredSize() {
            return compactSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return compactSize();
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension d = compactSize();
            return new Dimension(Integer.MAX_VALUE, d.height);
        }

        void setCount(int n) {
            this.count = n;
            countL.setText(n > 0 ? " (" + n + ")" : "");
            revalidate();
            repaint();
        }
    }
}
