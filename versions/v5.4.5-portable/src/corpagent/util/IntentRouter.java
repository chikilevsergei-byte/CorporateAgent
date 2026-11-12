package corpagent.util;

/**
 * Централизованная классификация интента пользовательского запроса (эволюция A3/C1.1).
 *
 * <p>Единое место для определения того, что хочет пользователь, вместо рассыпанных по
 * {@code Agent} проверок {@code contains(...)}. Распознаёт не только создание документов/БД,
 * но и операции над активной БД ({@link Intent#UPDATE_DB}, {@link Intent#QUESTION_DB}),
 * разбор протокола встречи ({@link Intent#PROTOCOL}) и статус-отчёт ({@link Intent#STATUS}).
 * Приоритет маркеров задан в {@link #detectIf(String)}. {@code CHAT} — запасной вариант.</p>
 */
public final class IntentRouter {

    private IntentRouter() {
    }

    /** Разновидности задачи, различимые на текущем этапе. */
    public enum Intent {
        CHAT,        // свободный диалог / анализ без создания файла
        PRESENTATION,// создание презентации (pptx)
        EXCEL,       // создание Excel-файла
        DATABASE,    // создание новой базы данных
        UPDATE_DB,   // запись/изменение данных в активной БД
        QUESTION_DB, // вопрос о содержимом активной БД (чтение)
        PROTOCOL,    // разбор протокола встречи (решения/поручения)
        STATUS,      // статус-отчёт: сделано / осталось / фокус
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
        // Приоритет (C1.1, остаток): сначала явные операции над БД, затем явное создание
        // документов, затем протокол/статус. Порядок подобран так, чтобы «обнови задачу в БД»
        // не превращалось в EXCEL из-за слова «табл…», а «сделай презентацию статуса» —
        // не уходило в STATUS из-за слова «статус».
        // 1) Создание новой БД — всегда первое (глаголы создания + слово «БД/база данных»).
        if (isCreateDbRequest(m)) {
            return Intent.DATABASE;
        }
        // A document can use database data without becoming a database-creation/query task.
        if(m.matches("(?s).*(?:создай|создать|сделай|подготовь|сформируй|собери)\\s+[^.!?\\n]{0,45}(?:презентаци|pptx).*"))return Intent.PRESENTATION;
        if(m.matches("(?s).*(?:создай|создать|сделай|подготовь|сформируй|собери)\\s+[^.!?\\n]{0,45}(?:excel|xlsx).*"))return Intent.EXCEL;
        if(isProtocolRequest(m)&&m.matches("(?s).*(?:разбери|обработай|извлеки|вытяни|зафиксируй)\\s+.*"))return Intent.PROTOCOL;
        // 2) Запись/изменение данных в уже активной БД (глаголы записи + ссылка на БД).
        if (isUpdateDbRequest(m)) {
            return Intent.UPDATE_DB;
        }
        // 3) Вопрос о содержимом активной БД (вопросительная форма/глаголы чтения + ссылка на БД).
        if (isQuestionDbRequest(m)) {
            return Intent.QUESTION_DB;
        }
        // 4) Явное создание презентации/Excel прежде общих слов («таблиц», «статус»).
        if (m.contains("презентаци") || m.contains("pptx")) {
            return Intent.PRESENTATION;
        }
        if (m.contains("excel") || m.contains("xlsx") || m.contains("таблиц")) {
            return Intent.EXCEL;
        }
        // 5) Разбор протокола встречи.
        if (isProtocolRequest(m)) {
            return Intent.PROTOCOL;
        }
        // 6) Статус-отчёт (сделано / осталось / фокус).
        if (isStatusRequest(m)) {
            return Intent.STATUS;
        }
        return null;
    }

    /**
     * Признак ссылки на базу данных в тексте: развёрнутое «баз[а-я]* … данн[а-я]*» либо
     * сокращение «бд» отдельным словом. Используется для распознавания операций над БД,
     * не создающих новую схему (запись/чтение).
     */
    public static boolean hasDbReference(String m) {
        if (m == null || m.isEmpty()) {
            return false;
        }
        return (m.contains("баз") && m.contains("данн")) || isStandaloneLatinToken(m, "бд");
    }

    /**
     * Признак просьбы изменить данные в активной БД: глагол записи/правки + ссылка на БД
     * (например «обнови задачу в БД», «добавь строку в таблицу данных проекта»).
     * Не срабатывает без глагола изменения, поэтому нейтральные вопросы сюда не попадают.
     */
    public static boolean isUpdateDbRequest(String m) {
        if (!hasDbReference(m)) {
            return false;
        }
        String[] writeVerbs = {
                "обнови", "обновить", "измени", "изменить", "добавь", "добавить",
                "запиши", "записать", "занеси", "внеси", "удали", "удалить",
                "отредактируй", "поправь", "поставь", "проставь", "заполни",
        };
        for (String v : writeVerbs) {
            if (m.contains(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Признак вопроса о содержимом активной БД (чтение): вопросительная форма или глаголы
     * чтения/поиска + ссылка на БД (например «какие таблицы в БД?», «покажи сделки из БД»).
     */
    public static boolean isQuestionDbRequest(String m) {
        if (!hasDbReference(m)) {
            return false;
        }
        if (m.contains("?")) {
            return true;
        }
        String[] questionVerbs = {
                "какие", "какая", "какое", "каков", "сколько", "покажи", "показать",
                "выведи", "вывести", "найди", "найти", "посмотри", "список",
                "есть ли", "кто ", "что в", "как устроена", "как устроен",
        };
        for (String v : questionVerbs) {
            if (m.contains(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Признак запроса на разбор протокола встречи (извлечение решений и поручений).
     * Например «разбери протокол встречи», «вытяни поручения из встречи», «обработай протокол».
     */
    public static boolean isProtocolRequest(String m) {
        if (m == null || m.isEmpty()) {
            return false;
        }
        // Явное упоминание протокола/поручений — достаточно само по себе.
        if (m.contains("протокол") || m.contains("поручени")) {
            return true;
        }
        // Иначе — связка «встреча» с маркером разбора/оформления.
        boolean hasMeeting = m.contains("встреч") || m.contains("митинг")
                || m.contains("meeting");
        if (!hasMeeting) {
            return false;
        }
        return m.contains("разбери") || m.contains("обработай")
                || m.contains("оформи") || m.contains("извлеки") || m.contains("вытяни")
                || m.contains("зафиксируй") || m.contains("занеси");
    }

    /**
     * Признак запроса статус-отчёта: что сделано / что осталось / на чём фокус.
     * Например «отчёт о статусе проекта», «что сделано и что осталось», «на чём фокус».
     */
    public static boolean isStatusRequest(String m) {
        if (m == null || m.isEmpty()) {
            return false;
        }
        boolean hasStatusWord = m.contains("статус") || m.contains("прогресс");
        boolean hasReportPair = (m.contains("сделано") && m.contains("осталось"))
                || m.contains("на чём фокус") || m.contains("на чем фокус")
                || m.contains("фокус проекта") || m.contains("что осталось")
                || m.contains("что сделано");
        return hasStatusWord || hasReportPair;
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
        return hasDbWord && java.util.regex.Pattern.compile("(?iu)(?:создай|создать|создам|заведи|завести|завёл|собери|сделай)\\s+(?:(?:пожалуйста|новую|новый|мне|нам|тестовую|отдельную|пустую)\\s+){0,4}(?:баз[а-яё]*\\s+данн[а-яё]*|бд)(?:$|[^\\p{L}])").matcher(m).find();
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

