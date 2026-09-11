package corpagent.gui;

import corpagent.data.DataStore;
import corpagent.data.ProjectManager;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Map;

/**
 * Мастер «Создать проект» (R-P0.2). Собирает имя, описание и системный промпт проекта,
 * затем через {@link ProjectManager} создаёт БД проекта и 7 авто-чатов-ролей.
 *
 * <p>Имя БД проекта по умолчанию выводится из имени проекта (транслитерация
 * не делается — используются только латинские символы; при вводе кириллицы
 * предлагается отдельно задать техническое имя БД).</p>
 */
public class CreateProjectDialog extends JDialog {

    private final MainFrame frame;
    private final DataStore store;

    private final JTextField nameField = new JTextField(28);
    private final JTextField dbField = new JTextField(28);
    private final JTextArea descArea = new JTextArea(3, 40);
    private final JTextArea promptArea = new JTextArea(5, 40);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton createBtn = new JButton("Создать проект");

    // R-P1.9.5: источник БД проекта — новая (по доменной схеме) или существующая из хранилища.
    private final javax.swing.JRadioButton newDbRadio = new javax.swing.JRadioButton("Новая БД проекта (доменная схема)");
    private final javax.swing.JRadioButton existingDbRadio = new javax.swing.JRadioButton("Существующая БД из списка");
    private final javax.swing.JComboBox<String> existingDbCombo = new javax.swing.JComboBox<String>();
    // UI-1.3: радио-карточки выбора источника БД.
    private Ui.RadioCard newDbCard;
    private Ui.RadioCard existingDbCard;
    private JPanel dbNameRow;
    private JPanel existingDbRow;

    private boolean created = false;
    private String createdProjectId;

