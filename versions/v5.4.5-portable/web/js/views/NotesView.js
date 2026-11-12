// CorporateAgent v4 — js/views/NotesView.js
// Этап 4.4: раздел «Заметки» (перенос экрана из прототипа v2).
// Данные — через api.notes* (Этап 4.5), состояние — в store.
// Разметка/классы — дословно из прототипа (notes-sidebar/notes-editor/note-item).
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // «Сегодня, 14:20» / «Вчера, 16:45» / «08.09.2026» — как в прототипе (по ISO updated).
  function formatNoteDate(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    var now = new Date();
    var time = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
    var sameDay = d.getFullYear() === now.getFullYear()
      && d.getMonth() === now.getMonth() && d.getDate() === now.getDate();
    if (sameDay) return 'Сегодня, ' + time;
    var yest = new Date(now.getTime() - 86400000);
    var isYest = d.getFullYear() === yest.getFullYear()
      && d.getMonth() === yest.getMonth() && d.getDate() === yest.getDate();
    if (isYest) return 'Вчера, ' + time;
    return ('0' + d.getDate()).slice(-2) + '.' + ('0' + (d.getMonth() + 1)).slice(-2) + '.' + d.getFullYear();
  }

  function preview(text) {
    var t = String(text || '').replace(/\s+/g, ' ').trim();
    return t.length > 40 ? t.slice(0, 40) + '…' : t;
  }

  var NotesView = {
    _bound: false,

    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function (st) {
          if (st.screen === 'notes') self.render();
        });
      }
      this.bindEditor();
      document.querySelectorAll('[data-note-format]').forEach(function(button){button.addEventListener('mousedown',function(e){e.preventDefault();});button.addEventListener('click',function(){self.format(button.dataset.noteFormat);});});
      this.load();
    },

    // Загружает заметки с сервера.
    load: function () {
      var self = this;
      if (!CA.api || !CA.api.notes) return;
      CA.api.notes.list().then(function (data) {
        var list = (data && data.notes) || [];
        CA.store.setNotes(list);
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось загрузить заметки');
      });
    },

    render: function () {
      var listContainer = el('notesList');
      if (!listContainer || !CA.store) return;
      var st = CA.store.getState();
      var notes = st.noteOrder.map(function (id) { return st.notes[id]; });

      if (notes.length === 0) {
        listContainer.innerHTML = '<div class="note-empty">Нет заметок. Нажмите «+ Новая».</div>';
      } else {
        listContainer.innerHTML = notes.map(function (n) {
          var active = n.id === st.activeNoteId ? ' active' : '';
          return '<div class="note-item' + active + '" data-note-id="' + esc(n.id) + '">' +
            '<div class="note-item-title">' + esc(n.title || 'Без названия') + '</div>' +
            '<div class="note-item-preview">' + esc(preview(n.text)) + '</div>' +
            '<div class="note-item-date">' + esc(formatNoteDate(n.updated)) + '</div>' +
          '</div>';
        }).join('');
      }

      listContainer.querySelectorAll('.note-item').forEach(function (item) {
        item.addEventListener('click', function () {
          CA.store.setActiveNote(item.getAttribute('data-note-id'));
        });
      });

      this.renderEditor();
    },

    renderEditor: function () {
      var st = CA.store.getState();
      var note = st.activeNoteId ? st.notes[st.activeNoteId] : null;
      var titleInput = document.querySelector('.notes-editor-title');
      var contentArea = document.querySelector('.notes-editor-content');
      if (!titleInput || !contentArea) return;
      var changed=this._renderedNoteId!==st.activeNoteId;this._renderedNoteId=st.activeNoteId;
      titleInput.disabled=contentArea.disabled=!note;
      document.querySelectorAll('.notes-toolbar button').forEach(function(b){b.disabled=!note;});
      if (note) {
        if (changed||document.activeElement !== titleInput) titleInput.value = note.title || '';
        if (changed||document.activeElement !== contentArea) contentArea.value = note.text || '';
      } else {
        titleInput.value = '';
        contentArea.value = '';
      }
    },

    // Автосохранение изменений заголовка/текста (blur).
    bindEditor: function () {
      var self = this;
      if (this._bound) return;
      var titleInput = document.querySelector('.notes-editor-title');
      var contentArea = document.querySelector('.notes-editor-content');
      if (!titleInput || !contentArea) return;
      this._bound = true;

      var save = function () {
        var st = CA.store.getState();
        var note = st.activeNoteId ? st.notes[st.activeNoteId] : null;
        if (!note) return;
        var title = titleInput.value.trim();
        var text = contentArea.value;
        if ((note.title || '') === title && (note.text || '') === text) return;
        CA.store.upsertNote(Object.assign({},note,{title:title,text:text}));
        self._saveChain=(self._saveChain||Promise.resolve()).catch(function(){}).then(function(){return CA.api.notes.update(note.id, { title: title, text: text });}).then(function (saved) {
          var current=CA.store.getState().notes[note.id];
          if(current&&current.title===title&&current.text===text)CA.store.upsertNote(saved);
          self.render();
        }).catch(function (e) {
          CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось сохранить заметку');
        });
      };

      titleInput.addEventListener('blur', save);
      contentArea.addEventListener('blur', save);
      this.saveEditor=save;
    },

    format:function(type){
      var area=document.querySelector('.notes-editor-content');if(!area||area.disabled)return;
      var start=area.selectionStart,end=area.selectionEnd,text=area.value.slice(start,end),replacement;
      if(type==='link'){var url=prompt('Адрес ссылки (HTTP/HTTPS):','https://');if(url===null)return;try{var parsed=new URL(url);if(!['https:','http:'].includes(parsed.protocol))throw Error();}catch(e){CA.toast('warning','Ссылка','Укажите корректный HTTP/HTTPS адрес');return;}replacement='['+(text||'Ссылка')+']('+url+')';}
      else if(type==='list')replacement=(text||'Пункт списка').split('\n').map(function(line){return '- '+line;}).join('\n');
      else{var markers=type==='bold'?['**','**']:type==='italic'?['*','*']:['<u>','</u>'];replacement=markers[0]+(text||'Текст')+markers[1];}
      area.setRangeText(replacement,start,end,'select');area.focus();this.saveEditor();
    },

    // Создаёт новую заметку и делает её активной.
    createNew: function () {
      var self = this;
      CA.api.notes.create({ title: 'Новая заметка', text: '' }).then(function (note) {
        CA.store.upsertNote(note);
        CA.store.setActiveNote(note.id);
        CA.toast && CA.toast('success', 'Заметка создана', 'Новая заметка добавлена');
        self.render();
        var titleInput = document.querySelector('.notes-editor-title');
        if (titleInput) titleInput.focus();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось создать заметку');
      });
    },

    // Удаляет активную заметку.
    deleteActive: function () {
      var st = CA.store.getState();
      var note = st.activeNoteId ? st.notes[st.activeNoteId] : null;
      if (!note) {
        CA.toast && CA.toast('warning', 'Внимание', 'Нет выбранной заметки');
        return;
      }
      if (!confirm('Удалить заметку «' + (note.title || 'Без названия') + '»?')) return;
      var self = this;
      CA.api.notes.remove(note.id).then(function () {
        CA.store.removeNote(note.id);
        CA.toast && CA.toast('success', 'Удалено', 'Заметка удалена');
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось удалить заметку');
      });
    }
  };

  CA.NotesView = NotesView;
})();
