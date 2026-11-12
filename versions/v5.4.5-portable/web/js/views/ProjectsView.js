// CorporateAgent v4 — js/views/ProjectsView.js
// Этап 5.1-F: экран «Проекты» (#projects) — список проектов, деталь проекта,
// создание (через мастер) и удаление. Данные — через api.projects*, состояние — в store.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  var ProjectsView = {
    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function (st) {
          if (st.screen === 'projects') self.render();
        });
      }
      this.load();
    },

    load: function () {
      var self = this;
      if (!CA.api || !CA.api.projects) return;
      CA.api.projects.list().then(function (data) {
        CA.store.setProjects((data && data.projects) || []);
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось загрузить проекты');
      });
    },

    render: function () {
      var container = el('projectsContent');
      if (!container || !CA.store) return;
      var st = CA.store.getState();
      var projects = st.projectOrder.map(function (id) { return st.projects[id]; });

      var head = '<div class="projects-header">' +
        '<div class="projects-header-title">📁 Проекты</div>' +
        '<button class="btn-primary" id="projectsCreateBtn">➕ Создать проект</button>' +
      '</div>';

      if (!projects.length) {
        container.innerHTML = head +
          '<div class="projects-empty">Пока нет проектов. Создайте первый — мастер создаст БД и 7 чатов-ролей.</div>';
      } else {
        container.innerHTML = head + '<div class="projects-grid">' + projects.map(function (p) {
          return '<div class="project-card" data-project-id="' + esc(p.id) + '">' +
            '<div class="project-card-title">' + esc(p.name || p.id) + '</div>' +
            '<div class="project-card-desc">' + esc(p.description || '') + '</div>' +
            '<div class="project-card-meta">🗄️ ' + esc(p.db || '—') + '</div>' +
            '<div class="project-card-actions">' +
              '<button class="btn-ghost project-open-btn" data-id="' + esc(p.id) + '">Открыть</button>' +
              '<button class="btn-danger project-del-btn" data-id="' + esc(p.id) + '">Удалить</button>' +
            '</div>' +
          '</div>';
        }).join('') + '</div>';
      }

      var createBtn = el('projectsCreateBtn');
      if (createBtn && window.openModal) {
        createBtn.addEventListener('click', function () { window.openModal('projectModal'); });
      }
      container.querySelectorAll('.project-open-btn').forEach(function (b) {
        b.addEventListener('click', function () {
          var id = b.getAttribute('data-id');
          CA.store.setActiveProject(id);
          var chats=CA.store.getState().chatOrder.filter(function(chatId){return CA.store.getState().chats[chatId].project===id;});
          if(chats.length)CA.store.setActiveChat(chats[0]);
          if(CA.app)CA.app.go('chat');
        });
      });
      container.querySelectorAll('.project-del-btn').forEach(function (b) {
        b.addEventListener('click', function () { ProjectsView.remove(b.getAttribute('data-id')); });
      });
      // Подсветка активного проекта.
      if (st.activeProjectId) {
        var active = container.querySelector('[data-project-id="' + st.activeProjectId + '"]');
        if (active) active.classList.add('active');
      }
    },

    remove: function (id) {
      var st = CA.store.getState();
      var p = st.projects[id];
      if (!p) return;
      if (!confirm('Удалить проект «' + (p.name || id) + '»? Связанные чаты тоже будут удалены.')) return;
      var self = this;
      CA.api.projects.remove(id).then(function () {
        st.chatOrder.slice().forEach(function (chatId) { if (st.chats[chatId].project === id) CA.store.removeChat(chatId); });
        CA.store.setProjects(st.projectOrder.filter(function(x){return x!==id;}).map(function(x){return st.projects[x];}));
        if(st.activeProjectId===id)CA.store.setActiveProject(null);
        CA.toast && CA.toast('success', 'Удалено', 'Проект удалён');
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось удалить проект');
      });
    }
  };

  CA.ProjectsView = ProjectsView;
})();
