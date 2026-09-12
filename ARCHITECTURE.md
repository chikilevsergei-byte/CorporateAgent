# Архитектура CorporateAgent v4 (Web-UI)

**CorporateAgent v4** — ИИ-ассистент руководителя проектов с **веб-интерфейсом**.
Тонкий клиент (браузер) + толстый сервер (Java 8). Бизнес-логика v3 (`Agent`, `llm/`,
`tools/`, `data/`, `db/`, `files/`, `util/`) не меняется — переписывается только UI-слой:
Swing заменяется на REST/SSE HTTP-сервер + статический фронтенд.

---

## 1. Принципы архитектуры

- **Тонкий клиент, толстый сервер.** Браузер — только рендер и UX. Вся логика, LLM, файлы,
  БД — в Java-процессе.
- **Без внешних зависимостей.** HTTP-сервер — `com.sun.net.httpserver` (встроен в JDK 8).
  JSON — `util.Json`. Никаких Spring, NanoHTTPD, Jackson.
- **Один процесс, один порт.** Java-процесс поднимает сервер на `127.0.0.1`, открывает браузер,
  работает как единое приложение.
- **Портативность сохраняется.** В `dist/` добавляется папка `web/` со статикой.
- **Бизнес-логика не трогается.** `Agent`, `llm/`, `tools/`, `data/`, `db/`, `files/`, `util/` —
  без изменений.
- **Поэтапная миграция.** Swing и Web сосуществуют до полного переезда.

---

## 2. Целевое дерево проекта

```
CorporateAgent v4/
│
├── App.java                          # Bootstrap: init → ApiServer.start → browse()
├── Agent.java                        # без изменений
│
├── src/corpagent/
│   ├── api/                          # 🆕 HTTP-слой (REST + SSE + статика)
│   │   ├── ApiServer.java            # HttpServer, роутинг, middleware
│   │   ├── RequestContext.java       # обёртка над HttpExchange (парсинг JSON, headers)
│   │   ├── ApiResponse.java          # унифицированный ответ {ok, data, error}
│   │   ├── Auth.java                 # токен-авторизация (Bearer <secret>)
│   │   ├── StaticHandler.java        # отдача файлов из web/ (с MIME)
│   │   ├── SseEmitter.java           # Server-Sent Events для стриминга LLM
│   │   │
│   │   ├── routes/                   # контроллеры по доменам
│   │   │   ├── ChatRoutes.java       # POST /api/chat, GET /api/chat/stream
│   │   │   ├── ProjectsRoutes.java   # CRUD /api/projects
│   │   │   ├── SkillsRoutes.java     # /api/skills
│   │   │   ├── ToolsRoutes.java      # /api/tools
│   │   │   ├── DatabasesRoutes.java  # /api/databases
│   │   │   ├── SettingsRoutes.java   # GET/PUT /api/settings
│   │   │   ├── FilesRoutes.java      # /api/files/download, /api/files/upload
│   │   │   └── SystemRoutes.java     # /api/system/health, /api/system/shutdown
│   │   │
│   │   └── dto/                      # DTO для API (Request/Response)
│   │       ├── ChatRequest.java
│   │       ├── ChatChunk.java
│   │       └── ...
│   │
│   ├── gui/                          # ⏳ Swing — УДАЛЯЕТСЯ ПОЭТАПНО
│   │   └── (остатки, пока не перенесены)
│   │
│   ├── llm/                          # без изменений
│   ├── tools/                        # без изменений
│   ├── data/                         # без изменений
│   ├── db/                           # без изменений
│   ├── files/                        # без изменений
│   └── util/                         # без изменений (+ новый HttpClient для SSE-клиента, если нужен)
│
├── web/                              # 🆕 Фронтенд (статика)
│   ├── index.html                    # SPA-точка входа (мастер-прототип v1 ∪ v2)
│   ├── css/
│   │   ├── prototype.css             # CSS из v1 + v2 (объединённый эталон)
│   │   ├── base.css                  # reset, типографика, переменные темы
│   │   ├── layout.css                # sidebar + main area
│   │   ├── overrides.css             # правки под реальные данные
│   │   └── screens/                  # стили по экранам
│   │       ├── chat.css
│   │       ├── notes.css             # 🆕 из v2
│   │       ├── projects.css
│   │       ├── settings.css
│   │       ├── skills.css
│   │       ├── tools.css
│   │       └── databases.css
│   ├── js/
│   │   ├── app.js                    # роутинг, инициализация
│   │   ├── api.js                    # обёртка над fetch + SSE
│   │   ├── store.js                  # состояние (chats, projects, settings)
│   │   ├── prototype-raw.js          # behavior-скрипты прототипа (на перенос)
│   │   ├── prototype-bindings.js     # привязка моков прототипа к реальным API
│   │   ├── views/
│   │   │   ├── ChatView.js
│   │   │   ├── NotesView.js          # 🆕 из v2
│   │   │   ├── ProjectsView.js
│   │   │   ├── SettingsView.js
│   │   │   ├── SkillsView.js
│   │   │   ├── ToolsView.js
│   │   │   └── DatabasesView.js
│   │   └── components/
│   │       ├── Sidebar.js            # с выпадающим списком проектов (v2)
│   │       ├── Message.js            # с кнопками действий (v2)
│   │       ├── ChatContextPanel.js   # 🆕 правая панель (v2)
│   │       ├── Markdown.js
│   │       ├── Modal.js
│   │       ├── Toast.js
│   │       ├── EmptyState.js
│   │       ├── SkeletonLoader.js
│   │       ├── WizardProgress.js
│   │       └── DropZone.js
│   └── assets/
│       ├── icons/                    # SVG
│       └── fonts/
│
├── lib/                              # poi, pdfbox, sqlite-jdbc, ...
├── dist/CorporateAgent_portable/
│   ├── jre/
│   ├── CorporateAgent.jar
│   ├── web/                          # копируется из /web при сборке
│   ├── config.json
│   ├── databases/
│   ├── backups/
│   ├── output/
│   └── Start.bat
│
├── config.json
├── build.bat                         # + копирование web/ в dist/
├── run.bat
├── run_portable.bat
└── prepare_portable.bat
```

