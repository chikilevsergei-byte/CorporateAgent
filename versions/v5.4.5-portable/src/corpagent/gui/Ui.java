package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
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
 *
 * <p><b>C6 (UI в духе Apple/iOS).</b> Кнопки получили «сплошной системный» вид
 * (filled = белый текст на tint-цвете; secondary = системная серая подложка с
 * синим текстом), поля — тонкую системную рамку с синим focus-состоянием,
 * добавлены крупные iOS-скругления и системные разделители.</p>
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
     * @param primary true — заполненная системная кнопка (tint); false — серая
     */
    public static void styleButton(JButton b, boolean primary) {
        if (primary) {
            stylePrimary(b);
        } else {
            styleSecondary(b);
        }
    }

    /** Primary-кнопка (как iOS filled): системная заливка, БЕЛЫЙ текст, скругление, hover. */
    public static void stylePrimary(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(WHITE);
        b.setBackground(PRIMARY);
        b.setBorder(new RoundedBorder(PRIMARY, RADIUS_BTN, 1));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setFocusable(true);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setMargin(new Insets(7, 16, 7, 16));
        installHover(b, PRIMARY, PRIMARY_HOVER, PRIMARY_ACTIVE, WHITE);
    }

    /**
     * Secondary-кнопка (как iOS tinted/grey): системная серая подложка
     * (systemGray6), синий текст, лёгкая рамка.
     */
    public static void styleSecondary(JButton b) {
        Color fill = new Color(242, 242, 247);      // systemGray6
        Color fillHover = new Color(232, 232, 237);  // чуть темнее
        Color fillActive = new Color(222, 222, 228);
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(PRIMARY);
        b.setBackground(fill);
        b.setBorder(new RoundedBorder(new Color(0, 0, 0, 0), RADIUS_BTN, 1));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setMargin(new Insets(7, 16, 7, 16));
        installHover(b, fill, fillHover, fillActive, PRIMARY);
    }

    /** Danger-кнопка: светлый фон, системный красный текст, hover с мягкой подложкой. */
    public static void styleDanger(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(ERROR);
        b.setBackground(SURFACE);
        b.setBorder(new RoundedBorder(new Color(0, 0, 0, 0), RADIUS_BTN, 1));
        b.setOpaque(true);
        b.setContentAreaFilled(true);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setMargin(new Insets(7, 16, 7, 16));
        installHover(b, SURFACE, ERROR_SOFT, new Color(255, 224, 221), ERROR);
    }

    /** Icon-кнопка: квадратная, без рамки, hover-подложка. */
    public static void styleIcon(JButton b) {
        b.setFocusPainted(false);
        b.setFont(FONT_PLAIN);
        b.setForeground(PRIMARY);
        b.setBackground(new Color(0, 0, 0, 0));
        b.setBorder(new EmptyBorder(6, 6, 6, 6));
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(36, 36));
        installHover(b, new Color(0, 0, 0, 0), HOVER, new Color(222, 222, 228), PRIMARY);
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
     * Стилизует текстовое поле: скругление, лёгкая системная рамка, focus-состояние.
     *
     * <p>ВАЖНО: компонент остаётся НЕПРОЗРАЧНЫМ (opaque=true) с белым фоном.
     * Прозрачные текстовые поля (opaque=false) в Swing некорректно отрисовывают
     * текст/каретку — введённый текст становится невидимым. Поэтому фон рисует сам
     * компонент, а рамка со скруглением накладывается поверх.</p>
     */
    public static void styleField(JTextField f) {
        f.setFont(FONT_INPUT);
        f.setForeground(TEXT);
        f.setCaretColor(PRIMARY);
        f.setBackground(SURFACE);
        f.setOpaque(true);
        f.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_INPUT, 1));
        installFieldFocus(f);
    }

    /** Стилизует многострочное поле: скругление, рамка, focus-состояние. */
    public static void styleArea(JTextArea a) {
        a.setFont(FONT_INPUT);
        a.setForeground(TEXT);
        a.setCaretColor(PRIMARY);
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
        p.setCaretColor(PRIMARY);
        p.setBackground(SURFACE);
        p.setOpaque(true);
        p.setBorder(new RoundedBorder(BORDER_INPUT, RADIUS_INPUT, 1));
        installFieldFocus(p);
    }

    /** Добавляет focus-эффект (синяя рамка) для поля. */
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

    /** Создаёт панель-карточку с фоном Surface и крупным скруглением (iOS grouped). */
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

    /**
     * Рисует горизонтальный системный разделитель-линию (iOS separator).
     * Используйте вместо явных «рамок-контуров» для группировки.
     */
    public static JPanel separator() {
        JPanel s = new JPanel();
        s.setBackground(BORDER);
        s.setPreferredSize(new Dimension(1, 1));
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    // ==================== РАДИО-КАРТОЧКИ (UI-1.3) ====================

    /**
     * UI-1.3: кликабельная «радио-карточка» — tint-подложка и синяя рамка при выборе.
     * Возвращает панель; выбор отмечается внутри через {@link RadioCard#setSelected(boolean)}.
     * Радио скрыт (управление — клик по всей карточке).
     */
    public static RadioCard radioCard(String title, String subtitle, boolean selected) {
        return new RadioCard(title, subtitle, selected);
    }

    /** Кликабельная радио-карточка (iOS selection card). */
    public static class RadioCard extends JPanel {
        private static final long serialVersionUID = 1L;
        private final javax.swing.JRadioButton radio = new javax.swing.JRadioButton();
        private Runnable onSelect;
        private boolean selected;

        RadioCard(String title, String subtitle, boolean selected) {
            setLayout(new java.awt.BorderLayout(10, 0));
            setOpaque(true);
            setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            setBorder(new javax.swing.border.EmptyBorder(10, 12, 10, 12));
            setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, subtitle == null ? 44 : 62));

            radio.setOpaque(false);
            radio.setFocusPainted(false);
            radio.setEnabled(false);
            radio.setSelected(selected);
            add(radio, java.awt.BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
            text.setOpaque(false);
            JLabel t = new JLabel(title);
            t.setFont(FONT_PLAIN);
            t.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            text.add(t);
            if (subtitle != null && !subtitle.isEmpty()) {
                JLabel s = new JLabel(subtitle);
                s.setFont(FONT_SMALL);
                s.setForeground(TEXT_MUTED);
                s.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                text.add(s);
            }
            add(text, java.awt.BorderLayout.CENTER);

            final RadioCard self = this;
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!radio.isEnabled()) {
                        setSelected(true);
                        if (onSelect != null) {
                            onSelect.run();
                        }
                    }
                }
            });
            this.selected = selected;
            paintState(selected);
        }

        /** true — если карточка выбрана. */
        public boolean isSelectedCard() {
            return selected;
        }

        /** Меняет визуальное состояние выбора (без вызова onSelect). */
        public void setSelected(boolean value) {
            this.selected = value;
            radio.setSelected(value);
            paintState(value);
            repaint();
        }

        /** Слушатель выбора карточки (вызывается при клике пользователя). */
        public RadioCard onSelect(Runnable action) {
            this.onSelect = action;
            return this;
        }

        private void paintState(boolean value) {
            setBackground(value ? PRIMARY_SOFT : SURFACE);
            setBorder(javax.swing.BorderFactory.createCompoundBorder(
                    new RoundedBorder(value ? PRIMARY : BORDER, RADIUS_BTN, value ? 2 : 1),
                    new javax.swing.border.EmptyBorder(10, 12, 10, 12)));
        }
    }

    // ==================== ФОРМЫ (UI-1.4) ====================

    /**
     * UI-1.4: единый ряд формы — подпись сверху + контрол снизу, с общим отступом
     * {@link UiTheme#GAP_SM}. Уменьшает «магические» Box.createVerticalStrut по
     * всему коду форм.
     */
    public static JPanel formRow(String label, java.awt.Component control) {
        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        if (label != null && !label.isEmpty()) {
            JLabel l = new JLabel(label);
            l.setFont(FONT_SMALL_BOLD);
            l.setForeground(TEXT_SECONDARY);
            l.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            row.add(l);
            row.add(Box.createVerticalStrut(GAP_XS));
        }
        // Единая ширина ряда; высота — по предпочтительной (для полей/кнопок).
        int maxH = control.getPreferredSize() != null ? control.getPreferredSize().height : 30;
        control.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(maxH, 30)));
        if (control instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) control).setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        }
        row.add(control);
        return row;
    }

    // ==================== СЛАЙДЕР (UI-2.3) ====================

    /**
     * UI-2.3: стилизует {@link javax.swing.JSlider} под iOS-вид: тонкий серый трек,
     * синяя заливка от начала до бегунка, круглый бегунок с тенью.
     */
    public static void styleSlider(javax.swing.JSlider s) {
        s.setUI(new SlimSliderUI(s));
        s.setOpaque(false);
    }

    /** Кастомный UI слайдера с заливкой трека до бегунка (iOS-стиль). */
    public static class SlimSliderUI extends javax.swing.plaf.basic.BasicSliderUI {
        private static final int TRACK_H = 4;
        private static final int KNOB_D = 18;
        private final javax.swing.JSlider slider;

        public SlimSliderUI(javax.swing.JSlider s) {
            super(s);
            this.slider = s;
        }

        @Override
        public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + trackRect.height / 2;
            int y = cy - TRACK_H / 2;
            g2.setColor(BORDER_INPUT);
            g2.fillRoundRect(trackRect.x, y, trackRect.width, TRACK_H, TRACK_H, TRACK_H);

            int fillW = thumbRect.x + thumbRect.width / 2 - trackRect.x;
            fillW = Math.max(0, Math.min(fillW, trackRect.width));
            g2.setColor(PRIMARY);
            g2.fillRoundRect(trackRect.x, y, fillW, TRACK_H, TRACK_H, TRACK_H);
            g2.dispose();
        }

        @Override
        public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int x = thumbRect.x + (thumbRect.width - KNOB_D) / 2;
            int y = thumbRect.y + (thumbRect.height - KNOB_D) / 2;
            g2.setColor(new Color(0, 0, 0, 45));
            g2.fillOval(x + 1, y + 2, KNOB_D, KNOB_D);
            g2.setColor(Color.WHITE);
            g2.fillOval(x, y, KNOB_D, KNOB_D);
            g2.setColor(BORDER_INPUT);
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.drawOval(x, y, KNOB_D, KNOB_D);
            g2.dispose();
        }

        @Override
        protected java.awt.Dimension getThumbSize() {
            return new java.awt.Dimension(KNOB_D, KNOB_D);
        }
    }

    // ==================== СОВРЕМЕННЫЕ ТАБЫ ====================

    /**
     * Стилизует кнопку-вкладку: активная — жирный текст + нижняя линия 2px Primary,
     * неактивная — серый текст без линии (iOS segmented/underline).
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
