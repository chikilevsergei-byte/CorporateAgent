# 📋 CorporateAgent v4 (Web-UI) — открытые задачи

> План миграции v3 (Swing) → v4 (Web-UI). Только НЕЗАКРЫТЫЕ задачи.
> Сделанное — в `DONE.md`. Целевая архитектура — в `ARCHITECTURE.md`.
> Этот файл (и только его) можно подкладывать в контекст LLM.
> Стек: Java 8 · `com.sun.net.httpserver` · vanilla JS · SQLite · OpenAI-совместимый LLM.

## 🎯 Цель v4
Тонкий клиент (браузер) + толстый сервер (Java). Swing удаляется поэтапно.
Бизнес-логика (`Agent`, `llm/`, `tools/`, `data/`, `db/`, `files/`, `util/`) не трогается.

## 📐 Правила работы
1. **Один коммит = одна задача** отсюда. Перед стартом — `git status` (дерево чистое).
2. **Definition of Done:** `build.bat` компилируется без ошибок + покрыто `test/corpagent/SmokeCoreTest.java`, затем закоммичено.
3. **Не читать файл целиком** при точечной правке — только нужный метод (offset/limit).
4. **Не создавать скрэтч-файлы** — временные проверки только в `test/corpagent/`.
5. **Не дублировать классы** — сначала grep по `src/`.
6. **Одна метка на задачу**: ✅ / 🔄 / ⬜.
7. **Не терять фичи**: каждый Swing-класс → веб-аналог или «удалить как ненужное».

---

## 🟢 Этап 0. Подготовка (2 дня)
**Цель:** не сломать то, что работает.
- [ ] **0.1** Git-ветка `feature/web-ui`.
- [ ] **0.2** Smoke-тест: `Agent` работает без GUI.
- [ ] **0.3** Инвентаризация `gui/`: чистый UI (удалить) vs. вызовы логики (перенести в API).
- [ ] **0.4** Пустые пакеты `api/`, `api/routes/`, `api/dto/` + папка `web/`.
- **Критерий:** проект компилируется, Swing запускается, тесты зелёные. ✅/⬜

## 🟢 Этап 1. Скелет HTTP-сервера (3 дня)
**Цель:** Java поднимает сервер, браузер открывает пустую страницу.
- [ ] **1.1** `ApiServer.java` — `HttpServer` на `127.0.0.1:8080`, роутинг по пути.
- [ ] **1.2** `StaticHandler.java` — отдача файлов из `web/` с правильными MIME.
- [ ] **1.3** `Auth.java` — генерация токена, проверка `Bearer`.
- [ ] **1.4** `ApiResponse.java` — унифицированный формат `{ok, data, error}`.
- [ ] **1.5** `App.java` — после инициализации запускает сервер и `Desktop.browse()`.
- [ ] **1.6** `web/index.html` — заглушка «Hello from CorporateAgent».
- [ ] **1.7** Обновить `build.bat` и `prepare_portable.bat` для копирования `web/`.
- **Критерий:** `run_portable.bat` → браузер с «Hello». Swing-окно тоже открывается (временно). Портативный дистрибутив работает на чистой машине.

## 🟢 Этап 2. Чат — ядро UX (1 неделя)
**Цель:** перенести самую важную фичу — чат с LLM.
**Backend:**
- [ ] **2.1** `ChatRoutes.java`: `POST /api/chat` (синхронный) и `POST /api/chat/stream` (SSE).
- [ ] **2.2** `SseEmitter.java` — стриминг токенов.
- [ ] **2.3** `Agent.chatStream(message, consumer)` — новый метод, вызывает `DeepSeekClient` в
      стриминг-режиме и дёргает callback на каждый токен.
- [ ] **2.4** DTO: `ChatRequest`, `ChatChunk`.
**Frontend:**
- [ ] **2.5** `js/api.js` — обёртка над `fetch` и `EventSource`.
- [ ] **2.6** `js/store.js` — хранение истории чатов.
- [ ] **2.7** `views/ChatView.js` + `components/Message.js` + `components/Markdown.js`.
- [ ] **2.8** `css/chat.css` — чат (HTML-макет сюда).
- **Критерий:** пишем сообщения, видим стриминг ответов, markdown рендерится. Swing-чат ещё работает параллельно.

