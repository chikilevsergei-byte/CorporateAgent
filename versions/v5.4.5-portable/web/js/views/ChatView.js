// CorporateAgent v4 — js/views/ChatView.js

// Этап 3.11/3.12: чат по макету прототипа. Рендерит историю из store,
// шлёт сообщения через api.chatStream (SSE), обрабатывает действия над сообщениями.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function nowTime() {
    var d = new Date();
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }

  function el(id) { return document.getElementById(id); }

  var ChatView = {
    _streamAbort: null,
    _activeRequest: null,

    // Точка входа: подписка на store + первичный рендер.
    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function () { self.render(); });
      }
      this.bindInput();
      this.render();
    },

    render: function () {
      var container = el('chatMessages');
      if (!container || !CA.store) return;
      var st = CA.store.getState();
      var stop = el('stopButton'); if(stop){stop.hidden=!st.streaming;stop.disabled=!this._activeRequest || !this._activeRequest.ready || this._activeRequest.stopping;stop.textContent=this._activeRequest && this._activeRequest.stopping?'Остановка…':'Стоп';}
      var send = el('sendButton');if(send)send.disabled=st.streaming;
      var chat = st.activeChatId ? st.chats[st.activeChatId] : null;
      var messages = (chat && chat.messages) || [];

      var switched = this._renderedChat !== st.activeChatId;
      var follow = switched || container.scrollHeight - container.scrollTop - container.clientHeight < 80;
      if (switched) container.replaceChildren();
      this._renderedChat = st.activeChatId;
      var existing = new Map();
      Array.from(container.children).forEach(function (node) { existing.set(node.getAttribute('data-message-id'), node); });
      var handlers = this.actions();
      messages.forEach(function (m, index) {
        var html = CA.Message.render(m), node = existing.get(String(m.id));
        existing.delete(String(m.id));
        if (!node || node._messageHtml !== html) {
          var template = document.createElement('template'); template.innerHTML = html;
          var fresh = template.content.firstElementChild;
          if (node) {
            // Keep the message element: restarting its appearance animation caused flicker.
            node.className = fresh.className;
            node.replaceChildren.apply(node, Array.from(fresh.childNodes));
          } else node = fresh;
          node._messageHtml = html;
          CA.Message.bindActions(node, handlers);
        }
        if (container.children[index] !== node) container.insertBefore(node, container.children[index] || null);
      });
      existing.forEach(function (node) { node.remove(); });
      if (follow) container.scrollTop = container.scrollHeight;
    },

    // Обработчики кнопок действий на сообщениях.
    actions: function () {
      var self = this;
      return {
        onCopy: function (messageEl) {
          var text = messageEl.querySelector('.message-bubble').innerText;
          if (navigator.clipboard) {
            navigator.clipboard.writeText(text);
          }
          CA.toast && CA.toast('success', 'Скопировано', 'Сообщение скопировано в буфер обмена');
        },
        onEdit: function (messageEl, messageId) {
          self.editMessage(messageEl, messageId);
        },
        onDelete: function (messageEl, messageId) {
          self.deleteMessage(messageId);
        },
        onRegenerate: function (messageEl, messageId) {
          self.regenerate(messageId);
        },
        onNote: function (messageEl, messageId, btn) {
          var chat = CA.store.getActiveChat();
          var msg = chat && chat.messages.filter(function (m) { return m.id === messageId; })[0];
          if (!msg) return;
          CA.api.notes.create({title: msg.text.slice(0, 60), text: msg.text}).then(function (note) {
            CA.store.upsertNote(note); btn.classList.add('active');
            CA.toast('success', 'Сохранено', 'Заметка создана');
          }).catch(function (e) { CA.toast('error', 'Ошибка', e.message); });
        },
        onDb: function (messageEl, messageId, btn) {
          var chat = CA.store.getActiveChat();
          var selected = (chat && chat.selected_databases) || [];
          var writable = selected.filter(function (name) { var d = CA.store.getState().databases[name]; return d && !d.preset; });
          var target = chat && selected.indexOf(chat.project_db) >= 0 ? chat.project_db : writable[0];
          if (!chat || !target || (!chat.project_db && writable.length > 1)) {
            CA.toast('warning', 'Запись в БД', 'Выберите одну БД в контексте чата.'); return;
          }
          if (!confirm('Сохранить текст ответа в таблицу agent_notes БД "' + target + '"?')) return;
          CA.api.post('chat/save-db', {chat_id: chat.id, message_id: messageId}).then(function () {
            btn.classList.add('active'); CA.toast('success', 'Сохранено', 'Ответ записан в agent_notes');
          }).catch(function (e) { CA.toast('error', 'Ошибка', e.message); });
        },
        // 6.4: скачивание файла-отчёта, созданного агентом (output/xxx.pptx/xlsx).
        onDownload: function (messageEl, messageId) {
          self.downloadReport(messageId);
        }
      };
    },

    // Скачивает файл отчёта из output/ по имени из meta.filePath сообщения.
    downloadReport: function (messageId) {
      var st = CA.store.getState();
      var chat = st.activeChatId ? st.chats[st.activeChatId] : null;
      var msg = null;
      if (chat && chat.messages) {
        chat.messages.forEach(function (m) { if (m.id === messageId) msg = m; });
      }
      var path = msg && msg.meta && msg.meta.filePath;
      if (!path) {
        CA.toast && CA.toast('warning', 'Внимание', 'У сообщения нет файла для скачивания');
        return;
      }
      // Сервер отдаёт файл по базовому имени из папки output/.
      var name = String(path).replace(/\\/g, '/').split('/').pop();
      CA.api.files.download(name).then(function () {
        CA.toast && CA.toast('success', 'Скачивание', 'Файл «' + name + '» загружается');
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось скачать файл');
      });
    },

    // --- Отправка сообщения (+ стриминг ответа) ---
    bindInput: function () {
      var self = this;
      var input = el('messageInput');
      if (!input) return;
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          self.send();
        }
      });
      var sendBtn = el('sendButton');
      if (sendBtn) {
        sendBtn.addEventListener('click', function () { self.send(); });
        var stop=document.createElement('button');stop.id='stopButton';stop.type='button';stop.className='btn-secondary';stop.hidden=true;stop.textContent='Стоп';stop.onclick=function(){self.stop();};sendBtn.parentNode.insertBefore(stop,sendBtn.nextSibling);
        if (!el('allowDbWrite')) {
          var label = document.createElement('label'); label.style.fontSize = '12px';
          var allow = document.createElement('input'); allow.type = 'checkbox'; allow.id = 'allowDbWrite';
          label.appendChild(allow); label.appendChild(document.createTextNode('Разрешить изменение БД в этом запросе'));
          sendBtn.parentNode.appendChild(label);
        }
      }
    },

    send: function () {
      var input = el('messageInput');
      if (!input) return;
      if (CA.store.getState().streaming) return;
      var text = input.value.trim();
      if (!text) return;
      var st = CA.store.getState();
      var chatId = st.activeChatId;
      if (CA.FilePicker && CA.FilePicker.isUploading(chatId)) { CA.toast('warning','Файлы','Дождитесь завершения загрузки'); return; }
      if (chatId && CA.Sidebar._saving[chatId]) {
        var self=this, original=input.value;
        CA.Sidebar.ensureSaved(chatId).then(function(){if(CA.store.getState().activeChatId===chatId && input.value===original)self.send();}).catch(function(e){CA.toast('error','Чат',e.message);});return;
      }

      // Если чата ещё нет — заводим локальный контейнер; сервер присвоит
      // (или подтвердит) id и вернёт его в кадре "done".
      if (!chatId) {
        chatId = 'chat_' + Date.now();
        CA.store.upsertChat({ id: chatId, title: text.slice(0, 40), messages: [], selected_databases: st.context.databases, selected_skills: st.context.skills, selected_tools: st.context.tools });
        CA.store.setActiveChat(chatId);
      }

      // Оптимистично показываем сообщение пользователя.
      CA.store.appendMessage(chatId, { id: 'local_u_' + Date.now(), role: 'user', text: text, time: nowTime() });
      input.value = '';

      // Плейсхолдер streaming-ответа ассистента.
      var assistantId = 'local_a_' + Date.now();
      CA.store.appendMessage(chatId, { id: assistantId, role: 'assistant', text: '', time: nowTime(), pending: true });
      var self=this;
      var request={id:'request_'+crypto.randomUUID(),chatId:chatId,ready:false,stopping:false};
      this._activeRequest=request;
      CA.store.setStreaming(true,chatId);

      var accumulated = '';
      var requestChatId = st.activeChatId || chatId;
      var ctx = st.context;
      var payload = {
        message: text,
        request_id: request.id,
        confirm_writes: !!(el("allowDbWrite") && el("allowDbWrite").checked),
        databases: ctx.databases || [],
        skills: ctx.skills || [],
        tools: ctx.tools || [],
        attachment_ids: CA.FilePicker ? CA.FilePicker.ids(chatId) : []
      };
      // chat_id отправляем только если он был подтверждён сервером ранее.
      payload.chat_id = requestChatId;

      if (el("allowDbWrite")) el("allowDbWrite").checked = false;

      function adoptServerId(serverId) {
        if (serverId && serverId !== requestChatId) {
          var existing = CA.store.getState().chats[requestChatId];
          CA.store.upsertChat({
            id: serverId,
            title: text.slice(0, 40),
            messages: existing ? existing.messages : [],
            _server: true
          });
          CA.store.removeChat(requestChatId);
          CA.store.setActiveChat(serverId);
          requestChatId = serverId;
        } else if (serverId) {
          // Сервер подтвердил тот же id — помечаем как серверный.
          CA.store.updateChat(requestChatId, { _server: true });
        }
      }

      this.pollStatus(request,assistantId);
      this._streamAbort = CA.api.chatStream(payload, {
        onStarted:function(){if(self._activeRequest===request){request.ready=true;self.render();}},
        onToken: function (delta) {
          accumulated += delta;
          CA.store.updateMessage(requestChatId, assistantId, { text: accumulated, pending: false });
        },
        onDone: function (full, data) {
          if(self._activeRequest!==request)return;
          self._activeRequest=null;self._streamAbort=null;
          if (data && data.chat) {
            CA.store.upsertChat(data.chat);
            if (data.chat.id !== requestChatId) CA.store.removeChat(requestChatId);
            if (CA.store.getState().activeChatId === requestChatId) CA.store.setActiveChat(data.chat.id);
            CA.store.setStreaming(false);
            if (CA.FilePicker) CA.FilePicker.renderAttachments();
            if (CA.DatabasesView) CA.DatabasesView.load();
            if (CA.ChatContextPanel) CA.ChatContextPanel.loadCatalogs();
            return;
          }
          if (data && data.chat_id) adoptServerId(data.chat_id);
          // 3.14: переносим structured data ответа (файл/usage), если сервер их вернул.
          var patch = { text: full || accumulated, pending: false };
          if (data && (data.file_path || data.usage)) {
            patch.meta = {
              filePath: data.file_path || '',
              usage: data.usage || []
            };
          }
          CA.store.updateMessage(requestChatId, assistantId, patch);
          CA.store.setStreaming(false);
        },
        onError: function (message) {
          if(self._activeRequest!==request)return;
          self._activeRequest=null;self._streamAbort=null;
          CA.store.updateMessage(requestChatId, assistantId, {
            text: '⚠️ Ошибка: ' + message,
            pending: false
          });
          CA.store.setStreaming(false);
        }
      });
    },

    pollStatus:function(request,assistantId){
      var self=this;
      function poll(){
        if(self._activeRequest!==request)return;
        CA.api.get('chat/status',{chat_id:request.chatId,request_id:request.id}).then(function(data){
          if(self._activeRequest!==request || !data.active || !data.started)return;
          request.ready=true;
          var chat=CA.store.getState().chats[request.chatId],message=chat && chat.messages.find(function(m){return m.id===assistantId;});
          if(message && data.partial && data.partial.length>message.text.length)CA.store.updateMessage(request.chatId,assistantId,{text:data.partial,pending:false});
          self.render();
        }).catch(function(){ /* SSE/error handler remains authoritative. */ }).finally(function(){
          if(self._activeRequest===request)setTimeout(poll,250);
        });
      }
      setTimeout(poll,100);
    },
    stop:function(){
      var self=this,request=this._activeRequest;if(!request || request.stopping)return;
      request.stopping=true;this.render();
      function cancel(attempt){
        return CA.api.post('chat/cancel',{chat_id:request.chatId,request_id:request.id}).then(function(data){
          if(self._activeRequest!==request)return;
          if(!data.active && attempt<4)return new Promise(function(resolve){setTimeout(resolve,100);}).then(function(){return cancel(attempt+1);});
          if(!data.active)throw new Error('Запрос уже завершён или ещё не запущен. Повторите остановку.');
          // Wait for the authoritative done frame/response; do not merely hide running work.
        });
      }
      cancel(0).catch(function(e){if(self._activeRequest!==request)return;request.stopping=false;self.render();CA.toast('error','Остановка',e.message);});
    },

    editMessage: function (messageEl, messageId) {
      var bubble = messageEl.querySelector('.message-bubble');
      var newText = prompt('Редактировать сообщение:', bubble.innerText);
      if (newText == null || newText === bubble.innerText) return;
      var chatId = CA.store.getState().activeChatId;
      CA.api.editMessage(chatId, messageId, newText).then(function () {
        CA.store.updateMessage(chatId, messageId, { text: newText, edited: true });
        CA.toast && CA.toast('success', 'Изменено', 'Сообщение отредактировано');
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message);
      });
    },

    deleteMessage: function (messageId) {
      if (!confirm('Удалить сообщение?')) return;
      var chatId = CA.store.getState().activeChatId;
      CA.api.deleteMessage(chatId, messageId).then(function () {
        CA.store.removeMessage(chatId, messageId);
        CA.toast && CA.toast('success', 'Удалено', 'Сообщение удалено');
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message);
      });
    },

    regenerate: function (messageId) {
      var self=this,st=CA.store.getState();if(st.streaming)return;
      var chatId=st.activeChatId,request={id:'request_'+crypto.randomUUID(),chatId:chatId,ready:true,stopping:false};
      this._activeRequest=request;CA.store.setStreaming(true,chatId);
      CA.api.regenerateMessage(chatId,messageId,request.id).then(function(data){
        if(self._activeRequest!==request)return;
        self._activeRequest=null;
        if(data && data.chat)CA.store.upsertChat(data.chat);
        CA.store.setStreaming(false);
        CA.toast('success',data && data.cancelled?'Остановлено':'Готово',data && data.cancelled?'Прежняя ветка сохранена':'Сообщение перегенерировано');
      }).catch(function(e){if(self._activeRequest!==request)return;self._activeRequest=null;CA.store.setStreaming(false);CA.toast('error','Ошибка',e.message);});
    }

  };

  CA.ChatView = ChatView;
})();
