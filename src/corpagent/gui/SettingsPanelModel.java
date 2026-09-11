package corpagent.gui;

import corpagent.util.Config;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.Hashtable;

/**
 * Вкладка «Модель» окна настроек: API-ключ, провайдер LLM, base URL,
 * модель, temperature и max tokens.
 *
 * <p>Выделено из {@link SettingsDialog} (R-P0.1): вся логика провайдеров
 * (подстановка URL и списка моделей, маппинг подпись↔код) инкапсулирована здесь,
 * а диалог лишь вызывает {@link #load()} / {@link #save()}.</p>
 */
public class SettingsPanelModel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JPasswordField apiKeyField = new JPasswordField(30);
    private final JComboBox<String> providerCombo = new JComboBox<String>(new String[]{
            "DeepSeek", "Яндекс ИИ студия", "Alibaba Qwen", "Другой (OpenAI-совместимый)"});
    private final JTextField baseUrlField = new JTextField(30);
    private final JComboBox<String> modelCombo = new JComboBox<String>(new String[]{
            "deepseek-chat", "deepseek-reasoner"});
    private final JSlider temperatureSlider = new JSlider(0, 200, 70);
    private final JLabel temperatureValue = new JLabel("0.7");
    private final JTextField maxTokensField = new JTextField("4000", 8);

    public SettingsPanelModel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(20, 24, 20, 24));
        setOpaque(false);
        build();
    }

    private void build() {
        add(sectionLabel("Параметры модели (сохраняются в config.json)"));
        add(Box.createVerticalStrut(16));

        // API-ключ с маскированием и переключателем видимости
        add(fieldLabel("API-ключ"));
        add(Box.createVerticalStrut(6));
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
        add(keyRow);
        add(Box.createVerticalStrut(14));

        // Провайдер (R-P0.1): DeepSeek / Яндекс ИИ студия / Произвольный (OpenAI-совместимый)
        add(fieldLabel("Провайдер LLM"));
        add(Box.createVerticalStrut(6));
        providerCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        providerCombo.setFont(Ui.FONT_PLAIN);
        providerCombo.addActionListener(e -> applyProviderDefaults());
        add(providerCombo);
        add(Box.createVerticalStrut(10));

        // Базовый URL API (адрес endpoint, до /chat/completions)
        add(fieldLabel("Base URL API (до /chat/completions)"));
        add(Box.createVerticalStrut(6));
        baseUrlField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        Ui.styleField(baseUrlField);
        baseUrlField.setToolTipText("Подставляется автоматически при выборе провайдера. Меняйте при необходимости.");
        add(baseUrlField);
        add(Box.createVerticalStrut(14));

        // Модель
        add(fieldLabel("Модель"));
        add(Box.createVerticalStrut(6));
        modelCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        modelCombo.setFont(Ui.FONT_PLAIN);
        add(modelCombo);
        add(Box.createVerticalStrut(14));

        // Temperature (слайдер) — UI-2.3: тонкий iOS-слайдер с заливкой до бегунка.
        add(fieldLabel("Temperature (креативность)"));
        add(Box.createVerticalStrut(6));
        Ui.styleSlider(temperatureSlider);
        temperatureSlider.setPreferredSize(new Dimension(420, 32));
        temperatureSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        temperatureSlider.setPaintTicks(false);
        temperatureSlider.setPaintLabels(false);
        temperatureSlider.addChangeListener(e -> {
            double v = temperatureSlider.getValue() / 100.0;
            temperatureValue.setText(String.format("%.1f", v));
        });
        add(temperatureSlider);
        add(Box.createVerticalStrut(4));
        temperatureValue.setFont(Ui.FONT_SMALL_BOLD);
        temperatureValue.setForeground(Ui.PRIMARY_DARK);
        temperatureValue.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(temperatureValue);
        add(Box.createVerticalStrut(14));

        // Max tokens
        add(fieldLabel("Max tokens (макс. длина ответа)"));
        add(Box.createVerticalStrut(6));
        maxTokensField.setMaximumSize(new Dimension(160, 34));
        Ui.styleField(maxTokensField);
        add(maxTokensField);

        add(Box.createVerticalGlue());
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
    static String providerCode(Object label) {
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
    static String providerLabel(String code) {
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

    /** Загружает текущие значения из {@link Config} в поля вкладки. */
    public void load(Config config) {
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
    }

    /**
     * Переносит значения полей вкладки в {@link Config} (без сохранения на диск).
     *
     * @return null при успехе или текст ошибки (некорректный max tokens).
     */
    public String applyTo(Config config) {
        config.setApiKey(new String(apiKeyField.getPassword()).trim());
        config.setProvider(providerCode(providerCombo.getSelectedItem()));
        config.setBaseUrl(baseUrlField.getText());
        config.setModel((String) modelCombo.getSelectedItem());
        config.setTemperature(temperatureSlider.getValue() / 100.0);
        try {
            config.setMaxTokens(Integer.parseInt(maxTokensField.getText().trim()));
        } catch (NumberFormatException ex) {
            return "Max tokens должен быть целым числом.";
        }
        return null;
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
