// CorporateAgent v4 — js/components/Sidebar.js
// Этап 4.1: список чатов + переключение экранов.
// Этап 4.2: выпадающий список «Проекты» (из прототипа v2).
// Разметка/классы — как в прототипе (chat-list/chat-item/project-list/project-item).
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Иконка чата по заголовку (эмодзи-эвристика прототипа), по умолчанию «💬».
  function chatIcon(title) {
    var t = String(title || '').toLowerCase();
    if (t.indexOf('статус') >= 0) return '📊';
    if (t.indexOf('риск') >= 0) return '⚠️';
    if (t.indexOf('паспорт') >= 0) return '📋';
    if (t.indexOf('план') >= 0) return '📅';
    if (t.indexOf('ук') >= 0 || t.indexOf('презент') >= 0) return '🎯';
    if (t.indexOf('встреч') >= 0 || t.indexOf('протокол') >= 0) return '📝';
    return '💬';
  }

  function lastPreview(chat) {
    var msgs = (chat && chat.messages) || [];
    for (var i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i] && msgs[i].text) {
        var t = String(msgs[i].text).replace(/\s+/g, ' ').trim();
        return t.length > 30 ? t.slice(0, 30) + '…' : t;
      }
    }
    return 'Нет сообщений';
  }

  var Sidebar = {
    _saving: {},
    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function () { self.render(); });
      }
      this.bindActions();
      document.addEventListener('click', function (e) { if (!e.target.closest('#chatActionsMenu') && !e.target.closest('.chat-item-menu')) self.closeMenu(); });
      document.addEventListener('keydown', function (e) { if (e.key === 'Escape') self.closeMenu(); });
      this.loadProjects();
      document.querySelectorAll('[data-quick-question]').forEach(function(button){button.addEventListener('click',function(){self.quickQuestion(button.dataset.quickQuestion);});});
      CA.api.get('chats').then(function (data) {
        (data.chats || []).slice().reverse().forEach(function (chat) { CA.store.upsertChat(chat); });
        var active = CA.store.getState().activeChatId;
        if (active) CA.store.setActiveChat(active);
      }).catch(function (e) { CA.toast('error', 'История чатов', e.message); });
      this.render();
    },

    bindActions: function () {
      var self = this;
      // «Новый чат»: создаём локальный чат и переходим на экран чата.
      var newChatBtn = document.querySelector('.btn-new-chat');
      if (newChatBtn) {
        newChatBtn.addEventListener('click', function () { self.newChat(); });
      }
      // «Заметки» — быстрый переход (кнопка в sidebar-quick-actions).
      var quickButtons = document.querySelectorAll('.sidebar-quick-actions .btn-quick');
      quickButtons.forEach(function (btn) {
        if (/заметк/i.test(btn.textContent)) {
          btn.addEventListener('click', function () {
            if (CA.app && CA.app.go) CA.app.go('notes');
          });
        }
      });
    },

    // Создаёт новый чат и делает его активным (Этап 4.1).
    newChat: function () {
      if (!CA.store) return;
      var id = 'chat_' + (crypto.randomUUID ? crypto.randomUUID() : Date.now() + '_' + Math.random().toString(36).slice(2));
      CA.store.upsertChat({id: id, title: 'Новый чат', messages: [], attachments: [], project: CA.store.getState().activeProjectId || null});
      CA.store.setActiveChat(id);
      if (CA.app && CA.app.go) CA.app.go('chat');
      var input = document.getElementById('messageInput'); if (input) input.focus();
      this.ensureSaved(id).catch(function (e) { CA.toast('error','Не удалось создать чат',e.message); });
      return id;
    },
    quickQuestion:function(type){
      var self=this,id=this.newChat();
      return this.ensureSaved(id).then(function(){return CA.api.get('catalogs');}).then(function(data){
        var db=(data.databases||[]).find(function(item){var text=(item.name+' '+(item.title||'')).toLowerCase();return type==='lpg'?text.includes('lpg'):text.includes('ит')||text.includes('it');});
        if(!db)throw Error('Методология не найдена в списке БД');
        return CA.api.setChatContext(id,{databases:[db.name],skills:[],tools:[]}).then(function(){
          CA.store.updateChat(id,{selected_databases:[db.name],selected_skills:[],selected_tools:[]});
          return self.update(id,{title:type==='lpg'?'Вопрос по LPG':'Вопрос по ИТ'});
        });
      }).then(function(){CA.store.setActiveChat(id);document.getElementById('messageInput').focus();}).catch(function(e){CA.toast('error','Быстрый вопрос',e.message);});
    },
    ensureSaved: function (id) {
      var self = this, chat = CA.store.getState().chats[id];
      if (!chat) return Promise.reject(new Error('Чат не найден'));
      if (chat._server) return Promise.resolve(chat);
      if (this._saving[id]) return this._saving[id];
      this._saving[id] = CA.api.post('chats',{chat_id:id,title:chat.title,project_id:chat.project || ''}).then(function (saved) {
        var current = CA.store.getState().chats[id];
        if (!current) return saved;
        CA.store.upsertChat(Object.assign({},current,saved,{messages:current.messages}));
        if (CA.store.getState().activeChatId === id) CA.store.setActiveChat(id);
        return saved;
      }).finally(function () { delete self._saving[id]; });
      return this._saving[id];
    },
    closeMenu: function () { var menu = el('chatActionsMenu'); if (menu) menu.remove(); },
    showMenu: function (id, button) {
      this.closeMenu();
      var self = this, st = CA.store.getState(), chat = st.chats[id]; if (!chat) return;
      var menu = document.createElement('div'); menu.id = 'chatActionsMenu'; menu.className='chat-actions-menu'; menu.setAttribute('role','menu');
      var actions=[['rename','Переименовать'],['pin',chat.pinned?'Открепить':'Закрепить'],['move','Переместить в проект…'],['delete','Удалить чат']];
      actions.forEach(function (a) {
        var item = document.createElement('button'); item.textContent=a[1]; item.dataset.action=a[0]; item.setAttribute('role','menuitem');
        item.disabled=st.streaming && st.streamingChatId===id;
        item.onclick=function () { self.closeMenu(); self.chatAction(id,a[0]); }; menu.appendChild(item);
      });
      document.body.appendChild(menu); var box=button.getBoundingClientRect();
      menu.style.left=Math.max(8,Math.min(box.left,window.innerWidth-menu.offsetWidth-8))+'px';
      menu.style.top=Math.max(8,Math.min(box.bottom,window.innerHeight-menu.offsetHeight-8))+'px';
    },
    update: function (id, patch) {
      var self=this;
      return this.ensureSaved(id).then(function () { return CA.api.put('chats',Object.assign({chat_id:id},patch)); }).then(function (chat) {
        CA.store.upsertChat(chat);
        if (CA.store.getState().activeChatId===id) CA.store.setActiveChat(id);
        self.render(); return chat;
      });
    },
    chatAction: function (id, action) {
      var self=this, chat=CA.store.getState().chats[id]; if(!chat)return;
      var operation;
      if(action==='rename') {
        var title=prompt('Название чата:',chat.title || ''); if(title===null)return;
        operation=this.update(id,{title:title});
      } else if(action==='pin') operation=this.update(id,{pinned:!chat.pinned});
      else if(action==='delete') {
        if(!confirm('Удалить чат «'+(chat.title || 'Новый чат')+'»? Перед удалением будет сохранена резервная копия.'))return;
        operation=this.ensureSaved(id).then(function(){return CA.api.del('chats',{chat_id:id});}).then(function(){
          CA.store.removeChat(id); CA.store.setActiveChat(CA.store.getState().activeChatId);
        });
      } else if(action==='move') { this.moveDialog(id); return; }
      if(operation) operation.catch(function(e){CA.toast('error','Ошибка',e.message);});
    },
    moveDialog: function(id) {
      var self=this, st=CA.store.getState(), chat=st.chats[id];
      var previous=el('chatMoveDialog'); if(previous)previous.remove();
      var dialog=document.createElement('div'); dialog.id='chatMoveDialog'; dialog.className='chat-move-dialog'; dialog.setAttribute('role','dialog'); dialog.setAttribute('aria-modal','true');
      var label=document.createElement('label'); label.textContent='Проект для чата';
      var select=document.createElement('select'); select.id='chatMoveProject';
      var empty=document.createElement('option'); empty.value=''; empty.textContent='Без проекта'; select.appendChild(empty);
      st.projectOrder.forEach(function(pid){var option=document.createElement('option');option.value=pid;option.textContent=st.projects[pid].name || pid;select.appendChild(option);});
      select.value=chat.project || ''; label.appendChild(select); dialog.appendChild(label);
      var save=document.createElement('button');save.textContent='Переместить';save.id='chatMoveSave';
      save.onclick=function(){save.disabled=true;self.update(id,{project_id:select.value}).then(function(){CA.store.setActiveProject(select.value || null);dialog.remove();}).catch(function(e){save.disabled=false;CA.toast('error','Ошибка',e.message);});};
      var cancel=document.createElement('button');cancel.textContent='Отмена';cancel.onclick=function(){dialog.remove();};
      dialog.appendChild(save);dialog.appendChild(cancel);document.body.appendChild(dialog);setTimeout(function(){(dialog.querySelector('.select-trigger') || select).focus();},0);
      dialog.addEventListener('keydown',function(e){if(e.key==='Escape'){dialog.remove();return;}if(e.key==='Tab'){var focusables=Array.from(dialog.querySelectorAll('button:not(:disabled),select:not(.native-enhanced)')),i=focusables.indexOf(document.activeElement);e.preventDefault();focusables[(i+(e.shiftKey?focusables.length-1:1))%focusables.length].focus();}});
    },

    // Загружает список проектов (если эндпоинт доступен) — для дропдауна (4.2).
    loadProjects: function () {
      if (!CA.api || !CA.api.projects) return;
      CA.api.projects.list().then(function (data) {
        var list = (data && data.projects) || (data || []);
        if (CA.store) CA.store.setProjects(list);
      }).catch(function () {
        // Проекты ещё не поддержаны бэкендом — не критично.
      });
    },

    render: function () {
      this.renderChats();
      this.renderProjects();
    },

    renderChats: function () {
      var container = document.querySelector('#chatsSection .chat-list');
      if (!container || !CA.store) return;
      var self = this;
      var st = CA.store.getState();
      var chats = st.chatOrder.map(function (id) { return st.chats[id]; }).filter(function (chat) { return !st.activeProjectId || chat.project === st.activeProjectId; }).sort(function(a,b){return Number(!!b.pinned)-Number(!!a.pinned) || String(b.updated || '').localeCompare(String(a.updated || ''));});

      if (chats.length === 0) {
        container.innerHTML = '<div class="chat-empty">Нет чатов. Создайте новый.</div>';
        return;
      }

      container.innerHTML = chats.map(function (c) {
        var active = c.id === st.activeChatId ? ' active' : '';
        var title = c.title || 'Новый чат';
        return '<div class="chat-item' + active + '" data-chat-id="' + esc(c.id) + '">' +
          '<div class="chat-item-icon">' + esc(chatIcon(title)) + '</div>' +
          '<div class="chat-item-content">' +
            '<div class="chat-item-title">' + (c.pinned ? '📌 ' : '') + esc(title) + '</div>' +
            '<div class="chat-item-preview">' + esc(lastPreview(c)) + '</div>' +
          '</div>' +
          '<button class="chat-item-menu" title="Действия">⋯</button>' +
        '</div>';
      }).join('');

      container.querySelectorAll('.chat-item').forEach(function (item) {
        item.addEventListener('click', function (e) {
          var menuButton=e.target.closest('.chat-item-menu');
          if (menuButton) { self.showMenu(item.getAttribute('data-chat-id'),menuButton); return; }
          var chatId = item.getAttribute('data-chat-id');
          CA.store.setActiveChat(chatId);
          if (CA.app && CA.app.go) CA.app.go('chat');
        });
      });
    },

    renderProjects: function () {
      var container = document.querySelector('#projectsSection .project-list');
      if (!container || !CA.store) return;
      var st = CA.store.getState();
      var projects = st.projectOrder.map(function (id) { return st.projects[id]; });

      if (projects.length === 0) {
        container.innerHTML = '<div class="project-empty">Нет проектов</div>';
        return;
      }

      container.innerHTML = projects.map(function (p) {
        var active = p.id === st.activeProjectId ? ' active' : '';
        return '<div class="project-item' + active + '" data-project-id="' + esc(p.id) + '">' +
          '<div class="project-item-icon">📁</div>' +
          '<div class="project-item-content"><div class="project-item-title">' +
            esc(p.name || p.id) + '</div></div>' +
        '</div>';
      }).join('');

      container.querySelectorAll('.project-item').forEach(function (item) {
        item.addEventListener('click', function () {
          var id = item.getAttribute('data-project-id');
          var current = CA.store.getState().activeProjectId;
          CA.store.setActiveProject(current === id ? null : id);
        });
      });
    }
  };

  CA.Sidebar = Sidebar;
})();
