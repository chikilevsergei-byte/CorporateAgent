package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPasswordField;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Стилизация UI-компонентов поверх дизайн-токенов {@link UiTheme}.
 *
 * <p>Класс наследует {@link UiTheme}, поэтому все константы палитры/шрифтов и
 * вложенный {@link UiTheme.RoundedBorder} доступны как {@code Ui.PRIMARY},
 * {@code Ui.FONT_PLAIN}, {@code Ui.RoundedBorder} — старый код менять не нужно.</p>
 *
 * <p>Здесь собраны только методы стилизации: кнопки, поля ввода, табы, карточки,
 * JFileChooser и hover/focus-эффекты. Палитра, шрифты, скругления и отрисовка
 * эмодзи-иконок вынесены в {@link UiTheme}.</p>
 */
public final class Ui extends UiTheme {

    private Ui() {
    }

    /** Устанавливает стандартный шрифт компоненту. */
    public static void font(JComponent c, Font f) {
        c.setFont(f);
    }

    // ==================== КНОПКИ ====================

    /**
     * Применяет современный стиль к кнопке (обратная совместимость).
     *
     * @param primary true — зелёная заливка с чёрным текстом; false — светлая кнопка
     */
    public static void styleButton(JButton b, boolean primary) {
        if (primary) {
            stylePrimary(b);
        } else {
            styleSecondary(b);
        }
    }

    /** Primary-кнопка: зелёная заливка, чёрный текст, скругление, hover. */
    public static void stylePrimary(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(BLACK);
        b.setBackground(PRIMARY);
        b.setBorder(new RoundedBorder(PRIMARY, RADIUS_BTN, 1));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setFocusable(true);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setMargin(new Insets(7, 16, 7, 16));
        installHover(b, PRIMARY, PRIMARY_HOVER, PRIMARY_ACTIVE, BLACK);
    }

