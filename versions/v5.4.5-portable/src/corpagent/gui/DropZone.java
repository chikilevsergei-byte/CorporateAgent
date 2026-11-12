package corpagent.gui;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * UI-3.2: зона drag&amp;drop для файлов в стиле прототипа
 * ({@code .file-drop-zone}): пунктирная скруглённая рамка, крупная иконка,
 * заголовок «Перетащите файлы сюда» и подсказка «или нажмите для выбора».
 *
 * <p>Заменяет «голый» {@link JFileChooser} там, где файлы просто выбираются для
 * анализа: пользователь может либо перетащить файлы из проводника, либо кликнуть
 * по зоне и выбрать через системный диалог. Drag&amp;drop реализован через
 * {@link TransferHandler} (flavor {@link DataFlavor#javaFileListFlavor}).</p>
 *
 * <p>Подсветка при наведении/перетаскивании: рамка становится синей, фон — голубым
 * ({@code .dragover}).</p>
 *
 * <p>Использование:</p>
 * <pre>
 * DropZone zone = new DropZone();
 * zone.onFiles(new DropZone.FileHandler() {
 *     public void filesChosen(java.util.List&lt;File&gt; files) { ... }
 * });
 * panel.add(zone);
 * </pre>
 */
public class DropZone extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Реакция на выбранные (перетащенные или через диалог) файлы. */
    public interface FileHandler {
        void filesChosen(List<File> files);
    }

    private final String[] extensions;
    private final boolean multi;
    private FileHandler handler;

    /** Флаг «над зоной сейчас тянут файлы» — для отрисовки .dragover. */
    private boolean dragover;

    /**
     * @param extensions допустимые расширения без точки (пусто/null — любые)
     * @param multi      разрешить выбор нескольких файлов
     */
    public DropZone(String[] extensions, boolean multi) {
        this.extensions = extensions;
        this.multi = multi;

        setLayout(new BorderLayout());
        setOpaque(false);
        setCursor(new Cursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(420, 160));
        setBorder(new DashedBorder(Ui.BORDER));

        JPanel inner = new JPanel();
        inner.setLayout(new javax.swing.BoxLayout(inner, javax.swing.BoxLayout.Y_AXIS));
        inner.setOpaque(false);
        inner.setBorder(new EmptyBorder(28, 20, 28, 20));

        JLabel icon = new JLabel("📤");
        icon.setFont(UiTheme.iconFont(40));
        icon.setForeground(Ui.TEXT_MUTED);
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        icon.setHorizontalAlignment(JLabel.CENTER);

        JLabel title = new JLabel("Перетащите файлы сюда");
        title.setFont(Ui.FONT_HEADER);
        title.setForeground(Ui.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel desc = new JLabel(extensions == null || extensions.length == 0
                ? "или нажмите для выбора" : "или нажмите для выбора (" + join(extensions) + ")");
        desc.setFont(Ui.FONT_SMALL);
        desc.setForeground(Ui.TEXT_MUTED);
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);

        inner.add(icon);
        inner.add(javax.swing.Box.createVerticalStrut(12));
        inner.add(title);
        inner.add(javax.swing.Box.createVerticalStrut(6));
        inner.add(desc);
        add(inner, BorderLayout.CENTER);

        installClickToBrowse();
        installDragAndDrop();
        installHover();
    }

    /** Регистрирует обработчик выбранных файлов. */
    public DropZone onFiles(FileHandler h) {
        this.handler = h;
        return this;
    }

    /** Клик по зоне открывает системный диалог выбора файлов. */
    private void installClickToBrowse() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openChooser();
            }
        });
    }

    /** Hover: синяя рамка и голубой фон (как .dragover в прототипе). */
    private void installHover() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!dragover) {
                    setBorder(new DashedBorder(Ui.PRIMARY));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!dragover) {
                    setBorder(new DashedBorder(Ui.BORDER));
                    repaint();
                }
            }
        });
    }

    /** Drag&amp;drop файлов через TransferHandler. */
    private void installDragAndDrop() {
        setTransferHandler(new TransferHandler() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean canImport(TransferSupport support) {
                boolean ok = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                setDragover(ok);
                return ok;
            }

            @Override
            public boolean importData(TransferSupport support) {
                setDragover(false);
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return false;
                }
                try {
                    Transferable t = support.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    List<File> accepted = filter(files);
                    if (!accepted.isEmpty() && handler != null) {
                        handler.filesChosen(accepted);
                    }
                    return !accepted.isEmpty();
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    /** Открывает JFileChooser (multi-режим) и передаёт выбранные файлы обработчику. */
    private void openChooser() {
        JFileChooser chooser = new JFileChooser();
        Ui.styleFileChooser(chooser);
        chooser.setMultiSelectionEnabled(multi);
        chooser.setDialogTitle(multi ? "Выберите файлы" : "Выберите файл");
        if (extensions != null && extensions.length > 0) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Поддерживаемые форматы", extensions));
        }
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        List<File> files = new ArrayList<File>();
        if (multi) {
            File[] sel = chooser.getSelectedFiles();
            if (sel != null) {
                for (File f : sel) {
                    files.add(f);
                }
            }
        } else if (chooser.getSelectedFile() != null) {
            files.add(chooser.getSelectedFile());
        }
        List<File> accepted = filter(files);
        if (!accepted.isEmpty() && handler != null) {
            handler.filesChosen(accepted);
        }
    }

    /** Фильтрует файлы по допустимым расширениям (директории исключаются). */
    private List<File> filter(List<File> files) {
        List<File> out = new ArrayList<File>();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            if (f == null || !f.isFile()) {
                continue;
            }
            if (extensions == null || extensions.length == 0 || matches(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private boolean matches(File f) {
        String name = f.getName().toLowerCase();
        for (String ext : extensions) {
            if (ext == null || ext.isEmpty()) {
                continue;
            }
            String e = ext.toLowerCase().startsWith(".") ? ext.substring(1) : ext;
            if (name.endsWith("." + e.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** Переключает визуальное состояние «над зоной тянут файлы». */
    void setDragover(boolean value) {
        if (dragover == value) {
            return;
        }
        dragover = value;
        setBorder(new DashedBorder(value ? Ui.PRIMARY : Ui.BORDER));
        repaint();
    }

    /** true — сейчас над зоной перетаскивают файлы (для смоук-теста). */
    public boolean isDragover() {
        return dragover;
    }

    private static String join(String[] exts) {
        StringBuilder sb = new StringBuilder();
        for (String e : exts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e);
        }
        return sb.toString();
    }

    /**
     * Пунктирная скруглённая рамка зоны. Цвет переключается между серым и синим
     * (hover/dragover). Фон — очень лёгкий голубой оттенок.
     */
    static class DashedBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private final Color color;

        DashedBorder(Color color) {
            this.color = color;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{7f, 6f}, 0f));
            int r = Ui.RADIUS_CARD;
            g2.drawRoundRect(x + 1, y + 1, width - 3, height - 3, r, r);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(6, 6, 6, 6);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    // ==================== ДЛЯ ТЕСТОВ ====================

    /** Проверяет соответствие одного имени файла допустимым расширениям (без IO). */
    public boolean acceptsName(String fileName) {
        return matches(new File(fileName == null ? "" : fileName));
    }

    /** Проверяет, что у компонента установлен TransferHandler (drag&amp;drop включён). */
    public boolean supportsFileDrop() {
        return getTransferHandler() != null;
    }

    /** Вызов из EDT-безопасного контекста (утилита для теста/интеграции). */
    public static void runLater(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}
