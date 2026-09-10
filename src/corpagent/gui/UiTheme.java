package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.AbstractBorder;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Дизайн-токены (палитра, шрифты, скругления) и низкоуровневые помощники UI.
 *
 * <p>Выделено из {@link Ui} для разделения ответственности: этот класс содержит
 * <b>только константы и базовые графические примитивы</b> ({@link RoundedBorder},
 * отрисовку эмодзи-иконок). Методы <i>стилизации</i> компонентов остаются в
 * {@link Ui}, который наследует этот класс — поэтому старый код вида
 * {@code Ui.PRIMARY}/{@code Ui.FONT_PLAIN}/{@code Ui.RoundedBorder} продолжает
 * работать без изменений.</p>
 */
public class UiTheme {

    // ==================== ПАЛИТРА (Design System) ====================
    // Primary (emerald)
    public static final Color PRIMARY = new Color(16, 185, 129);      // #10B981
    public static final Color PRIMARY_HOVER = new Color(5, 150, 105); // #059669
    public static final Color PRIMARY_ACTIVE = new Color(4, 120, 87); // #047857
    public static final Color PRIMARY_DARK = new Color(5, 150, 105);  // alias (обратная совместимость)
    public static final Color PRIMARY_SOFT = new Color(236, 253, 245);// #ECFDF5

    // Background / Surface / Border
    public static final Color BG = new Color(249, 250, 251);          // #F9FAFB (slate-50)
    public static final Color SURFACE = Color.WHITE;                  // #FFFFFF
    public static final Color BORDER = new Color(229, 231, 235);      // #E5E7EB (gray-200)
    public static final Color BORDER_INPUT = new Color(209, 213, 219);// #D1D5DB (gray-300)

    // Text
    public static final Color TEXT = new Color(17, 24, 39);           // #111827 (gray-900)
    public static final Color TEXT_SECONDARY = new Color(107, 114, 128); // #6B7280 (gray-500)
    public static final Color TEXT_MUTED = new Color(156, 163, 175);  // #9CA3AF (gray-400)

    // Semantic
    public static final Color SUCCESS = new Color(16, 185, 129);
    public static final Color ERROR = new Color(239, 68, 68);         // #EF4444
    public static final Color ERROR_SOFT = new Color(254, 226, 226);  // #FEE2E2
    public static final Color WARNING = new Color(245, 158, 11);      // #F59E0B

    // Hover / selection greys
    public static final Color HOVER = new Color(243, 244, 246);       // #F3F4F6 (gray-100)
    public static final Color SELECTED = new Color(236, 253, 245);    // #ECFDF5 (emerald-50)

    // ---- Обратная совместимость (старые имена) ----
    public static final Color BG_LIGHT = new Color(240, 253, 244);    // #f0fdf4
    public static final Color BG_USER = new Color(209, 250, 229);     // #d1fae5
    public static final Color BORDER_USER = new Color(167, 243, 208); // #a7f3d0
    public static final Color SIDEBAR_BG = new Color(249, 250, 251);  // slate-50
    public static final Color TEXT_DARK = new Color(17, 24, 39);      // #111827
    public static final Color TEXT_GRAY = new Color(107, 114, 128);   // #6b7280
    public static final Color WHITE = Color.WHITE;
    public static final Color BLACK = new Color(0, 0, 0);            // #000000

    // ==================== ШРИФТЫ ====================
    public static final String FONT = "Segoe UI";
    public static final String EMOJI_FONT = "Segoe UI Emoji";

