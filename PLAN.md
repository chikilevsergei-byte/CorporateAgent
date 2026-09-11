# 📋 CorporateAgent v3 — открытые задачи

> Только НЕЗАКРЫТЫЕ задачи. Сделанное — в `DONE.md`, детали подсистем — в `docs/`.
> Этот файл (и только его) можно подкладывать в контекст LLM.
> Стек: Java 8 + Swing · OpenAI-совместимый LLM · SQLite (sqlite-jdbc).

## 🔴 P1 — критично для «рабочего места РП»
- [x] **R-P1.4** Кнопки «Вопрос по LPG» / «Вопрос по ИТ методологии» в ряду основных кнопок.
      Новый `MainFrame.newMethodologyChat(String db)` — чат только на чтение методологической БД.
- [x] **R-P1.9.1** Сайдбар: убрать иконки (`🗂️`/`➕`) и `setIconTextGap` у кнопок «Новый чат» / «Создать проект».
- [x] **R-P1.9.2** Заголовки проекта и «Без проекта»: выровнять влево, убрать иконку, заменить центрирующий `FlowLayout`.
- [x] **R-P1.9.3** Сворачивание проекта (collapsible) в сайдбаре: `Set<String> collapsedProjects`, дисклокер `▸/▾`.
- [x] **R-P1.9.4** Пункт «Переместить в проект…» в `showChatMenu` + `ProjectManager.applyProjectDefaults(store, chat, project)`.
- [x] **R-P1.9.5** Мастер проекта: выбор источника БД (существующая из `DataStore` / новая через доменную схему).
      `ProjectManager.createProjectWithExistingDb(...)` + рефактор `createProjectRecordAndChats(...)`.
- [x] **R-P1.9.6** «Обновить БД проекта»: `UpdateProjectDbDialog` + `Agent.generateDbUpsertPlan(...)` (ИИ-сопоставление upsert),
      вопрос пользователю по конфликтам (удаление отсутствующих записей), запись через `DatabaseManager.autoExecuteSqlUpdates`.

## 🟠 P2 — надёжность БД и доменные сценарии
- [x] **C1.1 (остаток)** Интенты `UPDATE_DB` / `QUESTION_DB` / `PROTOCOL` / `STATUS` + приоритеты в `IntentRouter`
      (`isUpdateDbRequest` / `isQuestionDbRequest` / `isProtocolRequest` / `isStatusRequest`, `hasDbReference`).
- [x] **C3.2** Парсер протокола встречи → `meetings`/`meeting_decisions`/`tasks`:
      `ProtocolExtractor` (`buildExtractionPrompt` / `buildNoDbContextHint` / `parseExtraction`),
      ветка интента `PROTOCOL` в `Agent.processTask` (через общий путь `runConversation` + контур `db_execute`).
- [x] **C3.3** Отчёт «сделано / осталось / фокус» (SQL-агрегация по `tasks`):
      `StatusReport` (`buildStatusPrompt` / `buildNoDbContextHint` / `parseCounts` / `formatSummary`),
      ветка интента `STATUS` в `Agent.processTask` (сводка в чат через `runConversation`).
- [x] **C2.2** Excel по шаблону: `createExcel(data, templateFile)` (в `Шаблоны/` есть «Паспорт проекта», «Реестр рисков»).

## 🟠 P2 — политика / безопасность
- [ ] **R-P2.1** Пароль на изменение API/настроек (PBKDF2+HMAC, соль, «забыл пароль»).
- [ ] **R-P2.3** Дефолтный дневной лимит токенов (база A3 готова).
- [ ] **R-P2.2** `docs/security_checklist.md`.

