param(
    [Parameter(Mandatory = $true)][string]$Room,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][long]$DurationSeconds,
    [long]$IntervalSeconds = 15
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $projectRoot
try {
    & mvn -q -f legacy-demo/pom.xml '-DskipTests' package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '留言板采集程序构建失败' }
    $dependencyClasspath = (Get-Content -LiteralPath 'legacy-demo/target/runtime-classpath.txt' -Raw).Trim()
    & java -cp "legacy-demo/target/classes;$dependencyClasspath" dev.huya.connect.HeadLineBoardCapture $Room $OutputDirectory $DurationSeconds $IntervalSeconds
    if ($LASTEXITCODE -ne 0) { throw "留言板采集程序退出码：$LASTEXITCODE" }
}
finally {
    Pop-Location
}
