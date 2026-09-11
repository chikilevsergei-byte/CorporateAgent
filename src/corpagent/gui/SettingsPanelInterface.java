package corpagent.gui;

import corpagent.data.BackupManager;
import corpagent.util.Settings;
import corpagent.util.TokenBudget;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;

/**
 * Вкладка «Интерфейс» окна настроек: флаги поведения, тема, дневной лимит
 * токенов и кнопки резервного копирования (R-P1.7).
 *
 * <p>Выделено из {@link SettingsDialog}; диалог вызывает {@link #load} /
 * {@link #applyTo} и делегирует сюда операции бэкапа.</p>
 */
public class SettingsPanelInterface extends JPanel {

    private static final long serialVersionUID = 1L;

    private final MainFrame frame;

    private final JCheckBox autoUpdateCheck = new JCheckBox("🔄 Автообновление БД");
    private final JCheckBox showUsageCheck = new JCheckBox("Показывать использование навыков/инструментов/БД в чате");
    private final JCheckBox confirmDbWriteCheck = new JCheckBox("⚠️ Подтверждать запись изменений в БД (спрашивать перед db_execute)");
    private final JComboBox<String> themeCombo = new JComboBox<String>(new String[]{"green", "dark", "blue"});
    private final JTextField dailyLimitField = new JTextField("0", 12);
    private final JLabel spendLabel = new JLabel(" ");