## 🟡 P3 — релиз / упаковка / документация
- [ ] **R-P3.3** Брендинг «Мелана — AI ассистент РП» (заголовок окна, строки, батники).
- [ ] **R-P3.4** Иконка окна/ярлыка (`icon.ico/png` + `frame.setIconImage`).
- [ ] **R-P3.5** Ссылка «Оставить обратную связь».
- [ ] **R-P3.2** Портабельная сборка до качества релиза.
- [ ] **R-P3.6/R-P3.7** Инструкция пользователя + `docs/architecture.md`.
- [ ] **C4** Дашборд расходов + экспорт сводки (CSV/JSON).
- [ ] **C5/R-P3.8** Тесты новых путей (мастер / методологии / экспорт / бэкап / пароль).
- [ ] **R-P3.1/R-P3.9** Финальная уборка, `.gitignore`, git-tag/GH-release, ротация ключей.
- [ ] ⚠️ **Ротация API-ключей** при релизе (ключи выведены из трекинга).

## 🔴 Финальный этап (после релиза)
- [x] **C6** UI в духе Apple/iOS (дизайн-система поверх `Ui.java`, экраны по одному).
      `UiTheme`: палитра iOS (system blue #007AFF, grouped bg #F2F2F7, separator #E5E5EA,
      semantic green/red/orange), SF Pro/Segoe UI, радиусы BTN/INPUT/CARD/DIALOG.
      `Ui.stylePrimary` — filled-кнопка с БЕЛЫМ текстом (убраны ручные `setForeground(BLACK)`).
      Экраны на iOS-токенах: MainFrame, SidebarPanel, ChatView (синий пузырь пользователя),
      ChatChipsBar, SkillManagerView, ToolManagerView, DbManagerView, DbViewerView,
      Settings* и диалоги (карточки-формы, монопространство для SQL). Смоук: `testIosTheme`.

---

## 🎨 UI-рейд (ревью PLAN STILE.txt → Swing)

> Источник: `PLAN STILE.txt` (дизайн-спека в веб-CSS). Проект — Java Swing,
> токены уже в `UiTheme`/`Ui` (C6). Задачи ниже — перевод CSS-интентов в Swing
> по конкретным файлам. «Glass» и анимации делаются Swing-приёмами
> (полупрозрачные панели, скруглённый бордер, `javax.swing.Timer`), НЕ через CSS.

### ⚙️ Сквозные (фундамент, делать первыми)
- [x] **UI-0.1** Расширить `UiTheme`: spacing (`GAP_XS..GAP_2XL`), шрифты `FONT_XL/2XL`,
      `RADIUS_XL/2XL`, хелпер кликабельного курсора. Смоук: `testUiTokens`.
- [x] **UI-0.2** Реальный Dark Mode: переключение палитры из `SettingsPanelInterface`
      без перезапуска (сейчас тема сохраняется, но палитра фиксирована).
- [x] **UI-0.3** Общий хелпер иконок действий (`Ui.actionIcon(emoji)`) — единый размер/отступ.
      `UiTheme.ACTION_ICON_SIZE/GAP` + `actionIcon(emoji[,size])` + `withActionIcon(btn, emoji)`;
      заменены разрозненные `setIcon(iconImage(...,14/16/18))+setIconTextGap(5/6)` в Skill/Tool
      Manager, DbManager/Viewer, *EditDialog, CreateProjectDialog, ChatView. Смоук: `testActionIcon`.
- [ ] **UI-0.4** Кастомный title bar (traffic-lights) для `MainFrame` — опционально, только если
      не ломает нативный resize/снап Windows.

### 🔴 Приоритет 1 — критично (быстрый UX-выигрыш)
- [x] **UI-1.1** Кастомное контекстное меню чата/проекта (замена `JOptionPane.showOptionDialog`
      в `SidebarPanel.showChatMenu`/`showProjectMenu`): новый `ChatContextMenu` (`JPopupMenu`) —
      иконка-шапка, пункты (Переименовать / Переместить в проект… / Pin / Удалить), `danger`-стиль.
      DoD: компиляция + смоук состава пунктов. `testChatContextMenu`.
- [x] **UI-1.2** Toggle-свитч (кастомный `ToggleSwitch`): трек iPad-стиль, бегунок с тенью,
      анимация Timer. Применить в `SettingsPanelInterface` (3 чекбокса), `ChatChipsBar.showPicker`,
      `SettingsPanelDatabases`. DoD: смоук расчёта позиции бегунка. `testToggleSwitch`.
