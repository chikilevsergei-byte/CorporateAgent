param([switch]$Test, [switch]$Browser, [string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (!$JdkHome) { throw 'Specify -JdkHome or JAVA_HOME pointing to JDK 21.' }
$toolchain=Join-Path $JdkHome 'bin'
$javaCommand = Get-Item -LiteralPath (Join-Path $toolchain 'java.exe')
$javacCommand = Get-Item -LiteralPath (Join-Path $toolchain 'javac.exe')
$compiler = Join-Path $PSScriptRoot '.build-tools\ecj-3.26.0.jar'
$classpath = (Get-ChildItem -LiteralPath lib -Filter *.jar | ForEach-Object FullName) -join ';'
if (!$javacCommand -and !(Test-Path -LiteralPath $compiler)) { throw 'Install JDK 8+ or restore .build-tools/ecj-3.26.0.jar.' }
if (!$javacCommand -and (Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash -ne 'AC0BA5876EAF7EBB47749A0D1BE179C51F194B9DD0B875D1C09E1B530F5A2DB5') { throw 'Compiler checksum mismatch' }
$buildId = [guid]::NewGuid().ToString('N')
$classes = Join-Path $PSScriptRoot "build_test\classes-$buildId"
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath src -Filter *.java -Recurse)
if ($Test -or $Browser) { $sources += Get-ChildItem -LiteralPath test -Filter *.java -Recurse }
$argsPath = Join-Path $classes 'sources.args'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllLines($argsPath, @($sources | ForEach-Object { '"' + $_.FullName.Substring($PSScriptRoot.Length + 1).Replace('\','/') + '"' }), $utf8)
if ($javacCommand) {
    & $javacCommand.FullName --release 21 -encoding UTF-8 -cp $classpath -d $classes "@$argsPath"
} else {
    & $javaCommand.FullName -jar $compiler -1.8 -encoding UTF-8 -warn:none -cp $classpath -d $classes "@$argsPath"
}
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item -LiteralPath src/corpagent/data/presets -Destination (Join-Path $classes presets) -Recurse
Copy-Item -LiteralPath web -Destination (Join-Path $classes web) -Recurse
if ($Test -or $Browser) {
    foreach ($testName in @('corpagent.AgentHeadlessTest','corpagent.SmokeCoreTest','corpagent.api.WebFlowTest','corpagent.PresetRecoveryTest','corpagent.TemplateAcceptance','corpagent.api.YandexSetupTest','corpagent.ScenarioRulesTest')) {
        & $javaCommand.FullName '-Dfile.encoding=UTF-8' '-Djava.awt.headless=true' '-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory' -cp "$classes;$classpath" $testName
        if ($LASTEXITCODE -ne 0) { throw "Failed: $testName" }
    }
}
if ($Browser) {
    $env:CA_TEST_CLASSES = $classes
    $env:CA_TEST_JAVA = $javaCommand.FullName
    try { & node test/corpagent/browser-flow.cjs; if ($LASTEXITCODE -ne 0) { throw 'Browser test failed' } }
    finally { Remove-Item Env:CA_TEST_CLASSES -ErrorAction SilentlyContinue }
}
# Package production classes only. Keep previous builds intact.
$package = Join-Path $PSScriptRoot "build_test\package-$buildId"
New-Item -ItemType Directory -Path "$package\META-INF" -Force | Out-Null
Copy-Item -LiteralPath "$classes\corpagent" -Destination "$package\corpagent" -Recurse
if ($Test -or $Browser) {
    # These are generated test classes, not user files.
    Get-ChildItem -LiteralPath "$package\corpagent" -Filter '*.class' -Recurse | Where-Object { $_.Name -match '^(AgentHeadlessTest|SmokeCoreTest|WebFlowTest|PresetRecoveryTest|TemplateAcceptance|YandexSetupTest|RealFunctionalTest|ScenarioMatrixTest|ScenarioRulesTest|PackageProbe)(\$|\.)' } | Remove-Item
}
Copy-Item -LiteralPath "$classes\presets" -Destination "$package\presets" -Recurse
Copy-Item -LiteralPath "$classes\web" -Destination "$package\web" -Recurse
[IO.File]::WriteAllText("$package\META-INF\MANIFEST.MF", "Manifest-Version: 1.0`r`nMain-Class: corpagent.App`r`n`r`n", $utf8)
New-Item -ItemType Directory -Path dist -Force | Out-Null
$jar = Join-Path $PSScriptRoot ("dist\CorporateAgent-$buildId.jar")
if (Test-Path -LiteralPath $jar) { Move-Item -LiteralPath $jar -Destination "$jar.$buildId.bak" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in Get-ChildItem -LiteralPath $package -File -Recurse) {
        $entryName = $file.FullName.Substring($package.Length + 1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
if ($Test -or $Browser) {
    & $javaCommand.FullName -cp $classes corpagent.PackageProbe $jar
    if ($LASTEXITCODE -ne 0) { throw 'JAR package check failed' }
}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot "dist\current-jar.txt"), "dist\CorporateAgent-$buildId.jar", [Text.Encoding]::ASCII)
Write-Host "Built: $jar"