    public SettingsPanelInterface(MainFrame frame) {
        this.frame = frame;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(20, 24, 20, 24));
        setOpaque(false);
        build();
    }

    private void build() {
        add(sectionLabel("Настройки интерфейса (сохраняются в settings.json)"));
        add(Box.createVerticalStrut(16));

        autoUpdateCheck.setFont(Ui.FONT_PLAIN);
        autoUpdateCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(autoUpdateCheck);
        add(Box.createVerticalStrut(10));

        showUsageCheck.setFont(Ui.FONT_PLAIN);
        showUsageCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(showUsageCheck);
        add(Box.createVerticalStrut(10));

        confirmDbWriteCheck.setFont(Ui.FONT_PLAIN);
        confirmDbWriteCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(confirmDbWriteCheck);
        add(Box.createVerticalStrut(16));

        add(fieldLabel("Тема оформления"));
        add(Box.createVerticalStrut(6));
        themeCombo.setMaximumSize(new Dimension(200, 34));
        themeCombo.setFont(Ui.FONT_PLAIN);
        // UI-0.2: живое переключение палитры без перезапуска (мгновенный предпросмотр).
        themeCombo.addActionListener(e -> {
            Object sel = themeCombo.getSelectedItem();
            if (sel != null) {
                frame.applyTheme(String.valueOf(sel));
            }
        });
        add(themeCombo);
        add(Box.createVerticalStrut(20));

        add(fieldLabel("Дневной лимит токенов (0 — без лимита)"));
        add(Box.createVerticalStrut(6));
        dailyLimitField.setMaximumSize(new Dimension(220, 32));
        Ui.styleField(dailyLimitField);
        dailyLimitField.setToolTipText("0 = без лимита. После исчерпания лимита агент остановит обращения к LLM.");
        add(dailyLimitField);
        add(Box.createVerticalStrut(8));
        spendLabel.setFont(Ui.FONT_SMALL);
        spendLabel.setForeground(Ui.TEXT_GRAY);
        spendLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(spendLabel);
        add(Box.createVerticalStrut(16));

        // P1.7: кнопки бэкапа
        JPanel backupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        backupPanel.setOpaque(false);
        JButton backupBtn = new JButton("💾 Создать бэкап");
        Ui.stylePrimary(backupBtn);
        backupBtn.addActionListener(e -> createBackup());
        backupPanel.add(backupBtn);
        JButton restoreBtn = new JButton("📂 Восстановить из бэкапа");
        Ui.styleSecondary(restoreBtn);
        restoreBtn.addActionListener(e -> restoreBackup());
        backupPanel.add(restoreBtn);
        add(backupPanel);

        add(Box.createVerticalGlue());
    }

    /** Обновляет строку текущего расхода токенов за день. */
    private void updateSpendLabel() {
        long today = TokenBudget.tokensToday();
        long limit = frame.getSettings().getDailyLimitTokens();
        StringBuilder sb = new StringBuilder("Сегодня потрачено: ")
                .append(TokenBudget.formatTokens(today))
                .append(" (≈ ").append(TokenBudget.formatMoney(TokenBudget.costTodayUsd())).append(")");
        if (limit > 0) {
            long rem = Math.max(0, limit - today);
            sb.append(" · лимит ").append(TokenBudget.formatTokens(limit))
              .append(" · осталось ").append(TokenBudget.formatTokens(rem));
        }
        spendLabel.setText(sb.toString());
    }

    /** Загружает текущие значения из {@link Settings} в поля вкладки. */
    public void load(Settings settings) {
        autoUpdateCheck.setSelected(settings.isAutoUpdateDb());
        showUsageCheck.setSelected(settings.isShowUsageInChat());
        confirmDbWriteCheck.setSelected(settings.isConfirmDbWrite());
        // setSelectedItem программно не должно «проигрывать» тему — временно снимаем слушатели.
        java.awt.event.ActionListener[] themeListeners = themeCombo.getActionListeners();
        for (java.awt.event.ActionListener l : themeListeners) {
            themeCombo.removeActionListener(l);
        }
        themeCombo.setSelectedItem(settings.getTheme());
        for (java.awt.event.ActionListener l : themeListeners) {
            themeCombo.addActionListener(l);
        }
        dailyLimitField.setText(String.valueOf(settings.getDailyLimitTokens()));
        updateSpendLabel();
    }

    /**
     * Переносит значения полей вкладки в {@link Settings} (без сохранения на диск).
     * Дневной лимит дополнительно применяется к {@link TokenBudget}.
     *
     * @return null при успехе или текст ошибки (некорректный дневной лимит).
     */
    public String applyTo(Settings settings) {
        settings.setAutoUpdateDb(autoUpdateCheck.isSelected());
        settings.setShowUsageInChat(showUsageCheck.isSelected());
        settings.setConfirmDbWrite(confirmDbWriteCheck.isSelected());
        settings.setTheme((String) themeCombo.getSelectedItem());
        try {
            long limit = Long.parseLong(dailyLimitField.getText().trim());
            settings.setDailyLimitTokens(limit);
            TokenBudget.setDailyLimit(limit);
        } catch (NumberFormatException ex) {
            return "Дневной лимит токенов должен быть целым числом.";
        }
        return null;
    }

    // ==================== БЭКЭП (R-P1.7) ====================

    private void createBackup() {
        String baseDir = frame.getBaseDir();
        String backupsDir = baseDir + java.io.File.separator + "backups";
        try {
            String backupPath = BackupManager.createBackup(baseDir, backupsDir);
            JOptionPane.showMessageDialog(this,
                    "Бэкап создан:\n" + backupPath,
                    "Бэкап", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка создания бэкапа:\n" + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreBackup() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите бэкап для восстановления");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ZIP-архивы", "zip"));
        // Указываем директорию backups/
        String backupsDir = frame.getBaseDir() + java.io.File.separator + "backups";
        File backupsDirFile = new File(backupsDir);
        if (backupsDirFile.exists() && backupsDirFile.isDirectory()) {
            chooser.setCurrentDirectory(backupsDirFile);
        }
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File backupFile = chooser.getSelectedFile();
        int confirm = JOptionPane.showConfirmDialog(this,
                "Восстановить данные из бэкапа '" + backupFile.getName() + "'?\n" +
                "Текущие данные будут перезаписаны.",
                "Подтверждение восстановления", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            BackupManager.restoreBackup(frame.getBaseDir(), backupFile.getAbsolutePath());
            JOptionPane.showMessageDialog(this,
                    "Данные восстановлены из бэкапа.\n" +
                    "Перезапустите приложение для применения изменений.",
                    "Восстановление", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Ошибка восстановления:\n" + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== ХЕЛПЕРЫ ====================

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_HEADER);
        l.setForeground(Ui.TEXT_DARK);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL_BOLD);
        l.setForeground(Ui.TEXT_SECONDARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }
}
