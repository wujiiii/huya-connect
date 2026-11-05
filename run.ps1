param(
    [Parameter(Mandatory = $true)][string]$Room,
    [string]$OutputDirectory,
    [long]$DurationSeconds = 0,
    [string]$ReferenceProject = 'D:\Development\ordinaryroad-live-chat-client-main'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$referenceJar = Join-Path $env:USERPROFILE '.m2\repository\tech\ordinaryroad\live-chat-client-huya\1.2.0\live-chat-client-huya-1.2.0.jar'

if (-not (Test-Path -LiteralPath $referenceJar)) {
    if (-not (Test-Path -LiteralPath (Join-Path $ReferenceProject 'pom.xml'))) {
        throw "找不到参考项目：$ReferenceProject"
    }
    & mvn -q -f (Join-Path $ReferenceProject 'pom.xml') -pl live-chat-clients/live-chat-client-huya -am '-DskipTests' '-Dmaven.javadoc.skip=true' install
    if ($LASTEXITCODE -ne 0) { throw '参考项目构建失败' }
}

Push-Location $projectRoot
try {
    & mvn -q -f legacy-demo/pom.xml '-DskipTests' package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'demo 构建失败' }
    $dependencyClasspath = (Get-Content -LiteralPath 'legacy-demo/target/runtime-classpath.txt' -Raw).Trim()
    $runtimeClasspath = "legacy-demo/target/classes;$dependencyClasspath"
    $appArgs = @('--room', $Room, '--duration-seconds', $DurationSeconds.ToString())
    if ($OutputDirectory) { $appArgs += @('--out', $OutputDirectory) }
    & java -cp $runtimeClasspath dev.huya.connect.CaptureMain @appArgs
    if ($LASTEXITCODE -ne 0) { throw "采集程序退出码：$LASTEXITCODE" }
}
finally {
    Pop-Location
}
