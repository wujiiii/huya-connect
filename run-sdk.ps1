param(
    [string]$Room,
    [string]$PresenterUid,
    [long]$DurationSeconds = 120,
    [string]$OutputDirectory,
    [string]$ReplayDirectory
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & mvn -q -f sdk/pom.xml '-DskipTests' package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'SDK 构建失败' }
    $dependencies = (Get-Content -LiteralPath sdk/target/runtime-classpath.txt -Raw).Trim()
    if ($ReplayDirectory) {
        $sdkArgs = @('--replay', $ReplayDirectory)
    } else {
        if (-not $Room) { throw '请指定 -Room 或 -ReplayDirectory' }
        if (-not $PresenterUid) { $PresenterUid = '-' }
        $sdkArgs = @($Room, $PresenterUid, $DurationSeconds.ToString())
        if ($OutputDirectory) { $sdkArgs += $OutputDirectory }
    }
    & java -cp "sdk/target/classes;$dependencies" dev.huya.connect.sdk.CaptureExample @sdkArgs
    if ($LASTEXITCODE -ne 0) { throw "SDK 示例退出码：$LASTEXITCODE" }
} finally { Pop-Location }
