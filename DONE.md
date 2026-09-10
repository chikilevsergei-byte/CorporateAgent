# ✅ CorporateAgent v3 — сделанное (архив)

> Архив闭环 задач. НЕ подкладывать в контекст LLM целиком (только при необходимости справки).
> Полная история — в `git log` и `DEVELOPMENT_PLAN.md` (исторический документ).

## Ядро A — сделано
- **A1** Единый чёрный текст кнопок (`Ui.style*`, константа `Ui.BLACK`).
- **A2** Только локальный SQLite; создание БД из файлов (`DbCreateFromFilesDialog` → `Agent.createDatabaseFromFiles` → `DatabaseManager.createDatabase`); гигиена удаления `.db`.
- **A3** Учёт токенов и суточный лимит (`TokenBudget`, `TOKENS_PER_DOLLAR = 17_604_300`, показ расхода в чате, поле лимита в настройках).
- **A4** Управляемый agentic-цикл записи в БД (`db_query`/`db_execute` в `ToolExecutor`, охранники UPDATE/DELETE без WHERE).
- **A5** Обрезка истории (`Agent.trimHistory`, бюджет 20000), выборочная выборка таблиц (`buildDbContext`), `auto_update_db=false` по умолчанию, потокобезопасный `executedTools`.

## Релизный бэклог R — сделано
- **R-P0.1** Провайдеры: `Config` (`provider`/`base_url`, `PROVIDER_*`, `BASE_URL_*`), клиент на `getChatCompletionUrl()`, комбобокс в `SettingsDialog`. Поддержаны DeepSeek / Яндекс / Qwen / Custom.
- **R-P0.2** Мастер «Создать проект» + 7 авто-чатов (`ProjectManager`, `CreateProjectDialog`, группировка в `SidebarPanel`, `DataStore.projects`). Доменная схема `PROJECT_SCHEMA` (8 таблиц), `CHAT_ROLES` (7 ролей).
- **R-P1.1** 7 предустановленных навыков (`PresetLoader`, `presets/skills/*.json`, `preset=true`).
- **R-P1.2** 4 предустановленных инструмента (`presets/tools/*.json`), включая презентацию в шаблоне Супер Ленты.
- **R-P1.3** 2 БД-методологии (`presets/databases/*.json`, `.db` на первом старте, только чтение).
- **R-P1.6** Импорт/экспорт БД (ZIP + `metadata.json`).
- **R-P1.7** Резервное копирование (`BackupManager`, ZIP в `backups/`, восстановление).
- **Релиз-блокеры пресетов:** упаковка ресурсов в `build.bat` (копирование `presets/**` в classpath), корректный резолв Windows-пути `.db` (`PresetLoader.resolveDbPath`), инжект `code` навыков в системный промпт (`ChatView`).
- **C1.1 (каркас + остаток)** `IntentRouter`: CHAT/PRESENTATION/EXCEL/DATABASE + `isCreateDbRequest`, далее добавлены `UPDATE_DB`/`QUESTION_DB`/`PROTOCOL`/`STATUS` (`isUpdateDbRequest`, `isQuestionDbRequest`, `isProtocolRequest`, `isStatusRequest`, `hasDbReference`) с расстановкой приоритетов в `detectIf`.
- **C3.2** Разбор протокола встречи → `meetings`/`meeting_decisions`/`tasks`: `ProtocolExtractor` (инструкция ИИ, разбор ответа, подсказка без БД); ветка интента `PROTOCOL` в `Agent.processTask`. Общий путь диалога вынесен в `Agent.runConversation(...)` (переиспользуется CHAT и PROTOCOL, единый контур `db_query`/`db_execute` с подтверждением).
- **C3.3** Статус-отчёт «сделано / осталось / фокус» по `tasks`: `StatusReport` (инструкция ИИ с SQL-агрегацией, разбор чисел из ответа, краткая сводка); ветка интента `STATUS` в `Agent.processTask` (сводка дописывается в ответ).
- **C1.2 (базово)** Подтверждение DML (`WriteConfirmation`, `confirm_db_write`, диалог в `ChatView`).
- **C2.1** PPTX по корп. шаблону Супер Ленты (`FileCreator` + `Шаблоны/*.pptx`).
- **R-P1.5 (базово)** Раздельная работа чатов: чипы persist в записи чата.
- **R-P1.4** Кнопки «Вопрос по LPG» / «Вопрос по ИТ» в ряду кнопок сайдбара; `MainFrame.newMethodologyChat(db, tag)` — чат только на чтение методологической БД (`methodology_readonly=true`, единственный чип-БД). Константа `ProjectManager.IT_METHODOLOGY_DB`.
- **R-P1.9.1** Сайдбар: убраны иконки (`➕`/`🗂️`) и `setIconTextGap(8)` у кнопок «Новый чат» / «Создать проект».
- **R-P1.9.2** Заголовки проекта и «Без проекта» выровнены влево (BoxLayout вместо центрирующего FlowLayout), убрана иконка `🗂️`.
- **R-P1.9.3** Сворачивание проекта: `SidebarPanel.collapsedProjects` (в памяти сессии), дисклокер `▸/▾`, клик по заголовку скрывает/показывает чаты проекта.
- **R-P1.9.4** «Переместить в проект…» в `showChatMenu` + `ProjectManager.applyProjectDefaults(store, chat, project)` и `roleForTitle(...)` — переносит проект-поля и дефолтные чипы (БД проекта + LPG, навык/инструменты роли).
- **R-P1.9.5** Мастер проекта: выбор источника БД (существующая из списка `DataStore` / новая по доменной схеме). `ProjectManager.createProjectWithExistingDb(...)` + общий `createProjectRecordAndChats(...)`.
- **R-P1.9.6** «Обновить БД проекта» (`UpdateProjectDbDialog`): источник — другая БД или файлы; `Agent.generateDbUpsertPlan(...)` строит план upsert (INSERT/UPDATE) и секцию `###КОНФЛИКТЫ###`; пользователь решает про удаление отсутствующих записей; запись через `DatabaseManager.autoExecuteSqlUpdates`.

