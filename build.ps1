$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = Split-Path -Parent (Split-Path -Parent $projectRoot)
$javaHome = Join-Path $serverRoot 'runtime\jdk25\jdk-25.0.3'
$buildRoot = Join-Path $projectRoot 'build'
$pluginOut = Join-Path $buildRoot 'plugin-classes'
$testOut = Join-Path $buildRoot 'test-classes'
$jarPath = Join-Path $buildRoot 'StockExchange-1.0.0-gmzc.jar'
$sourcesList = Join-Path $buildRoot 'sources.txt'
$testSourcesList = Join-Path $buildRoot 'test-sources.txt'

$paperApi = Join-Path $serverRoot 'libraries\io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar'
$vaultJar = Join-Path $serverRoot 'plugins\Vault.jar'
$floodgateJar = Join-Path $serverRoot 'plugins\Floodgate-Spigot.jar'
$geyserJar = Join-Path $serverRoot 'plugins\Geyser-Spigot.jar'
$xconomyJar = Join-Path $serverRoot 'plugins\XConomy-Paper-2.26.3.jar'
$mgactivitysJar = Join-Path $serverRoot 'plugins\MGActivitys-1.0.0.jar'
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
foreach ($jar in @($vaultJar, $floodgateJar, $geyserJar, $xconomyJar, $mgactivitysJar)) {
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