- [x] **UI-1.3** Радио-карточки в `CreateProjectDialog` (источник БД) — `Ui.radioCard(...)`
      с tint-подложкой `PRIMARY_SOFT` и синей рамкой при выборе. Смоук: `testRadioCard`.
- [x] **UI-1.4** Whitespace/иерархия форм: единые `UiTheme.GAP_*` + `Ui.formRow(label, control)`;
      применено в `CreateProjectDialog`, `DbManagerView`. Смоук: `testFormRow`.

### 🟠 Приоритет 2 — важно
- [x] **UI-2.1** Wizard «Создать проект» (3 шага: Основное → База данных → Рабочие чаты):
      `WizardProgress` (кружки-номера `active/completed`), `CardLayout` шагов, кнопки Назад/Далее/Создать.
      Смоук: `testWizardProgress`.
- [x] **UI-2.2** БД-карточки вместо строк в `SettingsPanelDatabases` (иконка, имя, мета, путь mono,
      статус, hover-подсветка).
- [x] **UI-2.3** Кастомный слайдер (`Ui.styleSlider`/`SlimSliderUI` — заливка трека до бегунка);
      применён в `SettingsPanelModel`. Смоук: `testSlider`.
- [x] **UI-2.4** Единый компонент модалок: `BaseDialog extends JDialog` (`setUndecorated(true)` +
      `RoundedShadowBorder` + закрытие по ESC + `buttonBar`). Переведены `SettingsDialog`,
      `CreateProjectDialog`, `UpdateProjectDbDialog`, `DbConnectDialog`, `SkillEditDialog`,
      `ToolEditDialog`, `DbCreateFromFilesDialog`. Смоук: `testBaseDialog`.

### 🟡 Приоритет 3 — желательно
- [x] **UI-3.1** Toast-уведомления (`Toast`, стек, авто-скрытие 4с, пауза при hover, типы
      success/error/warning/info) вместо информационных `JOptionPane.showMessageDialog`
      (confirm/input оставлены). Внедрено: сохранение навыка/инструмента, бэкап/восстановление,
      пресет-защита, создание проекта, приветственный тост при старте. Смоук: `testToast`.
- [ ] **UI-3.2** Кастомный File Picker (drag&drop, `TransferHandler`) вместо `JFileChooser`.
- [ ] **UI-3.3** Анимация открытия модалок (`BaseDialog`).
- [ ] **UI-3.4** Skeleton loaders для долгих операций (генерация схемы, upsert-план).
- [x] **UI-3.5** Empty states с иконкой и подсказкой (чаты/навыки/инструменты/БД/просмотр БД) —
      компонент `EmptyState` (крупный глиф-эмодзи + заголовок + подсказка). Смоук: `testEmptyState`.

### 📝 Примечания
- Порядок: UI-0.1 → P1 → P2 → P3. Каждая задача — отдельный коммит + смоук (DoD выше).
- CSS-термины ревью (`backdrop-filter`, `@keyframes`, `:hover`) в Swing не применимы напрямую;
  «hover» = `MouseListener` (уже есть `Ui.installHover`).
- Файлы-кандидаты: `ChatContextMenu`, `ToggleSwitch`, `BaseDialog`, `Toast`, `FilePickerDialog`
  — перед созданием grep по `src/` (правило 5).

---

## 📐 Правила работы (сокращение токенов и рост качества)1. **Один коммит = одна задача** отсюда. Сначала проверь `git status` — рабочее дерево должно быть чистым.
2. **Definition of Done:** `build.bat` компилируется без ошибок + покрыто `test/corpagent/SmokeCoreTest.java`, затем закоммичено.
3. **Не читать файл целиком** при точечной правке — только нужный метод (offset/limit).
4. **Не создавать скрэтч-файлы** (ReTest.java и т.п.) — временные проверки только в `test/corpagent/`.
5. **Не дублировать классы** — сначала grep по `src/` (в проекте уже были дубли из worktree).
6. **Одна метка на задачу**: ✅ / 🔄 / ⬜. Не помечать одновременно «сделано» и «к доработке».

