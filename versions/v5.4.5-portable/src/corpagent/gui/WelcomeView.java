package corpagent.gui;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * UI-4.1: приветственный экран пустого чата в стиле прототипа
 * ({@code .welcome-container}): градиентная иконка-«бейдж» сверху, крупный
 * заголовок, подзаголовок и сетка «быстрых действий» (2×N) с hover-эффектом.
 *
 * <p>Заменяет простой HTML-текст из {@code ChatView.showWelcome()} на
 * структурированный экран. Действия — кликабельные карточки, каждая со своим
 * эмодзи, заголовком и описанием; по клику вызывается {@link Action#run()}.</p>
 *
 * <p>Использование:</p>
 * <pre>
 * WelcomeView welcome = new WelcomeView("👋", "Добро пожаловать!",
 *         "Выберите действие или начните диалог ниже");
 * welcome.addAction("➕", "Новый проект", "БД + рабочие чаты", () -&gt; ...);
 * panel.add(welcome);
 * </pre>
 */
public class WelcomeView extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Одно «быстрое действие» приветственного экрана. */
    public static class Action {
        final String emoji;
        final String title;
        final String desc;
        final Runnable run;

        public Action(String emoji, String title, String desc, Runnable run) {
            this.emoji = emoji;
            this.title = title;
            this.desc = desc;
            this.run = run;
        }
    }

    private final List<Action> actions = new ArrayList<Action>();
    private final JPanel actionsGrid = new JPanel();
    private final JLabel subtitle;

    public WelcomeView(String icon, String title, String subtitle) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(new EmptyBorder(Ui.GAP_2XL, Ui.GAP_XL, Ui.GAP_XL, Ui.GAP_XL));

        // Градиентная иконка-бейдж.
        GradientBadge badge = new GradientBadge(icon, 80);
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(badge);
        add(Box.createVerticalStrut(Ui.GAP_LG));

        JLabel titleLbl = new JLabel(title == null ? "" : title);
        titleLbl.setFont(Ui.FONT_2XL);
        titleLbl.setForeground(Ui.TEXT_DARK);
        titleLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(titleLbl);
        add(Box.createVerticalStrut(Ui.GAP_XS));

        this.subtitle = new JLabel(subtitle == null ? "" : subtitle);
        this.subtitle.setFont(Ui.FONT_TITLE);
        this.subtitle.setForeground(Ui.TEXT_SECONDARY);
        this.subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(this.subtitle);
        add(Box.createVerticalStrut(Ui.GAP_2XL));

        actionsGrid.setLayout(new GridLayout(0, 2, Ui.GAP_MD, Ui.GAP_MD));
        actionsGrid.setOpaque(false);
        actionsGrid.setAlignmentX(Component.CENTER_ALIGNMENT);
        actionsGrid.setMaximumSize(new Dimension(720, Integer.MAX_VALUE));
        actionsGrid.setPreferredSize(new Dimension(720, 0));
        add(actionsGrid);
    }

    /** Добавляет карточку быстрого действия (максимум 2 в ряд). */
    public WelcomeView addAction(String emoji, String title, String desc, Runnable run) {
        Action a = new Action(emoji, title, desc, run);
        actions.add(a);
        actionsGrid.add(buildCard(a));
        return this;
    }

    /** Число добавленных действий — для смоук-теста. */
    public int actionCount() {
        return actions.size();
    }

    /** Заголовок действия по индексу — для смоук-теста. */
    public String actionTitleAt(int index) {
        return actions.get(index).title;
    }

    private JPanel buildCard(final Action a) {
        final QuickActionCard card = new QuickActionCard(a);
        card.setCursor(Ui.handCursor());
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (a.run != null) {
                    a.run.run();
                }
            }
        });
        return card;
    }

    /**
     * Градиентный «бейдж»-иконка (linear-gradient 135°, #007AFF → #5856D6 из макета).
     */
    static class GradientBadge extends JLabel {
        private static final long serialVersionUID = 1L;
        private final int size;

        GradientBadge(String emoji, int size) {
            super(emoji);
            this.size = size;
            setHorizontalAlignment(CENTER);
            setVerticalAlignment(CENTER);
            setFont(UiTheme.iconFont(Math.max(16, size / 2)));
            setForeground(java.awt.Color.WHITE);
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new java.awt.GradientPaint(
                    0, 0, Ui.PRIMARY, size, size, new java.awt.Color(88, 86, 214)));
            g2.fillRoundRect(0, 0, size, size, Ui.RADIUS_XL, Ui.RADIUS_XL);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Карточка быстрого действия: tint-иконка слева + заголовок/описание,
     * hover-подсветка и лёгкое скругление (стиль {@code .quick-action-card}).
     */
    static class QuickActionCard extends JPanel {
        private static final long serialVersionUID = 1L;
        private boolean hover;
        private final JLabel iconLabel;

        QuickActionCard(Action a) {
            setLayout(new java.awt.BorderLayout(Ui.GAP_MD, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(Ui.GAP_LG, Ui.GAP_LG, Ui.GAP_LG, Ui.GAP_LG));

            iconLabel = new JLabel(a.emoji);
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setVerticalAlignment(SwingConstants.CENTER);
            iconLabel.setOpaque(true);
            iconLabel.setBackground(Ui.PRIMARY_SOFT);
            iconLabel.setFont(UiTheme.iconFont(24));
            iconLabel.setPreferredSize(new Dimension(48, 48));
            add(iconLabel, java.awt.BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            JLabel t = new JLabel(a.title);
            t.setFont(Ui.FONT_BOLD);
            t.setForeground(Ui.TEXT_DARK);
            t.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(t);
            if (a.desc != null && !a.desc.isEmpty()) {
                text.add(Box.createVerticalStrut(2));
                JLabel d = new JLabel(a.desc);
                d.setFont(Ui.FONT_SMALL);
                d.setForeground(Ui.TEXT_MUTED);
                d.setAlignmentX(Component.LEFT_ALIGNMENT);
                text.add(d);
            }
            add(text, java.awt.BorderLayout.CENTER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hover ? Ui.SURFACE : Ui.HOVER);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), Ui.RADIUS_CARD, Ui.RADIUS_CARD);
            g2.setColor(hover ? Ui.BORDER_INPUT : Ui.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Ui.RADIUS_CARD, Ui.RADIUS_CARD);
            g2.dispose();
            super.paintComponent(g);
        }

        boolean isHover() {
            return hover;
        }
    }
}
