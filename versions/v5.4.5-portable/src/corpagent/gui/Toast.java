package corpagent.gui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * UI-3.1: всплывающие Toast-уведомления в стиле macOS/iOS — замена информационных
 * {@code JOptionPane.showMessageDialog} (confirm/input остаются диалогами).
 *
 * <p>Перенос из прототипа {@code corporate-agent-prototype.html}:
 * стек тостов в правом-нижнем углу, авто-скрытие через 4 секунды, крестик для
 * ручного закрытия, семантический цвет полосы-иконки (success зелёный / error
 * красный / warning оранжевый / info синий), пауза авто-скрытия при наведении.</p>
 *
 * <p>Тосты рисуются в собственном {@link JWindow} (без оформления) поверх родителя,
 * поэтому не съедают раскладку и не блокируют UI. Каждый тост — отдельное окно,
 * выстроенное в стек над правым-нижним углом родителя.</p>
 *
 * <p>Использование:</p>
 * <pre>
 * Toast.success(frame, "Проект создан", "Создана БД и 7 рабочих чатов");
 * Toast.error(frame, "Ошибка", ex.getMessage());
 * </pre>
 */
public final class Toast {

    /** Тип тоста — определяет цвет и иконку. */
    public enum Type {
        SUCCESS("✓"),
        ERROR("✕"),
        WARNING("⚠"),
        INFO("ℹ");

        final String icon;

        Type(String icon) {
            this.icon = icon;
        }
    }

    /** Время авто-скрытия тоста (мс), как в прототипе (4000). */
    public static final int AUTO_HIDE_MS = 4000;

    /** Отступ тоста от краёв окна. */
    private static final int MARGIN = 20;
    /** Вертикальный зазор между тостами в стеке. */
    private static final int STACK_GAP = 10;
    /** Ширина тоста. */
    private static final int WIDTH = 340;

    /** Активные тосты (для стекинга и пересчёта позиций). */
    private static final List<ToastInstance> active = new ArrayList<ToastInstance>();

    private Toast() {
    }

    // ==================== ПУБЛИЧНЫЙ API ====================

    public static void success(Window owner, String title, String message) {
        show(owner, Type.SUCCESS, title, message);
    }

    public static void error(Window owner, String title, String message) {
        show(owner, Type.ERROR, title, message);
    }

    public static void warning(Window owner, String title, String message) {
        show(owner, Type.WARNING, title, message);
    }

    public static void info(Window owner, String title, String message) {
        show(owner, Type.INFO, title, message);
    }

