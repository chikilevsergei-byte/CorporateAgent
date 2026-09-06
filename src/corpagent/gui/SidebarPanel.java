package corpagent.gui;

import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

/**
 * Боковая панель: кнопка «Новый чат» и список чатов.
 * Разделы «Базы данных» и «Автообновление БД» перенесены в настройки.
 */
public class SidebarPanel extends JPanel {

    private final MainFrame frame;
    private final JPanel chatListPanel = new JPanel();

    public SidebarPanel(MainFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(Ui.SURFACE);
        setPreferredSize(new Dimension(300, 600));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(Ui.SURFACE);
        top.setBorder(new EmptyBorder(16, 12, 12, 12));

        // Кнопка «Новый чат»
        JButton newChatBtn = new JButton("Новый чат");
        Ui.stylePrimary(newChatBtn);
        newChatBtn.setForeground(Color.BLACK);
        newChatBtn.setFont(new Font("Segoe UI", Font.BOLD, 15));
        newChatBtn.setIcon(Ui.iconImage("➕", 16));
        newChatBtn.setIconTextGap(8);
        newChatBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        newChatBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        newChatBtn.addActionListener(e -> frame.newChat());
        top.add(newChatBtn);

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
            int shown = 0;
            for (Map.Entry<String, Map<String, Object>> e : chats) {
                if (shown >= 15) {
                    break;
                }
                shown++;
                String chatId = e.getKey();
                Map<String, Object> chat = e.getValue();
                String title = Json.str(chat, "title", "Без названия");
                boolean active = chatId.equals(frame.getCurrentChatId());
                chatListPanel.add(createChatRow(chatId, title, active));
            }
        }
        chatListPanel.revalidate();
        chatListPanel.repaint();
    }

    private JPanel createChatRow(String chatId, String title, boolean active) {
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
        left.add(Ui.icon("💬", 13));
        JLabel name = new JLabel(Utils.truncate(title, 20));
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

        Map<String, Object> chatRec = frame.getStore().getChat(chatId);
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
            String newName = JOptionPane.showInputDialog(this, "Новое название:", currentTitle);
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
}