---

## 3. Слои и их ответственность

| Слой | Что делает | Ключевые классы |
|------|-----------|-----------------|
| **Bootstrap** | Инициализация, запуск сервера, открытие браузера | `App` |
| **API (HTTP)** | REST-эндпоинты, SSE-стриминг, отдача статики, авторизация | `ApiServer`, `routes/*`, `SseEmitter`, `Auth`, `StaticHandler` |
| **Оркестрация** | Ядро агента, маршрутизация интентов | `Agent`, `util.IntentRouter` |
| **LLM** | HTTP-клиент к провайдерам, function-calling | `llm.DeepSeekClient` |
| **Инструменты** | Реестр и исполнение tools | `tools.ToolRegistry`, `tools.ToolExecutor` |
| **Данные** | JSON-хранилище, проекты, пресеты | `data/*` |
| **БД** | SQLite | `db/*` |
| **Файлы** | DOCX/PDF/XLSX/PPTX | `files/*` |
| **Утилиты** | JSON, конфиг, токен-бюджет | `util/*` |
| **Web (фронтенд)** | HTML/CSS/JS, рендер, UX | `web/**` |

---

## 4. Ключевые технические решения

### 4.1. Стриминг ответов от LLM (SSE)

Пользователь не должен ждать 10–30 секунд ответа. Используем **Server-Sent Events**:

```
POST /api/chat/stream
→ Java: Agent.chatStream() → SseEmitter
→ Браузер: EventSource читает токены по одному
```

`SseEmitter` пишет в `OutputStream` ответа:

```
data: {"token":"Прив"}
data: {"token":"етств"}
data: {"done":true}
```

### 4.2. Безопасность (localhost, но всё равно)

- Сервер слушает **только `127.0.0.1`** — извне недоступен.
- При старте генерируется **случайный токен**, передаётся в URL:
  `http://127.0.0.1:8080/?token=abc123`.
- Все `/api/*` запросы проверяют `Authorization: Bearer abc123`.
- Токен живёт, пока жив процесс.

### 4.3. Работа с файлами

Браузер не может писать на диск напрямую. Решения:

- **Скачивание** (xlsx/pptx из `output/`): `GET /api/files/download?id=...` →
  `Content-Disposition: attachment`.
- **Загрузка** (файлы для анализа): `POST /api/files/upload` (multipart) → складываем во
  временную папку.
- **Открытие папки** (output/, databases/): `GET /api/system/open-folder?path=output` →
  Java вызывает `Desktop.getDesktop().open()`.

### 4.4. Настройки

`GET /api/settings` возвращает JSON. `PUT /api/settings` сохраняет в `config.json`.
Никаких Swing-диалогов.

### 4.5. Завершение работы

Кнопка «Выход» в UI → `POST /api/system/shutdown` → Java делает `System.exit(0)`.
Браузер закрывает вкладку сам (или пользователь).

### 4.6. Сборка

`build.bat` дополняется:

