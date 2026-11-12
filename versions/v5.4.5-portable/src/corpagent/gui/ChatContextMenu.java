package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * UI-1.1: кастомное контекстное меню чата/проекта в стиле macOS/iOS.
 *
 * <p>Заменяет системный {@code JOptionPane.showOptionDialog} в
 * {@code SidebarPanel.showChatMenu}/{@code showProjectMenu}. Даёт единый вид с
 * остальным редизайном: скруглённая «glass»-панель, шапка с иконкой и заголовком,
 * пункты с emoji-иконками и {@code danger}-стилем для деструктивных действий.</p>
 *
 * <p>Использование:</p>
 * <pre>
 * ChatContextMenu menu = new ChatContextMenu("💬", "Чат: " + title);
 * menu.addAction("✏️", "Переименовать", () -&gt; ...);
 * menu.addAction("🗑️", "Удалить", true, () -&gt; ...);
 * menu.showAt(component, x, y);
 * </pre>
 */
public class ChatContextMenu extends JPopupMenu {

    private static final long serialVersionUID = 1L;

    /** Ширина меню по умолчанию (как в макете: пункты «Переместить в проект…»). */
    private static final int DEFAULT_WIDTH = 280;

    private final JPanel headerPanel;
    private final JPanel actionsPanel;

    public ChatContextMenu(String emoji, String title) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(Ui.GAP_SM, Ui.GAP_SM, Ui.GAP_SM, Ui.GAP_SM));
        setOpaque(false);

        headerPanel = buildHeader(emoji, title);
        add(headerPanel);
        add(buildSeparator());

        actionsPanel = new JPanel();
        actionsPanel.setLayout(new BoxLayout(actionsPanel, BoxLayout.Y_AXIS));
        actionsPanel.setOpaque(false);
        actionsPanel.setBorder(BorderFactory.createEmptyBorder(Ui.GAP_XS, 0, 0, 0));
        add(actionsPanel);
    }

    /** Собирает шапку меню: градиентная иконка + заголовок (macOS-стиль). */
    private static JPanel buildHeader(String emoji, String title) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        JLabel icon = new EmojiBadge(emoji, 36);
        icon.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(icon);
        row.add(Box.createHorizontalStrut(Ui.GAP_SM));

        JLabel text = new JLabel("<html><div style='width:170px'>" + escape(title) + "</div></html>");
        text.setFont(Ui.FONT_SMALL_BOLD);
        text.setForeground(Ui.TEXT);
        text.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(text);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel buildSeparator() {
        JPanel sep = new JPanel();
        sep.setBackground(Ui.BORDER);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setPreferredSize(new Dimension(1, 1));
        return sep;
    }

    /**
     * Добавляет пункт меню.
     *
     * @param emoji   эмодзи-иконка пункта
     * @param text    подпись
     * @param danger  true — деструктивный пункт (красный текст/hover)
     * @param action  действие по клику
     */
    public ChatContextMenu addAction(String emoji, String text, boolean danger, Runnable action) {
        ContextItem item = new ContextItem(emoji, text, danger);
        item.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setVisible(false);
                if (action != null) {
                    action.run();
                }
            }
        });
        actionsPanel.add(item);
        return this;
    }

    /** Пункт меню без danger-стиля. */
    public ChatContextMenu addAction(String emoji, String text, Runnable action) {
        return addAction(emoji, text, false, action);
    }

    /** Количество добавленных пунктов (для тестов/диагностики). */
    public int actionCount() {
        return actionsPanel.getComponentCount();
    }

    /** Текст (label) пункта по индексу — для смоук-теста состава меню. */
    public String actionLabelAt(int index) {
        Component c = actionsPanel.getComponent(index);
        if (c instanceof ContextItem) {
            return ((ContextItem) c).labelText;
        }
        return null;
    }

    /** true, если пункт по индексу помечен danger-стилем. */
    public boolean actionIsDangerAt(int index) {
        Component c = actionsPanel.getComponent(index);
        return c instanceof ContextItem && ((ContextItem) c).danger;
    }

    /** Показывает меню относительно компонента в точке (x, y). */
    public void showAt(Component invoker, int x, int y) {
        show(invoker, x, y);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ==================== ВНУТРЕННИЕ КОМПОНЕНТЫ ====================

    /**
     * Пункт меню: emoji-иконка, подпись, hover-подсветка и опциональный
     * danger-стиль. Плоский JPanel с MouseListener (полный контроль над видом).
     */
    static class ContextItem extends JPanel {
        private static final long serialVersionUID = 1L;

        final String labelText;
        final boolean danger;
        private Color background = new Color(0, 0, 0, 0);

        ContextItem(String emoji, String text, boolean danger) {
            this.labelText = text;
            this.danger = danger;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            setCursor(Ui.handCursor());

            JLabel badge = new JLabel(Ui.actionIcon(emoji, 15));
            badge.setAlignmentY(Component.CENTER_ALIGNMENT);
            add(badge);
            add(Box.createHorizontalStrut(Ui.GAP_SM));

            JLabel label = new JLabel(text);
            label.setFont(Ui.FONT_PLAIN);
            label.setForeground(danger ? Ui.ERROR : Ui.TEXT);
            label.setAlignmentY(Component.CENTER_ALIGNMENT);
            add(label);
            add(Box.createHorizontalGlue());

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    background = danger ? Ui.ERROR_SOFT : Ui.HOVER;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    background = new Color(0, 0, 0, 0);
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (background.getAlpha() > 0) {
                g2.setColor(background);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), Ui.RADIUS_BTN, Ui.RADIUS_BTN);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Скруглённый «бейдж» под эмодзи с синим градиентом
     * ({@code linear-gradient(135deg, #007AFF, #5856D6)} из макета).
     */
    static class EmojiBadge extends JLabel {
        private static final long serialVersionUID = 1L;
        private final int size;

        EmojiBadge(String emoji, int size) {
            super(emoji);
            this.size = size;
            setHorizontalAlignment(CENTER);
            setVerticalAlignment(CENTER);
            setFont(UiTheme.iconFont(Math.max(12, size - 16)));
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.GradientPaint gp = new java.awt.GradientPaint(
                    0, 0, Ui.PRIMARY, size, size, new Color(88, 86, 214));
            g2.setPaint(gp);
            g2.fillRoundRect(0, 0, size, size, Ui.RADIUS_BTN, Ui.RADIUS_BTN);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
