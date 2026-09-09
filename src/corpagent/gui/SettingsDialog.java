package corpagent.gui;

import corpagent.data.BackupManager;
import corpagent.util.Config;
import corpagent.util.Settings;
import corpagent.util.TokenBudget;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Hashtable;

/**
 * Модальное окно настроек (⚙️) с современными вкладками:
 * Модель | Интерфейс | Навыки | Инструменты | БД.
 *
 * <p>Вместо стандартного JTabbedPane используется собственная полоса вкладок
 * (активная вкладка выделяется нижней рамкой 2px цвета Primary) + CardLayout.
 * Окно имеет скругление и мягкую тень.</p>
 */
public class SettingsDialog extends JDialog {

    private final MainFrame frame;
    private final Config config;
    private final Settings settings;

    // Вкладка «Навыки»
    private final SettingsPanelSkills skillsPanel;

    // Вкладка «Инструменты»
    private final SettingsPanelTools toolsPanel;

    // Вкладка «Базы данных»
    private final SettingsPanelDatabases databasesPanel;

    // Вкладка «Модель»
    private final JPasswordField apiKeyField = new JPasswordField(30);
    private final JComboBox<String> providerCombo = new JComboBox<String>(new String[]{
            "DeepSeek", "Яндекс ИИ студия", "Alibaba Qwen", "Другой (OpenAI-совместимый)"});
    private final JTextField baseUrlField = new JTextField(30);
    private final JComboBox<String> modelCombo = new JComboBox<String>(new String[]{
            "deepseek-chat", "deepseek-reasoner"});
    private final JSlider temperatureSlider = new JSlider(0, 200, 70);
    private final JLabel temperatureValue = new JLabel("0.7");
    private final JTextField maxTokensField = new JTextField("4000", 8);

        // Вкладка «Интерфейс»
    private final JCheckBox autoUpdateCheck = new JCheckBox("🔄 Автообновление БД");
    private final JCheckBox showUsageCheck = new JCheckBox("Показывать использование навыков/инструментов/БД в чате");
    private final JCheckBox confirmDbWriteCheck = new JCheckBox("⚠️ Подтверждать запись изменений в БД (спрашивать перед db_execute)");
    private final JComboBox<String> themeCombo = new JComboBox<String>(new String[]{"green", "dark", "blue"});
    private final JTextField dailyLimitField = new JTextField("0", 12);
    private final JLabel spendLabel = new JLabel(" ");

    // Панели вкладок и CardLayout
    private final JPanel cardPanel = new JPanel(new CardLayout());
    private final JButton[] tabButtons = new JButton[5];

    public SettingsDialog(MainFrame frame) {
        super(frame, "⚙️ Настройки", true);
        this.frame = frame;
        this.config = frame.getConfig();
        this.settings = frame.getSettings();

        // Панели «Навыки», «Инструменты» и «БД» создаются заранее, чтобы их можно было
        // обновлять (refresh) при открытии диалога и при переключении на вкладку.
        this.skillsPanel = new SettingsPanelSkills(frame);
        this.toolsPanel = new SettingsPanelTools(frame);
        this.databasesPanel = new SettingsPanelDatabases(frame);

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setUndecorated(true);
        setSize(780, 580);
        setLocationRelativeTo(frame);

        // Контент внутри скруглённой «карточки» с тенью
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Ui.SURFACE);
        content.setBorder(new RoundedShadowBorder());

        // Полоса вкладок
        JPanel tabBar = buildTabBar();
        content.add(tabBar, BorderLayout.NORTH);

        // Панели вкладок
        cardPanel.setOpaque(false);
        cardPanel.add(buildModelTab(), "tab0");
        cardPanel.add(buildInterfaceTab(), "tab1");
        cardPanel.add(skillsPanel, "tab2");
        cardPanel.add(toolsPanel, "tab3");
        cardPanel.add(databasesPanel, "tab4");
        content.add(cardPanel, BorderLayout.CENTER);