    /**
     * Показывает тост. Гарантированно выполняется на EDT (безопасно вызывать из
     * фоновых потоков SwingWorker).
     */
    public static void show(final Window owner, final Type type,
                            final String title, final String message) {
        if (owner == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            showNow(owner, type, title, message);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    showNow(owner, type, title, message);
                }
            });
        }
    }

    // ==================== РЕАЛИЗАЦИЯ ====================

    private static void showNow(Window owner, Type type, String title, String message) {
        final ToastInstance toast = new ToastInstance(owner, type, title, message);
        active.add(toast);
        layoutStack(owner);
        toast.window.setVisible(true);
        startAutoHide(toast);
    }

    /** Запускает таймер авто-скрытия. */
    private static void startAutoHide(final ToastInstance toast) {
        if (toast.hideTimer != null) {
            toast.hideTimer.stop();
        }
        final Timer timer = new Timer(AUTO_HIDE_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toast.dismiss();
            }
        });
        timer.setRepeats(false);
        toast.hideTimer = timer;
        timer.start();
    }

    /** Пересчитывает позиции всех активных тостов в стек от правого-нижнего угла. */
    private static void layoutStack(Window owner) {
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).dismissed) {
                active.remove(i);
            }
        }
        int y = owner.getHeight() - MARGIN;
        for (int i = active.size() - 1; i >= 0; i--) {
            ToastInstance t = active.get(i);
            int h = t.window.getHeight();
            if (h <= 0) {
                h = t.panel.getPreferredSize().height;
                t.window.setSize(WIDTH, h);
            }
            Point base = owner.getLocationOnScreen();
            int x = base.x + owner.getWidth() - WIDTH - MARGIN;
            y -= h;
            t.window.setLocation(x, base.y + y);
            y -= STACK_GAP;
        }
    }

    /**
     * Экземпляр одного тоста: неотдекорированное окно {@link JWindow} с панелью.
     * Позиционируется относительно владельца, при закрытии убирает себя из стека.
     */
    private static final class ToastInstance {
        final Window owner;
        final JWindow window;
        final JPanel panel;
        Timer hideTimer;
        volatile boolean dismissed;

        ToastInstance(Window owner, Type type, String title, String message) {
            this.owner = owner;
            this.panel = buildPanel(type, title, message, this);
            this.window = new JWindow(owner);
            window.setFocusableWindowState(false);
            window.setBackground(new Color(0, 0, 0, 0));
            window.setContentPane(panel);
            int h = panel.getPreferredSize().height;
            window.setSize(new Dimension(WIDTH, h));
        }

        /** Скрывает тост, обновляет стек и освобождает ресурсы. */
        void dismiss() {
            if (dismissed) {
                return;
            }
            dismissed = true;
            if (hideTimer != null) {
                hideTimer.stop();
            }
            window.setVisible(false);
            window.dispose();
            active.remove(this);
            layoutStack(owner);
        }
    }

    /**
     * Строит панель тоста (используется также в смоук-тесте без показа окна).
     * Иконка-«кружок» + заголовок/сообщение + кнопка закрытия; цвет зависит от типа.
     */
    static JPanel buildPanel(final Type type, String title, String message,
                             final ToastInstance instance) {
        final Color accent = accentColor(type);

        JPanel card = new ToastCard();

        // Левая цветная полоса-акцент.
        JPanel stripe = new JPanel();
        stripe.setBackground(accent);
        stripe.setPreferredSize(new Dimension(4, 10));
        card.add(stripe, java.awt.BorderLayout.WEST);

        // Иконка.
        JLabel icon = new JLabel(type.icon);
        icon.setFont(UiTheme.iconFont(16));
        icon.setForeground(Color.WHITE);
        icon.setHorizontalAlignment(JLabel.CENTER);
        icon.setVerticalAlignment(JLabel.CENTER);
        icon.setOpaque(true);
        icon.setBackground(accent);
        icon.setPreferredSize(new Dimension(28, 28));
        JPanel iconWrap = new JPanel(new java.awt.GridBagLayout());
        iconWrap.setOpaque(false);
        iconWrap.setBorder(new EmptyBorder(12, 12, 12, 8));
        iconWrap.add(icon);

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.setBorder(new EmptyBorder(12, 0, 12, 4));
        JLabel titleLbl = new JLabel(title == null ? "" : title);
        titleLbl.setFont(Ui.FONT_SMALL_BOLD);
        titleLbl.setForeground(Ui.TEXT);
        titleLbl.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        text.add(titleLbl);
        if (message != null && !message.isEmpty()) {
            JLabel msgLbl = new JLabel("<html><div style='width:220px'>" + escape(message) + "</div></html>");
            msgLbl.setFont(Ui.FONT_SMALL);
            msgLbl.setForeground(Ui.TEXT_SECONDARY);
            msgLbl.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            text.add(Box.createVerticalStrut(2));
            text.add(msgLbl);
        }

        JPanel center = new JPanel(new java.awt.BorderLayout());
        center.setOpaque(false);
        center.add(iconWrap, java.awt.BorderLayout.WEST);
        center.add(text, java.awt.BorderLayout.CENTER);
        card.add(center, java.awt.BorderLayout.CENTER);

        // Кнопка закрытия.
        JButton close = new JButton("✕");
        close.setFont(Ui.FONT_SMALL);
        close.setForeground(Ui.TEXT_MUTED);
        close.setFocusPainted(false);
        close.setContentAreaFilled(false);
        close.setBorder(new EmptyBorder(6, 6, 6, 6));
        close.setCursor(Ui.handCursor());
        close.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (instance != null) {
                    instance.dismiss();
                }
            }
        });
        JPanel closeWrap = new JPanel(new java.awt.BorderLayout());
        closeWrap.setOpaque(false);
        closeWrap.setBorder(new EmptyBorder(8, 0, 0, 8));
        closeWrap.add(close, java.awt.BorderLayout.NORTH);
        card.add(closeWrap, java.awt.BorderLayout.EAST);

        // Пауза авто-скрытия при наведении мыши.
        if (instance != null) {
            card.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (instance.hideTimer != null) {
                        instance.hideTimer.stop();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (!instance.dismissed) {
                        startAutoHide(instance);
                    }
                }
            });
        }

        return card;
    }

    /** Цвет акцента по типу тоста (семантическая палитра UiTheme). */
    static Color accentColor(Type type) {
        switch (type) {
            case SUCCESS:
                return UiTheme.SUCCESS;
            case ERROR:
                return UiTheme.ERROR;
            case WARNING:
                return UiTheme.WARNING;
            default:
                return UiTheme.PRIMARY;
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    /**
     * Скруглённая «карточка» тоста с мягкой тенью. Непрозрачный фон рисуется
     * вручную, чтобы окно JWindow имел чистые углы.
     */
    static class ToastCard extends JPanel {
        private static final long serialVersionUID = 1L;

        ToastCard() {
            setLayout(new java.awt.BorderLayout());
            setOpaque(false);
            setBorder(new EmptyBorder(2, 2, 6, 2));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = UiTheme.RADIUS_CARD;
            g2.setColor(new Color(0, 0, 0, 40));
            g2.fillRoundRect(2, 4, getWidth() - 4, getHeight() - 6, r, r);
            g2.setColor(UiTheme.SURFACE);
            g2.fillRoundRect(0, 0, getWidth() - 4, getHeight() - 6, r, r);
            g2.setColor(UiTheme.BORDER);
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.drawRoundRect(0, 0, getWidth() - 5, getHeight() - 7, r, r);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ==================== ДЛЯ ТЕСТОВ ====================

    /** Число активных тостов (для смоук-теста). */
    public static int activeCount() {
        return active.size();
    }
}
