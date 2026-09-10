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
- **C1.1 (каркас)** `IntentRouter` (CHAT/PRESENTATION/EXCEL/DATABASE + `isCreateDbRequest`).
- **C1.2 (базово)** Подтверждение DML (`WriteConfirmation`, `confirm_db_write`, диалог в `ChatView`).
- **C2.1** PPTX по корп. шаблону Супер Ленты (`FileCreator` + `Шаблоны/*.pptx`).
- **R-P1.5 (базово)** Раздельная работа чатов: чипы persist в записи чата.

## Тесты
- `test/corpagent/SmokeCoreTest.java` — расширенные smoke-проверки (TokenBudget, роутер, охранники, пресеты, пути, ProjectManager).

## История git (13 коммитов)
- `0c70388` docs: C1.1 + C2.1
- `4730683` assets: шаблоны Супер/Группа/Гипер Ленты
- `b18e5a2` feat(c1.1): роутер интентов (сокращение БД/склонения)
- `c32e101` feat(c2.1): презентация в шаблоне Супер Ленты
- `8798bda` feat(settings): Alibaba Qwen
- `34fe4d2` chore(R-P3.1): gitignore
- `d653b35` v3.3: R-P0.1 + R-P1.1/1.2/1.3 + R-P1.6/1.7
- … и более ранние (v3.1/v3.2/initial).
