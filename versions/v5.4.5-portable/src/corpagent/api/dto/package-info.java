/**
 * DTO для API (запросы/ответы) — Этап 2+ миграции v3 → v4.
 *
 * <p>Здесь размещаются простые транспортивные объекты:
 * {@code ChatRequest}, {@code ChatChunk} и т.п. — изолируют HTTP-слой от внутренних
 * структур {@code Map&lt;String,Object&gt;}, которыми оперирует {@link corpagent.Agent}.</p>
 *
 * <p>Пакет намеренно пуст на Этапе 1 (скелет сервера).</p>
 */
package corpagent.api.dto;
