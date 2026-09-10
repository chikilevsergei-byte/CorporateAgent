package corpagent.gui;

import corpagent.data.ProjectManager;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

/**
 * Боковая панель: кнопки «Новый чат» / «Создать проект» и список чатов.
 * Разделы «Базы данных» и «Автообновление БД» перенесены в настройки.
 *
 * <p>R-P0.2: чаты группируются по проекту; кнопка «Создать проект» открывает
 * мастер создания проекта (БД проекта + 7 авто-чатов-ролей).</p>
 */
public class SidebarPanel extends JPanel {

        private final MainFrame frame;
    private final JPanel chatListPanel = new ScrollablePanel();

    public SidebarPanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(Ui.SURFACE);
        setPreferredSize(new Dimension(300, 600));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(Ui.SURFACE);
        top.setBorder(new EmptyBorder(16, 12, 12, 12));

        // Верхний ряд кнопок: «Новый чат» и «Создать проект» (R-P0.2).
        // Оба — одного размера, с одинаковыми отступами, отцентрованы относительно
        // друг друга и расположены в один ряд.
        JPanel topButtons = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        topButtons.setBackground(Ui.SURFACE);
        topButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
        topButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        JButton newChatBtn = new JButton("Новый чат");
        Ui.stylePrimary(newChatBtn);
        newChatBtn.setForeground(Color.BLACK);
        newChatBtn.setFont(new Font("Segoe UI", Font.BOLD, 15));
        newChatBtn.setIcon(Ui.iconImage("➕", 16));
        newChatBtn.setIconTextGap(8);
        newChatBtn.addActionListener(e -> frame.newChat());
        topButtons.add(newChatBtn);

        JButton newProjectBtn = new JButton("Создать проект");
        Ui.styleSecondary(newProjectBtn);
        newProjectBtn.setForeground(Color.BLACK);
        newProjectBtn.setFont(new Font("Segoe UI", Font.BOLD, 15));
        newProjectBtn.setIcon(Ui.iconImage("🗂️", 16));
        newProjectBtn.setIconTextGap(8);
        newProjectBtn.addActionListener(e -> frame.createProject());
        topButtons.add(newProjectBtn);

        top.add(topButtons);
        top.add(Box.createVerticalStrut(16));

        // Заголовок «Чаты»
        JLabel chatsTitle = new JLabel("ЧАТЫ");
        chatsTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        chatsTitle.setForeground(Ui.TEXT_MUTED);
        chatsTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(chatsTitle);
        top.add(Box.createVerticalStrut(6));

