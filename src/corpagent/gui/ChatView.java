package corpagent.gui;

import corpagent.Agent;
import corpagent.files.FileExtractor;
import corpagent.util.Json;
import corpagent.util.Utils;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Представление чата: история сообщений, поле ввода, прикрепление файлов,
 * опции работы с БД/навыками/инструментами.
 */
public class ChatView extends JPanel {

    private final MainFrame frame;
    private final JPanel messagesPanel = new JPanel();
    private final JTextArea inputArea = new JTextArea(2, 40);
    private final JLabel attachedLabel = new JLabel(" ");
    private final ChatChipsBar chipsBar;
    private final List<File> attachedFiles = new ArrayList<File>();

    public ChatView(MainFrame frame) {
        this.frame = frame;
        this.chipsBar = new ChatChipsBar(frame.getStore());
        setLayout(new BorderLayout());
        setBackground(Ui.SURFACE);

        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(Ui.SURFACE);
        JScrollPane scroll = new JScrollPane(messagesPanel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        add(buildInputPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildInputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Ui.SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.BORDER),
                new EmptyBorder(10, 12, 10, 12)));

        // Chips-панель выбора БД/навыков/инструментов (вместо чекбоксов+дропдаунов)
        JPanel options = new JPanel(new BorderLayout());
        options.setOpaque(false);
        options.add(chipsBar, BorderLayout.WEST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(new EmptyBorder(8, 0, 0, 0));
        bottom.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        JButton attachBtn = new JButton("Файлы");
        Ui.styleSecondary(attachBtn);
        attachBtn.setIcon(Ui.iconImage("📎", 16));
        attachBtn.setHorizontalTextPosition(SwingConstants.RIGHT);
        attachBtn.setIconTextGap(6);
        attachBtn.addActionListener(e -> chooseFiles());
        left.add(attachBtn);
        left.add(attachedLabel);
        attachedLabel.setForeground(Ui.TEXT_SECONDARY);
        attachedLabel.setFont(Ui.FONT_SMALL);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        JButton sendBtn = new JButton("Отправить");
        Ui.stylePrimary(sendBtn);
        sendBtn.setIcon(Ui.iconImage("➤", 16));
        sendBtn.setHorizontalTextPosition(SwingConstants.LEFT);
        sendBtn.setIconTextGap(6);
        sendBtn.addActionListener(e -> sendMessage());
        right.add(sendBtn);

        bottom.add(left, BorderLayout.WEST);
        bottom.add(right, BorderLayout.EAST);

        // Стилизованное многострочное поле с focus-состоянием
        Ui.styleArea(inputArea);
        inputArea.setBackground(Ui.SURFACE);
        installEnterToSend();
        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(null);
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.setPreferredSize(new Dimension(600, 64));

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(inputScroll, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(options, BorderLayout.NORTH);
        north.add(center, BorderLayout.CENTER);

        panel.add(north, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Отправка сообщения по Enter (Shift+Enter — перенос строки).
     */
    private void installEnterToSend() {
        inputArea.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendMessage");
        inputArea.getActionMap().put("sendMessage", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });
        // Shift+Enter — вставить перевод строки
        inputArea.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insertBreak");
        inputArea.getActionMap().put("insertBreak", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                inputArea.replaceSelection("\n");
            }
        });
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        Ui.styleFileChooser(chooser);
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Выберите файлы для анализа");
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                attachedFiles.add(f);
            }
            updateAttachedLabel();
        }
    }

    private void updateAttachedLabel() {
        if (attachedFiles.isEmpty()) {
            attachedLabel.setText(" ");
        } else {
            attachedLabel.setText("📎 Прикреплено файлов: " + attachedFiles.size());
        }
    }

    public void refresh() {
        messagesPanel.removeAll();
        String chatId = frame.getCurrentChatId();
        if (chatId == null) {
            showWelcome();
        } else {
            Map<String, Object> chat = frame.getStore().getChat(chatId);
            if (chat == null) {
                showWelcome();
            } else {
                Object msgs = chat.get("messages");
                if (msgs instanceof List && !((List<?>) msgs).isEmpty()) {
                    for (Object m : (List<?>) msgs) {
                        Map<String, Object> msg = Json.asMap(m);
                        if (msg != null) {
                            String role = Json.str(msg, "role", "assistant");
                            String text = Json.str(msg, "text");
                            addMessageBubble(role, text);
                        }
                    }
                } else {
                    addInfo("Начните диалог — напишите сообщение ниже 👇");
                }
            }
        }
        refreshChips();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        // Прокрутка вниз (только если контент реально превышает высоту viewport,
        // чтобы не уводить содержимое за пределы видимой области при сломанном viewport)
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                JScrollPane sp = (JScrollPane) messagesPanel.getParent().getParent();
                int viewH = sp.getViewport().getHeight();
                int contentH = messagesPanel.getHeight();
                // Прокручиваем вниз только когда контент выше видимой области
                // и viewport имеет корректную (положительную) высоту
                if (viewH > 0 && contentH > viewH) {
                    sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
                }
            } catch (Exception ex) {
                // игнорируем — прокрутка не критична
            }
        });
    }

    private void refreshChips() {
        chipsBar.refresh();
    }

    private void showWelcome() {
        addInfo("<b>👋 Добро пожаловать!</b><br>Нажмите <b>➕ Новый чат</b> в боковой панели, чтобы начать.<br><br>"
                + "<b>Что я умею:</b><br>"
                + "💬 Отвечать на вопросы (модель: DeepSeek)<br>"
                + "📊 Создавать презентации (PPTX)<br>"
                + "📈 Создавать Excel-таблицы<br>"
                + "🗄️ Создавать и управлять базами данных<br>"
                + "📎 Анализировать загруженные документы<br>"
                + "🧠 Сохранять навыки и инструменты<br>"
                + "📊 Визуализировать данные из БД<br>"
                + "🔄 Автоматически обновлять БД на основе контекста разговора");
    }

    private void addInfo(String html) {
        JEditorPane pane = makePane(html);
        pane.setBackground(Ui.PRIMARY_SOFT);
        pane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(209, 250, 229), 1),
                new EmptyBorder(10, 12, 10, 12)));
        pane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        messagesPanel.add(pane);
        messagesPanel.add(Box.createVerticalStrut(6));
    }

    private void addMessageBubble(String role, String text) {
        boolean user = "user".equals(role);
        String html = escapeHtml(text);
        html = html.replace("\n", "<br>");
        JEditorPane pane = makePane(html);
        pane.setEditable(false);
        pane.setOpaque(true);
        pane.setBackground(user ? Ui.BG_USER : Ui.SURFACE);
        pane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(user ? Ui.BORDER_USER : Ui.BORDER, 1),
                new EmptyBorder(8, 10, 8, 10)));
        pane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        messagesPanel.add(pane);
        messagesPanel.add(Box.createVerticalStrut(6));
    }

    private JEditorPane makePane(String html) {
        // ВАЖНО: JEditorPane с типом "text/html" использует HTMLEditorKit, который
        // ИГНОРИРУЕТ setForeground()/setFont() компонента и применяет собственную
        // таблицу стилей по умолчанию. Без явного цвета/шрифта в HTML текст может
        // рендериться невидимым на некоторых JDK/L&F. Поэтому оборачиваем контент
        // в <body> с явным цветом текста и шрифтом.
        String styled = "<html><body style=\"color:#111827;font-family:'Segoe UI';font-size:14px;\">"
                + html + "</body></html>";
        JEditorPane pane = new JEditorPane("text/html", styled);
        pane.setEditable(false);
        pane.setFont(Ui.FONT_PLAIN);
        return pane;
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        String amp = String.valueOf((char) 38);
        String lt = String.valueOf((char) 60);
        String gt = String.valueOf((char) 62);
        return s.replace("&", amp + "amp;").replace("<", lt + "t;").replace(">", gt + "t;");
    }

    private void sendMessage() {
        String chatId = frame.getCurrentChatId();
        if (chatId == null) {
            JOptionPane.showMessageDialog(this, "Сначала создайте новый чат в боковой панели.",
                    "Нет чата", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String message = inputArea.getText().trim();
        if (message.isEmpty() && attachedFiles.isEmpty()) {
            return;
        }

        Map<String, Object> chat = frame.getStore().getChat(chatId);
        if (chat == null) {
            return;
        }

        // Собираем содержимое файлов
        List<String> fileContents = new ArrayList<String>();
        for (File f : attachedFiles) {
            String content = FileExtractor.extractText(f);
            fileContents.add("Файл: " + f.getName() + "\n" + content);
        }

        String fullMessage = message;
        if (!fileContents.isEmpty()) {
            String joined = String.join("\n\n", fileContents);
            fullMessage = message.isEmpty() ? joined : message + "\n\n" + joined;
        }

        // Добавляем сообщение пользователя
        @SuppressWarnings("unchecked")
        List<Object> msgs = (List<Object>) chat.get("messages");
        if (msgs == null) {
            msgs = new ArrayList<Object>();
            chat.put("messages", msgs);
        }
        Map<String, Object> userMsg = Json.newObject();
        userMsg.put("role", "user");
        userMsg.put("text", fullMessage);
        msgs.add(userMsg);
        chat.put("updated", Utils.nowIso());
        frame.getStore().saveAll();

        addMessageBubble("user", fullMessage);
        if (!attachedFiles.isEmpty()) {
            addInfo("📎 Прикреплено файлов: " + attachedFiles.size());
        }

        // Очищаем ввод
        inputArea.setText("");
        attachedFiles.clear();
        updateAttachedLabel();

        // Определяем контексты
        String dbContext = null;
        // Контексты из выбранных chips (БД/навыки/инструменты)
        String dbPath = null;
        String dbName = null;
        if (chipsBar.hasDatabases()) {
            List<String> dbs = new ArrayList<String>(chipsBar.getSelectedDatabases());
            dbContext = frame.getAgent().buildDbContext(dbs);
            // Для авто-обновления БД используем первую выбранную БД
            dbName = dbs.get(0);
            Map<String, Object> dbInfo = frame.getStore().getDatabase(dbName);
            if (dbInfo != null) {
                dbPath = Json.str(dbInfo, "path");
            }
        }
        String skillsContext = null;
        if (chipsBar.hasSkills()) {
            StringBuilder sb = new StringBuilder();
            Map<String, Object> s = frame.getStore().getSkills();
            for (String skill : chipsBar.getSelectedSkills()) {
                Map<String, Object> skillData = Json.asMap(s.get(skill));
                String desc = skillData == null ? "" : Json.str(skillData, "description");
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("- ").append(skill).append(": ").append(desc);
            }
            skillsContext = sb.toString();
        }
        String toolsContext = null;
        if (chipsBar.hasTools()) {
            StringBuilder sb = new StringBuilder();
            Map<String, Object> t = frame.getStore().getTools();
            for (String tool : chipsBar.getSelectedTools()) {
                Map<String, Object> toolData = Json.asMap(t.get(tool));
                String desc = toolData == null ? "" : Json.str(toolData, "description");
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("- ").append(tool).append(": ").append(desc);
            }
            toolsContext = sb.toString();
        }

        // История без последнего сообщения
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < msgs.size() - 1; i++) {
            Map<String, Object> m = Json.asMap(msgs.get(i));
            if (m != null) {
                history.add(m);
            }
        }

        String fileContent = fileContents.isEmpty() ? null : String.join("\n\n", fileContents);
        String effectiveMessage = message.isEmpty() ? "Проанализируй загруженные файлы" : message;

        // Показываем индикатор "Думаю..."
        JEditorPane thinking = makePane("<i>🤔 Думаю...</i>");
        thinking.setBackground(Ui.SURFACE);
        messagesPanel.add(thinking);
        messagesPanel.revalidate();
        messagesPanel.repaint();

        final String fDbPath = dbPath;
        final String fDbName = dbName;
        final String fDbContext = dbContext;
        final String fSkillsContext = skillsContext;
        final String fToolsContext = toolsContext;
        final String fFileContent = fileContent;
        final String fMessage = effectiveMessage;
        final boolean autoUpdate = frame.isAutoUpdateDb();

        new SwingWorker<Agent.TaskResult, Void>() {
            @Override
            protected Agent.TaskResult doInBackground() {
                return frame.getAgent().processTask(fMessage, history, fFileContent,
                        fDbContext, fSkillsContext, fToolsContext, fDbPath, fDbName, autoUpdate);
            }

            @Override
            protected void done() {
                messagesPanel.remove(thinking);
                try {
                    Agent.TaskResult result = get();
                    // Добавляем ответ ассистента
                    @SuppressWarnings("unchecked")
                    List<Object> curMsgs = (List<Object>) chat.get("messages");
                    Map<String, Object> asstMsg = Json.newObject();
                    asstMsg.put("role", "assistant");
                    asstMsg.put("text", result.response);
                    curMsgs.add(asstMsg);
                    chat.put("updated", Utils.nowIso());

                    // Авто-название чата
                    if ("Новый чат".equals(Json.str(chat, "title", "Новый чат")) && curMsgs.size() >= 2) {
                        String autoTitle = frame.getAgent().generateChatTitle(fMessage);
                        chat.put("title", autoTitle);
                    }
                    frame.getStore().saveAll();

                    addMessageBubble("assistant", result.response);

                    // Визуализация использования БД/навыков/инструментов
                    if (result.usage != null && !result.usage.isEmpty()) {
                        StringBuilder ub = new StringBuilder("<b>⚡ Использовано:</b> ");
                        boolean first = true;
                        for (Map<String, Object> u : result.usage) {
                            if (!first) {
                                ub.append("&nbsp;&nbsp;");
                            }
                            first = false;
                            String icon = Json.str(u, "icon", "•");
                            String name = Json.str(u, "name");
                            ub.append(icon).append(" ").append(escapeHtml(name));
                        }
                        addInfo(ub.toString());
                    }

                    // Отображение авто-обновлений БД
                    if (result.dbUpdates != null && !result.dbUpdates.isEmpty()) {
                        StringBuilder sb = new StringBuilder("<b>🔄 Автоматические обновления БД:</b><br>");
                        for (Map<String, Object> u : result.dbUpdates) {
                            String status = Json.str(u, "status");
                            String query = Json.str(u, "query");
                            if ("executed".equals(status)) {
                                sb.append("✅ Выполнено: <code>").append(escapeHtml(Utils.truncate(query, 120)))
                                        .append("</code><br>");
                            } else if ("skipped".equals(status)) {
                                sb.append("⚠️ Пропущено: <code>").append(escapeHtml(Utils.truncate(query, 120)))
                                        .append("</code> (").append(escapeHtml(Json.str(u, "reason"))).append(")<br>");
                            } else if ("error".equals(status)) {
                                sb.append("❌ Ошибка: <code>").append(escapeHtml(Utils.truncate(query, 120)))
                                        .append("</code> (").append(escapeHtml(Json.str(u, "error"))).append(")<br>");
                            }
                        }
                        addInfo(sb.toString());
                    }

                    // Ссылка на созданный файл
                    if (result.filePath != null && !result.filePath.isEmpty()) {
                        final String path = result.filePath;
                        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
                        fileRow.setBackground(Ui.PRIMARY_SOFT);
                        JLabel fl = new JLabel("Файл создан: " + path);
                        fl.setIcon(Ui.iconImage("📄", 16));
                        fl.setIconTextGap(6);
                        fl.setFont(Ui.FONT_SMALL);
                        fileRow.add(fl);
                        JButton openBtn = new JButton("Открыть");
                        Ui.styleButton(openBtn, false);
                        openBtn.setIcon(Ui.iconImage("📥", 16));
                        openBtn.setIconTextGap(6);
                        openBtn.addActionListener(e -> openFile(path));
                        fileRow.add(openBtn);
                        fileRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
                        messagesPanel.add(fileRow);
                        messagesPanel.add(Box.createVerticalStrut(6));
                    }
                } catch (Exception ex) {
                    addMessageBubble("assistant", "❌ Ошибка: " + ex.getMessage());
                }
                messagesPanel.revalidate();
                messagesPanel.repaint();
                javax.swing.SwingUtilities.invokeLater(() -> {
                    JScrollPane sp = (JScrollPane) messagesPanel.getParent().getParent();
                    sp.getVerticalScrollBar().setValue(sp.getVerticalScrollBar().getMaximum());
                });
                frame.refreshSidebar();
            }
        }.execute();
    }

    private void openFile(String path) {
        try {
            File f = new File(path);
            if (f.exists()) {
                Desktop.getDesktop().open(f);
            } else {
                JOptionPane.showMessageDialog(this, "Файл не найден: " + path);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Не удалось открыть файл: " + e.getMessage());
        }
    }
}
