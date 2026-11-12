// CorporateAgent v4 — js/api.js
// Этап 3.9: тонкая обёртка над fetch и EventSource.
// Все вызовы идут на /api/* с Bearer-токеном (см. app.js) и единым форматом {ok,data,error}.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function token() {
    return (CA.getToken && CA.getToken()) || '';
  }

  function authHeaders(extra) {
    var h = extra || {};
    var t = token();
    if (t) {
      h['Authorization'] = 'Bearer ' + t;
    }
    return h;
  }

  // Разбор тела ответа: всегда пытаемся вернуть {ok,data,error}.
  function parseResponse(res) {
    return res.text().then(function (txt) {
      var json = null;
      try {
        json = txt ? JSON.parse(txt) : null;
      } catch (e) {
        json = null;
      }
      if (!res.ok) {
        var err = (json && json.error) || ('HTTP ' + res.status);
        var e = new Error(err);
        e.status = res.status;
        throw e;
      }
      if (json && json.ok === false) {
        var e2 = new Error(json.error || 'Ошибка запроса');
        e2.status = res.status;
        throw e2;
      }
      return json ? json.data : null;
    });
  }

  // GET /api/<path>?<query>
  function get(path, query) {
    var url = '/api/' + path;
    if (query) {
      var qs = new URLSearchParams(query).toString();
      if (qs) {
        url += (url.indexOf('?') < 0 ? '?' : '&') + qs;
      }
    }
    return fetch(url, { headers: authHeaders() }).then(parseResponse);
  }

  // POST/PUT/DELETE с JSON-телом.
  function send(method, path, body) {
    var opts = {
      method: method,
      headers: authHeaders({ 'Content-Type': 'application/json' })
    };
    if (body !== undefined && body !== null) {
      opts.body = JSON.stringify(body);
    }
    return fetch('/api/' + path, opts).then(parseResponse);
  }

  var api = {
    token: token,
    authHeaders: authHeaders,

    get: get,
    post: function (path, body) { return send('POST', path, body); },
    put: function (path, body) { return send('PUT', path, body); },
    del: function (path, body) { return send('DELETE', path, body); },

    // ---- Чат ----
    // Синхронный ответ агента.
    chat: function (payload) { return send('POST', 'chat', payload); },

    // Потоковый ответ (SSE) через fetch+ReadableStream.
    // onToken(delta), onDone(fullText), onError(message). Возвращает "отменятор".
    chatStream: function (payload, handlers) {
      handlers = handlers || {};
      var controller = (typeof AbortController !== 'undefined') ? new AbortController() : null;
      var opts = {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(payload)
      };
      if (controller) {
        opts.signal = controller.signal;
      }
      fetch('/api/chat/stream', opts)
        .then(function (res) {
          if (!res.ok || !res.body) {
            throw new Error('HTTP ' + res.status);
          }
          var reader = res.body.getReader();
          var decoder = new TextDecoder('utf-8');
          var buffer = '';
          var full = '';
          var doneData = null, streamError = null;
          function pump() {
            return reader.read().then(function (chunk) {
              if (chunk.done) {
                if (!doneData && handlers.onError) handlers.onError(streamError || "\u0421\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u0435 \u043f\u0440\u0435\u0440\u0432\u0430\u043d\u043e \u0434\u043e \u0437\u0430\u0432\u0435\u0440\u0448\u0435\u043d\u0438\u044f \u043e\u0442\u0432\u0435\u0442\u0430");
                return;
              }
              buffer += decoder.decode(chunk.value, { stream: true });
              var parts = buffer.split('\n\n');
              buffer = parts.pop();
              parts.forEach(function (frame) {
                var ev = parseSseFrame(frame);
                if (!ev) return;
                if (ev.event === 'started') {
                  if (handlers.onStarted) handlers.onStarted(ev.data);
                } else if (ev.event === 'token') {
                  var delta = extractDelta(ev.data);
                  full += delta;
                  if (handlers.onToken) handlers.onToken(delta);
                } else if (ev.event === 'error') {
                  streamError=(ev.data && ev.data.message) || 'Ошибка';
                } else if (ev.event === 'done') {

                  // Кадр done содержит chat_id и полный текст.
                  doneData = ev.data || null;
                  if (handlers.onDone) handlers.onDone(full, doneData);
                }
              });
              return pump();
            });
          }
          return pump();
        })
        .catch(function (e) {
          if (e && e.name === 'AbortError') return;
          if (handlers.onError) handlers.onError(e.message || String(e));
        });
      return function abort() { if (controller) controller.abort(); };
    },

    // Правка / удаление / перегенерация сообщения (этап 3.5–3.7).
    editMessage: function (chatId, messageId, text) {
      return send('PUT', 'chat/message', { chat_id: chatId, message_id: messageId, text: text });
    },
    deleteMessage: function (chatId, messageId) {
      return send('DELETE', 'chat/message', { chat_id: chatId, message_id: messageId });
    },
    regenerateMessage: function (chatId, messageId, requestId) {
      return send('POST', 'chat/message', { chat_id: chatId, message_id: messageId, request_id: requestId });
    },

    // Контекст чата (этап 3.8).
    getChatContext: function (chatId) {
      return get('chat/context', { chat_id: chatId });
    },
    setChatContext: function (chatId, ctx) {
      var body = {
        chat_id: chatId,
        databases: ctx.databases || [],
        skills: ctx.skills || [],
        tools: ctx.tools || []
      };
      return send('PUT', 'chat/context', body);
    },

    // ---- Заметки (этап 4.5) ----
    notes: {
      list: function () { return get('notes'); },
      get: function (noteId) { return get('notes', { note_id: noteId }); },
      create: function (note) {
        return send('POST', 'notes', { title: note.title || '', text: note.text || '' });
      },
      update: function (noteId, patch) {
        return send('PUT', 'notes', {
          note_id: noteId,
          title: patch.title,
          text: patch.text
        });
      },
      remove: function (noteId) {
        return send('DELETE', 'notes', { note_id: noteId });
      }
    },

    // ---- Проекты (этап 4.2 + 5.1) ----
    projects: {
      list: function () { return get('projects'); },
      get: function (projectId) { return get('projects', { project_id: projectId }); },
      create: function (project) {
        return send('POST', 'projects', {
          name: project.name || '',
          description: project.description || '',
          system_prompt: project.system_prompt || '',
          db_name: project.db_name || null,
          existing_db_id: project.existing_db_id || null
        });
      },
      update: function (projectId, patch) {
        var body = { project_id: projectId };
        if (patch.name !== undefined) body.name = patch.name;
        if (patch.description !== undefined) body.description = patch.description;
        if (patch.system_prompt !== undefined) body.system_prompt = patch.system_prompt;
        return send('PUT', 'projects', body);
      },
      remove: function (projectId) {
        return send('DELETE', 'projects', { project_id: projectId });
      }
    },

    // ---- Навыки (этап 5.2) ----
    skills: {
      list: function () { return get('skills'); },
      get: function (name) { return get('skills', { name: name }); },
      create: function (skill) {
        return send('POST', 'skills', {
          name: skill.name || '',
          description: skill.description || '',
          code: skill.code || '',
          icon: skill.icon || '🧠',
          enabled: skill.enabled !== false
        });
      },
      update: function (name, patch) {
        var body = { name: name };
        ['description', 'code', 'icon', 'enabled'].forEach(function (k) {
          if (patch[k] !== undefined) body[k] = patch[k];
        });
        return send('PUT', 'skills', body);
      },
      remove: function (name) { return send('DELETE', 'skills', { name: name }); }
    },

    // ---- Инструменты (этап 5.3) ----
    tools: {
      list: function () { return get('tools'); },
      get: function (name) { return get('tools', { name: name }); },
      create: function (tool) {
        return send('POST', 'tools', {
          name: tool.name || '',
          type: tool.type || 'http',
          endpoint: tool.endpoint || '',
          description: tool.description || '',
          code: tool.code || '',
          icon: tool.icon || '🛠️',
          enabled: tool.enabled !== false
        });
      },
      update: function (name, patch) {
        var body = { name: name };
        ['type', 'endpoint', 'description', 'code', 'icon', 'enabled'].forEach(function (k) {
          if (patch[k] !== undefined) body[k] = patch[k];
        });
        return send('PUT', 'tools', body);
      },
      remove: function (name) { return send('DELETE', 'tools', { name: name }); }
    },

    // ---- Базы данных (этап 5.4) ----
    databases: {
      list: function () { return get('databases'); },
      get: function (name) { return get('databases', { name: name }); },
      view: function (name) { return get('databases', { name: name, view: 1 }); },
      table: function (name, table) { return get('databases', { name: name, table: table }); },
      create: function (db) {
        return send('POST', 'databases', {
          name: db.name || '',
          type: db.type || 'sqlite',
          path: db.path || '',
          description: db.description || '',
          enabled: db.enabled !== false
        });
      },
      update: function (name, patch) {
        var body = { name: name };
        ['path', 'file', 'description', 'type', 'enabled'].forEach(function (k) {
          if (patch[k] !== undefined) body[k] = patch[k];
        });
        return send('PUT', 'databases', body);
      },
      remove: function (name) { return send('DELETE', 'databases', { name: name }); }
    },

    // ---- Файлы (этап 6.2/6.4) ----
    files: {
      // Список файлов output/ (имя, размер, дата).
      list: function () { return get('files/list'); },
      // Скачивает файл из output/ по имени и инициирует сохранение в браузере.
      // Скачивание — бинарный GET с Bearer-токеном, поэтому идём через fetch→blob.
      download: function (name) {
        var url = '/api/files/download?id=' + encodeURIComponent(name);
        return fetch(url, { headers: authHeaders() }).then(function (res) {
          if (!res.ok) {
            throw new Error('HTTP ' + res.status);
          }
          return res.blob();
        }).then(function (blob) {
          var a = document.createElement('a');
          var objUrl = URL.createObjectURL(blob);
          a.href = objUrl;
          a.download = name;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          setTimeout(function () { URL.revokeObjectURL(objUrl); }, 1000);
          return true;
        });
      },
      // Загружает файл(ы) для анализа (multipart/form-data во временную папку uploads/).
      upload: function (fileList, chatId) {
        var form = new FormData();
        for (var i = 0; i < fileList.length; i++) {
          form.append('files', fileList[i], fileList[i].name);
        }
        return fetch('/api/files/upload' + (chatId ? '?chat_id=' + encodeURIComponent(chatId) : ''), {
          method: 'POST',
          headers: authHeaders(),
          body: form
        }).then(parseResponse);
      },
      openFolder: function (path) {
        return get('files/open-folder', { path: path });
      }
    }
  };

  // ---- Хелперы SSE (чистые, тестируемые) ----

  // Разбирает один SSE-кадр "event: x\ndata: {...}" → {event, data} или null.
  function parseSseFrame(frame) {
    if (!frame) return null;
    var event = 'message';
    var dataLines = [];
    frame.split('\n').forEach(function (line) {
      if (line.indexOf(':') === 0) return; // комментарий/keep-alive
      if (line.indexOf('event:') === 0) {
        event = line.slice(6).trim();
      } else if (line.indexOf('data:') === 0) {
        dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    });
    if (dataLines.length === 0) return null;
    var raw = dataLines.join('\n');
    var data = raw;
    try { data = JSON.parse(raw); } catch (e) { data = raw; }
    return { event: event, data: data };
  }

  // Извлекает текст токена из data-объекта кадра token.
  function extractDelta(data) {
    if (data == null) return '';
    if (typeof data === 'string') return data;
    if (typeof data.delta === 'string') return data.delta;
    if (typeof data.text === 'string') return data.text;
    if (typeof data.content === 'string') return data.content;
    return '';
  }

  CA.api = api;
  CA.parseSseFrame = parseSseFrame; // для smoke-тестов
})();
