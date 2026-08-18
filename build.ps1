$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$devPluginsRoot = Split-Path -Parent $projectRoot
$workspaceRoot = Split-Path -Parent $devPluginsRoot
$devRoot = Join-Path $workspaceRoot 'dev'
$serverRoot = Join-Path $workspaceRoot 'StarCIty'
$javaHome = Join-Path $serverRoot 'runtime\jdk25\jdk-25.0.3'
$buildRoot = Join-Path $projectRoot 'build'
$pluginOut = Join-Path $buildRoot 'plugin-classes'
$testOut = Join-Path $buildRoot 'test-classes'
$jarPath = Join-Path $buildRoot 'StockExchange-1.0.0-gmzc.jar'
$sourcesList = Join-Path $buildRoot 'sources.txt'
$testSourcesList = Join-Path $buildRoot 'test-sources.txt'

$paperApi = Join-Path $serverRoot 'libraries\io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar'
$pluginRoot = Join-Path $serverRoot 'plugins'
$residenceCandidates = @(Get-ChildItem -LiteralPath $pluginRoot -Filter '*.jar' -File |
    Where-Object {
        $_.Name -like '*Residence6.0.2.3.jar' -and
        $_.Name -notmatch 'ResidenceList|ResidenceGuard'
    })
if ($residenceCandidates.Count -ne 1) {
    throw "Expected exactly one Residence6.0.2.3 main JAR, found $($residenceCandidates.Count)"
}
$residenceJar = $residenceCandidates[0].FullName
$vaultJar = Get-ChildItem -LiteralPath $pluginRoot -Filter '*Vault.jar' -File |
    Select-Object -First 1 -ExpandProperty FullName
$floodgateJar = Get-ChildItem -LiteralPath $pluginRoot -Filter '*Floodgate-Spigot.jar' -File |
    Select-Object -First 1 -ExpandProperty FullName
$geyserJar = Get-ChildItem -LiteralPath $pluginRoot -Filter '*Geyser-Spigot.jar' -File |
    Select-Object -First 1 -ExpandProperty FullName
$xconomyJar = Get-ChildItem -LiteralPath $pluginRoot -Filter '*XConomy*.jar' -File |
    Select-Object -First 1 -ExpandProperty FullName
$mgactivitysJar = Join-Path $devRoot 'local-plugins\mgactivitys\build\MGActivitys-1.0.0.jar'
$gmzcmailJar = Join-Path $devRoot 'local-plugins\mail-system\build\GMZCMail-1.0.0.jar'
$titleJar = Join-Path $devRoot 'local-plugins\title-system\build\GMZCTitles-1.0.0.jar'
$slimefunJar = Get-ChildItem -LiteralPath $pluginRoot -Filter '*Slimefun-United.jar' -File | Select-Object -First 1 -ExpandProperty FullName
$gsonJar = Join-Path $serverRoot 'libraries\com\google\code\gson\gson\2.13.2\gson-2.13.2.jar'
$libraryJars = Get-ChildItem -LiteralPath (Join-Path $serverRoot 'libraries') -Recurse -Filter '*.jar' -File |
    ForEach-Object { $_.FullName }
$compileEntries = @($paperApi)
foreach ($jar in @($residenceJar, $vaultJar, $floodgateJar, $geyserJar, $xconomyJar, $mgactivitysJar, $gmzcmailJar, $titleJar, $slimefunJar, $gsonJar)) {
    if (Test-Path -LiteralPath $jar) {
        $compileEntries += $jar
    }
}
$compileEntries += $libraryJars
$compileClassPath = $compileEntries -join ';'

foreach ($path in @($pluginOut, $testOut)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    New-Item -ItemType Directory -Path $path | Out-Null
}

$pluginSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($pluginSources.Count -gt 0) {
    $quotedSources = $pluginSources | ForEach-Object {
        $relative = $_.Substring($projectRoot.Length).TrimStart('\').Replace('\', '/')
        '"' + $relative + '"'
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($sourcesList, $quotedSources, $utf8NoBom)
    Push-Location -LiteralPath $projectRoot
    try {
        & (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 -proc:none -cp $compileClassPath -d $pluginOut "@$sourcesList"
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}

Copy-Item -LiteralPath (Join-Path $projectRoot 'plugin.yml') -Destination $pluginOut
Copy-Item -LiteralPath (Join-Path $projectRoot 'config.yml') -Destination $pluginOut
Copy-Item -LiteralPath (Join-Path $projectRoot 'vanilla-zh-cn.properties') -Destination $pluginOut
$dataDir = Join-Path $pluginOut 'data'
if (-not (Test-Path -LiteralPath $dataDir)) {
    New-Item -ItemType Directory -Path $dataDir -Force | Out-Null
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'data\item_database.json') -Destination $dataDir

$testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'test') -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName)
if ($testSources.Count -gt 0) {
    $quotedTestSources = $testSources | ForEach-Object {
        $relative = $_.Substring($projectRoot.Length).TrimStart('\').Replace('\', '/')
        '"' + $relative + '"'
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($testSourcesList, $quotedTestSources, $utf8NoBom)
    Push-Location -LiteralPath $projectRoot
    try {
        & (Join-Path $javaHome 'bin\javac.exe') -encoding UTF-8 -proc:none -cp "$compileClassPath;$pluginOut" -d $testOut "@$testSourcesList"
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }

    $testClasses = Get-ChildItem -LiteralPath $testOut -Recurse -Filter *Test.class | ForEach-Object {
        $relative = $_.FullName.Substring($testOut.Length).TrimStart('\')
        ($relative -replace '\\', '.' -replace '\.class$', '')
    }
    foreach ($testClass in $testClasses) {
        & (Join-Path $javaHome 'bin\java.exe') -ea -cp "$compileClassPath;$pluginOut;$testOut" $testClass
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
}

if (Test-Path -LiteralPath $jarPath) {
    Remove-Item -LiteralPath $jarPath -Force
}

& (Join-Path $javaHome 'bin\jar.exe') --create --file $jarPath -C $pluginOut .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Built $jarPath"
