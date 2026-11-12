const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
async function main() {
  const portable = path.resolve(process.argv[2]);
  const temp = process.env.CA_SMOKE_BASE || fs.mkdtempSync(path.join(process.cwd(),'build_test','portable-'));
  const app = spawn(path.join(portable,'jre','bin','java.exe'), ['-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-Dcorpagent.base='+temp,'-cp',path.join(portable,'dist','CorporateAgent.jar')+';'+path.join(portable,'lib','*'),'corpagent.App'], {windowsHide:true});
  let log='';app.stdout.on('data',b=>log+=b);app.stderr.on('data',b=>log+=b);
  try {
    for(let n=0;n<150 && !/http:\/\/127\.0\.0\.1:\d+\/\?token=\w+/.test(log);n++) await new Promise(r=>setTimeout(r,100));
    const url=log.match(/http:\/\/127\.0\.0\.1:\d+\/\?token=\w+/)?.[0];assert.ok(url,log);
    const root=new URL(url);const token=root.searchParams.get('token');
    assert.ok((await (await fetch(url)).text()).includes('js/app.js'));
    assert.equal((await fetch(new URL('/js/app.js',root))).status,200);
    assert.equal((await fetch(new URL('/api/chats',root))).status,401);
    const opts={headers:{Authorization:'Bearer '+token}};
    const settings=await (await fetch(new URL('/api/settings',root),opts)).json();assert.ok(settings.ok);assert.ok(!('api_key' in settings.data));
    const chats=await (await fetch(new URL('/api/chats',root),opts)).json();assert.ok(chats.ok);
    const create=await (await fetch(new URL('/api/chats',root),{...opts,method:'POST',body:JSON.stringify({chat_id:'portable_draft',title:'Draft'})})).json();assert.ok(create.ok);
    const rename=await (await fetch(new URL('/api/chats',root),{...opts,method:'PUT',body:JSON.stringify({chat_id:'portable_draft',title:'Renamed',pinned:true})})).json();assert.equal(rename.data.title,'Renamed');assert.ok(rename.data.pinned);
    const remove=await (await fetch(new URL('/api/chats',root),{...opts,method:'DELETE',body:JSON.stringify({chat_id:'portable_draft'})})).json();assert.ok(remove.ok);
    await fetch(new URL('/api/system/shutdown',root),{...opts,method:'POST'});
    console.log('PortableSmoke OK: bundled JRE, production App/JAR, embedded web assets, auth, settings, chat metadata CRUD, shutdown; isolated data');
  } finally { app.kill(); }
}
main().catch(e=>{console.error(e);process.exitCode=1});
