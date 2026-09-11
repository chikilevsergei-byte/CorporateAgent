package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.util.Json;

import javax.swing.BorderFactory;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ряд кнопок-селекторов: «БД», «Навыки», «Инструменты».
 * Размещается в одной строке с кнопкой «Файлы» и единообразен с ней
 * (одинаковый стиль styleSecondary, размер, отступы, без иконок).
 *
 * <p>По клику на кнопку открывается выпадающий список с чекбоксами
 * для выбора конкретных элементов (существующая логика выбора).</p>
 */
public class ChatChipsBar extends JPanel {

    /** Единая ширина кнопки семейства (включая «Файлы»). */
    public static final int BTN_WIDTH = 132;
    /** Единая высота кнопки семейства. */
    public static final int BTN_HEIGHT = 34;

    private final DataStore store;
    private final MainFrame frame;

    private final JButton dbButton;
    private final JButton skillsButton;
    private final JButton toolsButton;

    private int dbCount = 0;
    private int skillsCount = 0;
    private int toolsCount = 0;

    /** id чата, для которого в чипы загружено текущее выделение (R-P0.2 / R-P1.5). */
    private String loadedChatId = null;

    private final Set<String> selectedDatabases = new LinkedHashSet<String>();
    private final Set<String> selectedSkills = new LinkedHashSet<String>();
    private final Set<String> selectedTools = new LinkedHashSet<String>();

    /** Применяет к кнопке семейства единый стиль и размер. */
    static JButton applyStyle(JButton b, String label) {
        Ui.styleSecondary(b);
        b.setText(label);
        b.setFont(Ui.FONT_PLAIN);
        b.setHorizontalAlignment(JButton.CENTER);
        b.setVerticalAlignment(JButton.CENTER);
        Dimension d = new Dimension(BTN_WIDTH, BTN_HEIGHT);
        b.setPreferredSize(d);
        b.setMinimumSize(d);
        b.setMaximumSize(d);
        return b;
    }

    public ChatChipsBar(MainFrame frame) {
        this.frame = frame;
        this.store = frame.getStore();
        setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setOpaque(false);

        dbButton = applyStyle(new JButton(), "БД");
        skillsButton = applyStyle(new JButton(), "Навыки");
        toolsButton = applyStyle(new JButton(), "Инструменты");

        dbButton.addActionListener(e -> showPicker(dbButton, "Базы данных (SQLite)",
                availableSqliteDatabases(), selectedDatabases, "БД"));
        skillsButton.addActionListener(e -> showPicker(skillsButton, "Навыки",
                store.getSkills(), selectedSkills, "навык"));
        toolsButton.addActionListener(e -> showPicker(toolsButton, "Инструменты",
                store.getTools(), selectedTools, "инструмент"));

        add(dbButton);
        add(skillsButton);
        add(toolsButton);
    }

    /**
     * Обновляет счётчики и убирает выбранные элементы, которых больше нет.
     * Также подгружает выделение текущего чата из его записи (R-P0.2 / R-P1.5):
     * если активный чат сменился — выделение перезагружается из чата.
     */
    public void refresh() {
        loadSelectionForCurrentChat();
        prune(selectedDatabases, store.getDatabases());
        prune(selectedSkills, store.getSkills());
        prune(selectedTools, store.getTools());
        dbCount = selectedDatabases.size();
        skillsCount = selectedSkills.size();
        toolsCount = selectedTools.size();
        dbButton.setText(countSuffix("БД", dbCount));
        skillsButton.setText(countSuffix("Навыки", skillsCount));
        toolsButton.setText(countSuffix("Инструменты", toolsCount));
        revalidate();
        repaint();
    }

