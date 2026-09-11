package corpagent.gui;


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
 *
 * <p><b>C6 (UI в духе Apple/iOS).</b> Палитра и типографика приведены к
 * iOS-системному виду: почти белый фон (#F2F2F7, systemGroupedBackground),
 * белые «карточки» (SURFACE), системные разделители, акцент iOS-blue (#007AFF)
 * как {@link #PRIMARY}, «system green/red/orange» в семантических цветах.
 * Имена констант не менялись — это обратная совместимость для всего GUI.</p>
 */
public class UiTheme {

    // ==================== ПАЛИТРА (iOS Design System) ====================
    // ВНИМАНИЕ: цвета палитры НЕ final — их переопределяет applyTheme(...) при
    // переключении светлой/тёмной темы (UI-0.2). Компоненты читают их как
    // Ui.PRIMARY/Ui.BG в момент создания, поэтому смена темы требует пересборки
    // дерева UI (см. MainFrame.applyTheme).
    // Light = системный акцент iOS (system blue). Кнопки, ссылки, активные табы.
    public static Color PRIMARY = new Color(0, 122, 255);       // #007AFF iOS system blue
    public static Color PRIMARY_HOVER = new Color(0, 105, 220);  // темнее на hover
    public static Color PRIMARY_ACTIVE = new Color(0, 90, 190);  // ещё темнее на press
    public static Color PRIMARY_DARK = new Color(0, 109, 230);   // alias (обратная совместимость)
    public static Color PRIMARY_SOFT = new Color(232, 240, 254); // #E8F0FE blue-50 (подложки)

    // Background / Surface / Border (systemGroupedBackground / separator)
    public static Color BG = new Color(242, 242, 247);          // #F2F2F7 iOS grouped background
    public static Color SURFACE = Color.WHITE;                  // #FFFFFF карточки/списки
    public static Color BORDER = new Color(229, 229, 234);      // #E5E5EA iOS separator (opaque)
    public static Color BORDER_INPUT = new Color(209, 209, 214); // #D1D1D6 iOS systemGray4

    // Text (label / secondaryLabel / systemGray)
    public static Color TEXT = new Color(28, 28, 30);           // #1C1C1E iOS label
    public static Color TEXT_SECONDARY = new Color(60, 60, 67); // #3C3C43 iOS secondaryLabel
    public static Color TEXT_MUTED = new Color(142, 142, 147);  // #8E8E93 iOS systemGray

    // Semantic (system green / red / orange)
    public static Color SUCCESS = new Color(52, 199, 89);       // #34C759 iOS system green
    public static Color ERROR = new Color(255, 59, 48);         // #FF3B30 iOS system red
    public static Color ERROR_SOFT = new Color(255, 235, 233);  // мягкая подложка ошибки
    public static Color WARNING = new Color(255, 149, 0);       // #FF9500 iOS system orange

    // Hover / selection (systemFill / tinted selection)
    public static Color HOVER = new Color(239, 239, 244);       // #EFEFF4 фон-подложка hover
    public static Color SELECTED = new Color(223, 236, 255);    // #DFECFF голубая подсветка

    // ---- Обратная совместимость (старые имена) ----
    public static Color BG_LIGHT = new Color(235, 245, 255);    // голубая-50 подложка
    public static Color BG_USER = new Color(0, 122, 255);       // пузырь пользователя (iOS blue)
    public static Color BORDER_USER = new Color(0, 122, 255);   // рамка пузыря пользователя
    public static Color SIDEBAR_BG = new Color(242, 242, 247);  // grouped background
    public static Color TEXT_DARK = new Color(28, 28, 30);      // #1C1C1E
    public static Color TEXT_GRAY = new Color(142, 142, 147);   // #8E8E93
    public static final Color WHITE = Color.WHITE;
    public static final Color BLACK = new Color(0, 0, 0);            // #000000

    // ==================== ШРИФТЫ ====================
    // SF Pro на macOS, Segoe UI на Windows — максимально близко к системному виду Apple.
    public static final String FONT = pickSystemFont("SF Pro Text", "Segoe UI", "Helvetica Neue");
    public static final String EMOJI_FONT = "Segoe UI Emoji";

    public static final Font FONT_PLAIN = new Font(FONT, Font.PLAIN, 14);
    public static final Font FONT_BOLD = new Font(FONT, Font.BOLD, 14);
    public static final Font FONT_SMALL = new Font(FONT, Font.PLAIN, 12);
    public static final Font FONT_SMALL_BOLD = new Font(FONT, Font.BOLD, 12);
    public static final Font FONT_HEADER = new Font(FONT, Font.BOLD, 16);
    public static final Font FONT_TITLE = new Font(FONT, Font.BOLD, 20);
    public static final Font FONT_INPUT = new Font(FONT, Font.PLAIN, 15);
    public static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 13);

    // ==================== СКРУГЛЕНИЯ ====================
    // iOS: крупные непрерывные скругления (continuous corner).
    public static final int RADIUS_SM = 6;      // мелкие чипы/индикаторы
    public static final int RADIUS_BTN = 10;
    public static final int RADIUS_INPUT = 10;
    public static final int RADIUS_CARD = 14;
    public static final int RADIUS_XL = 18;
    public static final int RADIUS_DIALOG = 18;
    public static final int RADIUS_2XL = 24;    // крупные модалки/карточки

    // ==================== ОТСТУПЫ (spacing scale) ====================
    // Единая шкала интервалов вместо магических чисел в экранах.
    public static final int GAP_XS = 4;
    public static final int GAP_SM = 8;
    public static final int GAP_MD = 16;
    public static final int GAP_LG = 24;
    public static final int GAP_XL = 32;
    public static final int GAP_2XL = 48;

    // ==================== ДОПОЛНИТЕЛЬНАЯ ТИПОГРАФИКА ====================
    public static final Font FONT_XL = new Font(FONT, Font.BOLD, 22);
    public static final Font FONT_2XL = new Font(FONT, Font.BOLD, 28);

    /** Единый «кликабельный» курсор (рука) для интерактивных элементов. */
    public static java.awt.Cursor handCursor() {
        return java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR);
    }

    // ==================== ТЕМА (UI-0.2) ====================
    // Светлая («green»/«blue») и тёмная палитры. applyTheme(...) переопределяет
    // статические цвета выше; после вызова нужно пересобрать дерево UI, чтобы
    // компоненты перечитали Ui.BG/Ui.TEXT и т.п.

    /** Текущее имя темы: "green"/"blue" (светлая) или "dark" (тёмная). */
    private static String currentTheme = "green";

    /** Имя активной темы. */
    public static String currentThemeName() {
        return currentTheme;
    }

    /** Активна ли тёмная палитра. */
    public static boolean isDark() {
        return "dark".equals(currentTheme);
    }

    /**
     * Применяет палитру по имени темы (из settings.json).
     * Значения, отличные от "dark", трактуются как светлая тема.
     *
     * <p>Меняет статические цвета палитры. Чтобы изменения стали видны, вызывающий
     * код должен пересобрать UI (например, {@code MainFrame.applyTheme}).</p>
     */
    public static void applyTheme(String name) {
        currentTheme = (name == null) ? "green" : name;
        if ("dark".equals(currentTheme)) {
            applyDarkPalette();
        } else {
            applyLightPalette();
        }
    }

    /** Восстанавливает светлую iOS-палитру (значения по умолчанию). */
    private static void applyLightPalette() {
        PRIMARY = new Color(0, 122, 255);
        PRIMARY_HOVER = new Color(0, 105, 220);
        PRIMARY_ACTIVE = new Color(0, 90, 190);
        PRIMARY_DARK = new Color(0, 109, 230);
        PRIMARY_SOFT = new Color(232, 240, 254);

        BG = new Color(242, 242, 247);
        SURFACE = Color.WHITE;
        BORDER = new Color(229, 229, 234);
        BORDER_INPUT = new Color(209, 209, 214);

        TEXT = new Color(28, 28, 30);
        TEXT_SECONDARY = new Color(60, 60, 67);
        TEXT_MUTED = new Color(142, 142, 147);

        SUCCESS = new Color(52, 199, 89);
        ERROR = new Color(255, 59, 48);
        ERROR_SOFT = new Color(255, 235, 233);
        WARNING = new Color(255, 149, 0);

        HOVER = new Color(239, 239, 244);
        SELECTED = new Color(223, 236, 255);

        BG_LIGHT = new Color(235, 245, 255);
        BG_USER = new Color(0, 122, 255);
        BORDER_USER = new Color(0, 122, 255);
        SIDEBAR_BG = new Color(242, 242, 247);
        TEXT_DARK = new Color(28, 28, 30);
        TEXT_GRAY = new Color(142, 142, 147);
    }

    /** Тёмная iOS-палитра (systemGroupedBackground dark / label dark). */
    private static void applyDarkPalette() {
        PRIMARY = new Color(10, 132, 255);        // #0A84FF iOS system blue (dark)
        PRIMARY_HOVER = new Color(64, 156, 255);
        PRIMARY_ACTIVE = new Color(120, 180, 255);
        PRIMARY_DARK = new Color(10, 132, 255);
        PRIMARY_SOFT = new Color(28, 45, 71);      // приглушённая синяя подложка

        BG = new Color(28, 28, 30);                // #1C1C1E iOS systemGroupedBackground (dark)
        SURFACE = new Color(44, 44, 46);           // #2C2C2E карточки
        BORDER = new Color(56, 56, 58);            // #38383A разделитель
        BORDER_INPUT = new Color(72, 72, 74);      // #48484A

        TEXT = new Color(245, 245, 247);           // #F5F5F7 label (dark)
        TEXT_SECONDARY = new Color(161, 161, 166); // #A1A1A6 secondaryLabel
        TEXT_MUTED = new Color(142, 142, 147);     // #8E8E93 systemGray

        SUCCESS = new Color(48, 209, 88);          // #30D158 (dark)
        ERROR = new Color(255, 69, 58);            // #FF453A (dark)
        ERROR_SOFT = new Color(66, 32, 30);
        WARNING = new Color(255, 159, 10);         // #FF9F0A (dark)

        HOVER = new Color(58, 58, 60);
        SELECTED = new Color(38, 58, 88);

        BG_LIGHT = new Color(38, 48, 64);
        BG_USER = new Color(10, 132, 255);
        BORDER_USER = new Color(10, 132, 255);
        SIDEBAR_BG = new Color(28, 28, 30);
        TEXT_DARK = new Color(245, 245, 247);
        TEXT_GRAY = new Color(142, 142, 147);
    }

    // ==================== ИКОНКИ ДЕЙСТВИЙ (UI-0.3) ====================
    // Единый размер/отступ для иконок кнопок и пунктов меню, чтобы не
    // размазывать магические числа (14/16/18) по экранам.

    /** Стандартный размер эмодзи-иконки действия (в пунктах). */
    public static final int ACTION_ICON_SIZE = 16;
    /** Стандартный зазор между иконкой и подписью действия. */
    public static final int ACTION_ICON_GAP = GAP_SM;

    /** Эмодзи-иконка действия стандартного размера ({@link #ACTION_ICON_SIZE}). */
    public static Icon actionIcon(String emoji) {
        return iconImage(emoji, ACTION_ICON_SIZE);
    }

    /** Эмодзи-иконка действия заданного размера. */
    public static Icon actionIcon(String emoji, int size) {
        return iconImage(emoji, size);
    }

    /**
     * Ставит эмодзи-иконку действия на кнопку/пункт меню с единым зазором.
     * Возвращает сам компонент для чейнинга.
     */
    public static <T extends javax.swing.AbstractButton> T withActionIcon(T b, String emoji) {
        b.setIcon(actionIcon(emoji));
        b.setIconTextGap(ACTION_ICON_GAP);
        return b;
    }

    /** Возвращает первый доступный системный шрифт из списка (или последний как fallback). */
    private static String pickSystemFont(String... families) {
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            for (String want : families) {
                for (String have : available) {
                    if (want.equalsIgnoreCase(have)) {
                        return have;
                    }
                }
            }
        } catch (Exception ignored) {
            // в headless-окружении список шрифтов может быть недоступен
        }
        return families[families.length - 1];
    }

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
     * Рисует скруглённую рамку кнопки/поля/карточки.
     *
     * <p>Компонент должен иметь {@code setOpaque(true)} — фон рисует сам компонент
     * в {@code paintComponent()} ДО рамки; здесь рисуется только скруглённый контур
     * (paintBorder вызывается ПОСЛЕ paintComponent и не должен перекрывать содержимое).</p>
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
            // Не заливаем фон — рисуем только скруглённую рамку.
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
