package corpagent.gui;

import corpagent.util.Config;
import corpagent.util.Settings;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Модальное окно настроек (⚙️) с современными вкладками:
 * Модель | Интерфейс | Навыки | Инструменты | БД.
 *
 * <p>Вместо стандартного JTabbedPane используется собственная полоса вкладок
 * (активная вкладка выделяется нижней рамкой 2px цвета Primary) + CardLayout.
 * Окно имеет скругление и мягкую тень.</p>
 *
 * <p>Содержимое вкладок вынесено в отдельные панели: {@link SettingsPanelModel},
 * {@link SettingsPanelInterface}, {@link SettingsPanelSkills},
 * {@link SettingsPanelTools}, {@link SettingsPanelDatabases}. Этот класс отвечает
 * только за «оболочку» (полоса вкладок, карточка, кнопки) и координацию
 * загрузки/сохранения.</p>
 */
public class SettingsDialog extends BaseDialog {

    private final MainFrame frame;
    private final Config config;
    private final Settings settings;

    // Вкладка «Модель»
    private final SettingsPanelModel modelPanel = new SettingsPanelModel();

    // Вкладка «Интерфейс»
    private final SettingsPanelInterface interfacePanel;

    // Вкладка «Навыки»
    private final SettingsPanelSkills skillsPanel;

    // Вкладка «Инструменты»
    private final SettingsPanelTools toolsPanel;

    // Вкладка «Базы данных»
    private final SettingsPanelDatabases databasesPanel;

    // Панели вкладок и CardLayout
    private final JPanel cardPanel = new JPanel(new CardLayout());
    private final JButton[] tabButtons = new JButton[5];

    public SettingsDialog(MainFrame frame) {
        super(frame, "⚙️ Настройки", true);
        this.frame = frame;
        this.config = frame.getConfig();
        this.settings = frame.getSettings();

        // Панели создаются заранее, чтобы их можно было обновлять (refresh)
        // при открытии диалога и при переключении на вкладку.
        this.interfacePanel = new SettingsPanelInterface(frame);
        this.skillsPanel = new SettingsPanelSkills(frame);
        this.toolsPanel = new SettingsPanelTools(frame);
        this.databasesPanel = new SettingsPanelDatabases(frame);

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setUndecorated(true);
        setSize(780, 580);
        setLocationRelativeTo(frame);

        // UI-2.4: контент внутри скруглённой «карточки» с тенью (BaseDialog).
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Ui.SURFACE);

        // Полоса вкладок
        JPanel tabBar = buildTabBar();
        content.add(tabBar, BorderLayout.NORTH);

        // Панели вкладок
        cardPanel.setOpaque(false);
        cardPanel.add(modelPanel, "tab0");
        cardPanel.add(interfacePanel, "tab1");
        cardPanel.add(skillsPanel, "tab2");
        cardPanel.add(toolsPanel, "tab3");
        cardPanel.add(databasesPanel, "tab4");
        content.add(cardPanel, BorderLayout.CENTER);

        content.add(buildButtons(), BorderLayout.SOUTH);

        setCardContent(content, null);

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
        modelPanel.load(config);
        interfacePanel.load(settings);
    }

    private void saveAndClose() {
        // Модель → config.json
        String modelError = modelPanel.applyTo(config);
        if (modelError != null) {
            JOptionPane.showMessageDialog(this, modelError, "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        config.save();

        // Интерфейс → settings.json
        String uiError = interfacePanel.applyTo(settings);
        if (uiError != null) {
            JOptionPane.showMessageDialog(this, uiError, "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        settings.save();

        // Обновить состояние фрейма (автообновление БД читается из settings)
        frame.setAutoUpdateDb(settings.isAutoUpdateDb());
        // UI-0.2: гарантированно применить выбранную тему (светлая/тёмная) к UI.
        frame.applyTheme(settings.getTheme());
        frame.refreshSidebar();

        JOptionPane.showMessageDialog(this, "Настройки сохранены.");
        dispose();
    }
}