    public static final Font FONT_PLAIN = new Font(FONT, Font.PLAIN, 14);
    public static final Font FONT_BOLD = new Font(FONT, Font.BOLD, 14);
    public static final Font FONT_SMALL = new Font(FONT, Font.PLAIN, 12);
    public static final Font FONT_SMALL_BOLD = new Font(FONT, Font.BOLD, 12);
    public static final Font FONT_HEADER = new Font(FONT, Font.BOLD, 16);
    public static final Font FONT_TITLE = new Font(FONT, Font.BOLD, 18);
    public static final Font FONT_INPUT = new Font(FONT, Font.PLAIN, 15);
    public static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 13);

    // ==================== СКРУГЛЕНИЯ ====================
    public static final int RADIUS_BTN = 6;
    public static final int RADIUS_INPUT = 6;
    public static final int RADIUS_CARD = 8;
    public static final int RADIUS_DIALOG = 12;

    /** Проверяет, доступен ли шрифт эмодзи в системе. */
    public static boolean emojiFontAvailable() {
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            if (EMOJI_FONT.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Создаёт JLabel с эмодзи-иконкой, отрисованной шрифтом "Segoe UI Emoji".
     * Если шрифт недоступен — использует обычный шрифт (эмодзи может не отобразиться,
     * но приложение продолжит работать).
     */
    public static JLabel icon(String emoji, int size) {
        JLabel l = new JLabel(emoji);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setVerticalAlignment(SwingConstants.CENTER);
        String fam = emojiFontAvailable() ? EMOJI_FONT : FONT;
        l.setFont(new Font(fam, Font.PLAIN, size));
        return l;
    }

    /**
     * Создаёт ImageIcon с эмодзи, отрисованным шрифтом "Segoe UI Emoji".
     * Используется как иконка для кнопок (JButton.setIcon принимает Icon).
     * Если шрифт эмодзи недоступен — рисует обычным шрифтом.
     */
    public static Icon iconImage(String emoji, int size) {
        String fam = emojiFontAvailable() ? EMOJI_FONT : FONT;
        Font f = new Font(fam, Font.PLAIN, size);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = probe.createGraphics();
        g0.setFont(f);
        FontMetrics fm = g0.getFontMetrics();
        int w = fm.stringWidth(emoji) + 4;
        int h = fm.getHeight();
        g0.dispose();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(f);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        int baseline = fm.getAscent();
        g.drawString(emoji, 2, baseline);
        g.dispose();
        return new ImageIcon(img);
    }

    /**
     * Создаёт строку заголовка: эмодзи-иконка слева + жирный текст.
     * Используется вместо текста вида "🧠 Навыки", чтобы иконка
     * отображалась корректно (без прямоугольников).
     */
    public static JPanel header(String emoji, String text) {
        return header(emoji, text, FONT_HEADER);
    }

    public static JPanel header(String emoji, String text, Font textFont) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        JLabel icon = icon(emoji, textFont.getSize() + 2);
        icon.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(icon);
        row.add(Box.createHorizontalStrut(6));
        JLabel label = new JLabel(text);
        label.setFont(textFont);
        label.setForeground(TEXT_DARK);
        label.setAlignmentY(Component.CENTER_ALIGNMENT);
        row.add(label);
        return row;
    }

    /**
     * Рисует скруглённую рамку с заливкой фона кнопки/поля.
     * Компонент должен иметь setOpaque(false) и setContentAreaFilled(false),
     * чтобы фон рисовался здесь со скруглением.
     */
    public static class RoundedBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private final Color color;
        private final int radius;
        private final int thickness;

        public RoundedBorder(Color color, int radius, int thickness) {
            this.color = color;
            this.radius = radius;
            this.thickness = thickness;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = radius;
            int t = thickness;
            // ВАЖНО: НЕ заливаем фон здесь. paintBorder вызывается ПОСЛЕ paintComponent,
            // поэтому заливка фона здесь перекрывала бы текст/содержимое компонента.
            // Компоненты теперь opaque=true и сами красят свой фон в paintComponent.
            // Рисуем только скруглённую рамку.
            g2.setColor(color);
            g2.setStroke(new BasicStroke(t));
            g2.drawRoundRect(x + t / 2, y + t / 2, width - t, height - t, r, r);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            int pad = Math.max(radius / 2, 4);
            return new Insets(pad, pad + 4, pad, pad + 4);
        }
    }
}
