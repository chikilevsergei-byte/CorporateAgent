param([switch]$NoBrowser)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$state=Join-Path $root 'data\app-instance.json'
function Read-Instance {
 try {
  if(!(Test-Path -LiteralPath $state) -or (Get-Item -LiteralPath $state).Length -gt 4096){return $null}
  $info=Get-Content -LiteralPath $state -Raw -Encoding UTF8 | ConvertFrom-Json
  $uri=[Uri]$info.url
  if($uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -le 0 -or $uri.AbsolutePath -ne '/' -or $uri.Query -notmatch '^\?token=[A-Za-z0-9]{32}$' -or $uri.Fragment -or $uri.UserInfo){return $null}
  if(!(Get-Process -Id ([int]$info.pid) -ErrorAction SilentlyContinue)){return $null}
  $request=[Net.WebRequest]::Create($uri.GetLeftPart([UriPartial]::Authority)+'/api/system/health');$request.Proxy=$null;$request.Timeout=2000
  $response=$request.GetResponse()
  try{$reader=New-Object IO.StreamReader($response.GetResponseStream());try{$health=$reader.ReadToEnd() | ConvertFrom-Json}finally{$reader.Dispose()}}finally{$response.Dispose()}
  if(!$health.ok -or $health.data.version -ne '5.4.5-fixes' -or $health.data.instance_id -ne $info.instance_id){return $null}
  return $info
 }catch{return $null}
}
$hash=[Security.Cryptography.SHA256]::Create()
$name='Local\CorporateAgent-'+([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToLowerInvariant())))).Replace('-','')
$mutex=New-Object Threading.Mutex($false,$name);$held=$false
try {
 try{$held=$mutex.WaitOne(30000)}catch [Threading.AbandonedMutexException]{$held=$true}
 if(!$held){throw 'Другой запуск ещё выполняется. Повторите запуск через несколько секунд.'}
 $instance=Read-Instance
 $pointer=Join-Path $root 'dist\current-jar.txt'
 if($instance -and (Test-Path -LiteralPath $pointer)){
  $selected=[IO.File]::ReadAllText($pointer).Trim()
  if($selected -match '^dist[\\/]CorporateAgent[-a-zA-Z0-9.]*\.jar$' -and (Test-Path -LiteralPath (Join-Path $root $selected)) -and $instance.jar_file -ne (Split-Path $selected -Leaf)){
   $instanceUri=[Uri]$instance.url
   $stop=[Net.WebRequest]::Create($instanceUri.GetLeftPart([UriPartial]::Authority)+'/api/system/shutdown');$stop.Proxy=$null;$stop.Timeout=5000;$stop.Method='POST';$stop.ContentType='application/json';$stop.Headers['Authorization']='Bearer '+$instanceUri.Query.Substring(7)
   $bytes=[Text.Encoding]::UTF8.GetBytes('{}');$stop.ContentLength=$bytes.Length;$stream=$stop.GetRequestStream();try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()};$response=$stop.GetResponse();$response.Dispose()
   $deadline=[DateTime]::UtcNow.AddSeconds(8)
   while((Get-Process -Id ([int]$instance.pid) -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 100}
   if(Get-Process -Id ([int]$instance.pid) -ErrorAction SilentlyContinue){throw 'Не удалось завершить предыдущую сборку. Дождитесь её завершения и повторите запуск.'}
   $instance=$null
  }
 }
 if(!$instance){
  $java=Join-Path $root 'jre\bin\java.exe'
  if(!(Test-Path -LiteralPath $java)){throw 'Не найдена встроенная Java: jre\bin\java.exe.'}
  $pointer=Join-Path $root 'dist\current-jar.txt'
  if(!(Test-Path -LiteralPath $pointer)){throw 'Не найден указатель сборки dist\current-jar.txt.'}
  $relative=[IO.File]::ReadAllText($pointer).Trim()
  if($relative -notmatch '^dist[\\/]CorporateAgent[-a-zA-Z0-9.]*\.jar$'){throw 'Некорректный путь сборки.'}
  $jar=Join-Path $root $relative
  if(!(Test-Path -LiteralPath $jar)){throw 'Файл приложения не найден. Требуется пересборка.'}
  $logs=Join-Path $root 'launch-logs';New-Item -ItemType Directory -Path $logs -Force | Out-Null
  $stamp=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
  $stdout=Join-Path $logs ($stamp+'.log');$stderr=Join-Path $logs ($stamp+'.error.log')
  $arguments='-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory -Dfile.encoding=UTF-8 -Dcorpagent.port=0 -Dcorpagent.install="'+$root+'" -Dcorpagent.base="'+(Join-Path $root 'data')+'" -cp "'+$jar+';'+(Join-Path $root 'lib\*')+'" corpagent.App'
  $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
  $deadline=[DateTime]::UtcNow.AddSeconds(20)
  do {
   $instance=Read-Instance
   if($instance){break}
   $process.Refresh()
   if($process.HasExited){throw ('Приложение завершилось при запуске. Журнал: '+$stderr)}
   Start-Sleep -Milliseconds 200
  }while([DateTime]::UtcNow -lt $deadline)
  if(!$instance){throw ('Приложение не подтвердило готовность за 20 секунд. Журналы: '+$logs)}
 }
 if(!$NoBrowser){Start-Process -FilePath $instance.url}
 Write-Output ('Приложение открыто: '+([Uri]$instance.url).GetLeftPart([UriPartial]::Authority)+'/')
 if($instance.mode -eq 'diagnostics'){Write-Output 'Корпоративные сервисы не настроены: создание чатов и работа с моделью пока недоступны.'}
}catch{Write-Host ('Ошибка запуска: '+$_.Exception.Message) -ForegroundColor Red;exit 1}
finally{if($held){$mutex.ReleaseMutex()};$mutex.Dispose()}
