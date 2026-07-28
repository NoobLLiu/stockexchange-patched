$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = Split-Path -Parent (Split-Path -Parent $projectRoot)
$javaHome = 'C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot'
$buildRoot = Join-Path $projectRoot 'build'
$pluginOut = Join-Path $buildRoot 'plugin-classes'
$testOut = Join-Path $buildRoot 'test-classes'
$jarPath = Join-Path $buildRoot 'StockExchange-1.0.0-gmzc.jar'
$sourcesList = Join-Path $buildRoot 'sources.txt'
$testSourcesList = Join-Path $buildRoot 'test-sources.txt'

$paperApi = 'C:\Users\BrianLiu\.m2\repository\io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar'
$vaultJar = 'C:\Users\BrianLiu\.m2\repository\net\milkbowl\vault\VaultAPI\1.7\VaultAPI-1.7.jar'
$floodgateJar = 'C:\Users\BrianLiu\.m2\repository\org\geysermc\floodgate\api\2.2.2-SNAPSHOT\api-2.2.2-SNAPSHOT.jar'
$geyserJar = 'C:\Users\BrianLiu\.m2\repository\org\geysermc\geyser\common\2.2.1-SNAPSHOT\common-2.2.1-SNAPSHOT.jar'
$cumulusJar = 'C:\Users\BrianLiu\.m2\repository\org\geysermc\cumulus\cumulus\1.1.2\cumulus-1.1.2.jar'
$xconomyJar = Join-Path $serverRoot 'plugins\XConomy-Paper-2.26.3.jar'
$mgactivitysJar = Join-Path $projectRoot 'build\MGActivitys-stub-1.0.0.jar'
$gmzcmailJar = Join-Path $projectRoot '..\mail-system\build\GMZCMail-1.0.0.jar'

$m2 = "$env:USERPROFILE\.m2\repository"
$adventureApi = Join-Path $m2 'net\kyori\adventure-api\4.26.1\adventure-api-4.26.1.jar'
$adventureKey = Join-Path $m2 'net\kyori\adventure-key\4.26.1\adventure-key-4.26.1.jar'
$adventureNbt = Join-Path $m2 'net\kyori\adventure-nbt\4.26.1\adventure-nbt-4.26.1.jar'
$examinationApi = Join-Path $m2 'net\kyori\examination-api\1.3.0\examination-api-1.3.0.jar'
$examinationString = Join-Path $m2 'net\kyori\examination-string\1.3.0\examination-string-1.3.0.jar'
$option = Join-Path $m2 'net\kyori\option\1.0.0\option-1.0.0.jar'
$dataPackApi = Join-Path $m2 'io\papermc\paper\paper-api-data\1.21.11-R0.1-SNAPSHOT\paper-api-data-1.21.11-R0.1-SNAPSHOT.jar'
$textSerializerGson = Join-Path $m2 'net\kyori\adventure-text-serializer-gson\4.26.1\adventure-text-serializer-gson-4.26.1.jar'
$textSerializerLegacy = Join-Path $m2 'net\kyori\adventure-text-serializer-legacy\4.26.1\adventure-text-serializer-legacy-4.26.1.jar'
$textSerializerPlain = Join-Path $m2 'net\kyori\adventure-text-serializer-plain\5.1.1\adventure-text-serializer-plain-5.1.1.jar'
$bungeeChat = Join-Path $m2 'net\md-5\bungeecord-chat\1.21-R0.4\bungeecord-chat-1.21-R0.4.jar'
$gson = Join-Path $m2 'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
$guava = Join-Path $m2 'com\google\guava\guava\33.3.0-jre\guava-33.3.0-jre.jar'
$serverClassPathFile = Join-Path $serverRoot 'local-plugins\survival-return-fix\build\classpath.txt'

$baseClassPath = $paperApi
if (Test-Path -LiteralPath $serverClassPathFile) {
    $rawClassPath = (Get-Content -LiteralPath $serverClassPathFile -Raw).Trim()
    if ($rawClassPath) {
        $rewrittenEntries = $rawClassPath -split ';' | ForEach-Object {
            $entry = $_.Trim()
            if (-not $entry) {
                return $null
            }
            if (Test-Path -LiteralPath $entry) {
                return $entry
            }
            if ($entry -match '^[A-Za-z]:\\.*?mc-server\\(.+)$') {
                $candidate = Join-Path $serverRoot $Matches[1]
                if (Test-Path -LiteralPath $candidate) {
                    return $candidate
                }
            }
            return $null
        } | Where-Object { $_ }
        if ($rewrittenEntries.Count -gt 0) {
            $baseClassPath = ($rewrittenEntries -join ';')
        }
    }
}

$compileEntries = @($baseClassPath)
foreach ($jar in @($vaultJar, $floodgateJar, $geyserJar, $cumulusJar, $xconomyJar, $mgactivitysJar, $gmzcmailJar, $adventureApi, $adventureKey, $adventureNbt, $examinationApi, $examinationString, $option, $dataPackApi, $textSerializerGson, $textSerializerLegacy, $textSerializerPlain, $bungeeChat, $gson, $guava)) {
    if (Test-Path -LiteralPath $jar) {
        $compileEntries += $jar
    }
}
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
Copy-Item -LiteralPath (Join-Path $projectRoot 'data\item_database.json') -Destination (Join-Path $pluginOut 'data')

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