## Тесты
- `test/corpagent/SmokeCoreTest.java` — расширенные smoke-проверки (TokenBudget, роутер с новыми интентами, охранники, пресеты, пути, ProjectManager, перенос чата в проект, проект на существующей БД, план синхронизации БД, разбор протокола встречи, статус-отчёт). **116/116 OK**.

## Рефакторинг (уменьшение стоимости контекста)
- **perf(tokens)** `Agent.trimHistory` учитывает `systemPrompt` и `tool_calls`; `ToolExecutor.formatResult` ограничен 10 строками.
- **refactor(ui)** `Ui.java` → `UiTheme` (палитра/шрифты/радиусы/`RoundedBorder`) + `Ui` (стилизация), `Ui extends UiTheme`. `Ui.java` 22 КБ → 12 КБ.
- **refactor(settings)** `SettingsDialog` → `SettingsPanelModel` + `SettingsPanelInterface`. `SettingsDialog.java` 28 КБ → 10 КБ.
- **refactor(agent)** Построение контекста БД вынесено в `DbContextBuilder`. `Agent.java` 38 КБ → 34 КБ.
- Все рефакторы проверены `javac` (exit 0) + `SmokeCoreTest` (59/59 OK).

## История git (13 коммитов)
- `0c70388` docs: C1.1 + C2.1
- `4730683` assets: шаблоны Супер/Группа/Гипер Ленты
- `b18e5a2` feat(c1.1): роутер интентов (сокращение БД/склонения)
- `c32e101` feat(c2.1): презентация в шаблоне Супер Ленты
- `8798bda` feat(settings): Alibaba Qwen
- `34fe4d2` chore(R-P3.1): gitignore
- `d653b35` v3.3: R-P0.1 + R-P1.1/1.2/1.3 + R-P1.6/1.7
- … и более ранние (v3.1/v3.2/initial).