        chatListPanel.setLayout(new BoxLayout(chatListPanel, BoxLayout.Y_AXIS));
        chatListPanel.setBackground(Ui.SURFACE);
                JScrollPane chatScroll = new JScrollPane(chatListPanel);
        chatScroll.setBorder(null);
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);
        // Меню должно быть полностью видно без горизонтальной прокрутки: стрлки влево/вправо
        // убраны, ширина списка чатов подгоняется под ширину панели.
        chatScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Ui.SURFACE);
        body.add(top, BorderLayout.NORTH);
        body.add(chatScroll, BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);
    }

    public void refresh() {
        refreshChats();
    }

    private void refreshChats() {
        chatListPanel.removeAll();
        List<Map.Entry<String, Map<String, Object>>> chats = frame.getStore().sortedChats();
        if (chats.isEmpty()) {
            chatListPanel.add(emptyLabel("Нет чатов"));
        } else {
            // R-P0.2 / R-P1.5: чаты группируются по проекту (папка-заголовок + чаты-роли).
            // Чаты без проекта идут в общий раздел «Без проекта».
            Map<String, String> projectNames = ProjectManager.projectNames(frame.getStore());
            java.util.LinkedHashMap<String, List<Map.Entry<String, Map<String, Object>>>> grouped =
                    new java.util.LinkedHashMap<String, List<Map.Entry<String, Map<String, Object>>>>();
            List<Map.Entry<String, Map<String, Object>>> loose =
                    new java.util.ArrayList<Map.Entry<String, Map<String, Object>>>();
            for (Map.Entry<String, Map<String, Object>> e : chats) {
                String pid = ProjectManager.projectIdOfChat(e.getValue());
                if (pid != null && projectNames.containsKey(pid)) {
                    List<Map.Entry<String, Map<String, Object>>> bucket = grouped.get(pid);
                    if (bucket == null) {
                        bucket = new java.util.ArrayList<Map.Entry<String, Map<String, Object>>>();
                        grouped.put(pid, bucket);
                    }
                    bucket.add(e);
                } else {
                    loose.add(e);
                }
            }
            int shown = 0;
            for (Map.Entry<String, List<Map.Entry<String, Map<String, Object>>>> g : grouped.entrySet()) {
                if (shown >= 15) {
                    break;
                }
                chatListPanel.add(projectHeader(projectNames.get(g.getKey()), g.getKey(),
                        g.getValue().size()));
                for (Map.Entry<String, Map<String, Object>> e : g.getValue()) {
                    if (shown >= 15) {
                        break;
                    }
                    shown++;
                    chatListPanel.add(createChatRow(e.getKey(), e.getValue()));
                }
            }
            if (!loose.isEmpty()) {
                if (!grouped.isEmpty()) {
                    chatListPanel.add(sectionLabel("Без проекта"));
                }
                for (Map.Entry<String, Map<String, Object>> e : loose) {
                    if (shown >= 15) {
                        break;
                    }
                    shown++;
                    chatListPanel.add(createChatRow(e.getKey(), e.getValue()));
                }
            }
        }
        chatListPanel.revalidate();
        chatListPanel.repaint();
    }

    /** Заголовок-«папка» проекта в списке чатов (R-P0.2). */
    private JPanel projectHeader(String projectName, String projectId, int chatCount) {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Ui.SURFACE);
        header.setBorder(new EmptyBorder(10, 4, 4, 4));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(Ui.icon("🗂️", 14));
        JLabel name = new JLabel(Utils.truncate(projectName, 18) + " · " + chatCount);
        name.setFont(Ui.FONT_SMALL_BOLD);
        name.setForeground(Ui.PRIMARY_DARK);
        left.add(name);
        header.add(left, BorderLayout.CENTER);

        JButton menu = new JButton("...");
        menu.setToolTipText("Меню проекта");
        menu.setBorderPainted(false);
        menu.setFocusPainted(false);
        menu.setContentAreaFilled(false);
        menu.setOpaque(false);
        menu.setFont(Ui.FONT_SMALL_BOLD);
        menu.setForeground(Ui.TEXT_MUTED);
        menu.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        menu.addActionListener(e -> showProjectMenu(projectId, projectName));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(menu);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel sectionLabel(String text) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setBackground(Ui.SURFACE);
        row.setBorder(new EmptyBorder(10, 4, 2, 4));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL_BOLD);
        l.setForeground(Ui.TEXT_MUTED);
        row.add(l);
        return row;
    }

    /** Меню проекта: удаление проекта вместе с его чатами (БД проекта остаётся). */
    private void showProjectMenu(String projectId, String projectName) {
        Map<String, Object> project = frame.getStore().getProject(projectId);
        String db = project == null ? "" : Json.str(project, "db", "");
        String[] options = {"Удалить проект", "Отмена"};
        int choice = JOptionPane.showOptionDialog(this,
                "Проект: " + projectName + "\nБД проекта: " + db,
                "Меню проекта", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[1]);
        if (choice == 0) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Удалить проект '" + projectName + "' вместе с его чатами?\n"
                            + "Файл БД проекта ('" + db + "') останется в списке баз данных.",
                    "Подтверждение", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                List<String> removed = frame.getStore().removeProject(projectId);
                if (frame.getCurrentChatId() != null && removed.contains(frame.getCurrentChatId())) {
                    frame.setCurrentChatId(null);
                }
                frame.getStore().saveAll();
                refresh();
                frame.getChatView().refresh();
            }
        }
    }

    private JPanel createChatRow(String chatId, Map<String, Object> chatRec) {
        String title = Json.str(chatRec, "title", "Без названия");
        String icon = Json.str(chatRec, "icon", "💬");
        boolean inProject = Json.str(chatRec, "project", "").length() > 0;
        boolean active = chatId.equals(frame.getCurrentChatId());
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(active ? Ui.SELECTED : Ui.SURFACE);
        row.setBorder(new EmptyBorder(6, 8, 6, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                frame.setCurrentChatId(chatId);
                frame.showChat();
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        // Чаты внутри проекта немного отступают вправо (визуальная «вложенность» в папку).
        left.setBorder(new EmptyBorder(0, inProject ? 14 : 0, 0, 0));
        left.add(Ui.icon(icon.isEmpty() ? "💬" : icon, 13));
        JLabel name = new JLabel(Utils.truncate(title, inProject ? 17 : 20));
        name.setFont(Ui.FONT_SMALL);
        name.setForeground(active ? Ui.PRIMARY_DARK : Ui.TEXT);
        left.add(name);
        row.add(left, BorderLayout.CENTER);

        JButton menu = new JButton("...");
        menu.setToolTipText("Chat menu");
        menu.setBorderPainted(false);
        menu.setFocusPainted(false);
        menu.setContentAreaFilled(false);
        menu.setOpaque(false);
        menu.setFont(Ui.FONT_SMALL_BOLD);
        menu.setForeground(Ui.TEXT_MUTED);
        menu.addActionListener(e -> showChatMenu(chatId, title));

        boolean pinned = chatRec != null && Boolean.TRUE.equals(chatRec.get("pinned"));
        JButton pinBtn = new JButton("pin");
        pinBtn.setToolTipText(pinned ? "Unpin from top" : "Pin chat on top");
        pinBtn.setBorderPainted(false);
        pinBtn.setFocusPainted(false);
        pinBtn.setContentAreaFilled(false);
        pinBtn.setOpaque(false);
        pinBtn.setFont(Ui.FONT_SMALL_BOLD);
        pinBtn.setForeground(pinned ? Ui.PRIMARY : Ui.TEXT_MUTED);
        pinBtn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        menu.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        final boolean fPinned = pinned;
        pinBtn.addActionListener(e -> togglePin(chatId, fPinned));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(pinBtn);
        actions.add(menu);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private void showChatMenu(String chatId, String currentTitle) {
        String[] options = {"Переименовать", "Удалить", "Отмена"};
        int choice = JOptionPane.showOptionDialog(this, "Чат: " + currentTitle, "Меню чата",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[2]);
        if (choice == 0) {
            String newName = JOptionPane.showInputDialog(this, "Новое имя чата:", currentTitle);
            if (newName != null && !newName.trim().isEmpty()) {
                Map<String, Object> chat = frame.getStore().getChat(chatId);
                if (chat != null) {
                    chat.put("title", newName.trim());
                    chat.put("updated", Utils.nowIso());
                    frame.getStore().saveAll();
                    refresh();
                    frame.getChatView().refresh();
                }
            }
        } else if (choice == 1) {
            int confirm = JOptionPane.showConfirmDialog(this, "Удалить чат '" + currentTitle + "'?",
                    "Подтверждение", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                frame.getStore().removeChat(chatId);
                if (chatId.equals(frame.getCurrentChatId())) {
                    frame.setCurrentChatId(null);
                }
                frame.getStore().saveAll();
                refresh();
                frame.getChatView().refresh();
            }
        }
    }

    private void togglePin(String chatId, boolean currentlyPinned) {
        Map<String, Object> c = frame.getStore().getChat(chatId);
        if (c == null) {
            return;
        }
        c.put("pinned", Boolean.valueOf(!currentlyPinned));
        frame.getStore().saveAll();
        refresh();
        frame.getChatView().refresh();
    }

            private JLabel emptyLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Ui.TEXT_MUTED);
        l.setFont(Ui.FONT_SMALL);
        return l;
    }

    /**
     * Панель-список чатов, которая всегда растягивается по ширине вьюпорта
     * JScrollPane. Это убирает горизонтальную прокрутку бокового меню: все
     * элементы (включая правые кнопки «pin» и «…») видны без прокрутки влево/вправо.
     */
    private static final class ScrollablePanel extends JPanel implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            // Ширина списка = ширина вьюпорта => нет горизонтальной прокрутки.
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