```bat
xcopy /E /I /Y web dist\CorporateAgent_portable\web
```

---

## 5. Соответствие фронтенда целевому примеру (MyAssistant/)

| Пример (MyAssistant) | CorporateAgent v4 |
|----------------------|-------------------|
| `MyAssistant.exe` | `App.java` (точка входа, открывает браузер) |
| `runtime/` | `dist/CorporateAgent_portable/` (+ JRE) |
| `app/assistant.jar` | `dist/CorporateAgent.jar` (сервер) |
| `app/ui/index.html` | `web/index.html` |
| `app/ui/css/` | `web/css/` |
| `app/ui/js/` | `web/js/` |
| `app/ui/assets/` | `web/assets/` |
| `config/` | `config.json` |

---

## 6. Риски и митигация

| Риск | Вероятность | Митигация |
|------|-------------|-----------|
| SSE не работает в старых браузерах | низкая | `EventSource` поддерживается везде с 2012. Fallback — polling. |
| Порт занят (8080) | средняя | При старте пробуем 8080 → 8081 → 8082. Порт пишем в лог и в URL. |
| Антивирус блокирует localhost-сервер | низкая | Подпись jar (опционально). В README — инструкция. |
| Потеря данных при миграции | низкая | Все данные в JSON/SQLite — не трогаем. Только UI. |
| DeepSeek генерирует кривой JS | высокая | Дробить задачи: один экран за раз. Давать референсы (React/Vue-подобные паттерны на vanilla JS). |
| Забыть перенести фичу из Swing | средняя | Вести чек-лист: каждый Swing-класс → веб-аналог или «удалить как ненужное». |

---

## 7. Итоговый чек-лист «Definition of Done» для v4

- [ ] Нет ни одного `javax.swing.*` в проекте.
- [ ] `run_portable.bat` на чистой Windows-машине открывает браузер с UI.
- [ ] Все функции v3 работают в v4 (чат, проекты, навыки, инструменты, БД, настройки, файлы).
- [ ] Стриминг ответов от LLM работает.
- [ ] Файлы скачиваются/загружаются.
- [ ] Smoke-тест проходит.
- [ ] Размер `dist/` не вырос более чем на 20% (папка `web/` — несколько сотен КБ).

---

## 8. Эталон UI: 100% перенос прототипа (v1 ∪ v2)

Фронтенд — **дословный перенос** разметки/стилей/скриптов из мастер-прототипа:

- `corporate-agent-prototype v1.html` — базовые экраны.
- `corporate-agent-prototype v2.html` — дополнение к v1.
- **Финальный UI = v1 ∪ v2.** При расхождении приоритет у v2.
- Меняем только источник данных (моки → реальные `/api/*`); верстку, классы, отступы,
  цвета и компоненты сохраняем один-в-один.
- CSS прототипа → `web/css/prototype.css`, JS прототипа → `web/js/prototype-raw.js`,
  привязки к API — в `web/js/prototype-bindings.js`.

---

## 9. API-эндпоинты (итого по v1 ∪ v2)

| Метод | Путь | Назначение | Источник |
|---|---|---|---|
| POST | `/api/chat` | Отправить сообщение (sync) | v1 |
| POST | `/api/chat/stream` | Стриминг (SSE) | v1 |
| **PUT** | `/api/chat/{id}/message/{mid}` | Редактировать сообщение | **🆕 v2** |
| **DELETE** | `/api/chat/{id}/message/{mid}` | Удалить сообщение | **🆕 v2** |
| **POST** | `/api/chat/{id}/message/{mid}/regenerate` | Перегенерировать | **🆕 v2** |
| **GET** | `/api/chat/{id}/context` | Получить контекст чата (БД, навыки, инструменты) | **🆕 v2** |
| **PUT** | `/api/chat/{id}/context` | Обновить контекст чата | **🆕 v2** |
| GET | `/api/projects` | Список проектов | v1 |
| POST | `/api/projects` | Создать проект (включая `existing_db_id`) | v1 + v2 |
| **GET/POST/PUT/DELETE** | `/api/notes` | Заметки | **🆕 v2** |
| GET/PUT | `/api/settings` | Настройки (+ `POST /api/settings/test-llm`) | v1 |
| GET/POST/PUT/DELETE | `/api/skills` | Навыки | v1 |
| GET/POST/PUT/DELETE | `/api/tools` | Инструменты | v1 |
| GET/POST/PUT/DELETE | `/api/databases` | БД | v1 |
| POST | `/api/files/upload` | Загрузка файлов | v1 |
| GET | `/api/files/download` | Скачивание файлов | v1 |
| GET | `/api/system/health` | Health-check | v1 |
| POST | `/api/system/shutdown` | Завершение работы | v1 |
| GET | `/api/system/open-folder` | Открыть папку (output/, databases/) | v1 |

