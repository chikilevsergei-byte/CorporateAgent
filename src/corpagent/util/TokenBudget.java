package corpagent.util;

import java.util.Calendar;

/**
 * Учёт токенов и защита от перерасхода (завершение A1 / Этап D1).
 *
 * <p>Статистика суммы токенов (по данным {@code usage} ответов моделей):
 * prompt+completion за сессию/«сегодня», оценка стоимости по формуле
 * 17 604 300 токенов = 1 $ (актуальный курс проекта; см. TOKENS_PER_DOLLAR).</p>
 *
 * <p>Поддерживается «суточный лимит токенов» ({@link #setDailyLimit(long)}):
 * пока включён (лимит &gt; 0), {@link #canSpendToday(long)} возвращает false,
 * когда текущий расход + запрошенный уже превысили лимит → вызывающая сторона
 * (агент) коротко останавливает обращение к API и сообщает пользователю.</p>
 *
 * <p>Значения надёжны в рамках одной сессии. Дата «дня» автоматически
 * сдвигается по локальному календарю (нужен {@link #refreshDay()} из вызывающей
 * стороны хотя бы раз при старте / на рубеже суток).</p>
 */
public final class TokenBudget {

    public static final double TOKENS_PER_DOLLAR = 17_604_300.0;

    private static volatile long todayTokens;
    private static String todayKey = dayKey();
    private static long dailyLimit; // 0 = без лимита

    // Последний снятый запрос (для показа «сколько потратило это сообщение»).
    private static long latestPrompt;
    private static long latestCompletion;

    private TokenBudget() {
    }

    /** Фиксирует потребление одного ответа API (prompt+completion токенов из usage). */
    public static synchronized void record(long promptTokens, long completionTokens) {
        rolloverDay();
        long p = Math.max(0, promptTokens);
        long c = Math.max(0, completionTokens);
        todayTokens += p + c;
        latestPrompt = p;
        latestCompletion = c;
    }

    /** Сброс расходов и счётчиков за «сегодня» (нужно при явном сбросе из UI). */
    public static synchronized void resetToday() {
        todayTokens = 0;
        latestPrompt = 0;
        latestCompletion = 0;
        todayKey = dayKey();
    }

    /** Суммарно токенов за сегодня. */
    public static synchronized long tokensToday() {
        rolloverDay();
        return todayTokens;
    }

    /** Оценка стоимости сегодняшнего расхода в долларах (17 604 300 токенов = 1 $). */
    public static synchronized double costTodayUsd() {
        return tokensToday() / TOKENS_PER_DOLLAR;
    }

    /** Prompt/completion последнего запроса (для вывода «сколько съело сообщение»). */
    public static synchronized long latestTotal() {
        return latestPrompt + latestCompletion;
    }

    public static String formatTokens(long tokens) {
        if (tokens < 1000) {
            return tokens + " тк";
        }
        double k = tokens / 1000.0;
        if (k < 1000) {
            return String.format("%.1fк", k);
        }
        return String.format("%.2fМ", k / 1000.0);
    }

    public static String formatMoney(double usd) {
        if (usd < 0.00001) {
            return "$0";
        }
        return String.format("$%.4f", usd);
    }

    // ==================== лимит ====================

    /** Суточный лимит токенов (0 — без лимита). */
    public static synchronized long getDailyLimit() {
        return dailyLimit;
    }

    public static synchronized void setDailyLimit(long limit) {
        dailyLimit = Math.max(0, limit);
    }

    /** Можно ли израсходовать ещё {@code extraTokens} без превышения лимита. */
    public static synchronized boolean canSpendToday(long extraTokens) {
        rolloverDay();
        if (dailyLimit <= 0) {
            return true; // лимит выключен
        }
        return todayTokens + Math.max(0, extraTokens) <= dailyLimit;
    }

    /** Исчерпан ли сегодняшний лимит расходов (включён и достигнут). */
    public static synchronized boolean isExhaustedToday() {
        rolloverDay();
        return dailyLimit > 0 && todayTokens >= dailyLimit;
    }

    /** Сколько токенов осталось до лимита сегодня (0 если лимит выключен). */
    public static synchronized long remainingToday() {
        if (dailyLimit <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, dailyLimit - todayTokens);
    }

    /** Грубая оценка токенов по длине текста (≈ символов/3.5). */
    public static long estimateFromText(int charLength) {
        return Math.round(Math.max(0, charLength) / 3.5);
    }

    /**
     * Принудительно «переводит» день на текущую локальную дату. Нужно вызывать
     * при старте приложения и при чтении значения из [@code canSpendToday}/{@code isExhaustedToday}
     * происходит автоматически.
     */
    public static synchronized void refreshDay() {
        rolloverDay();
    }

    private static void rolloverDay() {
        String now = dayKey();
        if (todayKey == null || !todayKey.equals(now)) {
            todayTokens = 0;
            latestPrompt = 0;
            latestCompletion = 0;
            todayKey = now;
        }
    }

    private static String dayKey() {
        Calendar c = Calendar.getInstance();
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        return y + "-" + (m < 10 ? "0" : "") + m + "-" + (d < 10 ? "0" : "") + d;
    }
}