## 🟢 Этап 3. Sidebar + навигация (3 дня)
**Цель:** заменить `SidebarPanel` на веб-версию.
- [ ] **3.1** `components/Sidebar.js` — список чатов, переключение экранов.
- [ ] **3.2** `js/app.js` — простой роутер (hash-based: `#chat`, `#projects`, `#settings`).
- [ ] **3.3** `css/layout.css` — sidebar + main area.
- **Критерий:** навигация между экранами работает, чат открывается из sidebar.

## 🟡 Этап 4. Проекты, навыки, инструменты, БД (1.5 недели)
**Цель:** перенести все CRUD-экраны.
Для каждого домена (`projects`, `skills`, `tools`, `databases`):
- [ ] **4.x-B** Backend: `*Routes.java` — GET list, GET by id, POST create, PUT update, DELETE.
- [ ] **4.x-F** Frontend: `views/*View.js` + `components/*EditModal.js`.
- [ ] **4.x-D** Удалить соответствующие Swing-классы из `gui/` (`ProjectManagerView.java`, `SkillEditDialog.java` и т.д.).
- **Критерий:** все сущности создаются/редактируются/удаляются через веб. В `gui/` остались только `MainFrame` (пустой каркас) и заглушки.

## 🟡 Этап 5. Настройки и файлы (4 дня)
**Цель:** перенести `SettingsDialog` и работу с файлами.
- [ ] **5.1** `SettingsRoutes.java` — GET/PUT `config.json`, тест подключения (`POST /api/settings/test-llm`).
- [ ] **5.2** `FilesRoutes.java` — download/upload, `open-folder`.
- [ ] **5.3** `views/SettingsView.js` — вкладки Model/Tools/Skills/Databases/Interface.
- [ ] **5.4** Кнопка «Скачать отчёт» в чате → `GET /api/files/download`.
- **Критерий:** настройки меняются, LLM-провайдер тестируется, файлы скачиваются/загружаются.

## 🔴 Этап 6. Зачистка Swing (2 дня)
**Цель:** удалить `gui/` полностью.
- [ ] **6.1** Удалить весь пакет `gui/`.
- [ ] **6.2** Убрать из `App.java` создание `MainFrame` и `SwingUtilities.invokeLater` для UI.
- [ ] **6.3** Оставить `SwingUtilities.invokeLater` только для `Desktop.browse()`.
- [ ] **6.4** Обновить `SmokeCoreTest.java` — убрать упоминания GUI.
- [ ] **6.5** Обновить `README.md`, `PLAN.md`, `DONE.md`.
- **Критерий:** в проекте нет ни одного `javax.swing.*`. Тесты зелёные. Портативный дистрибутив стал легче.

---

## 🧩 DoD для v4 (итоговый чек-лист)
- [ ] Нет ни одного `javax.swing.*` в проекте.
- [ ] `run_portable.bat` на чистой Windows-машине открывает браузер с UI.
- [ ] Все функции v3 работают в v4 (чат, проекты, навыки, инструменты, БД, настройки, файлы).
- [ ] Стриминг ответов от LLM работает.
- [ ] Файлы скачиваются/загружаются.
- [ ] Smoke-тест проходит.
- [ ] Размер `dist/` не вырос более чем на 20%.

---

## 💬 Промпты для DeepSeek по этапам

**Этап 1 (скелет):**
> Java 8, без внешних библиотек. Напиши `ApiServer` на `com.sun.net.httpserver`:
> регистрация роутеров по пути, middleware авторизации по Bearer-токену, `StaticHandler` для
> раздачи файлов из папки `web/` с правильными MIME-типами. Токен генерируется при старте,
> передаётся в URL. Порт 8080, только 127.0.0.1.

**Этап 2 (стриминг):**
> Напиши `SseEmitter` для Java 8 на `com.sun.net.httpserver`: метод `start(HttpExchange)`
> устанавливает `Content-Type: text/event-stream`, и метод `send(String json)` пишет
> `data: ...\n\n`. Плюс `ChatRoutes` с эндпоинтом `/api/chat/stream`, который вызывает
> `Agent.chatStream(message, token -> emitter.send(...))` в отдельном потоке.

**Этап 3–5 (фронтенд):**
> Вот мой HTML-макет экрана [X]. Адаптируй его под vanilla JS без фреймворков: модуль
> `views/XView.js` с методами `render()` и `bindEvents()`, `components/...` для переиспользуемых
> частей. API-вызовы через `api.js`. Состояние — в `store.js`. CSS — в отдельном файле.