    /** Secondary-кнопка: прозрачный фон, серая рамка, чёрный текст. */
    public static void styleSecondary(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(BLACK);
        b.setBackground(SURFACE);
        b.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_BTN, 1));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setMargin(new Insets(7, 16, 7, 16));
        installHover(b, SURFACE, HOVER, new Color(229, 231, 235), BLACK);
    }

    /** Danger-кнопка: прозрачный фон, красный текст, hover с красной подложкой. */
    public static void styleDanger(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(ERROR);
        b.setBackground(SURFACE);
        b.setBorder(new RoundedBorder(new Color(252, 165, 165), RADIUS_BTN, 1));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setMargin(new Insets(7, 16, 7, 16));
        installHover(b, SURFACE, ERROR_SOFT, new Color(254, 202, 202), ERROR);
    }

    /** Icon-кнопка: квадратная, без рамки, hover-подложка, чёрный текст/иконка. */
    public static void styleIcon(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(BLACK);
        b.setBackground(new Color(0, 0, 0, 0));
        b.setBorder(new EmptyBorder(6, 6, 6, 6));
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(36, 36));
        installHover(b, new Color(0, 0, 0, 0), HOVER, new Color(229, 231, 235), BLACK);
    }

    /** Длительность плавного перехода цвета (мс). */
    private static final int TRANSITION_MS = 160;

    /** Устанавливает hover/active-эффекты для кнопки с заливкой (плавный переход). */
    private static void installHover(JButton b, final Color normal, final Color hover,
                                     final Color active, final Color fg) {
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) {
                    animateColor(b, hover);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                animateColor(b, normal);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (b.isEnabled()) {
                    animateColor(b, active);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                animateColor(b, hover);
            }
        });
    }

    /**
     * Плавно анимирует фоновый цвет компонента от текущего к целевому
     * за TRANSITION_MS миллисекунд с помощью Swing Timer.
     */
    private static void animateColor(final JComponent c, final Color target) {
        final Color from = c.getBackground();
        if (from == null || from.equals(target)) {
            c.setBackground(target);
            c.repaint();
            return;
        }
        final int steps = Math.max(4, TRANSITION_MS / 16);
        final float[] f = from.getRGBColorComponents(null);
        final float[] t = target.getRGBColorComponents(null);
        final javax.swing.Timer timer = new javax.swing.Timer(16, null);
        final int[] step = {0};
        timer.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                step[0]++;
                float p = Math.min(1f, (float) step[0] / steps);
                float ease = 1f - (1f - p) * (1f - p); // ease-out
                int r = (int) (f[0] + (t[0] - f[0]) * ease);
                int g = (int) (f[1] + (t[1] - f[1]) * ease);
                int b = (int) (f[2] + (t[2] - f[2]) * ease);
                c.setBackground(new Color(r, g, b));
                c.repaint();
                if (p >= 1f) {
                    timer.stop();
                }
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    // ==================== ПОЛЯ ВВОДА ====================

    /**
     * Стилизует текстовое поле: скругление, рамка, focus-состояние.
     *
     * <p>ВАЖНО: компонент остаётся НЕПРОЗРАЧНЫМ (opaque=true) с белым фоном.
     * Прозрачные текстовые поля (opaque=false) в Swing некорректно отрисовывают
     * текст/каретку — введённый текст становится невидимым. Поэтому фон рисует сам
     * компонент, а рамка со скруглением накладывается поверх.</p>
     */
    public static void styleField(JTextField f) {
        f.setFont(FONT_INPUT);
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setBackground(SURFACE);
        f.setOpaque(true);
        f.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_INPUT, 1));
        installFieldFocus(f);
    }

    /** Стилизует многострочное поле: скругление, рамка, focus-состояние. */
    public static void styleArea(JTextArea a) {
        a.setFont(FONT_INPUT);
        a.setForeground(TEXT);
        a.setCaretColor(TEXT);
        a.setBackground(SURFACE);
        a.setOpaque(true);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_INPUT, 1));
        installFieldFocus(a);
    }

    /** Стилизует поле пароля: скругление, рамка, focus-состояние. */
    public static void stylePassword(JPasswordField p) {
        p.setFont(FONT_INPUT);
        p.setForeground(TEXT);
        p.setCaretColor(TEXT);
        p.setBackground(SURFACE);
        p.setOpaque(true);
        p.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_INPUT, 1));
        installFieldFocus(p);
    }

    /** Добавляет focus-эффект (зелёная рамка + мягкая тень) для поля. */
    private static void installFieldFocus(final JComponent c) {
        c.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                c.setBorder(new RoundedBorder(PRIMARY, RADIUS_INPUT, 2));
                c.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                c.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_INPUT, 1));
                c.repaint();
            }
        });
    }

    // ==================== ДИАЛОГ ФАЙЛОВ ====================

    /**
     * Применяет единый стиль к диалогу выбора файла (JFileChooser):
     * светлый фон, стандартный шрифт. Полная кастомизация внутренностей
     * JFileChooser ограничена платформенным L&F, поэтому стилизуем доступное.
     */
    public static void styleFileChooser(JFileChooser fc) {
        fc.setBackground(SURFACE);
        fc.setFont(FONT_PLAIN);
        fc.setForeground(TEXT);
        fc.updateUI();
    }

    // ==================== ПАНЕЛИ / КАРТОЧКИ ====================

    /** Создаёт панель-карточку с фоном Surface и скруглением. */
    public static JPanel card() {
        JPanel p = new JPanel();
        p.setBackground(SURFACE);
        p.setBorder(new RoundedBorder(BORDER, RADIUS_CARD, 1));
        // Opaque=true: панель сама закрашивает свой фон в paintComponent ДО отрисовки
        // текста/детей, чтобы RoundedBorder (paintBorder, идёт ПОСЛЕ paintComponent)
        // не закрашивал содержимое поверх.
        p.setOpaque(true);
        return p;
    }

    // ==================== СОВРЕМЕННЫЕ ТАБЫ ====================

    /**
     * Стилизует кнопку-вкладку: активная — жирный текст + нижняя рамка 2px Primary,
     * неактивная — серый текст без рамки.
     */
    public static void styleTab(JButton b, boolean active) {
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setFont(active ? FONT_BOLD : FONT_PLAIN);
        b.setForeground(active ? PRIMARY : TEXT_SECONDARY);
        b.setBorder(active
                ? BorderFactory.createMatteBorder(0, 0, 2, 0, PRIMARY)
                : new EmptyBorder(10, 16, 10, 16));
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
    }
}
