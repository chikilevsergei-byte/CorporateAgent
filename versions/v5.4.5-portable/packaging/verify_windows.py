from pathlib import Path
import json, hashlib, zipfile, subprocess, time, urllib.request, shutil
import urllib.parse

root=Path(__file__).resolve().parents[1]
archive=root/'releases/CorporateAgent-5.4.5-Portable-Windows-x64.zip'
test=root/'verification'/('Перенос пробелы '+str(time.time_ns()))
test.mkdir(parents=True)
with zipfile.ZipFile(archive) as z: z.extractall(test)
app=test/archive.stem
manifest=json.loads((app/'package-manifest.json').read_text(encoding='utf-8'))
for name, expected in manifest['files'].items():
    assert hashlib.sha256((app/name).read_bytes()).hexdigest()==expected, name
assert not list((app/'data').iterdir()), 'Distribution contains user data'
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))

def api(info, path, method='GET', body=None, auth=True):
    url=info['url']; origin=url.split('/?')[0]
    headers={'Content-Type':'application/json'}
    if auth: headers['Authorization']='Bearer '+url.split('token=')[1]
    req=urllib.request.Request(origin+path, data=json.dumps(body).encode() if body is not None else None,
                               headers=headers,method=method)
    with http.open(req,timeout=10) as response: return json.load(response)

def launch(folder):
    print('Checking launch / reuse',flush=True)
    with (folder/'launcher-verification.log').open('wb') as log:
        result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(folder/'launcher.ps1'),'-NoBrowser'],
                               stdout=log,stderr=log,creationflags=subprocess.CREATE_NO_WINDOW,timeout=50)
    assert result.returncode==0, 'See launcher-verification.log'
    info=json.loads((folder/'data/app-instance.json').read_text(encoding='utf-8'))
    assert api(info,'/api/system/health')['data']['version']=='5.4.5-fixes'
    return info

def stop(info):
    api(info,'/api/system/shutdown','POST',{})
    for _ in range(100):
        # Wait for the application lease to be released before copying its data.
        p=subprocess.run(['tasklist','/FI',f'PID eq {info["pid"]}','/NH'],capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
        if str(info['pid']).encode() not in p.stdout: return
        time.sleep(.1)
    raise AssertionError('Shutdown timed out')

info=None
try:
    info=launch(app)
    assert launch(app)['pid']==info['pid'], 'Second launch started another instance'
    assert api(info,'/api/chats','POST',{'chat_id':'portable_verify','title':'Проверка переноса'})['ok']
    try:
        api(info,'/api/chats',auth=False)
        raise AssertionError('Unauthorized access accepted')
    except urllib.error.HTTPError as e: assert e.code==401
    with http.open(info['url'],timeout=5) as response: assert b'js/app.js' in response.read()
    origin=info['url'].split('/?')[0]
    with http.open(origin+'/js/app.js',timeout=5) as response: assert response.status==200
    stop(info); info=None
    destination=root/'verification'/('Другое место '+str(time.time_ns()))/'CorporateAgent'
    shutil.copytree(app,destination)
    info=launch(destination)
    databases=json.loads((destination/'data/databases.json').read_text(encoding='utf-8'))
    for name,database in databases.items():
        if database.get('preset'):
            assert Path(database['path']).is_relative_to(destination), 'Database still points to previous folder'
            assert Path(database['path']).is_file(), 'Relocated database missing'
            assert api(info,'/api/databases?'+urllib.parse.urlencode({'name':name,'view':'1'}))['ok'], 'Relocated database query failed'
    chats=api(info,'/api/chats')
    assert 'portable_verify' in json.dumps(chats), 'Chat metadata did not survive relocation'
    stop(info); info=None
    # Exercise the common Java launcher using bundled Windows Java, without a browser.
    result=subprocess.run([str(destination/'jre/bin/java.exe'),'-cp',str(root/'packaging/launcher.jar')+';'+str(destination/'dist/CorporateAgent.jar'),
                           'PortableLauncher',str(destination),'--no-browser'],capture_output=True,timeout=45,creationflags=subprocess.CREATE_NO_WINDOW)
    assert result.returncode==0, result.stderr.decode(errors='replace')
    info=json.loads((destination/'data/app-instance.json').read_text(encoding='utf-8'))
    assert api(info,'/api/system/health')['ok']
    assert api(info,'/api/chats','DELETE',{'chat_id':'portable_verify'})['ok']
    result=subprocess.run([str(destination/'jre/bin/java.exe'),'-cp',str(destination/'launcher.jar')+';'+str(destination/'dist/CorporateAgent.jar'),
                           'PortableLauncher',str(destination),'--stop'],capture_output=True,timeout=30,creationflags=subprocess.CREATE_NO_WINDOW)
    assert result.returncode==0, result.stderr.decode(errors='replace')
    info=None
    assert (destination/'INSTALL.md').is_file() and (destination/'USER_GUIDE.md').is_file()
    print('PASS: archive hashes; clean data; bundled Java; Unicode/spaces; health; auth; web assets; chat creation; duplicate launch; graceful shutdown; relocation with chat data and database paths; common Java launcher; bundled guides.')
finally:
    if info:
        stop(info)
