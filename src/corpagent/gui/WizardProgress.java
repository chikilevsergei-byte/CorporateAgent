package corpagent.gui;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * UI-2.1: панель прогресса мастера — ряд нумерованных кружков с подписями.
 *
 * <p>Состояния шага: {@code active} (текущий, синяя заливка), {@code completed}
 * (пройденный, галочка/синяя заливка), обычный (серый контур). Кружки соединены
 * линиями-разделителями. Используется в мастере «Создать проект».</p>
 */
public class WizardProgress extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int CIRCLE = 28;

    private final String[] labels;
    private int current; // 0-based индекс активного шага
    private final Dot[] dots;

    /** Кружок-номер с подписью. */
    private static class Dot extends JComponent {
        private final int number;
        private final String label;
        private boolean active;
        private boolean completed;

        Dot(int number, String label) {
            this.number = number;
            this.label = label;
            setPreferredSize(new Dimension(CIRCLE, CIRCLE + 20));
            setMinimumSize(new Dimension(CIRCLE, CIRCLE + 20));
            setMaximumSize(new Dimension(CIRCLE, CIRCLE + 20));
        }

        void setState(boolean active, boolean completed) {
            this.active = active;
            this.completed = completed;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = (getWidth() - CIRCLE) / 2;
            int cy = 0;
            boolean filled = active || completed;
            if (filled) {
                g2.setColor(Ui.PRIMARY);
                g2.fillOval(cx, cy, CIRCLE, CIRCLE);
            } else {
                g2.setColor(Ui.BORDER_INPUT);
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.drawOval(cx + 1, cy + 1, CIRCLE - 2, CIRCLE - 2);
            }
            String txt = String.valueOf(number);
            g2.setFont(Ui.FONT_SMALL_BOLD);
            g2.setColor(filled ? Color.WHITE : Ui.TEXT_MUTED);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(txt);
            g2.drawString(txt, cx + (CIRCLE - tw) / 2, cy + (CIRCLE + fm.getAscent() - 4) / 2);
            // Подпись под кружком.
            g2.setFont(Ui.FONT_SMALL);
            g2.setColor(active ? Ui.PRIMARY : Ui.TEXT_MUTED);
            java.awt.FontMetrics fm2 = g2.getFontMetrics();
            int lw = fm2.stringWidth(label);
            g2.drawString(label, (getWidth() - lw) / 2, CIRCLE + 14);
            g2.dispose();
        }
    }

    public WizardProgress(String... labels) {
        this.labels = labels;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(new EmptyBorder(4, 0, 8, 0));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        dots = new Dot[labels.length];
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                JPanel line = new JPanel();
                line.setBackground(Ui.BORDER);
                line.setPreferredSize(new Dimension(40, 2));
                line.setMaximumSize(new Dimension(60, 2));
                line.setAlignmentY(Component.TOP_ALIGNMENT);
                add(line);
            }
            Dot d = new Dot(i + 1, labels[i]);
            d.setAlignmentY(Component.TOP_ALIGNMENT);
            dots[i] = d;
            add(d);
        }
        add(Box.createHorizontalGlue());
        setCurrent(0);
    }

    /** Устанавливает активный шаг (0-based) и перекрашивает состояние. */
    public void setCurrent(int step) {
        this.current = step;
        for (int i = 0; i < dots.length; i++) {
            dots[i].setState(i == step, i < step);
        }
        repaint();
    }

    /** Число шагов. */
    public int stepCount() {
        return labels.length;
    }

    /** Индекс активного шага (0-based). */
    public int currentStep() {
        return current;
    }

    /** Подпись шага (для теста/использования). */
    public String labelAt(int i) {
        return labels[i];
    }

    /** Помощник: строка состояния шага ('active'/'completed'/'') — для смоук-теста. */
    public String stateOf(int i) {
        if (i == current) {
            return "active";
        }
        return i < current ? "completed" : "";
    }
}
