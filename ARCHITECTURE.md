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
│   ├── index.html                    # SPA-точка входа
│   ├── css/
│   │   ├── base.css                  # reset, типографика, переменные темы
│   │   ├── layout.css                # sidebar + main area
│   │   ├── chat.css                  # сообщения, markdown
│   │   └── components.css            # кнопки, инпуты, модалки
│   ├── js/
│   │   ├── app.js                    # роутинг, инициализация
│   │   ├── api.js                    # обёртка над fetch + SSE
│   │   ├── store.js                  # состояние (chats, projects, settings)
│   │   ├── views/
│   │   │   ├── ChatView.js
│   │   │   ├── ProjectsView.js
│   │   │   ├── SkillsView.js
│   │   │   ├── SettingsView.js
│   │   │   └── DatabasesView.js
│   │   └── components/
│   │       ├── Sidebar.js
│   │       ├── Message.js
│   │       ├── Markdown.js           # рендер markdown (marked.js локально)
│   │       └── Modal.js
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