    public CreateProjectDialog(MainFrame frame, DataStore store) {
        super(frame, "Создать проект", true);
        this.frame = frame;
        this.store = store;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(680, 640);
        setLocationRelativeTo(frame);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(Ui.SURFACE);
        root.setBorder(new EmptyBorder(18, 20, 16, 20));

        root.add(Ui.header("🗂️", "Создать проект", Ui.FONT_TITLE));
        root.add(Box.createVerticalStrut(6));
        JLabel hint = new JLabel("<html>Будет создана база данных проекта (tasks, risks, meetings, …) "
                + "и <b>7 рабочих чатов-ролей</b>.<br>"
                + "Каждый чат настроен на БД проекта + «Методология LPG» (только чтение) "
                + "и профильные навыки/инструменты.</html>");
        hint.setFont(Ui.FONT_SMALL);
        hint.setForeground(Ui.TEXT_GRAY);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(hint);
        root.add(Box.createVerticalStrut(14));

        // Имя проекта
        root.add(Ui.formRow("Название проекта", nameField));
        root.add(Box.createVerticalStrut(10));

        // R-P1.9.5: выбор источника БД проекта (UI-1.3: радио-карточки).
        root.add(fieldLabel("Источник БД проекта"));
        newDbRadio.setSelected(true);
        newDbRadio.setOpaque(false);
        existingDbRadio.setOpaque(false);
        newDbRadio.setFont(Ui.FONT_SMALL);
        existingDbRadio.setFont(Ui.FONT_SMALL);
        javax.swing.ButtonGroup srcGroup = new javax.swing.ButtonGroup();
        srcGroup.add(newDbRadio);
        srcGroup.add(existingDbRadio);

        JPanel srcCards = new JPanel();
        srcCards.setLayout(new BoxLayout(srcCards, BoxLayout.Y_AXIS));
        srcCards.setOpaque(false);
        srcCards.setAlignmentX(Component.LEFT_ALIGNMENT);
        newDbCard = Ui.radioCard("Новая БД проекта (доменная схема)",
                "Создать БД: tasks, risks, meetings, …", true);
        newDbCard.onSelect(() -> {
            newDbRadio.setSelected(true);
            newDbCard.setSelected(true);
            existingDbCard.setSelected(false);
            updateSourceRows();
        });
        existingDbCard = Ui.radioCard("Существующая БД из списка",
                "Использовать подключённую БД", false);
        existingDbCard.onSelect(() -> {
            existingDbRadio.setSelected(true);
            existingDbCard.setSelected(true);
            newDbCard.setSelected(false);
            updateSourceRows();
        });
        srcCards.add(newDbCard);
        srcCards.add(Box.createVerticalStrut(6));
        srcCards.add(existingDbCard);
        root.add(srcCards);
        root.add(Box.createVerticalStrut(6));

        // Ряд «Существующая БД» — комбобокс зарегистрированных БД (скрыт при выборе «новая»).
        existingDbRow = new JPanel();
        existingDbRow.setLayout(new BoxLayout(existingDbRow, BoxLayout.X_AXIS));
        existingDbRow.setOpaque(false);
        existingDbRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        existingDbRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        existingDbCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        existingDbCombo.setFont(Ui.FONT_SMALL);
        existingDbRow.add(existingDbCombo);
        existingDbRow.setVisible(false);
        root.add(existingDbRow);
        root.add(Box.createVerticalStrut(10));

        // Техническое имя БД — в отдельном ряду (скрывается при выборе существующей БД).
        dbNameRow = new JPanel();
        dbNameRow.setLayout(new BoxLayout(dbNameRow, BoxLayout.Y_AXIS));
        dbNameRow.setOpaque(false);
        dbNameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        dbNameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        dbNameRow.add(fieldLabel("Имя БД проекта (латиница/цифры/_-)"));
        dbField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        dbField.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Как только пользователь сам правит имя БД — автоподстановка из имени проекта отключается.
        dbField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (dbField.isFocusOwner()) {
                    dbNameUserEdited = true;
                }
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (dbField.isFocusOwner()) {
                    dbNameUserEdited = true;
                }
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        dbNameRow.add(dbField);
        root.add(dbNameRow);
        root.add(Box.createVerticalStrut(10));

        // Описание
        root.add(fieldLabel("Описание проекта (опционально)"));
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descArea.setFont(Ui.FONT_PLAIN);
        JScrollPane descScroll = new JScrollPane(descArea);
        descScroll.setPreferredSize(new Dimension(620, 70));
        descScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        descScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(descScroll);
        root.add(Box.createVerticalStrut(10));

        // Системный промпт
        root.add(fieldLabel("Системный промпт проекта (роль РП, цели, «без правки методологии LPG»)"));
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setFont(Ui.FONT_PLAIN);
        promptArea.setText("Ты — ассистент руководителя проекта. Работай в рамках БД проекта и методологии LPG. "
                + "Не изменяй данные методологии LPG (только чтение).");
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setPreferredSize(new Dimension(620, 110));
        promptScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        promptScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(promptScroll);
        root.add(Box.createVerticalStrut(14));

        // Список будущих чатов
        JLabel rolesTitle = fieldLabel("Будут созданы чаты:");
        root.add(rolesTitle);
        root.add(Box.createVerticalStrut(4));
        JPanel rolesPanel = new JPanel();
        rolesPanel.setLayout(new BoxLayout(rolesPanel, BoxLayout.Y_AXIS));
        rolesPanel.setOpaque(false);
        rolesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (ProjectManager.ChatRole r : ProjectManager.CHAT_ROLES) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(Ui.icon(r.icon, 14));
            JLabel l = new JLabel(r.title);
            l.setFont(Ui.FONT_SMALL);
            l.setForeground(Ui.TEXT);
            row.add(l);
            rolesPanel.add(row);
        }
        root.add(rolesPanel);

        // Кнопки
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        Ui.styleButton(createBtn, true);
        Ui.withActionIcon(createBtn, "🗂️");
        createBtn.addActionListener(e -> createProject());
        buttons.add(createBtn);
        JButton cancel = new JButton("Отмена");
        Ui.styleSecondary(cancel);
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        root.add(Box.createVerticalStrut(10));
        root.add(buttons);

        statusLabel.setFont(Ui.FONT_SMALL);
        statusLabel.setForeground(Ui.PRIMARY);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(Box.createVerticalStrut(4));
        root.add(statusLabel);

        root.add(Box.createVerticalGlue());
        setContentPane(root);

