// CorporateAgent v4 — js/router.js
// Этап 4.3: простой hash-based роутер (#chat, #notes, #projects, #settings).
// Управляет видимостью контентных областей прототипа (chatContent/notesContent/inputArea)
// и синхронизирует состояние store.screen. Не изобретает верстку — использует
// существующие контейнеры index.html.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  var SCREENS = ['chat', 'notes', 'projects', 'settings'];

  var Router = {
    _current: null,

    init: function () {
      var self = this;
      window.addEventListener('hashchange', function () { self.apply(location.hash); });
      // Переносим прототипные хелперы на навигацию через роутер.
      window.showNotes = function () { self.go('notes'); };
      window.showChat = function () { self.go('chat'); };
      this.apply(location.hash);
    },

    // Программная навигация.
    go: function (screen) {
      if (SCREENS.indexOf(screen) < 0) screen = 'chat';
      if (('#' + screen) === location.hash) {
        this.apply('#' + screen);
      } else {
        location.hash = screen;
      }
    },

    // Применяет экран: показывает нужную область и прячет остальные.
    apply: function (hash) {
      var screen = String(hash || '').replace(/^#/, '');
      if (SCREENS.indexOf(screen) < 0) screen = 'chat';
      if (this._current === screen) return;
      this._current = screen;

      this.toggle('chatContent', screen === 'chat');
      this.toggle('notesContent', screen === 'notes');
      this.toggle('projectsContent', screen === 'projects');
      this.toggle('inputArea', screen === 'chat');

      // Экран «Настройки» открывает модалку; остальные контент-области прячем.
      if (screen === 'settings') {
        this.toggle('chatContent', false);
        if (window.openModal) window.openModal('settingsModal');
      }

      if (CA.store) {
        CA.store.setScreen(screen);
      }
    },

    toggle: function (id, visible) {
      var node = el(id);
      if (!node) return;
      // chatContent/notesContent — flex-контейнеры, inputArea — block.
      var display = (id === 'chatContent' || id === 'notesContent') ? 'flex' : 'block';
      node.style.display = visible ? display : 'none';
    },

    screens: SCREENS
  };

  CA.Router = Router;
  // app.js вызывает CA.app.go(...) для навигации — экспортируем псевдоним.
  CA.app = CA.app || {};
  CA.app.go = function (screen) { Router.go(screen); };
})();
