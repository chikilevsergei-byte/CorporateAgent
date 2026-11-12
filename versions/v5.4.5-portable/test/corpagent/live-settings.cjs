// Read-only live model-catalog verification. Never prints keys, folder IDs or session URLs.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const root=process.cwd(),state=JSON.parse(fs.readFileSync(path.join(root,'data/app-instance.json'),'utf8')),profile=path.join(root,'build_test','live-settings-'+Date.now());fs.mkdirSync(profile,{recursive:true});let edge,ws,id=0;const pending=new Map();
 try{
  edge=spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',['--headless=new','--disable-gpu','--disable-background-networking','--disable-sync','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:'ignore'});
  for(let n=0;n<150&&!fs.existsSync(path.join(profile,'DevToolsActivePort'));n++)await delay(100);
  const port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0],pages=await(await fetch('http://127.0.0.1:'+port+'/json')).json();ws=new WebSocket(pages.find(x=>x.type==='page').webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r,{once:true}));
  const call=(method,params={})=>new Promise((resolve,reject)=>{const key=++id;pending.set(key,{resolve,reject});ws.send(JSON.stringify({id:key,method,params}));setTimeout(()=>{if(pending.delete(key))reject(Error('Browser timeout'));},30000).unref();});
  ws.addEventListener('message',event=>{const m=JSON.parse(event.data),p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(m.error.message)):p.resolve(m.result);}});
  const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error('UI evaluation failed');return r.result.value;};
  const until=async expression=>{for(let n=0;n<400;n++){if(await evaluate(expression))return;await delay(100);}throw Error('UI condition timeout');};
  await call('Page.navigate',{url:state.url});await until('!!window.CorporateAgent?.SettingsView?._settingsSnapshot');
  await evaluate('document.querySelector(".settings-btn").click();document.querySelector("[onclick*=tabModel]").click();document.getElementById("modelPassword").value="1234";document.getElementById("modelUnlockBtn").click()');
  await until('!document.getElementById("modelEditor").hidden&&!CorporateAgent.SettingsView._modelsLoading&&CorporateAgent.SettingsView._providerModels?.length>2');
  const result=await evaluate('(()=>{const list=[...document.getElementById("settingsModel").options];return {count:CorporateAgent.SettingsView._providerModels.length,selectable:list.filter(x=>!x.disabled).length,deepseek:list.some(x=>x.value.includes("/deepseek-")),qwen:list.some(x=>x.value.includes("/qwen"))};})()');
  assert.ok(result.count>2&&result.deepseek&&result.qwen);
  await evaluate('(()=>{const s=document.getElementById("settingsModel");s.value=[...s.options].find(x=>x.value.includes("/deepseek-")).value;s.dispatchEvent(new Event("change",{bubbles:true}));})()');
  assert.ok(await evaluate('document.getElementById("settingsModelUri").value.includes("/deepseek-")'));
  await evaluate('closeModal("settingsModal")');assert.ok(await evaluate('!CorporateAgent.SettingsView._modelPassword'));
  const report={status:'PASS',...result,scope:'live authenticated catalog and unsaved model selection; no chat requests'};fs.writeFileSync(path.join(root,'live-models-result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{if(ws)ws.close();if(edge)edge.kill();}
})().catch(()=>{console.error('Live model settings verification failed; credentials were not printed.');process.exitCode=1;});