---

## 10. Матрица миграции Swing → Web

Полное сопоставление всех 33 классов `gui/` с прототипами и решения по каждому.

### ✅ Полное совпадение (переносим дословно в веб-компоненты)

| Swing-класс | v1 | v2 | Веб-аналог |
|---|---|---|---|
| BaseDialog | ✅ | ✅ | Web Modal component |
| Ui, UiTheme | ✅ | ✅ | CSS variables + design tokens |
| ToggleSwitch | ✅ | ✅ | CSS toggle |
| Toast | ✅ | ✅ | JS toast library |
| EmptyState | ✅ | ✅ | CSS component |
| SkeletonLoader | ✅ | ✅ | CSS skeleton animation |
| WizardProgress | ✅ | ✅ | CSS stepper |
| DropZone | ✅ | ✅ | JS drag&drop |
| MessageBubble | ✅ | ✅ | Chat message component |
| WelcomeView | ✅ | ✅ | Welcome screen |
| ChatContextMenu | ✅ | ✅ | Context menu |
| MainFrame | ✅ | ✅ | App layout (sidebar + main) |
| SidebarPanel | ✅ | ✅ | Sidebar component |
| ChatView | ✅ | ✅ | Chat view |
| ChatChipsBar | ✅ | ✅ | Prompt chips |
| CreateProjectDialog | ✅ | ✅ | Project wizard |
| DbManagerView | ✅ | ✅ | DB list view |
| DbCreateFromFilesDialog | ✅ | ✅ | DB from files |
| SettingsDialog | ✅ | ✅ | Settings modal |
| SettingsPanelModel | ✅ | ✅ | Settings tab Model |
| SettingsPanelInterface | ✅ | ✅ | Settings tab Interface |
| SettingsPanelSkills | ✅ | ✅ | Settings tab Skills |
| SettingsPanelTools | ✅ | ✅ | Settings tab Tools |
| SettingsPanelDatabases | ✅ | ✅ | Settings tab Databases |
| SkillManagerView | ✅ | ✅ | Skills list |
| SkillEditDialog | ✅ | ✅ | Skill editor |
| ToolManagerView | ✅ | ✅ | Tools list |
| ToolEditDialog | ✅ | ✅ | Tool editor |
| FilePickerDialog | ✅ | ✅ | File upload modal |

### 🆕 Есть в прототипах, НЕТ в Swing (добавляем в Java + фронтенд)

| Фича из прототипа | Где в прототипе | Что делаем в v4 |
|---|---|---|
| **Раздел «Заметки»** | v2 sidebar + NotesView | API `GET/POST/PUT/DELETE /api/notes`; фронтенд `views/NotesView.js`. |
| **Выпадающий список «Проекты»** | v2 sidebar | API `GET /api/projects` (есть); фронтенд — dropdown в sidebar. |
| **Кнопки действий на сообщениях** | v2 ChatView (✏️🗑️ для user, 🔄📝👤 для assistant) | API edit/delete/regenerate сообщений; фронтенд — Message actions. |
| **Structured data в assistant-сообщениях** | v2 ChatView (карточки с полями) | Рендер structured content (в `Agent` уже есть — только отрисовать). |
| **ChatContextPanel (правая панель)** | v2 ChatView (табы Файлы/БД/Навыки/Инструменты) | API `GET/PUT /api/chat/{id}/context`; фронтенд `components/ChatContextPanel.js`. |
| **Выбор «Существующая БД»** | v2 CreateProjectDialog (шаг 2) | `POST /api/projects` уже принимает `existing_db_id`; добавить radio в визард. |

### ❓ Есть в Swing, НЕТ в прототипах (решения приняты)

| Swing-класс | Что делает | Решение |
|---|---|---|
| **UpdateProjectDbDialog** | Обновление БД проекта (upsert, выбор ветки при конфликте) | **B — автоматизировать, без UI.** Логика в `db_execute`, ветка выбирается автоматически + лог. Диалог удаляется. |
| **DbViewerView** | Просмотр таблиц БД: схемы, SQL, экспорт CSV | **C — расширенная фича.** Кнопка «Просмотр» в DbManagerView → модалка с таблицами. |
| **DbConnectDialog** | Подключение к внешним БД (PostgreSQL, MySQL, MongoDB, MSSQL, Oracle) | **C — расширенная фича.** Кнопка «Подключить внешнюю БД» в DbManagerView → модалка. |
