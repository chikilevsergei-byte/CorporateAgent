(function(){
 'use strict';
 const stack=[],openers=new Map();
 const byId=id=>document.getElementById(id);
 window.openModal=function(id){
  const modal=byId(id);if(!modal)return;
  document.querySelectorAll('.dropdown-menu.active').forEach(x=>x.classList.remove('active'));
  document.querySelectorAll('.toast.success,.toast.warning').forEach(x=>x.remove());
  if(id==='projectModal'&&window.CorporateAgent?.ProjectWizard)CorporateAgent.ProjectWizard.reset();
  openers.set(id,document.activeElement);const old=stack.indexOf(id);if(old>=0)stack.splice(old,1);stack.push(id);
  modal.style.zIndex=String(1000+stack.length*10);modal.classList.add('active');modal.setAttribute('role','dialog');modal.setAttribute('aria-modal','true');
  const title=modal.querySelector('.modal-title');if(title){if(!title.id)title.id=id+'TitleLabel';modal.setAttribute('aria-labelledby',title.id);}
  modal.dispatchEvent(new CustomEvent('modal:open',{bubbles:true}));
  setTimeout(()=>modal.querySelector('input:not([type=hidden]):not(:disabled),textarea:not(:disabled),button:not(:disabled)')?.focus(),0);
 };
 window.closeModal=function(id,reason){
  const modal=byId(id);if(!modal)return;modal.classList.remove('active');const n=stack.indexOf(id);if(n>=0)stack.splice(n,1);
  modal.dispatchEvent(new CustomEvent('modal:close',{bubbles:true,detail:{reason:reason||'cancel'}}));
  const opener=openers.get(id);if(opener?.isConnected)opener.focus();
 };
 document.addEventListener('click',e=>{if(e.target.classList.contains('modal-overlay')&&e.target.id===stack.at(-1))closeModal(e.target.id);if(!e.target.closest('.dropdown-wrapper,.dropdown-menu'))document.querySelectorAll('.dropdown-menu.active').forEach(x=>x.classList.remove('active'));});
 document.addEventListener('keydown',e=>{
  if(e.defaultPrevented)return;
  if(e.key==='Escape'){
   const menus=document.querySelectorAll('.dropdown-menu.active');if(menus.length){menus.forEach(x=>x.classList.remove('active'));return;}
   const id=stack.at(-1);if(id){e.preventDefault();closeModal(id);}return;
  }
  if(e.key==='Tab'&&stack.length){
   const modal=byId(stack.at(-1)),nodes=[...modal.querySelectorAll('button,input,textarea,select,[tabindex="0"],a[href]')].filter(x=>!x.disabled&&!x.classList.contains('native-enhanced')&&x.getClientRects().length&&getComputedStyle(x).visibility!=='hidden');
   if(!nodes.length)return;const first=nodes[0],last=nodes.at(-1);
   if(e.shiftKey&&(document.activeElement===first||!modal.contains(document.activeElement))){e.preventDefault();last.focus();}
   else if(!e.shiftKey&&(document.activeElement===last||!modal.contains(document.activeElement))){e.preventDefault();first.focus();}
  }
 });
 window.toggleSection=id=>byId(id)?.classList.toggle('collapsed');
 window.switchSettingsTab=function(event,id){document.querySelectorAll('.settings-tab').forEach(x=>x.classList.remove('active'));document.querySelectorAll('#settingsModal .tab-content').forEach(x=>x.classList.remove('active'));event.currentTarget.classList.add('active');byId(id)?.classList.add('active');const save=byId('settingsSaveBtn');save.hidden=id==='tabDB'||(id==='tabModel'&&!window.CorporateAgent?.SettingsView?._modelPassword);save.textContent=id==='tabSkills'?'Сохранить навык':id==='tabTools'?'Сохранить инструмент':'Сохранить';const cancel=document.querySelector('#settingsModal .modal-footer .btn-secondary');cancel.textContent=['tabSkills','tabTools','tabDB'].includes(id)?'Закрыть':'Отмена';};
 window.selectRadio=function(item){const radio=item.querySelector('input');if(!radio)return;item.parentElement.querySelectorAll('.radio-option').forEach(x=>x.classList.toggle('selected',x===item));radio.checked=true;const fresh=radio.value==='new';byId('newDbFields').style.display=fresh?'block':'none';byId('existingDbFields').style.display=fresh?'none':'block';};
 window.nextWizardStep=step=>window.CorporateAgent?.ProjectWizard?.showStep(step);
 window.selectDbItem=item=>item.parentElement.querySelectorAll('.db-select-item').forEach(x=>x.classList.toggle('selected',x===item));
 window.updateSliderValue=function(input,id){const v=Number(input.value);byId(id).textContent=v.toFixed(1);input.style.setProperty('--slider-progress',((v-Number(input.min))/(Number(input.max)-Number(input.min))*100)+'%');};
 window.toggleApiKey=function(){const input=byId('apiKey');input.type=input.type==='password'?'text':'password';};
 function positionDropdown(menu,button){
  const rect=button.getBoundingClientRect(),width=Math.min(460,innerWidth-24),above=rect.top-16,below=innerHeight-rect.bottom-16;
  menu.style.width=width+'px';menu.style.maxHeight=Math.max(100,Math.min(420,Math.max(above,below)))+'px';
  menu.style.left=Math.max(12,Math.min(innerWidth-width-12,rect.left))+'px';
  menu.style.top='auto';menu.style.bottom='auto';if(above>=Math.min(300,below))menu.style.bottom=(innerHeight-rect.top+8)+'px';else menu.style.top=(rect.bottom+8)+'px';
 }
 window.toggleDropdown=function(id,button,event){event?.stopPropagation();const menu=byId(id);if(!menu)return;const active=menu.classList.contains('active');document.querySelectorAll('.dropdown-menu.active').forEach(x=>{x.classList.remove('active');x._button?.setAttribute('aria-expanded','false');});if(menu.parentElement!==document.body)document.body.appendChild(menu);menu.classList.toggle('active',!active);button.setAttribute('aria-expanded',String(!active));if(!active){menu._button=button;positionDropdown(menu,button);}};
 window.addEventListener('resize',()=>document.querySelectorAll('.dropdown-menu.active').forEach(m=>m._button&&positionDropdown(m,m._button)));
 window.updateBadge=type=>window.CorporateAgent?.ChatContextPanel?.updateBadge(type);
 window.toggleDropdownItem=(item,type)=>{item.classList.toggle('selected');updateBadge(type);};
 window.resetDropdown=(id,type)=>window.CorporateAgent?.ChatContextPanel?.resetGroup(type);
 window.closeDropdown=(id,type)=>window.CorporateAgent?.ChatContextPanel?.commit(type);
 window.showNotes=()=>window.CorporateAgent?.app?.go('notes');window.showChat=()=>window.CorporateAgent?.app?.go('chat');
 window.sendMessage=()=>window.CorporateAgent?.ChatView?.send();
 window.showToast=function(type,title,message){
  const container=byId('toastContainer');if(!container)return;
  const toast=document.createElement('div');toast.className='toast '+type;toast.setAttribute('role',type==='error'?'alert':'status');
  const content=document.createElement('div');content.className='toast-content';
  for(const [name,text] of [['toast-title',title],['toast-message',message]]){const item=document.createElement('div');item.className=name;item.textContent=String(text??'');content.appendChild(item);}
  const close=document.createElement('button');close.className='toast-close';close.textContent='×';close.setAttribute('aria-label','Закрыть уведомление');close.onclick=()=>toast.remove();toast.append(content,close);container.appendChild(toast);
  while(container.children.length>3)container.firstElementChild.remove();setTimeout(()=>toast.remove(),6000);
 };
 // Remove mock content before the real stores load; keep the live list containers.
 for(const selector of ['.window-controls','#contextMenu'])document.querySelector(selector)?.remove();
 for(const selector of ['#projectsSection .project-list','#chatsSection .chat-list','#notesList']){const node=document.querySelector(selector);if(node)node.textContent='Загрузка…';}
 for(const selector of ['.notes-editor-title','.notes-editor-content']){const node=document.querySelector(selector);if(node)node.value='';}
})();
