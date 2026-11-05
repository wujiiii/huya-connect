param([Parameter(Mandatory = $true)][string]$CaptureDirectory)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $projectRoot
try {
    & mvn -q -f legacy-demo/pom.xml '-DskipTests' package dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '恢复程序构建失败' }
    $dependencyClasspath = (Get-Content -LiteralPath 'legacy-demo/target/runtime-classpath.txt' -Raw).Trim()
    & java -cp "legacy-demo/target/classes;$dependencyClasspath" dev.huya.connect.RecoverCapturedGifts $CaptureDirectory
    if ($LASTEXITCODE -ne 0) { throw "恢复程序退出码：$LASTEXITCODE" }
}
finally {
    Pop-Location
}