        content.add(buildButtons(), BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(0, 0, 0, 0));
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);

        // Загрузка текущих значений
        loadValues();

        // Первичное заполнение списков навыков, инструментов и БД
        skillsPanel.refresh();
        toolsPanel.refresh();
        databasesPanel.refresh();

        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                // При закрытии через крестик — без сохранения (как «Отмена»).
            }
        });
    }

    // ==================== ПОЛОСА ВКЛАДОК ====================

    private JPanel buildTabBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bar.setOpaque(false);

        String[] titles = {"Модель", "Интерфейс", "Навыки", "Инструменты", "Базы данных"};
        for (int i = 0; i < titles.length; i++) {
            final int idx = i;
            JButton b = new JButton(titles[i]);
            b.setFocusPainted(false);
            b.setContentAreaFilled(false);
            b.setOpaque(false);
            b.setBorder(new EmptyBorder(12, 18, 12, 18));
            b.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
            b.addActionListener(e -> selectTab(idx));
            tabButtons[i] = b;
            bar.add(b);
        }

        // Нижняя граница полосы вкладок
        JPanel barWrap = new JPanel(new BorderLayout());
        barWrap.setOpaque(false);
        barWrap.add(bar, BorderLayout.CENTER);
        JPanel line = new JPanel() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(Ui.BORDER);
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        line.setOpaque(false);
        line.setPreferredSize(new Dimension(10, 1));
        barWrap.add(line, BorderLayout.SOUTH);

        return barWrap;
    }

    private void selectTab(int idx) {
        for (int k = 0; k < tabButtons.length; k++) {
            boolean active = (k == idx);
            Ui.styleTab(tabButtons[k], active);
        }
        CardLayout cl = (CardLayout) cardPanel.getLayout();
        cl.show(cardPanel, "tab" + idx);

        // Обновляем списки при переключении на вкладки «Навыки»/«Инструменты»/«БД»
        if (idx == 2) {
            skillsPanel.refresh();
        } else if (idx == 3) {
            toolsPanel.refresh();
        } else if (idx == 4) {
            databasesPanel.refresh();
        }
    }

    // ==================== ВКЛАДКА «МОДЕЛЬ» ====================

    private JPanel buildModelTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(20, 24, 20, 24));
        p.setOpaque(false);

        p.add(sectionLabel("Параметры модели (сохраняются в config.json)"));
        p.add(Box.createVerticalStrut(16));

        // API-ключ с маскированием и переключателем видимости
        p.add(fieldLabel("API-ключ"));
        p.add(Box.createVerticalStrut(6));
        JPanel keyRow = new JPanel(new BorderLayout(8, 0));
        keyRow.setOpaque(false);
        keyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        Ui.stylePassword(apiKeyField);
        apiKeyField.setEchoChar('•');
        keyRow.add(apiKeyField, BorderLayout.CENTER);
        JButton eyeBtn = new JButton("👁");
        Ui.styleSecondary(eyeBtn);
        eyeBtn.setToolTipText("Показать / скрыть ключ");
        eyeBtn.setPreferredSize(new Dimension(44, 36));
        eyeBtn.addActionListener(e -> toggleKeyVisibility());
        keyRow.add(eyeBtn, BorderLayout.EAST);
        p.add(keyRow);
        p.add(Box.createVerticalStrut(14));

        // Провайдер (R-P0.1): DeepSeek / Яндекс ИИ студия / Произвольный (OpenAI-совместимый)
        p.add(fieldLabel("Провайдер LLM"));
        p.add(Box.createVerticalStrut(6));
        providerCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        providerCombo.setFont(Ui.FONT_PLAIN);
        providerCombo.addActionListener(e -> applyProviderDefaults());
        p.add(providerCombo);
        p.add(Box.createVerticalStrut(10));

        // Базовый URL API (адрес endpoint, до /chat/completions)
        p.add(fieldLabel("Base URL API (до /chat/completions)"));
        p.add(Box.createVerticalStrut(6));
        baseUrlField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        Ui.styleField(baseUrlField);
        baseUrlField.setToolTipText("Подставляется автоматически при выборе провайдера. Меняйте при необходимости.");
        p.add(baseUrlField);
        p.add(Box.createVerticalStrut(14));

        // Модель
        p.add(fieldLabel("Модель"));
        p.add(Box.createVerticalStrut(6));
        modelCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        modelCombo.setFont(Ui.FONT_PLAIN);
        p.add(modelCombo);
        p.add(Box.createVerticalStrut(14));

        // Temperature (слайдер)
        p.add(fieldLabel("Temperature (креативность)"));
        p.add(Box.createVerticalStrut(6));
        temperatureSlider.setOpaque(false);
        temperatureSlider.setPreferredSize(new Dimension(420, 40));
        temperatureSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        temperatureSlider.setPaintTicks(true);
        temperatureSlider.setPaintLabels(true);
        temperatureSlider.setMajorTickSpacing(50);
        temperatureSlider.setMinorTickSpacing(10);
        temperatureSlider.setLabelTable(buildSliderLabels());
        temperatureSlider.addChangeListener(e -> {
            double v = temperatureSlider.getValue() / 100.0;
            temperatureValue.setText(String.format("%.1f", v));
        });
        p.add(temperatureSlider);
        p.add(Box.createVerticalStrut(4));
        temperatureValue.setFont(Ui.FONT_SMALL_BOLD);
        temperatureValue.setForeground(Ui.PRIMARY_DARK);
        temperatureValue.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(temperatureValue);
        p.add(Box.createVerticalStrut(14));

        // Max tokens
        p.add(fieldLabel("Max tokens (макс. длина ответа)"));
        p.add(Box.createVerticalStrut(6));
        maxTokensField.setMaximumSize(new Dimension(160, 34));
        Ui.styleField(maxTokensField);
        p.add(maxTokensField);

        p.add(Box.createVerticalGlue());
        return p;
    }

    private Hashtable<Integer, JLabel> buildSliderLabels() {
        Hashtable<Integer, JLabel> table = new Hashtable<Integer, JLabel>();
        for (int v = 0; v <= 200; v += 50) {
            JLabel l = new JLabel(String.format("%.1f", v / 100.0));
            l.setFont(Ui.FONT_SMALL);
            l.setForeground(Ui.TEXT_MUTED);
            table.put(v, l);
        }
        return table;
    }

    private void toggleKeyVisibility() {
        if (apiKeyField.getEchoChar() == (char) 0) {
            apiKeyField.setEchoChar('•');
        } else {
            apiKeyField.setEchoChar((char) 0);
        }
    }

    /**
     * При смене провайдера подставляет его базовый URL по умолчанию (R-P0.1).
     * Для «custom» URL очищается — его вводит пользователь. Модели выбираются
     * через {@link #populateModelsFor(String)}.
     */
    private void applyProviderDefaults() {
        String code = providerCode(providerCombo.getSelectedItem());
        if (Config.PROVIDER_CUSTOM.equals(code)) {
            baseUrlField.setText("");
        } else {
            baseUrlField.setText(Config.defaultBaseUrl(code));
        }
        populateModelsFor(code);
    }

    /** Преобразует выбранную подпись провайдера в код ({@link Config#PROVIDER_*} константы). */
    private static String providerCode(Object label) {
        String s = label == null ? "" : String.valueOf(label);
        if (s.contains("Яндекс")) {
            return Config.PROVIDER_YANDEX;
        }
        if (s.contains("Alibaba") || s.contains("Qwen")) {
            return Config.PROVIDER_QWEN;
        }
        if (s.contains("Другой") || s.contains("Друг")) {
            return Config.PROVIDER_CUSTOM;
        }
        return Config.PROVIDER_DEEPSEEK;
    }

    /** Код провайдера → подпись, показываемая в комбобоксе. */
    private static String providerLabel(String code) {
        if (Config.PROVIDER_YANDEX.equals(code)) {
            return "Яндекс ИИ студия";
        }
        if (Config.PROVIDER_QWEN.equals(code)) {
            return "Alibaba Qwen";
        }
        if (Config.PROVIDER_CUSTOM.equals(code)) {
            return "Другой (OpenAI-совместимый)";
        }
        return "DeepSeek";
    }

    /** Заполняет список моделей согласно выбранному провайдеру. */
    private void populateModelsFor(String provider) {
        modelCombo.removeAllItems();
        if (Config.PROVIDER_YANDEX.equals(provider)) {
            modelCombo.addItem("yandexgpt-lite");
            modelCombo.addItem("yandexgpt");
            modelCombo.addItem("yandexgpt-32k");
        } else if (Config.PROVIDER_QWEN.equals(provider)) {
            modelCombo.addItem("qwen-max");
            modelCombo.addItem("qwen-plus");
            modelCombo.addItem("qwen-turbo");
        } else if (Config.PROVIDER_CUSTOM.equals(provider)) {
            modelCombo.addItem("gpt-4o");
            modelCombo.addItem("gpt-4o-mini");
            modelCombo.addItem("custom-model");
        } else {
            modelCombo.addItem("deepseek-chat");
            modelCombo.addItem("deepseek-reasoner");
        }
    }

    // ==================== ВКЛАДКА «ИНТЕРФЕЙС» ====================

    private JPanel buildInterfaceTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(20, 24, 20, 24));
        p.setOpaque(false);

        p.add(sectionLabel("Настройки интерфейса (сохраняются в settings.json)"));
        p.add(Box.createVerticalStrut(16));

        autoUpdateCheck.setFont(Ui.FONT_PLAIN);
        autoUpdateCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(autoUpdateCheck);
        p.add(Box.createVerticalStrut(10));

                showUsageCheck.setFont(Ui.FONT_PLAIN);
        showUsageCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(showUsageCheck);
        p.add(Box.createVerticalStrut(10));

        confirmDbWriteCheck.setFont(Ui.FONT_PLAIN);
        confirmDbWriteCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(confirmDbWriteCheck);
        p.add(Box.createVerticalStrut(16));

        p.add(fieldLabel("Тема оформления"));
        p.add(Box.createVerticalStrut(6));
                themeCombo.setMaximumSize(new Dimension(200, 34));
        themeCombo.setFont(Ui.FONT_PLAIN);
        p.add(themeCombo);
        p.add(Box.createVerticalStrut(20));

        p.add(fieldLabel("Дневной лимит токенов (0 — без лимита)"));
        p.add(Box.createVerticalStrut(6));
        dailyLimitField.setMaximumSize(new Dimension(220, 32));
        Ui.styleField(dailyLimitField);
        dailyLimitField.setToolTipText("0 = без лимита. После исчерпания лимита агент остановит обращения к LLM.");
        p.add(dailyLimitField);
        p.add(Box.createVerticalStrut(8));
        spendLabel.setFont(Ui.FONT_SMALL);
        spendLabel.setForeground(Ui.TEXT_GRAY);
        spendLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(spendLabel);
        p.add(Box.createVerticalStrut(16));

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
        p.add(backupPanel);

        p.add(Box.createVerticalGlue());
        return p;
    }

    /** Обновляет строку текущего расхода токенов за день. */
    private void updateSpendLabel() {
        long today = TokenBudget.tokensToday();
        long limit = settings.getDailyLimitTokens();
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

    // ==================== КНОПКИ ====================

    private JPanel buildButtons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setBackground(Ui.SURFACE);
        row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.BORDER));

        JButton cancel = new JButton("Отмена");
        Ui.styleSecondary(cancel);
        cancel.addActionListener(e -> dispose());
        row.add(cancel);

        JButton save = new JButton("Сохранить");
        Ui.stylePrimary(save);
        save.addActionListener(e -> saveAndClose());
        row.add(save);

        return row;
    }

    // ==================== ЗАГРУЗКА / СОХРАНЕНИЕ ====================

    private void loadValues() {
        // Модель
        apiKeyField.setText(config.getApiKey());
        // Провайдер/URL (R-P0.1): подставляем сохранённые значения поверх дефолтов.
        String provider = config.getProvider();
        String pLabel = providerLabel(provider);
        if (containsItem(providerCombo, pLabel)) {
            providerCombo.setSelectedItem(pLabel);
        }
        baseUrlField.setText(config.getBaseUrl());
        populateModelsFor(provider);
        if (!containsItem(modelCombo, config.getModel())) {
            modelCombo.addItem(config.getModel());
        }
        modelCombo.setSelectedItem(config.getModel());
        temperatureSlider.setValue((int) Math.round(config.getTemperature() * 100));
        temperatureValue.setText(String.format("%.1f", config.getTemperature()));
        maxTokensField.setText(String.valueOf(config.getMaxTokens()));

                // Интерфейс
                autoUpdateCheck.setSelected(settings.isAutoUpdateDb());
        showUsageCheck.setSelected(settings.isShowUsageInChat());
        confirmDbWriteCheck.setSelected(settings.isConfirmDbWrite());
        themeCombo.setSelectedItem(settings.getTheme());
        dailyLimitField.setText(String.valueOf(settings.getDailyLimitTokens()));
        updateSpendLabel();
    }

    /** Возвращает true, если значение присутствует среди элементов джкобокса. */
    private static boolean containsItem(JComboBox<String> combo, String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (value.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void saveAndClose() {
        // Модель → config.json
        config.setApiKey(new String(apiKeyField.getPassword()).trim());
        config.setProvider(providerCode(providerCombo.getSelectedItem()));
        config.setBaseUrl(baseUrlField.getText());
        config.setModel((String) modelCombo.getSelectedItem());
        config.setTemperature(temperatureSlider.getValue() / 100.0);
        try {
            config.setMaxTokens(Integer.parseInt(maxTokensField.getText().trim()));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Max tokens должен быть целым числом.",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        config.save();

        // Интерфейс → settings.json
                settings.setAutoUpdateDb(autoUpdateCheck.isSelected());
        settings.setShowUsageInChat(showUsageCheck.isSelected());
        settings.setConfirmDbWrite(confirmDbWriteCheck.isSelected());
        settings.setTheme((String) themeCombo.getSelectedItem());
        // Дневной лимит токенов: числовой ввод, 0 = без лимита.
        try {
            long limit = Long.parseLong(dailyLimitField.getText().trim());
            settings.setDailyLimitTokens(limit);
            TokenBudget.setDailyLimit(limit);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Дневной лимит токенов должен быть целым числом.",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        settings.save();

        // Обновить состояние фрейма (автообновление БД читается из settings)
        frame.setAutoUpdateDb(autoUpdateCheck.isSelected());
        frame.refreshSidebar();

        JOptionPane.showMessageDialog(this, "Настройки сохранены.");
        dispose();
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

    // ==================== СКРУГЛЁННАЯ РАМКА С ТЕНЬЮ ====================

    /** Рисует скруглённую рамку с мягкой тенью вокруг контента диалога. */
    private static class RoundedShadowBorder extends javax.swing.border.AbstractBorder {
        private static final long serialVersionUID = 1L;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = Ui.RADIUS_DIALOG;
            // Тень
            g2.setColor(new Color(0, 0, 0, 30));
            g2.fillRoundRect(x + 2, y + 4, width - 4, height - 4, r, r);
            // Фон
            g2.setColor(Ui.SURFACE);
            g2.fillRoundRect(x, y, width - 4, height - 6, r, r);
            // Рамка
            g2.setColor(Ui.BORDER);
            g2.drawRoundRect(x, y, width - 4, height - 6, r, r);
            g2.dispose();
        }

        @Override
        public java.awt.Insets getBorderInsets(Component c) {
            return new java.awt.Insets(4, 4, 8, 4);
        }
    }
}
