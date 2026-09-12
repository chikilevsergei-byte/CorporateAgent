// CorporateAgent v4 — точка входа фронтенда (Этап 1: заглушка).
// Проверяет доступность API и показывает токен из URL.

(function () {
  'use strict';

  // Токен передаётся сервером в URL (?token=...). Сохраняем для последующих API-запросов.
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token') || '';
  if (token) {
    sessionStorage.setItem('corpagent_token', token);
  }

  const info = document.getElementById('token-info');
  if (info) {
    info.textContent = token ? 'Токен авторизации получен.' : 'Токен не передан.';
  }

  // Проверка живости сервера (health не требует токена).
  fetch('/api/system/health')
    .then((r) => r.json())
    .then((data) => {
      if (info && data && data.ok) {
        info.textContent += ' API отвечает: OK.';
      }
    })
    .catch(() => {
      if (info) {
        info.textContent += ' API недоступен.';
      }
    });
})();
