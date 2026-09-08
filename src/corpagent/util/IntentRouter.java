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
        if ((m.contains("создай") || m.contains("создать") || m.contains("созд"))
                && m.contains("баз") && m.contains("данны")) {
            return Intent.DATABASE;
        }
        return null;
    }
}
