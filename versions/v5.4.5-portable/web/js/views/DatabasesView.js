// CorporateAgent v4 — js/views/DatabasesView.js
// Этап 5.4-F: раздел «Базы данных» (вкладка настроек) — список карточек БД,
// модалка просмотра (5.c, аналог DbViewerView) и модалка подключения (5.d, DbConnectDialog).
// Данные — через api.databases*, состояние — в store.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  var DatabasesView = {
    _bound: false,

    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function (st) {
          if (st.screen === 'settings') self.render();
        });
      }
      this.load();
    },

    load: function () {
      var self = this;
      if (!CA.api || !CA.api.databases) return;
      CA.api.databases.list().then(function (data) {
        CA.store.setDatabases((data && data.databases) || []);
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось загрузить БД');
      });
    },

    render: function () {
      var list = el('dbList');
      if (!list || !CA.store) return;
      var st = CA.store.getState();
      if (!st.databaseOrder.length) {
        list.innerHTML = '<div class="db-empty">Нет подключённых баз данных</div>';
        return;
      }
      list.innerHTML = st.databaseOrder.map(function (name) {
        var d = st.databases[name];
        var type = (d.type || 'sqlite').toUpperCase();
        var meta = '<span>' + esc(type) + '</span>';
        if (d.tables_count) meta += '<span>•</span><span>' + d.tables_count + ' таблиц</span>';
        if (d.records_count) meta += '<span>•</span><span>' + d.records_count + ' записей</span>';
        var status = d.enabled === false ? 'Отключена' : 'Активна';
        status += ' • Использований: ' + (d.usage_count || 0);
        var actions = '<div class="db-card-actions">' +
          '<button class="btn-ghost db-view-btn" data-name="' + esc(name) + '">👁 Просмотр</button>' +
          '<button class="btn-ghost db-edit-btn" data-name="' + esc(name) + '">✏️ Изменить</button>' +
          (!d.preset ? '<button class="btn-ghost db-del-btn" data-name="' + esc(name) + '">🗑 Удалить</button>' : '') +
        '</div>';
        return '<div class="db-card" data-name="' + esc(name) + '">' +
          '<div class="db-card-icon">🗄️</div>' +
          '<div class="db-card-content">' +
            '<div class="db-card-title">' + esc(d.title || name) + '</div>' +
            '<div class="db-card-meta">' + meta + '</div>' +
            '<div class="db-card-path">' + esc(d.path || '') + '</div>' +
            '<div class="db-card-status">' + esc(status) + '</div>' +
          '</div>' +
          actions +
        '</div>';
      }).join('');

      this.bindCardActions();
    },

    bindCardActions: function () {
      var self = this;
      document.querySelectorAll('#dbList .db-view-btn').forEach(function (b) {
        b.addEventListener('click', function () { self.openView(b.getAttribute('data-name')); });
      });
      document.querySelectorAll('#dbList .db-edit-btn').forEach(function (b) {
        b.addEventListener('click', function () { self.openEdit(b.getAttribute('data-name')); });
      });
      document.querySelectorAll('#dbList .db-del-btn').forEach(function (b) {
        b.addEventListener('click', function () { self.remove(b.getAttribute('data-name')); });
      });
    },

    // ---- Просмотр (5.c) ----

    openView: function (name) {
      this._tableData = null;
      if (!window.openModal) return;
      var title = el('dbViewTitle');
      var body = el('dbViewBody');
      if (title) title.textContent = '🗄️ ' + name;
      if (body) body.innerHTML = '<div class="db-view-loading">Загрузка…</div>';
      window.openModal('dbViewModal');

      return CA.api.databases.view(name).then(function (data) {
        if (!body) return;
        if (!data || !data.available) {
          body.innerHTML = '<div class="db-empty">Файл БД недоступен для просмотра.</div>';
          return;
        }
        var tables = data.tables || [];
        var tabsHtml = tables.map(function (t, i) {
          return '<button class="db-tab' + (i === 0 ? ' active' : '') + '" data-table="' +
            esc(t.name) + '">' + esc(t.name) + ' (' + t.columns_count + ')</button>';
        }).join('');
        var html = '<div style="display:flex; gap:16px;">' +
          '<div class="db-view-sidebar" style="min-width:220px; max-height:420px; overflow:auto;">' +
            (tabsHtml || '<div class="db-empty">Нет таблиц</div>') +
          '</div>' +
          '<div class="db-view-main" id="dbViewMain" style="flex:1; max-height:420px; overflow:auto;"></div>' +
        '</div>';
        body.innerHTML = '<details><summary>SQL-схема</summary><pre>' + esc(data.schema || '') + '</pre></details>' +
          '<div class="db-actions"><button id="dbBackup">Резервная копия</button> <button id="dbExportSql">Экспорт SQL</button> <button id="dbExportCsv">Экспорт CSV</button> <button id="dbImportCsv">Импорт CSV</button></div>' +
          '<textarea id="dbSql" rows="3" placeholder="SQL"></textarea><button id="dbRunSql">Выполнить SQL</button><pre id="dbSqlResult"></pre>' + html;
        DatabasesView._viewName = name;
        var readonly=!!CA.store.getState().databases[name]?.preset;
        el('dbRunSql').disabled=el('dbImportCsv').disabled=readonly;el('dbExportCsv').disabled=true;
        if(readonly)el('dbSql').placeholder='Предустановленная БД доступна только для чтения';
        function action(type, extra) {
          return CA.api.post('databases/action', Object.assign({name: name, action: type}, extra || {}));
        }
        el('dbBackup').onclick = function () { action('backup').then(function (r) { CA.toast('success', 'Копия создана', r.path); }).catch(DatabasesView.showError); };
        el('dbExportSql').onclick = function () { action('export').then(function (r) { DatabasesView.download(name + '.sql', r.text); }).catch(DatabasesView.showError); };
        el('dbExportCsv').onclick = function () {
          var t = DatabasesView._tableData;
          if (!t) return;
          var text = [t.columns].concat(t.rows).map(function (row) { return row.map(function (v) { return '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"'; }).join(','); }).join('\r\n');
          DatabasesView.download(t.table + '.csv', '\ufeff' + text);
          CA.toast('success', 'CSV', 'Экспортированы отображаемые строки: ' + t.rows.length);
        };
        el('dbRunSql').onclick = function () {
          var sql = el('dbSql').value; if (!sql.trim()) return;
          if (!confirm('Выполнить SQL в БД "' + name + '"?')) return;
          action('sql', {sql: sql}).then(function (r) {return DatabasesView.openView(name).then(function(){el('dbSql').value=sql;el('dbSqlResult').textContent=JSON.stringify(r,null,2);});}).catch(DatabasesView.showError);
        };
        el('dbImportCsv').onclick = function () {
          var table = DatabasesView._tableData && DatabasesView._tableData.table;
          if (!table) return;
          var mode = prompt('Режим: insert или update (первый столбец CSV — ключ)', 'insert');
          if (mode !== 'insert' && mode !== 'update') return;
          var input = document.createElement('input'); input.type = 'file'; input.accept = '.csv';
          input.dataset.csvImport='true';input.style.display='none';document.body.appendChild(input);input.addEventListener('cancel',function(){input.remove();});
          input.onchange = function () {
            var file = input.files[0]; if (!file || !confirm('Импортировать CSV в ' + table + '?')) return;
            file.text().then(function (text) { return action('import', {table: table, mode: mode, text: text}); }).then(function (r) {
              CA.toast('success', 'Импорт', 'Обработано строк: ' + r.count); DatabasesView.loadTable(name, table);
            }).catch(DatabasesView.showError).finally(function(){input.remove();});
          }; input.click();
        };
        body.querySelectorAll('.db-tab').forEach(function (btn) {
          btn.addEventListener('click', function () {
            body.querySelectorAll('.db-tab').forEach(function (x) { x.classList.remove('active'); });
            btn.classList.add('active');
            DatabasesView.loadTable(name, btn.getAttribute('data-table'));
          });
        });
        if (tables.length) {
          DatabasesView.loadTable(name, tables[0].name);
        }
      }).catch(function (e) {
        if (body) body.innerHTML = '<div class="db-empty">Ошибка: ' + esc(e.message || '') + '</div>';
      });
    },

    loadTable: function (name, table) {
      this._tableRequest=(this._tableRequest||0)+1;var request=this._tableRequest;
      this._tableData=null;if(el('dbExportCsv'))el('dbExportCsv').disabled=true;
      var main = el('dbViewMain');
      if (!main) return;
      main.innerHTML = '<div class="db-view-loading">Загрузка…</div>';
      CA.api.databases.table(name, table).then(function (data) {
        if(request!==DatabasesView._tableRequest||!main.isConnected||DatabasesView._viewName!==name)return;
        if (!data || !data.available) {
          main.innerHTML = '<div class="db-empty">Таблица недоступна.</div>';
          return;
        }
        DatabasesView._tableData = data;
        if(el('dbExportCsv'))el('dbExportCsv').disabled=false;
        var cols = data.columns || [];
        var rows = data.rows || [];
        if (!cols.length) {
          main.innerHTML = '<div class="db-empty">Пусто.</div>';
          return;
        }
        var head = '<tr>' + cols.map(function (c) { return '<th>' + esc(c) + '</th>'; }).join('') + '</tr>';
        var bodyRows = rows.map(function (r) {
          return '<tr>' + r.map(function (v) {
            return '<td>' + esc(v == null ? '' : v) + '</td>';
          }).join('') + '</tr>';
        }).join('');
        main.innerHTML = '<table class="db-table"><thead>' + head + '</thead><tbody>' +
          bodyRows + '</tbody></table>';
      }).catch(function (e) {
        main.innerHTML = '<div class="db-empty">Ошибка: ' + esc(e.message || '') + '</div>';
      });
    },

    // ---- Подключение (5.d) ----

    openConnect: function () {
      if (!window.openModal) return;
      el('dbConnectName').value = '';
      el('dbConnectType').value = 'sqlite';
      el('dbConnectPath').value = '';
      el('dbConnectDesc').value = '';
      window.openModal('dbConnectModal');
    },

    saveConnect: function () {
      var self = this;
      var name = el('dbConnectName').value.trim();
      if (!name) {
        CA.toast && CA.toast('warning', 'Внимание', 'Укажите имя БД');
        return;
      }
      var payload = {
        name: name,
        type: el('dbConnectType').value,
        path: el('dbConnectPath').value.trim(),
        description: el('dbConnectDesc').value
      };
      CA.api.databases.create(payload).then(function (saved) {
        CA.store.upsertDatabase(saved);
        CA.toast && CA.toast('success', 'БД подключена', 'Новая база данных добавлена');
        if (window.closeModal) window.closeModal('dbConnectModal');
        self.render();
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось подключить БД');
      });
    },

    // Изменение описания/типа по карточке (простой prompt-стиль, чтобы не плодить модалки).
    openEdit: function (name) {
      var st = CA.store.getState();
      var d = st.databases[name];
      if (!d) return;
      var desc = prompt('Описание БД «' + name + '»:', d.description || '');
      if (desc === null) return;
      CA.api.databases.update(name, { description: desc }).then(function (saved) {
        CA.store.upsertDatabase(saved);
        CA.toast && CA.toast('success', 'Сохранено', 'БД обновлена');
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось обновить БД');
      });
    },

    remove: function (name) {
      if (!confirm('Удалить запись БД «' + name + '»? (файл .db не удаляется)')) return;
      var self = this;
      CA.api.databases.remove(name).then(function () {
        CA.store.removeDatabase(name);
        CA.toast && CA.toast('success', 'Удалено', 'Запись БД удалена');
        self.render();
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось удалить БД');
      });
    },

    showError: function (e) { CA.toast('error', 'Ошибка', e.message || String(e)); },
    download: function (name, text) {
      var url = URL.createObjectURL(new Blob([text], {type: 'text/plain;charset=utf-8'}));
      var link = document.createElement('a'); link.href = url; link.download = name; document.body.appendChild(link); link.click(); link.remove(); setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    },
    createFromFiles: function () {
      // Перенос «Создать БД из файлов» — открывает выбор файлов (пока заглушка-тост).
      if (CA.FilePicker) CA.FilePicker.open(function () {
        if (window.closeModal) window.closeModal('settingsModal');
        if (CA.app && CA.app.go) CA.app.go('chat');
        var input = document.getElementById('messageInput');
        input.value = 'Создай базу данных из загруженных файлов'; CA.ChatView.send();
      });
    }
  };

  CA.DatabasesView = DatabasesView;
})();
