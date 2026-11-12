// CorporateAgent v4 — js/store.js
// Этап 3.10: клиентское состояние UI — текущий чат, список чатов, контекст,
// кеш выбранных БД/навыков/инструментов. Лёгкий pub/sub для перерисовки вью.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  var state = {
    screen: 'chat',        // 'chat' | 'notes' | 'projects' | 'settings'
    activeChatId: null,
    chats: {},             // id -> { id, title, messages: [] }
    chatOrder: [],         // порядок чатов (последние сверху)
    projects: {},          // id -> { id, name, db }
    projectOrder: [],      // порядок проектов
    activeProjectId: null, // выбранный проект (для фильтра чатов)
    notes: {},             // id -> { id, title, text, updated }
    noteOrder: [],         // порядок заметок (последние сверху)
    activeNoteId: null,
    context: {             // выбранный контекст активного чата
      databases: [],
      skills: [],
      tools: []
    },
    catalogs: {            // справочники для панели контекста
      databases: [],
      skills: [],
      tools: []
    },
    skills: {},            // name -> { name, title, description, code, enabled, preset }
    skillOrder: [],        // порядок навыков
    activeSkill: null,     // выбранный навык (в редакторе настроек)
    tools: {},             // name -> { name, ... }
    toolOrder: [],
    activeTool: null,
    databases: {},         // name -> { name, ... }
    databaseOrder: [],
    activeDatabase: null,
    streamingChatId: null,
    streaming: false
  };

  var listeners = [];

  function notify() {
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](state); } catch (e) { /* не роняем UI из-за одного вью */ }
    }
  }

  var store = {
    getState: function () { return state; },

    // Подписка на изменения: возвращает функцию отписки.
    subscribe: function (fn) {
      listeners.push(fn);
      return function () {
        var i = listeners.indexOf(fn);
        if (i >= 0) listeners.splice(i, 1);
      };
    },

    setScreen: function (screen) {
      state.screen = screen;
      notify();
    },

    // ---- Заметки (Этап 4.4/4.5) ----

    setNotes: function (list) {
      state.notes = {};
      state.noteOrder = [];
      list = list || [];
      for (var i = 0; i < list.length; i++) {
        var n = list[i];
        if (!n || !n.id) continue;
        state.notes[n.id] = n;
        state.noteOrder.push(n.id);
      }
      if (!state.activeNoteId || !state.notes[state.activeNoteId]) {
        state.activeNoteId = state.noteOrder[0] || null;
      }
      notify();
    },

    upsertNote: function (note) {
      if (!note || !note.id) return;
      if (!state.notes[note.id]) {
        state.noteOrder.unshift(note.id);
      }
      state.notes[note.id] = note;
      notify();
    },

    removeNote: function (noteId) {
      delete state.notes[noteId];
      state.noteOrder = state.noteOrder.filter(function (id) { return id !== noteId; });
      if (state.activeNoteId === noteId) {
        state.activeNoteId = state.noteOrder[0] || null;
      }
      notify();
    },

    setActiveNote: function (noteId) {
      state.activeNoteId = noteId;
      notify();
    },

    getActiveNote: function () {
      return state.activeNoteId ? state.notes[state.activeNoteId] : null;
    },

    // ---- Проекты (Этап 4.2) ----

    setProjects: function (list) {
      state.projects = {};
      state.projectOrder = [];
      list = list || [];
      for (var i = 0; i < list.length; i++) {
        var p = list[i];
        if (!p || !p.id) continue;
        state.projects[p.id] = p;
        state.projectOrder.push(p.id);
      }
      notify();
    },

    setActiveProject: function (projectId) {
      state.activeProjectId = projectId;
      notify();
    },

    // ---- Чаты ----

    setActiveChat: function (chatId) {
      state.activeChatId = chatId;
      var chat = state.chats[chatId];
      state.context = {
        databases: (chat && chat.selected_databases) || [],
        skills: (chat && chat.selected_skills) || [],
        tools: (chat && chat.selected_tools) || []
      };
      notify();
    },

    getActiveChat: function () {
      return state.activeChatId ? state.chats[state.activeChatId] : null;
    },

    // Заводит (или обновляет) чат в кеше.
    upsertChat: function (chat) {
      if (!chat || !chat.id) return;
      var existed = !!state.chats[chat.id];
      state.chats[chat.id] = chat;
      if (!existed) {
        state.chatOrder.unshift(chat.id);
      }
      if (!state.activeChatId) {
        state.activeChatId = chat.id;
      }
      notify();
    },

    // Обновляет поля чата в кеше.
    updateChat: function (chatId, patch) {
      var chat = state.chats[chatId];
      if (!chat) return;
      for (var k in patch) {
        if (Object.prototype.hasOwnProperty.call(patch, k)) {
          chat[k] = patch[k];
        }
      }
      notify();
    },

    // Убирает чат из кеша.
    removeChat: function (chatId) {
      delete state.chats[chatId];
      state.chatOrder = state.chatOrder.filter(function (id) { return id !== chatId; });
      if (state.activeChatId === chatId) {
        state.activeChatId = state.chatOrder[0] || null;
      }
      notify();
    },

    setChatMessages: function (chatId, messages) {
      var chat = state.chats[chatId];
      if (!chat) {
        chat = { id: chatId, messages: [] };
        state.chats[chatId] = chat;
        state.chatOrder.unshift(chatId);
      }
      chat.messages = messages || [];
      notify();
    },

    appendMessage: function (chatId, msg) {
      var chat = state.chats[chatId];
      if (!chat) {
        chat = { id: chatId, messages: [] };
        state.chats[chatId] = chat;
        state.chatOrder.unshift(chatId);
      }
      if (!chat.messages) chat.messages = [];
      chat.messages.push(msg);
      notify();
    },

    removeMessage: function (chatId, messageId) {
      var chat = state.chats[chatId];
      if (!chat || !chat.messages) return;
      for (var i = 0; i < chat.messages.length; i++) {
        if (chat.messages[i].id === messageId) {
          chat.messages.splice(i, 1);
          break;
        }
      }
      notify();
    },

    updateMessage: function (chatId, messageId, patch) {
      var chat = state.chats[chatId];
      if (!chat || !chat.messages) return;
      for (var i = 0; i < chat.messages.length; i++) {
        if (chat.messages[i].id === messageId) {
          for (var k in patch) {
            if (Object.prototype.hasOwnProperty.call(patch, k)) {
              chat.messages[i][k] = patch[k];
            }
          }
          break;
        }
      }
      notify();
    },

    // ---- Контекст ----
    setContext: function (ctx) {
      state.context = {
        databases: (ctx && ctx.databases) || [],
        skills: (ctx && ctx.skills) || [],
        tools: (ctx && ctx.tools) || []
      };
      var chat = state.chats[state.activeChatId];
      if (chat) {
        chat.selected_databases = state.context.databases;
        chat.selected_skills = state.context.skills;
        chat.selected_tools = state.context.tools;
      }
      notify();
    },

    setCatalogs: function (catalogs) {
      state.catalogs = {
        databases: (catalogs && catalogs.databases) || [],
        skills: (catalogs && catalogs.skills) || [],
        tools: (catalogs && catalogs.tools) || []
      };
      notify();
    },

    // ---- Навыки (этап 5.2) ----

    setSkills: function (list) {
      state.skills = {};
      state.skillOrder = [];
      list = list || [];
      for (var i = 0; i < list.length; i++) {
        var s = list[i];
        if (!s || !s.name) continue;
        state.skills[s.name] = s;
        state.skillOrder.push(s.name);
      }
      if (!state.activeSkill || !state.skills[state.activeSkill]) {
        state.activeSkill = state.skillOrder[0] || null;
      }
      notify();
    },

    upsertSkill: function (skill) {
      if (!skill || !skill.name) return;
      if (!state.skills[skill.name]) state.skillOrder.push(skill.name);
      state.skills[skill.name] = skill;
      notify();
    },

    removeSkill: function (name) {
      delete state.skills[name];
      state.skillOrder = state.skillOrder.filter(function (n) { return n !== name; });
      if (state.activeSkill === name) state.activeSkill = state.skillOrder[0] || null;
      notify();
    },

    setActiveSkill: function (name) { state.activeSkill = name; notify(); },
    getActiveSkill: function () { return state.activeSkill ? state.skills[state.activeSkill] : null; },

    // ---- Инструменты (этап 5.3) ----

    setTools: function (list) {
      state.tools = {};
      state.toolOrder = [];
      list = list || [];
      for (var i = 0; i < list.length; i++) {
        var t = list[i];
        if (!t || !t.name) continue;
        state.tools[t.name] = t;
        state.toolOrder.push(t.name);
      }
      if (!state.activeTool || !state.tools[state.activeTool]) {
        state.activeTool = state.toolOrder[0] || null;
      }
      notify();
    },

    upsertTool: function (tool) {
      if (!tool || !tool.name) return;
      if (!state.tools[tool.name]) state.toolOrder.push(tool.name);
      state.tools[tool.name] = tool;
      notify();
    },

    removeTool: function (name) {
      delete state.tools[name];
      state.toolOrder = state.toolOrder.filter(function (n) { return n !== name; });
      if (state.activeTool === name) state.activeTool = state.toolOrder[0] || null;
      notify();
    },

    setActiveTool: function (name) { state.activeTool = name; notify(); },
    getActiveTool: function () { return state.activeTool ? state.tools[state.activeTool] : null; },

    // ---- Базы данных (этап 5.4) ----

    setDatabases: function (list) {
      state.databases = {};
      state.databaseOrder = [];
      list = list || [];
      for (var i = 0; i < list.length; i++) {
        var d = list[i];
        if (!d || !d.name) continue;
        state.databases[d.name] = d;
        state.databaseOrder.push(d.name);
      }
      if (!state.activeDatabase || !state.databases[state.activeDatabase]) {
        state.activeDatabase = state.databaseOrder[0] || null;
      }
      notify();
    },

    upsertDatabase: function (db) {
      if (!db || !db.name) return;
      if (!state.databases[db.name]) state.databaseOrder.push(db.name);
      state.databases[db.name] = db;
      notify();
    },

    removeDatabase: function (name) {
      delete state.databases[name];
      state.databaseOrder = state.databaseOrder.filter(function (n) { return n !== name; });
      if (state.activeDatabase === name) state.activeDatabase = state.databaseOrder[0] || null;
      notify();
    },

    setActiveDatabase: function (name) { state.activeDatabase = name; notify(); },
    getActiveDatabase: function () { return state.activeDatabase ? state.databases[state.activeDatabase] : null; },

    // ---- Стриминг ----

    setStreaming: function (flag, chatId) {
      state.streaming = !!flag;
      state.streamingChatId = flag ? (chatId || state.activeChatId) : null;
      notify();
    }
  };

  CA.store = store;
})();

