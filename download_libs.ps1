$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = 'https://repo1.maven.org/maven2'
$libDir = Join-Path $PSScriptRoot 'lib'
New-Item -ItemType Directory -Force -Path $libDir | Out-Null

# List of Maven Central paths to download
$jars = @(
    # sqlite-jdbc
    'org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar',
    # Apache POI 5.2.5
    'org/apache/poi/poi/5.2.5/poi-5.2.5.jar',
    'org/apache/poi/poi-ooxml/5.2.5/poi-ooxml-5.2.5.jar',
    'org/apache/poi/poi-ooxml-lite/5.2.5/poi-ooxml-lite-5.2.5.jar',
    'org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar',
    'commons-io/commons-io/2.15.1/commons-io-2.15.1.jar',
    'org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar',
    'org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar',
    'org/apache/xmlbeans/xmlbeans/5.2.0/xmlbeans-5.2.0.jar',
    'org/apache/logging/log4j/log4j-api/2.20.0/log4j-api-2.20.0.jar',
    'com/github/virtuald/curvesapi/1.08/curvesapi-1.08.jar',
    'com/zaxxer/SparseBitSet/1.3/SparseBitSet-1.3.jar',
    # PDFBox 2.0.31
    'org/apache/pdfbox/pdfbox/2.0.31/pdfbox-2.0.31.jar',
    'org/apache/pdfbox/fontbox/2.0.31/fontbox-2.0.31.jar',
    'commons-logging/commons-logging/1.2/commons-logging-1.2.jar'
)

foreach ($j in $jars) {
    $name = Split-Path $j -Leaf
    $dest = Join-Path $libDir $name
    if (Test-Path $dest) {
        Write-Output "SKIP (exists): $name"
        continue
    }
    $url = "$base/$j"
    try {
        Write-Output "Downloading: $name"
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -TimeoutSec 120
    } catch {
        Write-Output "FAIL: $name -> $($_.Exception.Message)"
    }
}
Write-Output '--- DONE ---'
Get-ChildItem $libDir -Filter *.jar | Select-Object -ExpandProperty Name
