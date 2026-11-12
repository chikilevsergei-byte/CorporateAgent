(function(){
 'use strict';
 let current=null,seq=0;
 const records=new WeakMap();
 function close(){if(!current)return;const rec=current;current=null;rec.popup?.remove();rec.popup=null;rec.button.setAttribute('aria-expanded','false');rec.button.removeAttribute('aria-activedescendant');}
 function sync(rec){
  const option=rec.select.options[rec.select.selectedIndex];rec.button.textContent=option?.textContent||'Выберите значение';rec.button.disabled=rec.select.disabled;
  rec.button.title=rec.button.textContent;rec.button.setAttribute('aria-label',(rec.label?rec.label+': ':'')+rec.button.textContent);
 }
 function position(rec){
  const r=rec.button.getBoundingClientRect(),width=Math.min(Math.max(r.width,320),innerWidth-24),above=r.top-16,below=innerHeight-r.bottom-16;
  rec.popup.style.width=width+'px';rec.popup.style.left=Math.max(12,Math.min(r.left,innerWidth-width-12))+'px';rec.popup.style.maxHeight=Math.max(90,Math.min(360,Math.max(above,below)))+'px';
  rec.popup.style.top='auto';rec.popup.style.bottom='auto';if(below>=Math.min(240,above))rec.popup.style.top=(r.bottom+6)+'px';else rec.popup.style.bottom=(innerHeight-r.top+6)+'px';
 }
 function choose(rec,index){
  if(rec.select.options[index]?.disabled)return;
  rec.select.selectedIndex=index;rec.select.dispatchEvent(new Event('input',{bubbles:true}));rec.select.dispatchEvent(new Event('change',{bubbles:true}));sync(rec);close();rec.button.focus();
 }
 function open(rec){
  if(rec.select.disabled)return;const was=current===rec;close();sync(rec);if(was)return;
  const popup=document.createElement('div');popup.className='select-popup';popup.id=rec.id+'List';popup.setAttribute('role','listbox');popup.setAttribute('aria-label',rec.label||'Варианты');
  rec.popup=popup;rec.active=Math.max(0,rec.select.selectedIndex);rec.button.setAttribute('aria-controls',popup.id);rec.button.setAttribute('aria-expanded','true');
  [...rec.select.options].forEach((option,index)=>{
   const row=document.createElement('button');row.type='button';row.className='select-option';row.id=rec.id+'Option'+index;row.textContent=option.textContent;row.setAttribute('role','option');row.tabIndex=-1;row.setAttribute('aria-selected',String(index===rec.select.selectedIndex));row.setAttribute('aria-disabled',String(option.disabled));
   row.addEventListener('mousedown',e=>e.preventDefault());row.onclick=()=>choose(rec,index);popup.appendChild(row);
  });document.body.appendChild(popup);current=rec;position(rec);highlight(rec);
 }
 function highlight(rec){if(!rec.popup)return;rec.popup.querySelectorAll('.select-option').forEach((node,index)=>node.classList.toggle('keyboard-active',index===rec.active));const row=rec.popup.children[rec.active];if(row){rec.button.setAttribute('aria-activedescendant',row.id);row.scrollIntoView({block:'nearest'});}}
 function enhance(select){
  if(records.has(select)||select.multiple||select.size>1)return;
  const wrapper=document.createElement('span');wrapper.className='select-control';select.parentNode.insertBefore(wrapper,select);wrapper.appendChild(select);
  const button=document.createElement('button');button.type='button';button.className='select select-trigger';button.setAttribute('role','combobox');button.setAttribute('aria-haspopup','listbox');button.setAttribute('aria-expanded','false');
  const label=select.closest('.form-group')?.querySelector('label')?.textContent.trim()||select.getAttribute('aria-label')||select.title||'';
  const rec={select,button,wrapper,label,id:'selectControl'+(++seq)};records.set(select,rec);select.classList.add('native-enhanced');select.setAttribute('aria-hidden','true');select.tabIndex=-1;wrapper.appendChild(button);sync(rec);
  button.onclick=()=>open(rec);
  button.addEventListener('keydown',e=>{
   if(['ArrowDown','ArrowUp','Home','End','Enter',' ','Escape','Tab'].includes(e.key)){
    if(e.key==='Tab'){close();return;}
    if(e.key==='Escape'){if(current===rec){e.preventDefault();e.stopPropagation();close();}return;}
    e.preventDefault();e.stopPropagation();
    if(current!==rec){open(rec);return;}
    if(e.key==='Enter'||e.key===' '){choose(rec,rec.active);return;}
    const length=rec.select.options.length,step=e.key==='ArrowUp'?-1:1;
    rec.active=e.key==='Home'?0:e.key==='End'?length-1:(rec.active+step+length)%length;
    for(let n=0;n<length&&rec.select.options[rec.active]?.disabled;n++)rec.active=(rec.active+step+length)%length;
    highlight(rec);
   }else if(e.key.length===1){
    const start=rec.select.selectedIndex+1,options=[...rec.select.options];for(let n=0;n<options.length;n++){const i=(start+n)%options.length;if(!options[i].disabled&&options[i].textContent.trim().toLocaleLowerCase().startsWith(e.key.toLocaleLowerCase())){choose(rec,i);break;}}
   }
  });
  select.addEventListener('change',()=>sync(rec));new MutationObserver(()=>{sync(rec);if(current===rec)close();}).observe(select,{subtree:true,childList:true,attributes:true,characterData:true});
 }
 function scan(node){if(node.nodeType!==1)return;if(node.matches('select'))enhance(node);node.querySelectorAll('select').forEach(enhance);}
 document.querySelectorAll('select').forEach(enhance);
 new MutationObserver(mutations=>{for(const change of mutations)for(const node of change.addedNodes)scan(node);if(current&&!current.select.isConnected)close();}).observe(document.body,{childList:true,subtree:true});
 document.addEventListener('click',e=>{if(current&&!current.wrapper.contains(e.target)&&!current.popup?.contains(e.target))close();});
 document.addEventListener('keydown',e=>{if(current&&e.key==='Escape'){e.preventDefault();e.stopImmediatePropagation();close();}},true);
 document.addEventListener('modal:open',()=>document.querySelectorAll('select').forEach(s=>{const rec=records.get(s);if(rec)sync(rec);}));
 document.addEventListener('modal:close',close);window.addEventListener('resize',()=>current&&position(current));
 document.addEventListener('scroll',e=>{if(current&&!current.popup?.contains(e.target))position(current);},true);
 window.CorporateAgent=window.CorporateAgent||{};window.CorporateAgent.selectControls={sync:()=>document.querySelectorAll('select').forEach(s=>{const r=records.get(s);if(r)sync(r);}),close};
})();
