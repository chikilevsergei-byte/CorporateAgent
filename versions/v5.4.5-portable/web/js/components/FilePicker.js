// Files belong to a persisted chat; removing a chip detaches context, not the disk file.
(function () {
  'use strict';
  var CA = (window.CorporateAgent = window.CorporateAgent || {});
  function el(id) { return document.getElementById(id); }
  var FilePicker = {
    _pending: {}, _uploading: {}, _bound:false, _action:null,
    chatId:function(){ var id=CA.store.getState().activeChatId; return id || CA.Sidebar.newChat(); },
    files:function(id){ var chat=CA.store.getState().chats[id || CA.store.getState().activeChatId]; return (chat && chat.attachments) || []; },
    ids:function(id){ return this.files(id).map(function(f){return f.id;}); },
    content:function(){ return this.files().map(function(f){return '['+f.name+']\n'+f.text;}).join('\n\n'); },
    isUploading:function(id){return !!this._uploading[id];},
    open:function(callback){ var id=this.chatId(); this._action=callback?{id:id,callback:callback}:null; this.renderList(); if(window.openModal)window.openModal('filePickerModal'); },
    init:function(){
      if(this._bound)return; this._bound=true; var self=this;
      CA.store.subscribe(function(){self.renderList();self.renderAttachments();});
      var zone=el('dropZone'),input=el('filePickerInput'),button=el('filePickerUploadBtn');
      var attach=document.querySelector('.tool-btn[onclick*="filePickerModal"]');
      if(attach){attach.removeAttribute('onclick');attach.addEventListener('click',function(){self.open();});}
      if(zone && input){
        zone.addEventListener('click',function(e){if(!e.target.closest('button[data-remove-pending]'))input.click();});
        input.addEventListener('change',function(){self.addFiles(input.files);input.value='';});
      }
      if(zone){
        ['dragenter','dragover'].forEach(function(event){zone.addEventListener(event,function(e){e.preventDefault();zone.classList.add('dragover');});});
        ['dragleave','drop'].forEach(function(event){zone.addEventListener(event,function(e){e.preventDefault();zone.classList.remove('dragover');});});
        zone.addEventListener('drop',function(e){if(e.dataTransfer)self.addFiles(e.dataTransfer.files);});
      }
      if(button)button.addEventListener('click',function(){self.upload();});
      this.renderAttachments();
    },
    addFiles:function(list){
      if(!list || !list.length)return; var id=this.chatId();
      this._pending[id]=(this._pending[id] || []).concat(Array.from(list)); this.renderList();
    },
    renderList:function(){
      var zone=el('dropZone');if(!zone)return;
      var old=zone.querySelector('.file-picker-selected');if(old)old.remove();
      var id=CA.store.getState().activeChatId, pending=this._pending[id] || [],self=this;
      var button=el('filePickerUploadBtn');if(button){button.disabled=this.isUploading(id);button.textContent=this.isUploading(id)?'Загрузка…':'Загрузить';}
      if(!pending.length)return;
      var box=document.createElement('div');box.className='file-picker-selected';
      pending.forEach(function(file,index){
        var row=document.createElement('div');row.className='file-picker-item';
        var name=document.createElement('span');name.textContent=file.name;row.appendChild(name);
        var remove=document.createElement('button');remove.textContent='Убрать';remove.dataset.removePending=String(index);remove.disabled=self.isUploading(id);
        remove.onclick=function(e){e.stopPropagation();self._pending[id].splice(index,1);self.renderList();};row.appendChild(remove);box.appendChild(row);
      });zone.appendChild(box);
    },
    renderAttachments:function(){
      var input=el('messageInput');if(!input)return;
      var box=el('chatAttachments');if(!box){box=document.createElement('div');box.id='chatAttachments';box.className='chat-attachments';input.parentNode.insertBefore(box,input);}
      box.textContent='';var self=this,id=CA.store.getState().activeChatId,st=CA.store.getState();
      this.files(id).forEach(function(file){
        var chip=document.createElement('span');chip.className='chat-attachment';chip.dataset.attachmentId=file.id;
        var name=document.createElement('span');name.textContent='📎 '+file.name;chip.appendChild(name);
        var remove=document.createElement('button');remove.textContent='×';remove.title='Убрать из следующих вложений. Ранее отправленный текст останется в истории.';remove.setAttribute('aria-label','Убрать '+file.name);
        remove.disabled=st.streaming && st.streamingChatId===id;
        remove.onclick=function(){self.detach(id,file.id);};chip.appendChild(remove);box.appendChild(chip);
      });
      if(this.isUploading(id)){var status=document.createElement('span');status.textContent='Загрузка файлов…';box.appendChild(status);}
    },
    detach:function(chatId,attachmentId){
      return CA.api.del('chat/attachment',{chat_id:chatId,attachment_id:attachmentId}).then(function(chat){CA.store.upsertChat(chat);}).catch(function(e){CA.toast('error','Ошибка',e.message);});
    },
    upload:function(){
      var self=this,id=CA.store.getState().activeChatId,files=(this._pending[id] || []).slice();
      if(!files.length){CA.toast('warning','Внимание','Не выбраны файлы для этого чата');return Promise.resolve();}
      if(this.isUploading(id))return Promise.resolve();
      this._uploading[id]=true;this.renderList();this.renderAttachments();
      var action=this._action && this._action.id===id?this._action:null;
      return CA.Sidebar.ensureSaved(id).then(function(){return CA.api.files.upload(files,id);}).then(function(data){
        self._pending[id]=(self._pending[id] || []).filter(function(file){return files.indexOf(file)<0;});
        if(data.chat && CA.store.getState().chats[id])CA.store.upsertChat(data.chat);
        var active=CA.store.getState().activeChatId===id;
        CA.toast('success','Загружено',(data.count || files.length)+' файл(ов) прикреплено к чату');
        if(active && window.closeModal)window.closeModal('filePickerModal');
        if(self._action===action)self._action=null;
        delete self._uploading[id];
        if(active && action) action.callback(id);
      }).catch(function(e){CA.toast('error','Ошибка загрузки',e.message);}).finally(function(){delete self._uploading[id];self.renderList();self.renderAttachments();});
    }
  };
  CA.FilePicker=FilePicker;
})();