        // Имя БД по умолчанию — из имени проекта (только пока пользователь его не менял).
        nameField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                autofillDbName();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                autofillDbName();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                autofillDbName();
            }
        });

        // R-P1.9.5: заполняем список существующих БД и переключаем видимость рядов.
        for (String dbName : store.enabledDatabases()) {
            existingDbCombo.addItem(dbName);
        }
        newDbRadio.addActionListener(e -> updateSourceRows());
        existingDbRadio.addActionListener(e -> updateSourceRows());
        updateSourceRows();
    }

    private boolean dbNameUserEdited = false;

    /** R-P1.9.5: показывает ряд имени новой БД либо ряд выбора существующей БД. */
    private void updateSourceRows() {
        boolean useExisting = existingDbRadio.isSelected();
        dbNameRow.setVisible(!useExisting);
        existingDbRow.setVisible(useExisting);
        revalidate();
        repaint();
    }

    private void autofillDbName() {
        if (dbNameUserEdited) {
            return;
        }
        String suggestion = ProjectManager.sanitizeDbName(nameField.getText());
        dbField.setText(suggestion == null ? "" : suggestion);
    }

    public boolean isCreated() {
        return created;
    }

    public String getCreatedProjectId() {
        return createdProjectId;
    }

    private void createProject() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите название проекта.");
            return;
        }
        final boolean useExisting = existingDbRadio.isSelected();
        final String fName = name;
        final String fDesc = descArea.getText().trim();
        final String fPrompt = promptArea.getText().trim();
        final String fDb;
        if (useExisting) {
            Object sel = existingDbCombo.getSelectedItem();
            if (sel == null) {
                JOptionPane.showMessageDialog(this,
                        "Нет зарегистрированных БД. Создайте БД или выберите «Новая БД проекта».");
                return;
            }
            fDb = String.valueOf(sel);
        } else {
            String db = dbField.getText().trim();
            String sanitized = db.isEmpty() ? ProjectManager.sanitizeDbName(name) : db;
            if (sanitized == null || !Utils.isValidDbName(sanitized)) {
                JOptionPane.showMessageDialog(this,
                        "Некорректное имя БД проекта. Используйте латиницу, цифры, '_' или '-'.");
                return;
            }
            if (store.getDatabase(sanitized) != null) {
                JOptionPane.showMessageDialog(this,
                        "БД проекта '" + sanitized + "' уже существует. Укажите другое имя.");
                return;
            }
            fDb = sanitized;
        }

        createBtn.setEnabled(false);
        statusLabel.setForeground(Ui.TEXT_GRAY);
        statusLabel.setText(useExisting
                ? "⏳ Создание проекта на существующей БД и чатов..."
                : "⏳ Создание БД проекта и чатов...");
        new SwingWorker<ProjectManager.CreateProjectResult, Void>() {
            @Override
            protected ProjectManager.CreateProjectResult doInBackground() {
                if (useExisting) {
                    return ProjectManager.createProjectWithExistingDb(store, fName, fDesc, fPrompt, fDb);
                }
                return ProjectManager.createProject(store, frame.getDbManager(),
                        fName, fDesc, fPrompt, fDb);
            }

            @Override
            protected void done() {
                createBtn.setEnabled(true);
                try {
                    ProjectManager.CreateProjectResult r = get();
                    if (r.success) {
                        created = true;
                        createdProjectId = r.projectId;
                        statusLabel.setForeground(Ui.PRIMARY);
                        statusLabel.setText("✅ Проект создан: " + r.chatIds.size() + " чатов.");
                        dispose();
                    } else {
                        statusLabel.setForeground(Ui.ERROR);
                        statusLabel.setText("❌ " + r.error);
                        JOptionPane.showMessageDialog(CreateProjectDialog.this, r.error);
                    }
                } catch (Exception ex) {
                    statusLabel.setForeground(Ui.ERROR);
                    statusLabel.setText("❌ " + ex.getMessage());
                }
            }
        }.execute();
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_SMALL_BOLD);
        l.setForeground(Ui.TEXT_SECONDARY);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        return l;
    }
}
