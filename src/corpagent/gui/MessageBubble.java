package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * UI-4.2: пузырь сообщения чата в стиле прототипа ({@code .message}) как отдельный
 * переиспользуемый компонент: круглый аватар-эмодзи, «пузырь» с HTML-контентом,
 * метка времени и скрытая панель действий, всплывающая при наведении
 * ({@code .message-actions:hover}).
 *
 * <p>Сообщения пользователя выравниваются вправо (синий сплошной пузырь, белый
 * текст), ответы ассистента — влево (белая «карточка» с тонкой рамкой). Действия
 * (копировать / редактировать / удалить / повторить) опциональны и задаются
 * колбэками; кнопка показывается только если колбэк не {@code null}.</p>
 */
public class MessageBubble extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Пользователь (true) или ассистент (false). */
    private final boolean user;
    private final JPanel actions;

    /**
     * @param role    "user" или "assistant"
     * @param html    контент (уже безопасный HTML, переводы строк — {@code <br>})
     * @param time    человекочитаемое время (например, "14:32") или null
     */
    public MessageBubble(String role, String html, String time) {
        this.user = "user".equals(role);
        setLayout(new BorderLayout(Ui.GAP_SM, 0));
        setOpaque(false);
        setBorder(new EmptyBorder(2, Ui.GAP_XS, 2, Ui.GAP_XS));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        // Аватар-эмодзи слева (для пользователя справа — меняем местами ниже).
        JLabel avatar = new JLabel(user ? "👤" : "🤖");
        avatar.setFont(UiTheme.iconFont(18));
        avatar.setForeground(java.awt.Color.WHITE);
        avatar.setHorizontalAlignment(JLabel.CENTER);
        avatar.setVerticalAlignment(JLabel.CENTER);
        avatar.setOpaque(true);
        avatar.setBackground(user ? Ui.PRIMARY : new java.awt.Color(52, 199, 89));
        avatar.setPreferredSize(new Dimension(36, 36));
        avatar.setMinimumSize(new Dimension(36, 36));
        avatar.setMaximumSize(new Dimension(36, 36));

        // Контент: пузырь + время + действия.
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        content.add(buildBubble(html));

        if (time != null && !time.isEmpty()) {
            JLabel timeLbl = new JLabel(time);
            timeLbl.setFont(Ui.FONT_SMALL);
            timeLbl.setForeground(Ui.TEXT_MUTED);
            timeLbl.setAlignmentX(user ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
            content.add(Box.createVerticalStrut(2));
            content.add(timeLbl);
        }

        actions = new JPanel(new FlowLayout(user ? FlowLayout.RIGHT : FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        actions.setVisible(false);
        actions.setAlignmentX(user ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
        content.add(actions);

        // Хвостик-пузырь и аватар: для пользователя — строка реверсируется.
        if (user) {
            add(content, BorderLayout.CENTER);
            add(avatarWrapper(avatar), BorderLayout.EAST);
        } else {
            add(avatarWrapper(avatar), BorderLayout.WEST);
            add(content, BorderLayout.CENTER);
        }

        installHoverActions();
    }

    private JPanel avatarWrapper(JLabel avatar) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(avatar, BorderLayout.NORTH);
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        // Аватар прижат к верху пузыря.
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.add(avatar);
        col.add(Box.createVerticalGlue());
        wrap.add(col, BorderLayout.CENTER);
        return wrap;
    }

    /** Строит «пузырь» с HTML-контентом на {@link JEditorPane}. */
    private JEditorPane buildBubble(String html) {
        String textColor = user ? "#FFFFFF" : "#1C1C1E";
        String styled = "<html><body style=\"color:" + textColor + ";font-family:'" + Ui.FONT
                + "';font-size:14px;\">" + (html == null ? "" : html) + "</body></html>";
        JEditorPane pane = new JEditorPane("text/html", styled);
        pane.setEditable(false);
        pane.setOpaque(true);
        pane.setBackground(user ? Ui.PRIMARY : Ui.SURFACE);
        pane.setBorder(BorderFactory.createCompoundBorder(
                new Ui.RoundedBorder(user ? Ui.PRIMARY : Ui.BORDER, Ui.RADIUS_CARD, 1),
                new EmptyBorder(10, 14, 10, 14)));
        return pane;
    }

    /** Показываем панель действий при наведении на пузырь. */
    private void installHoverActions() {
        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                actions.setVisible(true);
                revalidate();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                // Скрываем, только если курсор ушёл за пределы всего компонента.
                java.awt.Point p = getMousePosition();
                if (p == null) {
                    actions.setVisible(false);
                    revalidate();
                    repaint();
                }
            }
        };
        addMouseListener(hover);
        for (Component c : getComponents()) {
            c.addMouseListener(hover);
        }
    }

    /** Добавляет кнопку-действие (показывается при hover). {@code emoji} + {@code tooltip}. */
    public MessageBubble addAction(String emoji, String tooltip, Runnable run) {
        if (run == null) {
            return this;
        }
        JButton b = new JButton(Ui.actionIcon(emoji));
        b.setToolTipText(tooltip);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.setBorder(new EmptyBorder(2, 4, 2, 4));
        b.addActionListener(e -> run.run());
        actions.add(b);
        return this;
    }

    /** true, если это сообщение пользователя — для смоук-теста. */
    public boolean isUser() {
        return user;
    }

    /** Число добавленных действий — для смоук-теста. */
    public int actionCount() {
        return actions.getComponentCount();
    }
}
