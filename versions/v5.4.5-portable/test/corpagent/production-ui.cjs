const {spawn}=require('node:child_process');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function main(){
 const url=JSON.parse(fs.readFileSync(path.join(process.cwd(),'data','app-instance.json'),'utf8')).url;assert.ok(url?.startsWith('http://127.0.0.1:'));
 const profile=fs.mkdtempSync(path.join(process.cwd(),'build_test','edge-security-'));
 const edge=spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',[
  '--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--disable-background-networking','--disable-component-update','--disable-sync','--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:['ignore','ignore','pipe']});
 let launchErrors='';edge.stderr.on('data',b=>launchErrors+=b.toString());
 let ws;
 try{
  for(let i=0;i<100&&!fs.existsSync(path.join(profile,'DevToolsActivePort'));i++)await delay(100);
  if(!fs.existsSync(path.join(profile,'DevToolsActivePort')))throw Error('Browser launch failed, exit='+edge.exitCode+'; '+launchErrors.slice(0,120));
  const port=fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0];
  const tabs=await(await fetch('http://127.0.0.1:'+port+'/json')).json();ws=new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);
  await new Promise(r=>ws.addEventListener('open',r,{once:true}));let seq=0;const pending=new Map();const errors=[];let externalBlocked=0;
  function cdp(method,params={}){return new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});setTimeout(()=>{if(pending.delete(id))reject(Error('CDP timeout'));},10000).unref();ws.send(JSON.stringify({id,method,params}));});}
  ws.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);if(p)m.error?p.reject(Error(m.error.message)):p.resolve(m.result);}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text);if(m.method==='Fetch.requestPaused'){const allowed=m.params.request.url.startsWith(new URL(url).origin+'/')||m.params.request.url.startsWith('data:');if(!allowed)externalBlocked++;cdp(allowed?'Fetch.continueRequest':'Fetch.failRequest',{requestId:m.params.requestId,...(allowed?{}:{errorReason:'BlockedByClient'})}).catch(()=>{});}});
  const evaluate=async expression=>{const r=await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error('Browser evaluation failed');return r.result.value;};
  await cdp('Runtime.enable');await cdp('Page.enable');await cdp('Fetch.enable',{patterns:[{urlPattern:'*'}]});await cdp('Page.navigate',{url});
  for(let i=0;i<100;i++){if(await evaluate('!!window.CorporateAgent?.health && !!window.CorporateAgent?.Message'))break;await delay(100);}

  let checks=0;
  async function until(expression){for(let i=0;i<100;i++){if(await evaluate(expression))return;await delay(100);}throw Error('Timeout: '+expression);}
  await until('!!CorporateAgent.Sidebar && !!CorporateAgent.SettingsView');checks++;
  const id=await evaluate('document.querySelector(".btn-new-chat").click();CorporateAgent.store.getState().activeChatId');
  await until('!!CorporateAgent.store.getState().chats['+JSON.stringify(id)+']?._server');checks++;
  assert.ok(await evaluate('!!document.querySelector(\'[data-chat-id="'+id+'"]\')'));checks++;
  await cdp('Page.reload');await until('!!window.CorporateAgent?.store?.getState()?.chats['+JSON.stringify(id)+']?._server');checks++;
  await evaluate('document.querySelector(\'[data-chat-id="'+id+'"]\').click()');
  assert.equal(await evaluate('CorporateAgent.store.getState().activeChatId'),id);checks++;
  for(const screen of ['notes','projects','chat']){await evaluate('CorporateAgent.app.go('+JSON.stringify(screen)+')');await until('CorporateAgent.store.getState().screen==='+JSON.stringify(screen));checks++;}
  await evaluate('document.querySelector(".settings-btn").click()');
  assert.ok(await evaluate('document.getElementById("settingsModal").classList.contains("active")'));checks++;
  for(let i=0;i<5;i++){await evaluate('document.querySelectorAll(".settings-tab")['+i+'].click()');assert.ok(await evaluate('document.querySelectorAll("#settingsModal .tab-content.active").length===1'));checks++;}
  assert.ok(await evaluate('!document.getElementById("apiKey").disabled&&!document.getElementById("settingsBaseUrl").disabled'));checks++;
  await evaluate('closeModal("settingsModal");CorporateAgent.DatabasesView.openView(Object.keys(CorporateAgent.store.getState().databases)[0])');
  await until('!!document.getElementById("dbRunSql") && !!CorporateAgent.DatabasesView._tableData');checks++;
  assert.equal(errors.length,0);checks++;
  const result={suite:'production-ui-v54',status:'PASS',checks,unhandledExceptions:errors.length,externalRequestsBlocked:externalBlocked,model:'not invoked',scope:'actual packaged application and embedded Java; real button creates a persisted chat; all settings tabs and DB viewer open'};
  fs.writeFileSync(path.join(process.cwd(),'build_test','production-ui.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 }finally{if(ws)ws.close();edge.kill();}
}
main().catch(error=>{console.error('Production UI failed: '+String(error.message));process.exitCode=1;});
