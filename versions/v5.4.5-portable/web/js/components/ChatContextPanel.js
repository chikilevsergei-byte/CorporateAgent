// CorporateAgent v4 — js/components/ChatContextPanel.js
// Этап 3.13: панель контекста чата (Файлы/БД/Навыки/Инструменты) по макету прототипа v2.
// Разметка-эталон — #dbDropdown/#skillsDropdown/#toolsDropdown в index.html; здесь
// только наполнение выпадающих списков реальными справочниками (/api/catalogs) и
// синхронизация выбора с store.context и сервером (/api/chat/context).
//
// Прототип хранит выбор в DOM-классе .selected и обновляет «бейдж» счётчиком.
// Модуль строится поверх этих же классов/функций, чтобы результат в браузере
// оставался визуально неотличим от прототипа.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  // Соответствие групп: тип → {dropdownId, badgeId, catalogKey, icon}.
  var GROUPS = {
    db: { dropdownId: 'dbDropdown', badgeId: 'dbBadge', catalogKey: 'databases', icon: '🗄️' },
    skills: { dropdownId: 'skillsDropdown', badgeId: 'skillsBadge', catalogKey: 'skills', icon: '🎯' },
    tools: { dropdownId: 'toolsDropdown', badgeId: 'toolsBadge', catalogKey: 'tools', icon: '🛠️' }
  };

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  var ChatContextPanel = {
    _catalogs: { databases: [], skills: [], tools: [] },

    // Точка входа: подписка на store (чтобы отмечать выбранное) + загрузка справочников.
    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function () { self.reflectSelection(); });
      }
      this.bindControls();
      this.loadCatalogs();
    },

    // Загружает справочники из API и наполняет выпадающие списки прототипа.
    loadCatalogs: function () {
      var self = this;
      if (!CA.api || !CA.api.get) return;
      CA.api.get('catalogs').then(function (data) {
        self._catalogs = {
          databases: (data && data.databases) || [],
          skills: (data && data.skills) || [],
          tools: (data && data.tools) || []
        };
        if (CA.store) {
          CA.store.setCatalogs(self._catalogs);
        }
        self.renderGroup('db');
        self.renderGroup('skills');
        self.renderGroup('tools');
        self.reflectSelection();
      }).catch(function () {
        // Справочники недоступны (сервер не поднят) — оставляем разметку прототипа как есть.
      });
    },

    // Наполняет .dropdown-list одной группы компактными DTO каталога.
    renderGroup: function (type) {
      var g = GROUPS[type];
      if (!g) return;
      var dropdown = el(g.dropdownId);
      if (!dropdown) return;
      var list = dropdown.querySelector('.dropdown-list');
      if (!list) return;
      var items = this._catalogs[g.catalogKey] || [];
      list.innerHTML = items.map(function (item) {
        var meta = item.description ? '<div class="dropdown-item-meta">' + esc(item.description) + '</div>' : '';
        return ''
          + '<div class="dropdown-item" role="checkbox" tabindex="0" aria-checked="false" data-name="' + esc(item.name) + '">'
          +   '<div class="dropdown-checkbox"></div>'
          +   '<div class="dropdown-item-icon">' + g.icon + '</div>'
          +   '<div class="dropdown-item-text">'
          +     '<div class="dropdown-item-title">' + esc(item.title || item.name) + '</div>'
          +     meta
          +   '</div>'
          + '</div>';
      }).join('');
    },

    // Навешивает обработчики на элементы, кнопки «Сбросить»/«Готово» всех групп.
    bindControls: function () {
      var self = this;
      Object.keys(GROUPS).forEach(function (type) {
        var g = GROUPS[type];
        var dropdown = el(g.dropdownId);
        if (!dropdown) return;

        // Делегирование клика по пунктам: переключаем .selected и обновляем счётчик.
        dropdown.addEventListener('click', function (e) {
          var item = e.target.closest && e.target.closest('.dropdown-item');
          if (!item || !dropdown.contains(item)) return;
          item.classList.toggle('selected');
          self.updateBadge(type);
        });
        dropdown.addEventListener('keydown',function(e){var item=e.target.closest('.dropdown-item');if(item&&(e.key===' '||e.key==='Enter')){e.preventDefault();item.click();}});

        // Кнопки футера: Сбросить — снять выбор, Готово — сохранить контекст в чат.
        var resetBtn = dropdown.querySelector('.dropdown-btn-reset');
        if (resetBtn) {
          resetBtn.addEventListener('click', function (e) {
            e.preventDefault();
            self.resetGroup(type);
          });
        }
        var doneBtn = dropdown.querySelector('.dropdown-btn-done');
        if (doneBtn) {
          doneBtn.addEventListener('click', function (e) {
            e.preventDefault();
            self.commit(type);
          });
        }
      });
    },

    // Список выбранных имён в группе.
    selectedNames: function (type) {
      var g = GROUPS[type];
      var dropdown = g && el(g.dropdownId);
      if (!dropdown) return [];
      var out = [];
      dropdown.querySelectorAll('.dropdown-item.selected').forEach(function (item) {
        var name = item.getAttribute('data-name');
        if (name) out.push(name);
      });
      return out;
    },

    // Обновляет бейдж-счётчик (как updateBadge в прототипе).
    updateBadge: function (type) {
      var g = GROUPS[type];
      if (!g) return;
      var badge = el(g.badgeId);
      if (!badge) return;
      var n = this.selectedNames(type).length;
      el(g.dropdownId).querySelectorAll('.dropdown-item').forEach(function(item){item.setAttribute('aria-checked',String(item.classList.contains('selected')));});
      if (n > 0) {
        badge.textContent = n;
        badge.style.display = 'inline-flex';
      } else {
        badge.style.display = 'none';
      }
    },

    // Снимает выбор в группе (кнопка «Сбросить»).
    resetGroup: function (type) {
      var g = GROUPS[type];
      var dropdown = g && el(g.dropdownId);
      if (dropdown) {
        dropdown.querySelectorAll('.dropdown-item.selected').forEach(function (item) {
          item.classList.remove('selected');
        });
      }
      this.updateBadge(type);
      return this.pushContext().then(function(){CA.toast('success','Сброшено','Выбор очищен');}).catch(function(e){CA.toast('error','Контекст чата',e.message);});
    },

    // «Готово»: закрывает список, сохраняет выбранный контекст, показывает тост.
    commit: function (type) {
      var g = GROUPS[type];
      var dropdown = g && el(g.dropdownId);
      if (dropdown) {
        dropdown.classList.remove('active');
        if(dropdown._button)dropdown._button.setAttribute('aria-expanded','false');
      }
      var names = { db: 'БД', skills: 'навыков', tools: 'инструментов' };
      var n = this.selectedNames(type).length;
      return this.pushContext().then(function(){if (n > 0) {
        CA.toast && CA.toast('success', 'Применено', 'Выбрано ' + n + ' ' + names[type]);
      } else {
        CA.toast && CA.toast('warning', 'Внимание', 'Не выбрано ни одного ' + names[type]);
      }}).catch(function(e){CA.toast('error','Контекст чата',e.message);});
    },

    // Собирает текущий выбор в store.context и (если чат серверный) сохраняет на сервере.
    pushContext: function () {
      var ctx = {
        databases: this.selectedNames('db'),
        skills: this.selectedNames('skills'),
        tools: this.selectedNames('tools')
      };
      var st = CA.store && CA.store.getState();
      var chatId = st && st.activeChatId;
      if(!chatId)chatId=CA.Sidebar.newChat();
      var chat = chatId && st.chats[chatId];
      var fields={selected_databases:ctx.databases,selected_skills:ctx.skills,selected_tools:ctx.tools};
      CA.store.updateChat(chatId,fields);CA.store.setContext(ctx);
      this._queues=this._queues||{};var self=this;
      this._queues[chatId]=(this._queues[chatId]||Promise.resolve()).catch(function(){}).then(function(){return CA.Sidebar.ensureSaved(chatId);}).then(function(){return CA.api.setChatContext(chatId,ctx);});
      return this._queues[chatId].then(function(){if(CA.store.getState().activeChatId===chatId)CA.store.setActiveChat(chatId);return ctx;});
    },

    // Отмечает в DOM пункты, соответствующие store.context (после смены чата).
    reflectSelection: function () {
      var ctx = CA.store && CA.store.getState().context;
      if (!ctx) return;
      var self = this;
      this.applyGroup('db', ctx.databases);
      this.applyGroup('skills', ctx.skills);
      this.applyGroup('tools', ctx.tools);
      ['db', 'skills', 'tools'].forEach(function (t) { self.updateBadge(t); });
    },

    applyGroup: function (type, names) {
      var g = GROUPS[type];
      var dropdown = g && el(g.dropdownId);
      if (!dropdown) return;
      var set = {};
      (names || []).forEach(function (n) { set[n] = true; });
      dropdown.querySelectorAll('.dropdown-item').forEach(function (item) {
        var name = item.getAttribute('data-name');
        item.classList.remove('selected');
        if (name && set[name]) {
          item.classList.add('selected');
        }
      });
    }
  };

  CA.ChatContextPanel = ChatContextPanel;
})();
