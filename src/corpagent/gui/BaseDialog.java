package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

/**
 * UI-2.4: единая основа модальных диалогов в стиле macOS/iOS.
 *
 * <p>Собирает характерный вид прототипа: неотдекорированное окно
 * ({@code setUndecorated(true)}), прозрачный корень со скруглённой «карточкой»
 * и мягкой тенью ({@link RoundedShadowBorder}), закрытие по {@code Escape},
 * единые внешние отступы {@link UiTheme#GAP_MD}.</p>
 *
 * <p>Использование (наследник только наполняет тело и футер):</p>
 * <pre>
 * public class MyDialog extends BaseDialog {
 *     public MyDialog(Frame owner) {
 *         super(owner, "Заголовок", true);
 *         setCardContent(body(), footer());
 *     }
 * }
 * </pre>
 *
 * <p>Ранее {@code RoundedShadowBorder} был приватным вложенным классом
 * {@link SettingsDialog}; теперь он общий и переиспользуется всеми модалками.</p>
 */
public class BaseDialog extends JDialog {

    /** Корневая «карточка» диалога (скруглённый фон с тенью). */
    private final JPanel card = new JPanel(new BorderLayout());

    public BaseDialog(Frame owner, String title, boolean modal) {
        super(owner, title, modal);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setUndecorated(true);

        card.setBackground(Ui.SURFACE);
        card.setBorder(new RoundedShadowBorder());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(0, 0, 0, 0));
        root.setBorder(new EmptyBorder(Ui.GAP_MD, Ui.GAP_MD, Ui.GAP_MD, Ui.GAP_MD));
        root.add(card, BorderLayout.CENTER);
        super.setContentPane(root);

        installEscapeToClose();
    }

    /**
     * Размещает содержимое внутри скруглённой карточки диалога.
     *
     * @param center центральный контент (тело)
     * @param south  нижняя панель (обычно кнопки) или {@code null}
     */
    protected void setCardContent(Component center, Component south) {
        card.removeAll();
        if (center != null) {
            card.add(center, BorderLayout.CENTER);
        }
        if (south != null) {
            card.add(south, BorderLayout.SOUTH);
        }
        card.revalidate();
        card.repaint();
    }

    /** Ставит контент вверху карточки (например, полоса вкладок). */
    protected void setCardNorth(Component north) {
        card.add(north, BorderLayout.NORTH);
    }

    /** Доступ к корневой карточке (для тонкой настройки наследниками). */
    protected JPanel card() {
        return card;
    }

    /**
     * UI-2.4: закрытие диалога по Escape (как в прототипе:
     * {@code document.addEventListener('keydown', ... ESC)}).
     */
    private void installEscapeToClose() {
        getRootPane().registerKeyboardAction(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onEscape();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    /** Реакция на Escape — по умолчанию закрыть диалог. Наследник может переопределить. */
    protected void onEscape() {
        dispose();
    }

    /**
     * Рисует скруглённую рамку с мягкой тенью вокруг контента диалога
     * (перенесено из {@link SettingsDialog}). Общий для всех модалок UI-2.4.
     */
    public static class RoundedShadowBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = Ui.RADIUS_DIALOG;
            // Тень
            g2.setColor(new Color(0, 0, 0, 30));
            g2.fillRoundRect(x + 2, y + 4, width - 4, height - 4, r, r);
            // Фон
            g2.setColor(Ui.SURFACE);
            g2.fillRoundRect(x, y, width - 4, height - 6, r, r);
            // Рамка
            g2.setColor(Ui.BORDER);
            g2.drawRoundRect(x, y, width - 4, height - 6, r, r);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(4, 4, 8, 4);
        }
    }

    /** Фабрика стандартной панели кнопок диалога (Отмена + primary-действие). */
    public static JPanel buttonBar(JComponent... buttons) {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        row.setBackground(Ui.SURFACE);
        row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.BORDER));
        for (JComponent b : buttons) {
            row.add(b);
        }
        return row;
    }
}
