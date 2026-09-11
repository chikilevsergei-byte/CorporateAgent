package corpagent.gui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * UI-3.4: Skeleton loader — «скелетон»-заглушка для долгих операций
 * (генерация схемы БД, ИИ-upsert-план, ожидание ответа LLM).
 *
 * <p>Перенос паттерна прототипа (пульсирующие серые плейсхолдеры) в Swing:
 * прямоугольники-«строки» с анимированной прозрачностью (плавная пульсация
 * alpha от {@value #MIN_ALPHA} до {@value #MAX_ALPHA}). Анимация включается при
 * добавлении в иерархию ({@link #addNotify()}) и останавливается при удалении
 * ({@link #removeNotify()}) — ресурсы не расходуются, когда компонент скрыт.</p>
 *
 * <p>Использование:</p>
 * <pre>
 * SkeletonLoader loader = SkeletonLoader.paragraph(3);
 * panel.add(loader);   // показываем, пока грузятся данные
 * panel.remove(loader); // убираем, когда данные готовы
 * </pre>
 */
public class SkeletonLoader extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final float MIN_ALPHA = 0.35f;
    private static final float MAX_ALPHA = 0.85f;
    private static final int TICK_MS = 40;
    private static final int STEP = 4; // изменение alpha (0..100) за тик

    private final int lines;
    private final int lineHeight;
    private final int gap;
    private int alpha = 40;   // текущая alpha (0..100)
    private int direction = STEP;
    private Timer timer;

    /** @param lines      число «строк»-плейсхолдеров
     *  @param lineHeight высота одной строки (px) */
    public SkeletonLoader(int lines, int lineHeight) {
        this.lines = Math.max(1, lines);
        this.lineHeight = Math.max(8, lineHeight);
        this.gap = Ui.GAP_SM;
        setOpaque(false);
        setFocusable(false);
    }

    /** Стандартный «абзац» из {@code lines} строк. */
    public static SkeletonLoader paragraph(int lines) {
        return new SkeletonLoader(lines, 12);
    }

    /** Круглая «аватар»-заглушка + строка (для индикатора «печатает…»). */
    public static SkeletonLoader message() {
        return new SkeletonLoader(2, 14);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (timer == null) {
            timer = new Timer(TICK_MS, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    alpha += direction;
                    if (alpha >= MAX_ALPHA * 100) {
                        alpha = (int) (MAX_ALPHA * 100);
                        direction = -STEP;
                    } else if (alpha <= MIN_ALPHA * 100) {
                        alpha = (int) (MIN_ALPHA * 100);
                        direction = STEP;
                    }
                    repaint();
                }
            });
            timer.start();
        }
    }

    @Override
    public void removeNotify() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        int h = lines * lineHeight + Math.max(0, lines - 1) * gap;
        return new Dimension(240, h);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        Color base = Ui.BORDER;
        g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.min(255, alpha * 255 / 100)));
        int y = 0;
        for (int i = 0; i < lines; i++) {
            int lineW = (i == lines - 1 && lines > 1) ? (int) (w * 0.6) : w;
            g2.fillRoundRect(0, y, lineW, lineHeight, Ui.RADIUS_SM, Ui.RADIUS_SM);
            y += lineHeight + gap;
        }
        g2.dispose();
    }

    /** Текущая прозрачность (%) — для смоук-теста. */
    public int currentAlpha() {
        return alpha;
    }

    /** Число строк-плейсхолдеров — для смоук-теста. */
    public int lineCount() {
        return lines;
    }
}
