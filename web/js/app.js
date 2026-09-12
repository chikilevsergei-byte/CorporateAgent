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
})();

