package corpagent.gui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * UI-1.2: кастомный toggle-свитч в стиле iOS/iPadOS.
 *
 * <p>Заменяет стандартный {@link javax.swing.JCheckBox} там, где нужен
 * современный «тумблер»: трек-«пилюля» с системной заливкой в активном
 * состоянии и белый бегунок с мягкой тенью, плавно скользящий по треку
 * (анимация через {@link Timer}).</p>
 *
 * <p>Использование:</p>
 * <pre>
 * ToggleSwitch t = new ToggleSwitch(true);
 * t.addChangeListener(() -&gt; save(t.isSelected()));
 * </pre>
 */
public class ToggleSwitch extends JComponent {

    private static final long serialVersionUID = 1L;

    /** Размеры трека (компактные iOS-пропорции). */
    private static final int TRACK_W = 46;
    private static final int TRACK_H = 28;
    private static final int KNOB_PAD = 3;      // отступ бегунка от края трека
    private static final int ANIM_MS = 140;

    private boolean selected;
    private float progress; // 0..1 позиция бегунка (для анимации)
    private Timer anim;
    private Runnable onChange;

    public ToggleSwitch() {
        this(false);
    }

    public ToggleSwitch(boolean selected) {
        this.selected = selected;
        this.progress = selected ? 1f : 0f;
        setPreferredSize(new Dimension(TRACK_W, TRACK_H));
        setMinimumSize(new Dimension(TRACK_W, TRACK_H));
        setMaximumSize(new Dimension(TRACK_W, TRACK_H));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (isEnabled()) {
                    setSelected(!ToggleSwitch.this.selected);
                }
            }
        });
    }

    /** Текущее состояние. */
    public boolean isSelected() {
        return selected;
    }

    /** Устанавливает состояние (с анимацией) и уведомляет слушателя изменения. */
    public void setSelected(boolean value) {
        if (this.selected == value) {
            return;
        }
        this.selected = value;
        animateTo(value ? 1f : 0f);
        if (onChange != null) {
            onChange.run();
        }
    }

    /** Устанавливает состояние без вызова слушателя (для загрузки/инициализации). */
    public void setSelectedSilently(boolean value) {
        this.selected = value;
        this.progress = value ? 1f : 0f;
        repaint();
    }

    /** Слушатель изменения состояния (вызывается после каждого переключения). */
    public void addChangeListener(Runnable listener) {
        this.onChange = listener;
    }

    /** Текст-подпись не поддерживается (для совместимости с API чекбокса). */
    public void setText(String ignored) {
        // no-op: подпись рисует внешняя панель (см. settings-экраны)
    }

    private void animateTo(float target) {
        if (anim != null && anim.isRunning()) {
            anim.stop();
        }
        final float from = progress;
        final int steps = Math.max(3, ANIM_MS / 16);
        final int[] step = {0};
        anim = new Timer(16, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                step[0]++;
                float p = Math.min(1f, (float) step[0] / steps);
                float ease = 1f - (1f - p) * (1f - p); // ease-out
                progress = from + (target - from) * ease;
                repaint();
                if (p >= 1f) {
                    anim.stop();
                }
            }
        });
        anim.setRepeats(true);
        anim.start();
    }

    /** Позиция бегунка (0..1) — вынесена для смоук-теста без отрисовки. */
    public float knobProgress() {
        return progress;
    }

    /** Вычисляет X-координату бегунка при заданной ширине и прогрессе (для теста). */
    public static int knobX(int width, int height, float progress) {
        int knobD = height - KNOB_PAD * 2;
        int travel = width - knobD - KNOB_PAD * 2;
        return KNOB_PAD + Math.round(travel * progress);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int radius = h;

        // Трек: off — systemGray, on — PRIMARY (интерполяция по progress).
        Color off = new Color(120, 120, 128); // iOS systemGray (track off)
        Color on = Ui.PRIMARY;
        Color track = blend(off, on, progress);
        if (!isEnabled()) {
            track = new Color(track.getRed(), track.getGreen(), track.getBlue(), 120);
        }
        g2.setColor(track);
        g2.fillRoundRect(0, 0, w, h, radius, radius);

        // Бегунок + мягкая тень.
        int knobD = h - KNOB_PAD * 2;
        int knobX = knobX(w, h, progress);
        int knobY = KNOB_PAD;

        g2.setColor(new Color(0, 0, 0, 40));
        g2.fillOval(knobX, knobY + 1, knobD, knobD);
        g2.setColor(Color.WHITE);
        g2.fillOval(knobX, knobY, knobD, knobD);

        g2.dispose();
    }

    private static Color blend(Color a, Color b, float t) {
        int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
        int gr = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, gr, bl);
    }
}
