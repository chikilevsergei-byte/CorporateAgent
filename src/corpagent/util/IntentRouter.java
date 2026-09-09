package corpagent.util;

/**
 * Централизованная классификация интента пользовательского запроса (эволюция A3/C1.1).
 *
 * <p>Каркас: единое место для определения того, что хочет пользователь, вместо
 * рассыпанных по {@code Agent} проверок {@code contains(...)}. На текущем этапе
 * он воспроизводит прежнюю эвристику без изменения поведения ({@code CHAT} — запасной
 * вариант), но выделяет классификацию в переиспользуемую, удобно-тестируемую функцию.
 * В дальнейшем сюда можно добавлять более точные правила (протокол, статус-отчёт и т.д.).</p>
 */
public final class IntentRouter {

    private IntentRouter() {
    }

    /** Разновидности задачи, различимые на текущем этапе. */
    public enum Intent {
        CHAT,        // свободный диалог / анализ без создания файла
        PRESENTATION,// создание презентации (pptx)
        EXCEL,       // создание Excel-файла
        DATABASE,    // создание/работа с базой данных
        UNKNOWN
    }

    /**
     * Определяет интент по текстовому содержимому запроса (с уже пришитыми файлами).
     *
     * <p>C1.1: распознаёт не только явное создание документов, но и работу с базой данных
     * ({@link #isCreateDbRequest(String)} умеет и полное «база данных», и сокращение «БД»).</p>
     *
     * @param fullMessage исходное/полное сообщение пользователя (уже в нижнем регистре)
     * @return класс интента (никогда не null; по умолчанию {@link Intent#CHAT})
     */
    public static Intent detect(String fullMessage) {
        Intent i = detectIf(fullMessage);
        return i == null ? Intent.CHAT : i;
    }

    /**
     * Определяет интент, но не применяет запасного значения {@code CHAT}
     * (возвращает {@code null}, если ни один маркер не сработал). Полезно для
     * тонкой настройки приоритетов.
     */
    public static Intent detectIf(String fullMessage) {
        if (fullMessage == null) {
            return null;
        }
        String m = fullMessage.toLowerCase();
        // Приоритет: явное создание документов/БД прежде общих слов («таблиц» и пр.).
        if (m.contains("презентаци") || m.contains("pptx")) {
            return Intent.PRESENTATION;
        }
        if (m.contains("excel") || m.contains("таблиц") || m.contains("xlsx")) {
            return Intent.EXCEL;
        }
        if (isCreateDbRequest(m)) {
            return Intent.DATABASE;
        }
        return null;
    }

    /**
     * Признак явной просьбы создать / завести новую базу данных. Распознаёт и развёрнутое
     * «база данных», и сокращение «бд», но только в связке с глаголами создания/заведения
     * (чтобы обычные вопросы/записи в уже активной БД не ошибочно создавали новую схему).
     *
     * @param m сообщение в нижнем регистре
     */
    public static boolean isCreateDbRequest(String m) {
        if (m == null || m.isEmpty()) {
            return false;
        }
        // Глаголы намерения «завести новую БД» (без ложного срабатывания на «обновить/посмотреть»).
        boolean wantCreate = m.contains("создай") || m.contains("создать")
                || m.contains("создам") || m.contains("заведи") || m.contains("завести")
                || m.contains("завёл") || m.contains("собери базу") || m.contains("сделай базу");
        if (!wantCreate) {
            return false;
        }
        // Полное словосочетание «база/бáзы/базу/базой … данных» (любое склонение «баз… данных»)
        // либо сокращение «бд» как отдельное слово.
        boolean hasDbWord = (m.contains("баз") && m.contains("данн"))
                || isStandaloneLatinToken(m, "бд");
        return hasDbWord;
    }

    /**
     * Правда, если последовательность {@code token} встречается в {@code m} как отдельное
     * «слово», т.е. не входит в состав более длинного буквенного слова. Это надёжнее
     * regexp-границы {@code \b}, которая в Java понимает только ASCII (кириллица не-word char).
     */
    private static boolean isStandaloneLatinToken(String m, String token) {
        if (token == null || token.isEmpty()) {
        return false;
    }
        int len = token.length();
        int idx = m.indexOf(token);
        while (idx >= 0) {
            int left = idx - 1;
            int right = idx + len;
            boolean leftBoundary = left < 0 || !Character.isLetter(m.charAt(left));
            boolean rightBoundary = right >= m.length() || !Character.isLetter(m.charAt(right));
            if (leftBoundary && rightBoundary) {
                return true;
}
            idx = m.indexOf(token, idx + 1);
        }
        return false;
    }
}

