package corpagent.gui;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Dimension;

/**
 * UI-3.5: «пустое состояние» списка (empty state) в стиле macOS/iOS — крупная
 * бледная иконка-эмодзи сверху, заголовок и подсказка под ней.
 *
 * <p>Перенос из прототипа (блоки «Пока нет чатов», «Что я умею» и т.п.):
 * центрированный вертикальный столбец, приглушённый цвет, короткая подсказка
 * «что сделать, чтобы здесь что-то появилось».</p>
 *
 * <p>Использование:</p>
 * <pre>
 * panel.add(new EmptyState("💬", "Пока нет чатов",
 *                          "Создайте проект или новый чат слева"));
 * </pre>
 */
public class EmptyState extends JPanel {

    private static final long serialVersionUID = 1L;

    private final String icon;
    private final String title;
    private final String hint;

    /**
     * @param icon  эмодзи-иконка (крупная)
     * @param title короткий заголовок (что здесь появится)
     * @param hint  подсказка, как это создать (может быть {@code null})
     */
    public EmptyState(String icon, String title, String hint) {
        this.icon = icon;
        this.title = title;
        this.hint = hint;

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.CENTER_ALIGNMENT);
        setBorder(new EmptyBorder(28, 16, 28, 16));

        // Иконка рисуется вручную (крупный эмодзи приглушённого цвета).
        IconGlyph glyph = new IconGlyph(icon);
        glyph.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(glyph);

        add(Box.createVerticalStrut(Ui.GAP_SM));

        JLabel titleLbl = new JLabel(title == null ? "" : title);
        titleLbl.setFont(Ui.FONT_SMALL_BOLD);
        titleLbl.setForeground(Ui.TEXT_SECONDARY);
        titleLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        titleLbl.setHorizontalAlignment(JLabel.CENTER);
        add(titleLbl);

        if (hint != null && !hint.isEmpty()) {
            add(Box.createVerticalStrut(Ui.GAP_XS));
            JLabel hintLbl = new JLabel("<html><div style='text-align:center;width:220px'>"
                    + escape(hint) + "</div></html>");
            hintLbl.setFont(Ui.FONT_SMALL);
            hintLbl.setForeground(Ui.TEXT_MUTED);
            hintLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
            hintLbl.setHorizontalAlignment(JLabel.CENTER);
            add(hintLbl);
        }
    }

    /** Иконка-эмодзи крупного размера с приглушённым цветом. */
    static class IconGlyph extends JLabel {
        private static final long serialVersionUID = 1L;
        private final int size = 40;

        IconGlyph(String emoji) {
        super(emoji);
        setFont(UiTheme.iconFont(size));
        setForeground(Ui.TEXT_MUTED);
            setHorizontalAlignment(CENTER);
            setVerticalAlignment(CENTER);
            setPreferredSize(new Dimension(size + 8, size + 8));
            setMinimumSize(new Dimension(size + 8, size + 8));
            setMaximumSize(new Dimension(size + 8, size + 8));
            setOpaque(false);
        }
    }

    /** Заголовок пустого состояния (для смоук-теста). */
    public String titleText() {
        return title;
    }

    /** Подсказка пустого состояния (для смоук-теста). */
    public String hintText() {
        return hint;
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

