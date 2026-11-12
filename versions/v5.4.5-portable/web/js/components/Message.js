// CorporateAgent v4 — js/components/Message.js
// Этап 3.11/3.12/3.14: рендер сообщения (bubble + время) и кнопок действий.
// Разметка один-в-один с прототипом v2 (классы .message/.message-avatar/.message-bubble/
// .message-time/.message-actions), но данные приходят из API, а действия дёргают api.js.
// 3.14: assistant-сообщение дополнительно рендерит structured data — «карточки полей»
// (строки вида **Метка:** значение из ответа) и карточку метаданных (usage/filePath).
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  var AVATARS = { user: '👤', assistant: '🤖', ai: '🤖' };

  // Кнопки действий: для user — копировать/редактировать/удалить,
  // для assistant — копировать/перегенерировать/в заметки/в БД.
  var USER_ACTIONS = [
    { act: 'copy', title: 'Копировать', icon: '📋' },
    { act: 'edit', title: 'Редактировать', icon: '✏️' },
    { act: 'delete', title: 'Удалить', icon: '🗑️' }
  ];
  var AI_ACTIONS = [
    { act: 'copy', title: 'Копировать', icon: '📋' },
    { act: 'regenerate', title: 'Сгенерировать заново', icon: '🔄' },
    { act: 'note', title: 'Сохранить в заметки', icon: '📝' },
    { act: 'db', title: 'Сохранить в БД', icon: '💾' }
  ];

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Минимальный markdown → HTML (жирный, курсив, code, ссылки, переводы строк,
  // простое форматирование **bold**, *italic*, `code`). Сначала экранируем HTML.
  function renderMarkdown(text) {
    var html = esc(text);
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    html = html.replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>');
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    html = html.replace(/\n/g, '<br>');
    return html;
  }

  // 6.4: кнопка «Скачать отчёт» — показывается только когда ответ содержит файл
  // (meta.filePath), который можно скачать через GET /api/files/download.
  var DOWNLOAD_ACTION = { act: 'download', title: 'Скачать отчёт', icon: '⬇️' };

  function actionsFor(role, msg) {
    if (role === 'user') return USER_ACTIONS;
    var list = AI_ACTIONS.slice();
    if (msg && msg.meta && msg.meta.filePath) {
      list.push(DOWNLOAD_ACTION);
    }
    return list;
  }
  function actionsHtml(role, msg) {
    return actionsFor(role, msg).map(function (a) {
      return '<button class="message-action-btn" data-act="' + a.act + '" title="'
        + esc(a.title) + '">' + a.icon + '</button>';
    }).join('');
  }

  // ---- 3.14: structured data в assistant-сообщениях ----

  // Извлекает из текста строки-поля вида `**Метка:** значение` (по одной на строку).
  // Возвращает массив {label, value}. Используется для «карточки полей» прототипа
  // (Цель проекта / Сроки / Бюджет …), где данные идут парами «метка: значение».
  function extractFields(text) {
    var out = [];
    if (!text) return out;
    var lines = String(text).split('\n');
    for (var i = 0; i < lines.length; i++) {
      // **Метка:** значение  (двоеточие внутри ** … ** или сразу после)
      var m = lines[i].match(/^\s*\*\*([^*]+?)\*\*\s*[:：]?\s*(.*)$/);
      if (!m) continue;
      var label = m[1].replace(/[:：]\s*$/, '').trim();
      var value = (m[2] || '').trim();
      if (label && value) {
        out.push({ label: label, value: value });
      }
    }
    return out;
  }

  // HTML «карточки полей» — таблица метка/значение (класс .message-fields).
  function fieldsCardHtml(fields) {
    if (!fields || !fields.length) return '';
    var rows = fields.map(function (f) {
      return '<div class="message-field-row">'
        + '<span class="message-field-label">' + esc(f.label) + '</span>'
        + '<span class="message-field-value">' + renderMarkdown(f.value) + '</span>'
        + '</div>';
    }).join('');
    return '<div class="message-fields">' + rows + '</div>';
  }

  // HTML карточки метаданных ответа (usage / filePath) — «⚡ Использовано», «📄 Файл».
  function metaCardHtml(meta) {
    if (!meta) return '';
    var rows = '';
    if (meta.usage && meta.usage.length) {
      var used = meta.usage.map(function (u) {
        var kind = u.type || u.kind || '';
        var name = u.name || u.title || '';
        return esc((kind ? kind + ' ' : '') + name);
      }).join(' · ');
      rows += '<div class="message-field-row message-usage">'
        + '<span class="message-field-label">⚡ Использовано</span>'
        + '<span class="message-field-value">' + used + '</span></div>';
    }
    if (meta.filePath) {
      rows += '<div class="message-field-row">'
        + '<span class="message-field-label">📄 Файл</span>'
        + '<span class="message-field-value">' + esc(meta.filePath) + '</span></div>';
    }
    return rows ? '<div class="message-fields">' + rows + '</div>' : '';
  }

  // Собирает все structured-блоки для assistant-сообщения (поля + метаданные).
  function structuredHtml(msg) {
    if (msg.role === 'user') return '';
    return metaCardHtml(msg.meta) + fieldsCardHtml(msg.fields || extractFields(msg.text));
  }

  var Message = {
    renderMarkdown: renderMarkdown,
    esc: esc,
    // Экспорт для smoke-тестов (3.14): разбор structured data и сборка карточек.
    extractFields: extractFields,
    fieldsCardHtml: fieldsCardHtml,
    metaCardHtml: metaCardHtml,
    structuredHtml: structuredHtml,

    // Возвращает HTML одного сообщения. msg = {id, role, text, time, edited, pending, meta, fields}
    render: function (msg) {
      var role = msg.role === 'assistant' ? 'ai' : msg.role;
      var avatar = msg.role === 'user' ? AVATARS.user : AVATARS.ai;
      var body = msg.pending ? '<em>' + esc(msg.text || '') + '</em>' : renderMarkdown(msg.text);
      var edited = msg.edited ? ' <span class="message-edited" title="Изменено">(изм.)</span>' : '';
      if(msg.cancelled)edited += ' <span class="message-cancelled">(остановлено)</span>';
      var structured = msg.pending ? '' : structuredHtml(msg);
      return ''
        + '<div class="message ' + esc(role) + '" data-message-id="' + esc(msg.id || '') + '">'
        +   '<div class="message-avatar">' + avatar + '</div>'
        +   '<div class="message-content">'
        +     '<div class="message-bubble">' + body + '</div>'
        +     structured
        +     '<div class="message-time">' + esc(msg.time || '') + edited + '</div>'
        +   '</div>'
        +   '<div class="message-actions">' + actionsHtml(msg.role, msg) + '</div>'
        + '</div>';
    },

    // Навешивает обработчики на кнопки действий внутри контейнера.
    // handlers = {onCopy, onEdit, onDelete, onRegenerate, onNote, onDb}
    bindActions: function (container, handlers) {
      handlers = handlers || {};
      container.querySelectorAll('.message-action-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var messageEl = btn.closest('.message');
          if (!messageEl) return;
          var messageId = messageEl.getAttribute('data-message-id');
          var act = btn.getAttribute('data-act');
          var fn = handlers['on' + act.charAt(0).toUpperCase() + act.slice(1)];
          if (fn) fn(messageEl, messageId, btn);
        });
      });
    }
  };

  CA.Message = Message;
})();