    /**
     * Загружает выделение чипов из записи активного чата, если чат сменился.
     * Данные чата — источник истины: авто-чаты проекта (R-P0.2) приходят
     * с предустановленными {@code selected_databases/skills/tools}.
     */
    private void loadSelectionForCurrentChat() {
        String chatId = frame.getCurrentChatId();
        if (chatId == null) {
            if (loadedChatId != null) {
                selectedDatabases.clear();
                selectedSkills.clear();
                selectedTools.clear();
                loadedChatId = null;
            }
            return;
        }
        if (chatId.equals(loadedChatId)) {
            return;
        }
        loadedChatId = chatId;
        selectedDatabases.clear();
        selectedSkills.clear();
        selectedTools.clear();
        Map<String, Object> chat = store.getChat(chatId);
        if (chat != null) {
            selectedDatabases.addAll(stringList(Json.arr(chat, "selected_databases")));
            selectedSkills.addAll(stringList(Json.arr(chat, "selected_skills")));
            selectedTools.addAll(stringList(Json.arr(chat, "selected_tools")));
        }
    }

    private List<String> stringList(List<Object> arr) {
        List<String> out = new ArrayList<String>();
        if (arr != null) {
            for (Object o : arr) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    /**
     * Сохраняет текущее выделение в запись активного чата, чтобы оно сохранялось
     * между переключениями чатов и перезапусками приложения.
     */
    private void saveSelectionToCurrentChat() {
        String chatId = frame.getCurrentChatId();
        if (chatId == null) {
            return;
        }
        Map<String, Object> chat = store.getChat(chatId);
        if (chat == null) {
            return;
        }
        chat.put("selected_databases", new ArrayList<Object>(selectedDatabases));
        chat.put("selected_skills", new ArrayList<Object>(selectedSkills));
        chat.put("selected_tools", new ArrayList<Object>(selectedTools));
        store.saveAll();
    }

    private String countSuffix(String base, int n) {
        return n > 0 ? base + " (" + n + ")" : base;
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

    /**
     * Возвращает только выбранные подключения, пригодные для активной работы:
     * тип SQLite и указан непустой файл/путь. Серверные типы (без драйверов)
     * сюда не попадают и не допускаются к запуску в чате (Задача 2.1).
     */
    public Set<String> getSelectedSqliteDatabases() {
        Set<String> out = new LinkedHashSet<String>();
        for (String n : selectedDatabases) {
            Map<String, Object> d = Json.asMap(store.getDatabases().get(n));
            String type = d == null ? "sqlite" : Json.str(d, "type", "sqlite");
            if (!"sqlite".equals(type)) {
                continue;
            }
            String file = d == null ? "" : Json.str(d, "file", Json.str(d, "path", ""));
            if (file == null || file.trim().isEmpty()) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    /** Подключения SQLite с указанным файлом (для пикера БД). */
    private Map<String, Object> availableSqliteDatabases() {
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : store.getDatabases().entrySet()) {
            Map<String, Object> d = Json.asMap(e.getValue());
            String type = d == null ? "sqlite" : Json.str(d, "type", "sqlite");
            if (!"sqlite".equals(type)) {
                continue;
            }
            String file = d == null ? "" : Json.str(d, "file", Json.str(d, "path", ""));
            if (file == null || file.trim().isEmpty()) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
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

    private void showPicker(JButton anchor, String title, Map<String, Object> items,
                            Set<String> selected, String noun) {
        if (items == null || items.isEmpty()) {
            JPopupMenu empty = new JPopupMenu();
            JLabel l = new JLabel("  Нет доступных " + noun + "ов. Добавьте в настройках.  ");
            l.setFont(Ui.FONT_SMALL);
            l.setForeground(Ui.TEXT_GRAY);
            empty.add(l);
            empty.show(anchor, 0, anchor.getHeight());
            return;
        }

        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(Ui.BORDER_INPUT));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.SURFACE);

        JLabel titleL = new JLabel("  " + title);
        titleL.setFont(Ui.FONT_SMALL_BOLD);
        titleL.setForeground(Ui.TEXT_DARK);
        titleL.setBorder(new EmptyBorder(6, 4, 6, 4));
        root.add(titleL, BorderLayout.NORTH);

        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(Ui.SURFACE);

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
        footer.setBackground(Ui.SURFACE);
        JButton clear = new JButton("Сбросить");
        Ui.styleButton(clear, false);
        clear.addActionListener(ev -> {
            selected.clear();
            refresh();
            saveSelectionToCurrentChat();
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
}
