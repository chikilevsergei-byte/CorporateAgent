// CorporateAgent v4 — js/views/SettingsView.js
// Этап 5.2-F/5.3-F: раздел «Настройки» → вкладки «Навыки» и «Инструменты».
// Этап 6.3: вкладки «Модель» и «Интерфейс» (model/config.json + interface/settings.json).
// Перенос из прототипа v2: sidebar-список + редактор (skill-item/skill-editor).
// Данные — через api.skills* / api.tools* / api.settings*, состояние — в store.
(function () {
  'use strict';

  var CA = (window.CorporateAgent = window.CorporateAgent || {});

  function el(id) { return document.getElementById(id); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Подпись/код провайдера → код (совместимо с SettingsRoutes.providerCode,
  // SettingsPanelModel.providerCode). Дублируется на клиенте для мгновенной
  // реакции на смену провайдера до сохранения.
  function providerCode(labelOrCode) {
    var s = String(labelOrCode == null ? '' : labelOrCode);
    if (s.indexOf('Яндекс') >= 0 || s.toLowerCase() === 'yandex') return 'yandex';
    if (s.indexOf('Alibaba') >= 0 || s.indexOf('Qwen') >= 0 || s.toLowerCase() === 'qwen') return 'qwen';
    if (s.indexOf('Друг') >= 0 || s.toLowerCase() === 'custom') return 'custom';
    return 'deepseek';
  }

  function providerLabel(code) {
    if (code === 'yandex') return 'Яндекс ИИ студия';
    if (code === 'qwen') return 'Alibaba Qwen';
    if (code === 'custom') return 'Другой (OpenAI-совместимый)';
    return 'DeepSeek';
  }

  // Список моделей провайдера (совместимо с SettingsRoutes.modelsFor).
  function modelsFor(code) {
    if (code === 'yandex') return ['yandexgpt/latest', 'yandexgpt-lite/latest'];
    if (code === 'qwen') return ['qwen-max', 'qwen-plus', 'qwen-turbo'];
    if (code === 'custom') return ['gpt-4o', 'gpt-4o-mini', 'custom-model'];
    return ['deepseek-chat', 'deepseek-reasoner'];
  }

  // Базовый URL по умолчанию (совместимо с Config.defaultBaseUrl; хранится без
  // /chat/completions). Для custom — пусто (вводит пользователь).
  function defaultBaseUrl(code) {
    if (code === 'yandex') return 'https://ai.api.cloud.yandex.net/v1';
    if (code === 'qwen') return 'https://dashscope.aliyuncs.com/compatible-mode/v1';
    if (code === 'custom') return '';
    return 'https://api.deepseek.com';
  }

  var SettingsView = {
    _bound: false,
    _modelBound: false,
    _interfaceBound: false,
    _saveBound: false,

    init: function () {
      var self = this;
      if (CA.store) {
        CA.store.subscribe(function (st) {
          if (st.screen === 'settings') self.render();
        });
      }
      this.bindSkills();
      this.bindTools();
      this.loadSkills();
      this.loadTools();
      this.bindModel();
      this.bindInterface();
      this.bindSave();
      this.loadSettings();
      var modal=el('settingsModal');
      this.lockModel();
      modal.addEventListener('modal:open',function(){self.lockModel();var tab=document.querySelector('[onclick*="tabInterface"]');switchSettingsTab({currentTarget:tab},'tabInterface');});
      modal.addEventListener('modal:close',function(){self.lockModel();});
      el('modelUnlockBtn').onclick=function(){self.unlockModel();};
      el('modelPassword').onkeydown=function(e){if(e.key==='Enter'){e.preventDefault();self.unlockModel();}};
      if(modal){modal.addEventListener('modal:open',function(){self.loadSettings();});modal.addEventListener('modal:close',function(e){if(e.detail.reason!=='saved'&&self._settingsSnapshot)self.applySettings(self._settingsSnapshot);});}
      el('backupCreateBtn').addEventListener('click',function(){self.createBackup();});
      el('backupRestoreBtn').addEventListener('click',function(){self.openBackups();});
    },

    // ---- Модель + Интерфейс (6.3) ----
    lockModel:function(){this._lockEpoch=(this._lockEpoch||0)+1;this._modelPassword='';el('modelPassword').value='';el('modelEditor').hidden=true;el('modelLockPanel').hidden=false;el('modelUnlockError').textContent='';},
    unlockModel:function(){
      var self=this,password=el('modelPassword').value,epoch=this._lockEpoch,button=el('modelUnlockBtn');button.disabled=true;
      CA.api.post('settings/model/unlock',{_model_password:password}).then(function(){if(epoch!==self._lockEpoch||!el('settingsModal').classList.contains('active'))return;self._modelPassword=password;el('modelPassword').value='';el('modelLockPanel').hidden=true;el('modelEditor').hidden=false;el('settingsSaveBtn').hidden=false;el('apiKey').focus();if(providerCode(el('settingsProvider').value)==='yandex'&&el('settingsFolderId').value&&(self._settingsSnapshot?.api_key_configured||el('apiKey').value))self.refreshModels();}).catch(function(e){if(epoch!==self._lockEpoch)return;el('modelUnlockError').textContent=e.message;el('modelPassword').select();}).finally(function(){button.disabled=false;});
    },
    reflectYandex:function(){
      var active=providerCode(el('settingsProvider').value)==='yandex';el('yandexFields').hidden=!active;
      var name=el('settingsModelUri').value.trim(),folder=el('settingsFolderId').value.trim();
      el('yandexUriPreview').textContent=name.includes('://')?name:'gpt://'+(folder||'<каталог>')+'/'+(name||'<модель>');
      el('settingsTemperature').max=active?'1':'2';
    },
    refreshModels:function(){
      if(!this._modelPassword||this._modelsLoading)return;
      var self=this,epoch=this._lockEpoch,button=el('settingsRefreshModels'),status=el('settingsModelsStatus');
      var body={_model_password:this._modelPassword,provider:el('settingsProvider').value,base_url:el('settingsBaseUrl').value,api_key:el('apiKey').value,folder_id:el('settingsFolderId').value.trim(),auth_type:el('settingsAuthType').value};
      var source=[body.provider,body.base_url,body.folder_id,body.auth_type].join('|');
      this._modelsLoading=true;button.disabled=true;status.textContent='Загружаем каталог моделей…';
      return CA.api.post('settings/models',body).then(function(data){
        if(epoch!==self._lockEpoch||source!==[el('settingsProvider').value,el('settingsBaseUrl').value,el('settingsFolderId').value.trim(),el('settingsAuthType').value].join('|'))return;
        var current=el('settingsModelUri').value.trim();if(current&&!current.includes('://'))current='gpt://'+body.folder_id+'/'+current;
        self._providerModels=data.models;self.fillModels('yandex',current);
        status.textContent='Получено из API: '+data.models.length+' моделей. Для чата выберите текстовую модель; другие типы могут требовать отдельного API.';
      }).catch(function(e){if(epoch===self._lockEpoch)status.textContent=e.message+' Текущий выбор сохранён; URI можно ввести вручную.';}).finally(function(){self._modelsLoading=false;button.disabled=false;});
    },

    loadSettings: function () {
      var self = this;
      if (!CA.api || !CA.api.get) return;
      return CA.api.get('settings').then(function (data) {
        self.applySettings(data || {});
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось загрузить настройки');
      });
    },

    // Раскладывает DTO настроек по полям вкладок «Модель» и «Интерфейс».
    applySettings: function (d) {
      this._settingsSnapshot=JSON.parse(JSON.stringify(d));
      // Провайдер и URL.
      var prov = el('settingsProvider');
      if (prov) prov.value = providerLabel(d.provider);
      var base = el('settingsBaseUrl');
      if (base) base.value = d.base_url || '';
      // Модели провайдера + текущая модель.
      this.fillModels(providerCode(d.provider), d.model);
      el('settingsFolderId').value=d.folder_id||'';
      el('settingsAuthType').value=d.auth_type||'api_key';
      el('settingsModelUri').value=d.provider==='yandex'?(d.model||'yandexgpt/latest').replace(/^gpt:\/\/[^/]+\//,''):'';
      this.reflectYandex();
      // Ключ, temperature, max tokens.
      var key = el('apiKey');
      if (key) { key.value = ''; key.placeholder = d.api_key_configured ? 'Ключ сохранён; введите новый для замены' : ''; }
      var temp = el('settingsTemperature');
      if (temp) {
        var tv = (typeof d.temperature === 'number') ? d.temperature : 0.1;
        temp.value = tv;
        var tvLabel = el('tempValue');
        if (tvLabel) tvLabel.textContent = Number(tv).toFixed(1);
      }
      var maxTok = el('settingsMaxTokens');
      if (maxTok) maxTok.value = d.max_tokens || 1000;
      // Интерфейс.
      this.setChecked('settingsAutoUpdateDb', d.auto_update_db);
      this.setChecked('settingsShowUsage', d.show_usage_in_chat);
      document.documentElement.dataset.showUsage=String(d.show_usage_in_chat!==false);
      this.setChecked('settingsConfirmDbWrite', d.confirm_db_write);
      var limit = el('tokenLimit');
      if (limit) limit.value = d.daily_limit_tokens || 0;
      var display=document.querySelector('.token-limit-display');
      if(display){var used=Number(d.tokens_today)||0,cap=Number(d.daily_limit_tokens)||0;display.textContent='Сегодня учтено: '+used.toLocaleString('ru-RU')+' тк · лимит: '+(cap?cap.toLocaleString('ru-RU'):'без лимита')+(cap?' · осталось: '+Math.max(0,cap-used).toLocaleString('ru-RU'):'');}
      this.setActiveTheme(d.theme || 'green');
    },

    setChecked: function (id, value) {
      var c = el(id);
      if (c) c.checked = !!value;
    },

    setActiveTheme: function (theme) {
      document.documentElement.dataset.theme=theme||'green';
      var box = el('settingsThemeOptions');
      if (!box) return;
      box.querySelectorAll('.theme-option').forEach(function (b) {
        b.classList.toggle('active', b.getAttribute('data-theme') === theme);
      });
    },

    fillModels: function (code, selected) {
      var sel = el('settingsModel');
      if (!sel) return;
      var models = code==='yandex'&&this._providerModels?.length?this._providerModels:modelsFor(code);
      // Сохраняем текущую модель, если её нет в списке провайдера.
      if (selected && models.indexOf(selected) < 0) {
        models = models.concat([selected]);
      }
      sel.innerHTML = models.map(function (m) {
        var other=code==='yandex'&&(m.startsWith('emb://')||m.includes('/speech-realtime-'));
        return '<option value="'+esc(m)+'"'+(other?' disabled':'')+'>' + esc(m)+(other?' — отдельный API (не чат)':'')+'</option>';
      }).join('');
      if (selected) sel.value = selected;
    },

    bindModel: function () {
      var self = this;
      if (this._modelBound) return;
      this._modelBound = true;

      var prov = el('settingsProvider');
      if (prov) {
        prov.addEventListener('change', function () {
          var code = providerCode(prov.value);self._providerModels=null;
          el('apiKey').value='';
          el('apiKey').placeholder=self._settingsSnapshot?.api_key_configured&&self._settingsSnapshot.provider===code?'Ключ сохранён; введите новый для замены':'Введите ключ выбранного провайдера';
          // Подставляем URL провайдера и список его моделей (как SettingsPanelModel).
          var base = el('settingsBaseUrl');
          if (base) base.value = defaultBaseUrl(code);
          self.fillModels(code, modelsFor(code)[0]);
          el('settingsModelUri').value=code==='yandex'?modelsFor(code)[0]:'';
          if(code==='yandex'&&Number(el('settingsTemperature').value)>1)el('settingsTemperature').value='1';
          self.reflectYandex();
        });
      }
      el('settingsModel').addEventListener('change',function(){if(providerCode(prov.value)==='yandex')el('settingsModelUri').value=el('settingsModel').value;self.reflectYandex();});
      ['settingsFolderId','settingsModelUri'].forEach(function(id){el(id).addEventListener('input',function(){self.reflectYandex();});});

      el('settingsRefreshModels').onclick=function(){self.refreshModels();};
      var testBtn = el('settingsTestLlmBtn');
      if (testBtn) {
        testBtn.addEventListener('click', function () { self.testLlm(); });
      }
    },

    bindInterface: function () {
      var self = this;
      if (this._interfaceBound) return;
      this._interfaceBound = true;
      var box = el('settingsThemeOptions');
      if (box) {
        box.querySelectorAll('.theme-option').forEach(function (btn) {
          btn.addEventListener('click', function () {
            self.setActiveTheme(btn.getAttribute('data-theme'));
          });
        });
      }
    },

    bindSave: function () {
      var self = this;
      if (this._saveBound) return;
      this._saveBound = true;
      var saveBtn = el('settingsSaveBtn');
      if (saveBtn) saveBtn.addEventListener('click', function () {var tab=document.querySelector('#settingsModal .tab-content.active')?.id;if(tab==='tabSkills')self.saveSkill();else if(tab==='tabTools')self.saveTool();else self.saveSettings();});
    },

    // Собирает изменяемые поля вкладок и сохраняет через PUT /api/settings.
    saveSettings: function () {
      var self = this;
      if (!CA.api || !CA.api.put) return;
      var prov = el('settingsProvider');
      var body = {
        _model_password:this._modelPassword,
        folder_id:el('settingsFolderId').value.trim(),auth_type:el('settingsAuthType').value,
        theme:(document.querySelector('#settingsThemeOptions .theme-option.active')||{}).dataset?.theme||'green',
        provider: prov ? prov.value : undefined,
        base_url: el('settingsBaseUrl') ? el('settingsBaseUrl').value : undefined,
        model: providerCode(prov.value)==='yandex'?el('settingsModelUri').value.trim():el('settingsModel').value,
        api_key: el('apiKey') ? el('apiKey').value : undefined,
        temperature: el('settingsTemperature') ? parseFloat(el('settingsTemperature').value) : undefined,
        max_tokens: el('settingsMaxTokens') ? Number(el('settingsMaxTokens').value) : undefined,
        auto_update_db: el('settingsAutoUpdateDb') ? el('settingsAutoUpdateDb').checked : undefined,
        show_usage_in_chat: el('settingsShowUsage') ? el('settingsShowUsage').checked : undefined,
        confirm_db_write: el('settingsConfirmDbWrite') ? el('settingsConfirmDbWrite').checked : undefined,
        daily_limit_tokens: el('tokenLimit') ? Number(el('tokenLimit').value) : undefined
      };
      if(this._modelPassword){
      if(!Number.isFinite(body.temperature)||body.temperature<0||body.temperature>(providerCode(prov.value)==='yandex'?1:2)||!Number.isInteger(body.max_tokens)||body.max_tokens<1||!Number.isInteger(body.daily_limit_tokens)||body.daily_limit_tokens<0){CA.toast('warning','Проверьте настройки','Проверьте температуру, длину ответа и дневной лимит.');return;}
      try{var url=new URL(body.base_url);if(!['http:','https:'].includes(url.protocol))throw Error();}catch(e){CA.toast('warning','Адрес модели','Укажите полный HTTP или HTTPS адрес API');return;}
      }else{['provider','base_url','model','api_key','temperature','max_tokens','daily_limit_tokens','folder_id','auth_type','_model_password'].forEach(function(key){delete body[key];});}
      CA.api.put('settings', body).then(function (data) {
        self.applySettings(data || {});
        CA.toast && CA.toast('success', 'Сохранено', 'Настройки применены');
        if (window.closeModal) window.closeModal('settingsModal','saved');
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось сохранить настройки');
      });
    },

    // Пробный запрос к LLM с текущими (несохранёнными) override-полями.
    testLlm: function () {
      if(!this._modelPassword){CA.toast('warning','Модель','Сначала введите пароль раздела');return;}
      if(this._testing)return;this._testing=true;var button=el('settingsTestLlmBtn');button.disabled=true;
      if (!CA.api || !CA.api.post) return;
      var prov = el('settingsProvider');
      var body = {
        provider: prov ? prov.value : undefined,
        _model_password:this._modelPassword,
        folder_id:el('settingsFolderId').value.trim(),auth_type:el('settingsAuthType').value,
        base_url: el('settingsBaseUrl') ? el('settingsBaseUrl').value : undefined,
        model:providerCode(prov.value)==='yandex'?el('settingsModelUri').value.trim():el('settingsModel').value,
        api_key: el('apiKey') ? el('apiKey').value : undefined
      };
      CA.toast && CA.toast('success', 'Проверка', 'Отправляем пробный запрос к LLM…');
      el('settingsConnectionStatus').textContent='Проверяем подключение коротким запросом…';
      CA.api.post('settings/test-llm', body).then(function (data) {
        var ok = data && data.ok;
        var detail=(data&&data.detail)||'';
        if(!ok){var hint=/HTTP 401/.test(detail)?'Проверьте ключ или срок действия IAM-токена. ':/HTTP 403/.test(detail)?'Проверьте доступ сервисного аккаунта к каталогу и моделям. ':/HTTP 404/.test(detail)?'Проверьте адрес API и URI модели. ':/HTTP 429/.test(detail)?'Достигнута квота API; повторите позже. ':'';detail=hint+detail;}
        el('settingsConnectionStatus').textContent=(ok?'Подключение работает. ':'Ошибка подключения. ')+detail;
        CA.toast && CA.toast(ok ? 'success' : 'error',
          ok ? 'Подключение работает' : 'Ошибка подключения',
          detail);
      }).catch(function (e) {
        el('settingsConnectionStatus').textContent=e.message||'Не удалось проверить подключение';
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось проверить подключение');
      }).finally(function(){CA.SettingsView._testing=false;button.disabled=false;});
    },

    createBackup:function(){
      var button=el('backupCreateBtn');button.disabled=true;
      return CA.api.post('settings/backups',{action:'create'}).then(function(data){CA.toast('success','Копия создана','Настройки интерфейса сохранены: '+data.name);}).catch(function(e){CA.toast('error','Резервная копия',e.message);}).finally(function(){button.disabled=false;});
    },
    openBackups:function(){
      var self=this,modal=el('backupModal');
      if(!modal){modal=document.createElement('div');modal.id='backupModal';modal.className='modal-overlay';modal.innerHTML='<div class="modal"><div class="modal-header"><div class="modal-title">Восстановление настроек интерфейса</div><button class="modal-close" aria-label="Закрыть">×</button></div><div class="modal-body"><p>Восстанавливаются тема, дневной лимит и переключатели интерфейса. Модель, API-ключи и чаты не изменяются.</p><label for="backupSelect">Резервная копия</label><select id="backupSelect"></select><p id="backupStatus"></p></div><div class="modal-footer"><button id="backupCancel" class="btn-secondary">Отмена</button><button id="backupApply" class="btn-primary">Восстановить</button></div></div>';document.body.appendChild(modal);modal.querySelector('.modal-close').onclick=el('backupCancel').onclick=function(){closeModal('backupModal');};el('backupApply').onclick=function(){self.restoreBackup();};}
      el('backupApply').disabled=true;el('backupStatus').textContent='Загрузка…';openModal('backupModal');
      return CA.api.get('settings/backups').then(function(data){var select=el('backupSelect');select.textContent='';(data.backups||[]).forEach(function(item){var option=document.createElement('option');option.value=item.name;option.textContent=new Date(item.created).toLocaleString('ru-RU')+' — '+item.name;select.appendChild(option);});el('backupApply').disabled=!select.options.length;select.disabled=!select.options.length;el('backupStatus').textContent=select.options.length?'Перед восстановлением автоматически сохраняется текущая копия.':'Копий пока нет. Сначала создайте копию настроек.';}).catch(function(e){el('backupStatus').textContent=e.message;});
    },
    restoreBackup:function(){
      var self=this,name=el('backupSelect').value;if(!name)return;
      if(!confirm('Восстановить настройки интерфейса из выбранной копии? Текущие настройки также будут сохранены.'))return;
      el('backupApply').disabled=true;
      return CA.api.post('settings/backups',{action:'restore',name:name}).then(function(data){self.applySettings(data);closeModal('backupModal','saved');CA.toast('success','Настройки восстановлены','Изменения применены и сохранены.');}).catch(function(e){CA.toast('error','Восстановление',e.message);}).finally(function(){el('backupApply').disabled=false;});
    },

    // ---- Навыки (5.2) ----

    loadSkills: function () {
      var self = this;
      if (!CA.api || !CA.api.skills) return;
      CA.api.skills.list().then(function (data) {
        CA.store.setSkills((data && data.skills) || []);
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось загрузить навыки');
      });
    },

    render: function () {
      this.renderSkills();
      this.renderTools();
    },

    renderSkills: function () {
      var self=this;
      var list = el('skillsList');
      if (!list || !CA.store) return;
      var st = CA.store.getState();
      list.innerHTML = st.skillOrder.map(function (name) {
        var s = st.skills[name];
        var active = name === st.activeSkill ? ' active' : '';
        var icon = s.icon || '🧠';
        var title = s.title || name;
        return '<div class="skill-item' + active + '" data-skill-name="' + esc(name) + '">' +
          '<div class="skill-item-icon">' + esc(icon) + '</div>' +
          '<div class="skill-item-title">' + esc(title) + '</div>' +
        '</div>';
      }).join('') || '<div class="skill-empty">Нет навыков</div>';

      list.querySelectorAll('.skill-item').forEach(function (item) {
        item.addEventListener('click', function () {
          CA.store.setActiveSkill(item.getAttribute('data-skill-name'));
          self.renderSkills();
        });
      });
      this.renderSkillEditor();
    },

    renderSkillEditor: function () {
      var st = CA.store.getState();
      var s = st.activeSkill ? st.skills[st.activeSkill] : null;
      var nameI = el('skillName');
      var descI = el('skillDesc');
      var codeI = el('skillCode');
      var enI = el('skillEnabled');
      var hint = el('skillHint');
      if (!nameI) return;
      if (s) {
        nameI.value = s.name || '';
        descI.value = s.description || '';
        codeI.value = s.code || '';
        enI.checked = s.enabled !== false;
        if (hint) hint.textContent = s.preset ? 'Предустановленный навык (удаление недоступно)' : '';
      } else {
        nameI.value = '';
        descI.value = '';
        codeI.value = '';
        enI.checked = true;
        if (hint) hint.textContent = '';
      }
      // Пресет — имя нельзя менять (ключ каталога защищён).
      nameI.disabled = !!s;
      var del = el('skillDeleteBtn');
      if (del) del.disabled = !!(s && s.preset);
    },

    bindSkills: function () {
      if (this._bound) return;
      this._bound = true;
      var self = this;

      var addBtn = el('skillAddBtn');
      if (addBtn) addBtn.addEventListener('click', function () {
        CA.store.setActiveSkill(null);
        self.renderSkills();
        el('skillName').focus();
      });

      var clearBtn = el('skillClearBtn');
      if (clearBtn) clearBtn.addEventListener('click', function () {
        CA.store.setActiveSkill(null);
        self.renderSkills();
      });

      var saveBtn = el('skillSaveBtn');
      if (saveBtn) saveBtn.addEventListener('click', function () { self.saveSkill(); });

      var delBtn = el('skillDeleteBtn');
      if (delBtn) delBtn.addEventListener('click', function () { self.deleteSkill(); });
    },

    saveSkill: function () {
      var self = this;
      var nameI = el('skillName');
      var name = nameI.value.trim();
      if (!name) {
        CA.toast && CA.toast('warning', 'Внимание', 'Укажите название навыка');
        return;
      }
      var st = CA.store.getState();
      var existing = st.activeSkill ? st.skills[st.activeSkill] : null;
      var payload = {
        description: el('skillDesc').value,
        code: el('skillCode').value,
        enabled: el('skillEnabled').checked
      };
      var op;
      if (existing) {
        op = CA.api.skills.update(existing.name, payload);
      } else {
        payload.name = name;
        op = CA.api.skills.create(payload);
      }
      op.then(function (saved) {
        CA.store.upsertSkill(saved);
        CA.store.setActiveSkill(saved.name);
        CA.toast && CA.toast('success', 'Сохранено', 'Навык сохранён');
        self.render();
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось сохранить навык');
      });
    },

    deleteSkill: function () {
      var self = this;
      var st = CA.store.getState();
      var s = st.activeSkill ? st.skills[st.activeSkill] : null;
      if (!s) {
        CA.toast && CA.toast('warning', 'Внимание', 'Нет выбранного навыка');
        return;
      }
      if (!confirm('Удалить навык «' + (s.title || s.name) + '»?')) return;
      CA.api.skills.remove(s.name).then(function () {
        CA.store.removeSkill(s.name);
        CA.toast && CA.toast('success', 'Удалено', 'Навык удалён');
        self.render();
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось удалить навык');
      });
    },

    // ---- Инструменты (5.3) ----

    loadTools: function () {
      var self = this;
      if (!CA.api || !CA.api.tools) return;
      CA.api.tools.list().then(function (data) {
        CA.store.setTools((data && data.tools) || []);
        self.render();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось загрузить инструменты');
      });
    },

    renderTools: function () {
      var self=this;
      var list = el('toolsList');
      if (!list || !CA.store) return;
      var st = CA.store.getState();
      list.innerHTML = st.toolOrder.map(function (name) {
        var t = st.tools[name];
        var active = name === st.activeTool ? ' active' : '';
        var icon = t.icon || '🛠️';
        var title = t.title || name;
        return '<div class="skill-item' + active + '" data-tool-name="' + esc(name) + '">' +
          '<div class="skill-item-icon">' + esc(icon) + '</div>' +
          '<div class="skill-item-title">' + esc(title) + '</div>' +
        '</div>';
      }).join('') || '<div class="skill-empty">Нет инструментов</div>';

      list.querySelectorAll('.skill-item').forEach(function (item) {
        item.addEventListener('click', function () {
          CA.store.setActiveTool(item.getAttribute('data-tool-name'));
          self.renderTools();
        });
      });
      this.renderToolEditor();
    },

    renderToolEditor: function () {
      var st = CA.store.getState();
      var t = st.activeTool ? st.tools[st.activeTool] : null;
      var nameI = el('toolName');
      var typeI = el('toolType');
      var epI = el('toolEndpoint');
      var descI = el('toolDesc');
      var enI = el('toolEnabled');
      var hint = el('toolHint');
      if (!nameI) return;
      if (t) {
        nameI.value = t.name || '';
        typeI.value = t.type || 'http';
        epI.value = t.endpoint || '';
        descI.value = t.description || '';
        enI.checked = t.enabled !== false;
        if (hint) hint.textContent = t.preset ? 'Предустановленный инструмент (удаление недоступно)' : '';
      } else {
        nameI.value = '';
        typeI.value = 'http';
        epI.value = '';
        descI.value = '';
        enI.checked = true;
        if (hint) hint.textContent = '';
      }
      nameI.disabled = !!t;
      var del = el('toolDeleteBtn');
      if (del) del.disabled = !!(t && t.preset);
    },

    bindTools: function () {
      var self = this;
      var addBtn = el('toolAddBtn');
      if (addBtn) addBtn.addEventListener('click', function () {
        CA.store.setActiveTool(null);
        self.renderTools();
        el('toolName').focus();
      });

      var clearBtn = el('toolClearBtn');
      if (clearBtn) clearBtn.addEventListener('click', function () {
        CA.store.setActiveTool(null);
        self.renderTools();
      });

      var saveBtn = el('toolSaveBtn');
      if (saveBtn) saveBtn.addEventListener('click', function () { self.saveTool(); });

      var delBtn = el('toolDeleteBtn');
      if (delBtn) delBtn.addEventListener('click', function () { self.deleteTool(); });
    },

    saveTool: function () {
      var self = this;
      var name = el('toolName').value.trim();
      if (!name) {
        CA.toast && CA.toast('warning', 'Внимание', 'Укажите название инструмента');
        return;
      }
      var st = CA.store.getState();
      var existing = st.activeTool ? st.tools[st.activeTool] : null;
      var payload = {
        type: el('toolType').value,
        endpoint: el('toolEndpoint').value,
        description: el('toolDesc').value,
        enabled: el('toolEnabled').checked
      };
      var op;
      if (existing) {
        op = CA.api.tools.update(existing.name, payload);
      } else {
        payload.name = name;
        op = CA.api.tools.create(payload);
      }
      op.then(function (saved) {
        CA.store.upsertTool(saved);
        CA.store.setActiveTool(saved.name);
        CA.toast && CA.toast('success', 'Сохранено', 'Инструмент сохранён');
        self.render();
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось сохранить инструмент');
      });
    },

    deleteTool: function () {
      var self = this;
      var st = CA.store.getState();
      var t = st.activeTool ? st.tools[st.activeTool] : null;
      if (!t) {
        CA.toast && CA.toast('warning', 'Внимание', 'Нет выбранного инструмента');
        return;
      }
      if (!confirm('Удалить инструмент «' + (t.title || t.name) + '»?')) return;
      CA.api.tools.remove(t.name).then(function () {
        CA.store.removeTool(t.name);
        CA.toast && CA.toast('success', 'Удалено', 'Инструмент удалён');
        self.render();
        CA.ChatContextPanel.loadCatalogs();
      }).catch(function (e) {
        CA.toast && CA.toast('error', 'Ошибка', e.message || 'Не удалось удалить инструмент');
      });
    }
  };

  CA.SettingsView = SettingsView;
})();
