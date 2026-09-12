# Инвентаризация gui/ (Этап 0.3 миграции v3 → v4)

> Классификация Swing-классов: что вызывает бизнес-логику (→ перенести вызовы в API),
> что чисто UI (→ удалить, заменить веб-аналогом).
> Цель — при миграции не потерять ни одной фичи.

## Легенда
- **[API]** — содержит вызовы логики (`Agent`, `store`, `dbManager`, `fileCreator`) →
  соответствующий вызов переносится в `api/routes/*`.
- **[UI]** — чистая отрисовка/взаимодействие → удаляется, заменяется `web/js/views/*`.
- **[DIALOG]** — модальное окно → заменяется веб-модалкой (`components/Modal.js`).
- **→ web** — целевой фронтенд-модуль.

## Сводка по классам

| Класс | Тип | Вызовы логики | Замена в v4 | Этап |
|-------|-----|---------------|-------------|------|
| `MainFrame.java` | [API] | каркас, навигация, `store`, `settings`, `dbManager` | `app.js` + `index.html` (роутер) | 3 |
| `SidebarPanel.java` | [API]+[UI] | `store.*` (чаты/проекты/навыки/инструменты/БД) | `components/Sidebar.js` | 3, 4 |
| `ChatView.java` | [API]+[UI] | `agent.processTask`, `store`, `fileCreator` | `views/ChatView.js` | 2 |
| `ChatChipsBar.java` | [UI] | выбор чипов | часть `views/ChatView.js` | 2 |
| `ChatContextMenu.java` | [UI] | — | контекстное меню в `Sidebar.js` | 3 |
| `MessageBubble.java` | [UI] | — | `components/Message.js` | 2 |
| `WelcomeView.java` | [UI] | — | часть `views/ChatView.js` | 2 |
| `SkeletonLoader.java` | [UI] | — | skeleton-loader в `chat.css` | 2 |
| `EmptyState.java` | [UI] | — | пустое состояние в `components/*` | 2–4 |
| `SkillManagerView.java` | [API]+[UI] | `store.*` навыки | `views/SkillsView.js` | 4 |
| `SkillEditDialog.java` | [DIALOG] | `store.*` навык | модалка в `views/SkillsView.js` | 4 |
| `ToolManagerView.java` | [API]+[UI] | `store.*` инструменты | `views/ToolsView.js` | 4 |
| `ToolEditDialog.java` | [DIALOG] | `store.*` инструмент | модалка в `views/ToolsView.js` | 4 |
| `DbManagerView.java` | [API]+[UI] | `dbManager.*` | `views/DatabasesView.js` | 4 |
| `DbViewerView.java` | [API]+[UI] | `dbManager.*` (таблицы/данные/SQL) | `views/DatabasesView.js` | 4 |
| `DbConnectDialog.java` | [DIALOG] | `dbManager`, `store` | модалка в `views/DatabasesView.js` | 4 |
| `DbCreateFromFilesDialog.java` | [DIALOG] | `dbManager`, `agent` | модалка в `views/DatabasesView.js` | 4 |
| `UpdateProjectDbDialog.java` | [DIALOG]+[API] | `agent.generateDbUpsertPlan`, `dbManager` | модалка + `ProjectsRoutes` | 4 |
| `CreateProjectDialog.java` | [DIALOG]+[API] | `ProjectManager.*`, `agent` | `views/ProjectsView.js` | 4 |
| `WizardProgress.java` | [UI] | — | шаги-индикатор в `ProjectsView.js` | 4 |
| `SettingsDialog.java` | [DIALOG] | `config`, `settings` | `views/SettingsView.js` | 5 |
| `SettingsPanelModel.java` | [API]+[UI] | `config.*` (провайдеры/модель) | `views/SettingsView.js` | 5 |
| `SettingsPanelInterface.java` | [API]+[UI] | `settings.*`, `UiTheme` | `views/SettingsView.js` | 5 |
| `SettingsPanelTools.java` | [API]+[UI] | `store.*` | `views/SettingsView.js` | 4 |
| `SettingsPanelSkills.java` | [API]+[UI] | `store.*` | `views/SettingsView.js` | 4 |
| `SettingsPanelDatabases.java` | [API]+[UI] | `dbManager.*`, `store.*` | `views/SettingsView.js` | 4 |
| `FilePickerDialog.java` | [DIALOG] | выбор файлов | `<input type=file>` + `FilesRoutes` | 5 |
| `DropZone.java` | [UI] | — | drag&drop в веб | 5 |
| `Toast.java` | [UI] | — | тост в `components`/CSS | 2 |
| `BaseDialog.java` | [UI] | — | `components/Modal.js` | 2–4 |
| `Ui.java` | [UI] | — | `css/base.css`, `components.css` | 2 |
| `UiTheme.java` | [UI] | — | CSS-переменные темы | 2 |
| `ToggleSwitch.java` | [UI] | — | чекбокс-свитч в CSS | 4 |
| `FileExtractor`/`FileCreator` (files/) | — | **не трогаем** | через `FilesRoutes` | 5 |

## Методы `Agent`, которые нужны API (не менять сигнатуры)
- `Agent.processTask(message, history, fileContent, dbContext, skillsContext, toolsContext, dbPath, dbName, autoUpdateDb)` → `TaskResult` — ядро чата (Этап 2).
- `Agent.generateChatTitle(firstMessage)` — авто-название чата.
- `Agent.buildDbContext(selectedDbs, userMessage)` — контекст БД.
- `Agent.createDatabaseFromFiles(...)`, `Agent.generateDbUpsertPlan(...)` — БД (Этап 4).
- `Agent.setDbWriteConfirmation(...)` — подтверждение записи (в веб — через модалку/SSE-событие).
- **Нужно добавить в Этапе 2:** `Agent.chatStream(message, consumer)` — стриминг токенов.

## Ключевые зависимости, требующие новых эндпоинтов
- `DataStore` — CRUD чатов/проектов/навыков/инструментов/БД → `SkillsRoutes`, `ToolsRoutes`,
  `DatabasesRoutes`, `ProjectsRoutes`, часть `ChatRoutes`.
- `DatabaseManager` — таблицы/данные/SQL/импорт/экспорт/бэкап → `DatabasesRoutes`.
- `Config`/`Settings` → `SettingsRoutes`.
- `FileCreator`/`FileExtractor` → `FilesRoutes`.

## Выводы для Этапа 0
1. Пакет `gui/` можно удалить целиком после Этапа 6 — **ни один класс не переиспользуется**
   в веб-версии как Java-код (всё заменяется фронтендом).
2. Логика полностью лежит в `Agent`/`DataStore`/`DatabaseManager`/`Config` — API-слой
   будет «тонкой обёрткой» над этими классами.
3. Единственный метод, которого не хватает для v4, — стриминговый чат (`Agent.chatStream`);
   добавляется на Этапе 2, остальные сигнатуры переиспользуются как есть.
