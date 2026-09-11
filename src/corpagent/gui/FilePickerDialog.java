package corpagent.gui;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * UI-3.2: кастомный File Picker в стиле прототипа ({@code #filePickerModal}).
 *
 * <p>Единая модалка выбора файлов на базе {@link BaseDialog} с {@link DropZone}:
 * пользователь либо перетаскивает файлы из проводника, либо кликает по зоне и
 * выбирает через системный диалог. Выбранные файлы показываются списком с
 * возможностью удаления.</p>
 *
 * <p>Заменяет «голый» {@link javax.swing.JFileChooser} в {@code ChatView.chooseFiles()}.
 * Возвращает выбранные файлы через {@link #getSelectedFiles()} после закрытия по
 * кнопке «Загрузить» ({@link #isApproved()}).</p>
 */
public class FilePickerDialog extends BaseDialog {

    private static final long serialVersionUID = 1L;

    private final List<File> selected = new ArrayList<File>();
    private final JPanel listPanel = new JPanel();
    private final JLabel countLabel = new JLabel(" ");
    private boolean approved;

    /**
     * @param owner      родительское окно
     * @param extensions допустимые расширения без точки (пусто/null — любые)
     */
    public FilePickerDialog(Frame owner, final String[] extensions) {
        super(owner, "📎 Выберите файлы", true);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(Ui.GAP_LG, Ui.GAP_LG, Ui.GAP_LG, Ui.GAP_LG));
        body.setPreferredSize(new Dimension(560, 420));

        JLabel title = new JLabel("📎 Выберите файлы");
        title.setFont(Ui.FONT_HEADER);
        title.setForeground(Ui.TEXT_DARK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(Ui.GAP_MD));

        DropZone zone = new DropZone(extensions, true);
        zone.onFiles(this::addFiles);
        zone.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(zone);
        body.add(Box.createVerticalStrut(Ui.GAP_MD));

        countLabel.setFont(Ui.FONT_SMALL_BOLD);
        countLabel.setForeground(Ui.TEXT_SECONDARY);
        countLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(countLabel);
        body.add(Box.createVerticalStrut(Ui.GAP_XS));

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        listPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(scroll);

        JButton cancel = new JButton("Отмена");
        Ui.styleSecondary(cancel);
        cancel.addActionListener(e -> dispose());

        JButton upload = new JButton("Загрузить");
        Ui.stylePrimary(upload);
        upload.addActionListener(e -> {
            approved = true;
            dispose();
        });

        setCardContent(body, BaseDialog.buttonBar(cancel, upload));
        updateList();
    }

    /** Добавляет файлы (из drag&amp;drop или системного диалога), без дублей. */
    private void addFiles(List<File> files) {
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f != null && !selected.contains(f)) {
                selected.add(f);
            }
        }
        updateList();
    }

    /** Перерисовывает список выбранных файлов и счётчик. */
    private void updateList() {
        listPanel.removeAll();
        countLabel.setText(selected.isEmpty()
                ? "Файлы не выбраны" : "Выбрано файлов: " + selected.size());
        for (final File f : selected) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setBorder(new EmptyBorder(4, 2, 4, 2));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            JLabel name = new JLabel(Ui.actionIcon("📄"), JLabel.LEFT);
            name.setIconTextGap(Ui.ACTION_ICON_GAP);
            name.setText(f.getName() + "  (" + humanSize(f.length()) + ")");
            name.setFont(Ui.FONT_SMALL);
            name.setForeground(Ui.TEXT);
            row.add(name, BorderLayout.CENTER);

            JButton remove = new JButton("✕");
            remove.setFont(Ui.FONT_SMALL);
            remove.setForeground(Ui.TEXT_MUTED);
            remove.setFocusPainted(false);
            remove.setContentAreaFilled(false);
            remove.setBorder(new EmptyBorder(2, 6, 2, 6));
            remove.setCursor(Ui.handCursor());
            remove.addActionListener(e -> {
                selected.remove(f);
                updateList();
            });
            row.add(remove, BorderLayout.EAST);
            listPanel.add(row);
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    /** Выбранные файлы (после подтверждения). */
    public List<File> getSelectedFiles() {
        return new ArrayList<File>(selected);
    }

    /** true — пользователь нажал «Загрузить». */
    public boolean isApproved() {
        return approved;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " Б";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " КБ";
        }
        return String.format("%.1f МБ", bytes / (1024.0 * 1024.0));
    }
}
