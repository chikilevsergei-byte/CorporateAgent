// Real Edge smoke test against a local LLM fixture; no external credentials.
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const java = spawn(process.env.CA_TEST_JAVA || 'java', ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true','-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory', '-cp', (process.env.CA_TEST_CLASSES || 'build_test')+';lib/*', 'corpagent.api.WebFlowTest', '--serve'], {windowsHide:true});
  let log = ''; java.stdout.on('data', b => log += b); java.stderr.on('data', () => {});
  const profile = fs.mkdtempSync(path.join(process.cwd(), 'build_test', 'edge-'));
  let edge, ws;
  try {
    for (let n=0; n<150 && !log.includes('TEST_URL='); n++) await delay(100);
    const url = log.match(/TEST_URL=(\S+)/)?.[1]; assert.ok(url, log);
    edge = spawn('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe', ['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'], {windowsHide:true,stdio:'ignore'});
    const active = path.join(profile,'DevToolsActivePort');
    for (let n=0;n<150 && !fs.existsSync(active);n++) await delay(100);
    const port = fs.readFileSync(active,'utf8').split('\n')[0];
    const tabs = await (await fetch('http://127.0.0.1:'+port+'/json')).json();
    ws = new WebSocket(tabs.find(t=>t.type==='page').webSocketDebuggerUrl);
    await new Promise(resolve => ws.addEventListener('open',resolve,{once:true}));
    let seq=0; const pending=new Map(); const errors=[];
    ws.addEventListener('message', e => { const m=JSON.parse(e.data); if(m.id){const p=pending.get(m.id);pending.delete(m.id); m.error?p.reject(m.error):p.resolve(m.result);} if(m.method==='Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.text); });
    function cdp(method,params={}) { return new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});setTimeout(()=>{if(pending.delete(id))reject(new Error("CDP timeout: "+method));},20000).unref();ws.send(JSON.stringify({id,method,params}));}); }
    async function evaluate(expression) {const r=await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true}); if(r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails)); return r.result.value;}
    async function until(expression) {for(let n=0;n<150;n++){if(await evaluate(expression))return; await delay(100);} throw Error('Timed out: '+expression+' STATE '+JSON.stringify(await evaluate('({streaming:CorporateAgent.store.getState().streaming,request:CorporateAgent.ChatView._activeRequest,chat:CorporateAgent.store.getActiveChat(),stop:document.getElementById("stopButton").disabled})')));}
    await cdp('Runtime.enable'); await cdp('Page.enable'); await cdp('Page.navigate',{url});
    await until('!!window.CorporateAgent?.ChatView && !!window.CorporateAgent?.health');
    if(process.argv.includes('--cancel-only')) {
      await evaluate("document.getElementById('messageInput').value='CANCEL_FIXTURE';document.getElementById('sendButton').click()");
      await until('CorporateAgent.store.getState().streaming && !document.getElementById("stopButton").disabled && CorporateAgent.store.getActiveChat().messages.at(-1).text.includes("partial")');
      await evaluate('document.getElementById("stopButton").click()');
      await until('!CorporateAgent.store.getState().streaming && CorporateAgent.store.getActiveChat().messages.at(-1).cancelled');
      console.log('CancelOnly OK');return;
    }
    await evaluate("document.getElementById('messageInput').value='hello';document.getElementById('sendButton').click()");
    await until("!!CorporateAgent.store.getActiveChat()?._server && !CorporateAgent.store.getState().streaming");
    const first = await evaluate('CorporateAgent.store.getActiveChat()');
    assert.equal(first.messages.length,2); assert.ok(first.messages.every(m=>m.id.startsWith('m_')));
    assert.equal(await evaluate(`(()=>{const node=document.querySelector('.message');for(let i=0;i<25;i++)CorporateAgent.ChatView.render();return node===document.querySelector('.message')&&getComputedStyle(node).animationName==='none';})()`),true,'Polling must preserve message DOM and avoid fade animation');
    assert.equal(await evaluate(`(()=>{const nodes=Array.from(document.querySelectorAll('.message'));const chat=CorporateAgent.store.getActiveChat();CorporateAgent.store.updateMessage(chat.id,chat.messages[1].id,{text:chat.messages[1].text+' STREAM_DELTA'});return nodes.every((n,i)=>n===document.querySelectorAll('.message')[i])&&document.querySelectorAll('.message')[1].textContent.includes('STREAM_DELTA');})()`),true,'Streaming must update content without replacing message elements');
    await evaluate("document.querySelector('[data-act=note]').click()");
    await until('CorporateAgent.store.getState().noteOrder.length===1');
    await cdp('Page.reload');
    await until('!!window.CorporateAgent?.store?.getActiveChat()?._server');
    assert.equal(await evaluate('CorporateAgent.store.getActiveChat().messages.length'),2);
    await evaluate(`(async()=>{const f=new File(['BROWSER_ATTACHMENT'],'sample.txt',{type:'text/plain'});CorporateAgent.FilePicker.addFiles([f]);CorporateAgent.FilePicker.upload();})()`);
    await until("CorporateAgent.FilePicker.content().includes('BROWSER_ATTACHMENT')");
    await evaluate("document.getElementById('messageInput').value='Создай презентацию';document.getElementById('sendButton').click()");
    await until('!CorporateAgent.store.getState().streaming && !!CorporateAgent.store.getActiveChat()?.messages.at(-1)?.meta?.filePath');
    assert.ok(await evaluate("!!document.querySelector('[data-act=download]')"));
    await evaluate("window.prompt=()=> 'Browser edited';document.querySelector('[data-act=edit]').click()");
    await until("CorporateAgent.store.getActiveChat().messages[0].text==='Browser edited'");
    await evaluate("document.querySelector('[data-act=regenerate]').click()");
    await until("!CorporateAgent.store.getState().streaming && CorporateAgent.store.getActiveChat().messages.length===2");
    await evaluate("window.confirm=()=>true;document.querySelector('[data-act=delete]').click()");
    await until('CorporateAgent.store.getActiveChat().messages.length===1');
    await evaluate("document.getElementById('projectName').value='Browser project';CorporateAgent.ProjectWizard.create()");
    await until('!!CorporateAgent.store.getState().activeProjectId');
    assert.equal(await evaluate('Object.values(CorporateAgent.store.getState().chats).filter(c=>c.project===CorporateAgent.store.getState().activeProjectId).length'),7);
    await evaluate("var project=CorporateAgent.store.getState().activeProjectId;var role=Object.values(CorporateAgent.store.getState().chats).find(c=>c.project===project);CorporateAgent.store.setActiveChat(role.id);document.getElementById('messageInput').value='hello';CorporateAgent.ChatView.send()");
    await until('!CorporateAgent.store.getState().streaming && CorporateAgent.store.getActiveChat().messages.length===2');
    assert.ok(!(await evaluate('CorporateAgent.store.getActiveChat().messages[1].text')).startsWith('\u26a0'));
    await evaluate("CorporateAgent.DatabasesView.openView(CorporateAgent.store.getActiveChat().project_db)");
    await until('!!document.getElementById("dbRunSql") && !!CorporateAgent.DatabasesView._tableData');
    await evaluate("window.closeModal('dbViewModal');CorporateAgent.store.setActiveProject(null);CorporateAgent.Sidebar.newChat()");
    await until('CorporateAgent.store.getActiveChat()?._server && !CorporateAgent.Sidebar._saving[CorporateAgent.store.getState().activeChatId]');
    const scopeA=await evaluate('CorporateAgent.store.getState().activeChatId');
    async function menu(id,action) {
      await evaluate(`CorporateAgent.Sidebar.closeMenu();document.querySelector('[data-chat-id="'+${JSON.stringify(id)}+'"] .chat-item-menu').click();document.querySelector('#chatActionsMenu [data-action="${action}"]').click()`);
    }
    await evaluate("window.prompt=()=> 'Scope A'"); await menu(scopeA,'rename');
    await until('CorporateAgent.store.getActiveChat().title==="Scope A"');
    await menu(scopeA,'pin');await until('CorporateAgent.store.getActiveChat().pinned===true');
    assert.equal(await evaluate("document.querySelector('#chatsSection .chat-item').dataset.chatId"),scopeA);
    await menu(scopeA,'move');
    await evaluate("document.getElementById('chatMoveProject').value=CorporateAgent.store.getState().projectOrder[0];document.getElementById('chatMoveSave').click()");
    await until('!!CorporateAgent.store.getActiveChat().project && !document.getElementById("chatMoveDialog")');
    await menu(scopeA,'move');await evaluate("document.getElementById('chatMoveProject').value='';document.getElementById('chatMoveSave').click()");
    await until('!CorporateAgent.store.getActiveChat().project && !document.getElementById("chatMoveDialog")');
    await evaluate(`window.savedUpload=CorporateAgent.api.files.upload;CorporateAgent.api.files.upload=(files,id)=>new Promise((resolve,reject)=>{window.finishScopedUpload=()=>savedUpload(files,id).then(resolve,reject)});CorporateAgent.FilePicker.addFiles([new File(['SCOPE_A'],'scope-a.txt')]);CorporateAgent.FilePicker.upload();void 0`);
    await until('typeof window.finishScopedUpload==="function"');
    await evaluate('CorporateAgent.Sidebar.newChat()');
    await until('CorporateAgent.store.getActiveChat()?._server && !CorporateAgent.Sidebar._saving[CorporateAgent.store.getState().activeChatId]');
    const scopeB=await evaluate('CorporateAgent.store.getState().activeChatId');
    await evaluate("CorporateAgent.FilePicker.addFiles([new File(['SCOPE_B'],'scope-b.txt')]);window.finishScopedUpload();CorporateAgent.api.files.upload=window.savedUpload");
    await until(`!CorporateAgent.FilePicker.isUploading(${JSON.stringify(scopeA)})`);
    assert.equal(await evaluate('CorporateAgent.FilePicker.ids().length'),0);
    assert.equal(await evaluate('CorporateAgent.FilePicker._pending[CorporateAgent.store.getState().activeChatId][0].name'),'scope-b.txt');
    await evaluate(`CorporateAgent.store.setActiveChat(${JSON.stringify(scopeA)})`);
    assert.equal(await evaluate('CorporateAgent.FilePicker.files()[0].name'),'scope-a.txt');
    await cdp('Page.reload');
    await until(`!!window.CorporateAgent?.store?.getState()?.chats[${JSON.stringify(scopeA)}]`);
    await evaluate(`CorporateAgent.store.setActiveChat(${JSON.stringify(scopeA)})`);
    assert.equal(await evaluate('CorporateAgent.FilePicker.files().length'),1);
    assert.equal(await evaluate('CorporateAgent.store.getActiveChat().title'),'Scope A');
    assert.equal(await evaluate('CorporateAgent.store.getActiveChat().pinned'),true);
    await evaluate("document.querySelector('#chatAttachments button').click()");
    await until('CorporateAgent.FilePicker.files().length===0');
    await evaluate(`CorporateAgent.store.setActiveChat(${JSON.stringify(scopeB)});document.getElementById('messageInput').value='CANCEL_FIXTURE';document.getElementById('sendButton').click()`);
    await until('CorporateAgent.store.getState().streaming && !document.getElementById("stopButton").disabled && CorporateAgent.store.getActiveChat().messages.at(-1).text.includes("partial")');
    await evaluate('document.getElementById("stopButton").click()');
    await until('!CorporateAgent.store.getState().streaming && CorporateAgent.store.getActiveChat().messages.at(-1).cancelled');
    assert.equal(await evaluate('CorporateAgent.store.getActiveChat().messages.at(-1).text'),'partial');
    const oldBranch=await evaluate('JSON.stringify(CorporateAgent.store.getActiveChat().messages)');
    await evaluate('document.querySelector("[data-act=regenerate]").click()');
    await until('CorporateAgent.store.getState().streaming && !document.getElementById("stopButton").disabled');
    await evaluate('document.getElementById("stopButton").click()');
    await until('!CorporateAgent.store.getState().streaming');
    assert.equal(await evaluate('JSON.stringify(CorporateAgent.store.getActiveChat().messages)'),oldBranch);
    await evaluate("document.getElementById('messageInput').value='after cancel';document.getElementById('sendButton').click()");
    await until('!CorporateAgent.store.getState().streaming && CorporateAgent.store.getActiveChat().messages.length===4');
    assert.equal(await evaluate('CorporateAgent.store.getActiveChat().messages.at(-1).text'),'hello');
    await evaluate('window.confirm=()=>false');await menu(scopeA,'delete');
    assert.ok(await evaluate(`!!CorporateAgent.store.getState().chats[${JSON.stringify(scopeA)}]`));
    await evaluate('window.confirm=()=>true');await menu(scopeA,'delete');
    await until(`!CorporateAgent.store.getState().chats[${JSON.stringify(scopeA)}]`);
    await cdp('Page.reload');await until('!!window.CorporateAgent?.health && CorporateAgent.store.getState().chatOrder.length>0');
    assert.equal(await evaluate(`!!CorporateAgent.store.getState().chats[${JSON.stringify(scopeA)}]`),false);
    assert.deepEqual(errors,[]);
    console.log('BrowserFlow OK: send, server IDs, save note, reload history, upload, create PPTX, download button, edit/delete/regenerate, project with 7 chats, project context, DB viewer; rename/pin/move/delete, attachment isolation and reload, stream/regeneration cancellation; no JS exceptions');
  } finally {
    if(ws) ws.close(); if(edge) edge.kill(); java.stdin.end('\n');
    // Test profile is retained under build_test for diagnostics.
  }
}
main().catch(e=>{console.error(e);process.exitCode=1});

