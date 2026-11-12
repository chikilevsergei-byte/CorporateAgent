// CorporateAgent v4 — точка входа фронтенда.
// Этап 2 (v3.1 → v4): визуальная копия прототипа. Behavior-скрипты прототипа
// лежат в prototype-raw.js; здесь — только инфраструктура приложения (токен,
// доступ к API). Реальные привязки к /api/* добавляются на Этапе 3+.
(function () {
  'use strict';

  // Токен передаётся сервером в URL (?token=...). Сохраняем для последующих API-запросов.
  var params = new URLSearchParams(window.location.search);
  var token = params.get('token') || '';
  if (token) {
    sessionStorage.setItem('corpagent_token', token);
  }
  window.CorporateAgent = window.CorporateAgent || {};
  window.CorporateAgent.getToken = function () {
    return sessionStorage.getItem('corpagent_token') || '';
  };

  // Проверка живости сервера (health не требует токена).
  fetch('/api/system/health')
    .then(function (r) { return r.json(); })
    .then(function (data) {
      if (data && data.ok) {
        window.CorporateAgent.health = data.data;
      }
    })
    .catch(function () { /* сервер ещё не поднят — не критично для отрисовки прототипа */ });

  // CA.toast — обёртка над прототипным showToast для модулей v4.
  window.CorporateAgent.toast = function (type, title, message) {
    if (typeof window.showToast === 'function') {
      window.showToast(type, title, message);
    }
  };

  // Запуск чата после загрузки DOM (все модули уже подгружены в index.html).
  document.addEventListener('DOMContentLoaded', function () {
    // Этап 4.3: hash-based роутер (#chat, #notes, #projects, #settings).
    if (window.CorporateAgent.Router) {
      window.CorporateAgent.Router.init();
    }
    // Этап 4.1/4.2: sidebar (список чатов + проекты).
    if (window.CorporateAgent.Sidebar) {
      window.CorporateAgent.Sidebar.init();
    }
    if (window.CorporateAgent.ChatView) {
      window.CorporateAgent.ChatView.init();
    }
    // Этап 4.4: раздел заметок.
    if (window.CorporateAgent.NotesView) {
      window.CorporateAgent.NotesView.init();
    }
    // Этап 3.13: панель контекста (БД/Навыки/Инструменты) наполняется справочниками.
    if (window.CorporateAgent.ChatContextPanel) {
      window.CorporateAgent.ChatContextPanel.init();
    }
    // Этап 5.1-F: экран «Проекты».
    if (window.CorporateAgent.ProjectsView) {
      window.CorporateAgent.ProjectsView.init();
    }
    // Этап 5.2-F/5.3-F: редакторы навыков/инструментов в настройках.
    if (window.CorporateAgent.SettingsView) {
      window.CorporateAgent.SettingsView.init();
    }
    // Этап 5.4-F: раздел «Базы данных» + модалки просмотра/подключения.
    if (window.CorporateAgent.DatabasesView) {
      window.CorporateAgent.DatabasesView.init();
    }
    // Этап 5.a: мастер создания проекта.
    if (window.CorporateAgent.ProjectWizard) {
      window.CorporateAgent.ProjectWizard.init();
    }
    // Этап 6.2-F: загрузка файлов для анализа (#filePickerModal).
    if (window.CorporateAgent.FilePicker) {
      window.CorporateAgent.FilePicker.init();
    }
  });
})();

