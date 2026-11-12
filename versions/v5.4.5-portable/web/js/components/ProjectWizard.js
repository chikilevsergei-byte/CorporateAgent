// CorporateAgent v4 — js/views/ProjectWizard.js
// Этап 5.a: мастер «Создать проект» (3 шага) из прототипа v2.
// Шаг 1 — имя/описание, шаг 2 — источник БД (новая / существующая, 5.b),
// шаг 3 — предпросмотр 7 чатов-ролей и создание через POST /api/projects.
// Состав ролей совпадает с ProjectManager.CHAT_ROLES (бэкенд).
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Роли проекта (зеркало ProjectManager.CHAT_ROLES).
  var CHAT_ROLES = [
    { icon: '📋', title: 'Работа с паспортом проекта' },
    { icon: '⚠️', title: 'Работа с рисками' },
    { icon: '🗓️', title: 'Работа с планом проекта' },
    { icon: '📊', title: 'Работа со статусом проекта' },
    { icon: '📝', title: 'Работа с протоколами встреч' },
    { icon: '🎯', title: 'Подготовка к УК' },
    { icon: '🔎', title: 'Проверка страницы проекта в Confluence' }
  ];

  var ProjectWizard = {
    _bound: false,
    _step: 1,

    init: function () {
      this._step = 1;
      this.bind();
      this.renderChats();
    },

    bind: function () {
      if (this._bound) return;
      this._bound = true;
      var self = this;
      // Загрузка списка БД при открытии шага 2.
      var openers = document.querySelectorAll('[onclick*="projectModal"]');
      openers.forEach(function (b) {
        if (/openModal/.test(b.getAttribute('onclick') || '')) {
          b.addEventListener('click', function () { self.reset(); });
        }
      });
      // Переключение источника БД — используем прототипный selectRadio,
      // который показывает/скрывает newDbFields/existingDbFields.
    },

    reset: function () {
      this._step = 1;
      if (el('projectName')) el('projectName').value = '';
      if (el('projectDesc')) el('projectDesc').value = '';
      if (el('projectDbName')) el('projectDbName').value = '';
      var radio=document.querySelector('#projectModal input[name="dbSource"][value="new"]');if(radio)selectRadio(radio.closest('.radio-option'));
      this.showStep(1);
      this.loadDatabases();
    },

    showStep: function (step) {
      this._step = step;
      var steps = document.querySelectorAll('#projectModal .wizard-step');
      steps.forEach(function (s) { s.classList.remove('active'); });
      var cur = el('wizardStep' + step);
      if (cur) cur.classList.add('active');
      var inds = document.querySelectorAll('#projectModal .wizard-step-indicator');
      inds.forEach(function (ind) {
        var n = parseInt(ind.getAttribute('data-step'), 10);
        ind.classList.toggle('active', n === step);
      });
      if (step === 2) this.loadDatabases();
      if (step === 3) this.renderChats();
    },

    next: function (step) {
      if (step === 2) {
        var name = el('projectName') ? el('projectName').value.trim() : '';
        if (!name) {
          CA.toast && CA.toast('warning', 'Внимание', 'Укажите название проекта');
          return;
        }
        // Автоподсказка имени БД (латиница) из названия.
        if (el('projectDbName') && !el('projectDbName').value) {
          el('projectDbName').value = this.sanitize(name);
        }
      }
      if(step===3){var existing=document.querySelector('#projectModal input[name="dbSource"][value="existing"]');if(existing.checked&&!this.selectedDb()){CA.toast('warning','База проекта','Выберите пользовательскую SQLite-БД или создайте новую');return;}if(!existing.checked&&!/^[a-zA-Z][a-zA-Z0-9_-]{1,39}$/.test(el('projectDbName').value.trim())){CA.toast('warning','Имя БД','Используйте 2–40 латинских букв, цифр, _ или -; начните с буквы');return;}}
      this.showStep(step);
    },

    sanitize: function (name) {
      var s = String(name || '').replace(/[^A-Za-z0-9_-]+/g, '_').replace(/_+/g, '_');
      s = s.replace(/^_+|_+$/g, '');
      if(!/^[a-zA-Z]/.test(s))s='project_'+s;
      return (s||'project').slice(0,30)+'_'+Date.now().toString(36).slice(-6);
    },

    renderChats: function () {
      var list = el('wizardChatList');
      if (!list) return;
      list.innerHTML = CHAT_ROLES.map(function (r) {
        return '<div class="chat-preview-item"><div class="chat-preview-icon">' + esc(r.icon) +
          '</div><div class="chat-preview-name">' + esc(r.title) + '</div></div>';
      }).join('');
    },

    loadDatabases: function () {
      var list = el('wizardDbList');
      if (!list || !CA.api || !CA.api.databases) return;
      CA.api.databases.list().then(function (data) {
        var dbs = ((data && data.databases) || []).filter(function(d){return d.type==='sqlite'&&!d.preset&&d.enabled!==false;});
        if (!dbs.length) {
          list.innerHTML = '<div class="db-empty">Нет доступных БД</div>';
          return;
        }
        list.innerHTML = dbs.map(function (d, i) {
          return '<div class="db-select-item' + (i === 0 ? ' selected' : '') +
            '" data-name="' + esc(d.name) + '">' +
            '<div class="db-card-icon" style="width:36px;height:36px;font-size:18px;">🗄️</div>' +
            '<div class="db-card-content"><div class="db-card-title">' + esc(d.title || d.name) +
              '</div><div class="db-card-meta"><span>' + esc((d.type || 'sqlite').toUpperCase()) +
              '</span></div></div></div>';
        }).join('');
        list.querySelectorAll('.db-select-item').forEach(function (item) {
          item.addEventListener('click', function () {
            list.querySelectorAll('.db-select-item').forEach(function (x) { x.classList.remove('selected'); });
            item.classList.add('selected');
          });
        });
      }).catch(function () {
        list.innerHTML = '<div class="db-empty">Не удалось загрузить БД</div>';
      });
    },

    selectedDb: function () {
      var item = document.querySelector('#wizardDbList .db-select-item.selected');
      return item ? item.getAttribute('data-name') : null;
    },

    // Создаёт проект через API (новая БД или существующая).
    create: function () {
      if(this._creating)return;
      var self = this;
      var name = el('projectName') ? el('projectName').value.trim() : '';
      if (!name) {
        CA.toast && CA.toast('warning', 'Внимание', 'Укажите название проекта');
        return;
      }
      var payload = {
        name: name,
        description: el('projectDesc') ? el('projectDesc').value : '',
        system_prompt: ''
      };
      var existingRadio = document.querySelector('#projectModal input[name="dbSource"][value="existing"]');
      var useExisting = existingRadio && existingRadio.checked;
      if (useExisting) {
        var db = this.selectedDb();
        if (!db) {
          CA.toast && CA.toast('warning', 'Внимание', 'Выберите существующую БД');
          return;
        }
        payload.existing_db_id = db;
      } else {
        payload.db_name = el('projectDbName') ? el('projectDbName').value.trim() : null;
      }

      this._creating=true;var createButton=document.querySelector('#projectModal [onclick*="ProjectWizard.create"]');if(createButton)createButton.disabled=true;
      CA.api.projects.create(payload).then(function (res) {
        CA.toast && CA.toast('success', 'Проект создан', 'Создана БД и 7 рабочих чатов');
        if (window.closeModal) window.closeModal('projectModal');
        // Обновляем дропдаун проектов в сайдбаре.
        CA.api.projects.list().then(function (data) {
          CA.store.setProjects((data && data.projects) || []);
        }).catch(function () {});
        CA.api.get('chats').then(function (data) {
          (data.chats || []).forEach(function (chat) { CA.store.upsertChat(chat); });
          if (res && res.project_id) CA.store.setActiveProject(res.project_id);
          var first=(data.chats||[]).find(function(c){return c.project===res.project_id;});if(first)CA.store.setActiveChat(first.id);
          CA.app.go('chat');CA.DatabasesView.load();CA.ChatContextPanel.loadCatalogs();
        }).catch(function (e) { CA.toast('error', 'История чатов', e.message); });
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось создать проект');
      }).finally(function(){self._creating=false;if(createButton)createButton.disabled=false;});
    }
  };

  CA.ProjectWizard = ProjectWizard;
})();
