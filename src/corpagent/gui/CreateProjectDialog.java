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
        root.add(fieldLabel("Название проекта"));
        nameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(nameField);
        root.add(Box.createVerticalStrut(10));

        // Техническое имя БД
        root.add(fieldLabel("Имя БД проекта (латиница/цифры/_-)"));
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
        root.add(dbField);
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
        createBtn.setIcon(Ui.iconImage("🗂️", 16));
        createBtn.setIconTextGap(6);
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
    }

    private boolean dbNameUserEdited = false;

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
        String db = dbField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите название проекта.");
            return;
        }
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
        final String fName = name;
        final String fDb = sanitized;
        final String fDesc = descArea.getText().trim();
        final String fPrompt = promptArea.getText().trim();

        createBtn.setEnabled(false);
        statusLabel.setForeground(Ui.TEXT_GRAY);
        statusLabel.setText("⏳ Создание БД проекта и чатов...");
        new SwingWorker<ProjectManager.CreateProjectResult, Void>() {
            @Override
            protected ProjectManager.CreateProjectResult doInBackground() {
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
