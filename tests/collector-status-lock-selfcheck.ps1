param([Parameter(Mandatory=$true)][string]$RuntimeDirectory)
$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
. (Join-Path $repository 'collector-demo.ps1')
$testRoot=Join-Path $repository ('captures/status-lock-check-'+[Guid]::NewGuid().ToString('N'))
$seed=Join-Path $testRoot 'runtime/seed'
New-Item -ItemType Directory -Path $seed -Force | Out-Null
Get-ChildItem -LiteralPath $RuntimeDirectory -Filter '*.jar' -File | Copy-Item -Destination $seed
Write-CollectorJson (Join-Path $testRoot 'launch.json') @{runtimeDirectory=$seed}
$started=$false; $reader=$null
try {
    $health=Start-Collector $testRoot -ReuseRuntime -NoWatchdog
    $started=$true
    if(-not $health.healthy){throw 'Disposable collector did not register both rooms'}
    $before=Get-CollectorHealth $testRoot
    $reader=[IO.File]::Open((Join-Path $testRoot 'status.json'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    Start-Sleep -Seconds 7
    $during=Get-CollectorHealth $testRoot
    if(-not $during.identityConfirmed){throw 'Status lock terminated disposable capture'}
    $growth=@{}
    foreach($roomId in @('1995','527988')){
        $growth[$roomId]=[long]$during.rooms[$roomId].rawBytes-[long]$before.rooms[$roomId].rawBytes
        if($growth[$roomId] -le 0){throw ('Raw did not grow during status lock: '+$roomId)}
    }
    $reader.Dispose();$reader=$null
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(12)
    do {
        Start-Sleep -Milliseconds 300
        $after=Get-CollectorHealth $testRoot
    } while(([DateTimeOffset]::Parse((Read-CollectorJson (Join-Path $testRoot 'status.json')).updatedAt) -le [DateTimeOffset]::Parse($before.checkedAt)) -and [DateTimeOffset]::UtcNow -lt $deadline)
    if(-not $after.healthy -or $after.statusWriteFailures -le 0 -or $after.fatalError){throw 'Status did not recover after release'}
    [pscustomobject]@{verified=$true;identityConfirmed=$after.identityConfirmed;rawGrowthWhileLocked=$growth
        statusWriteFailures=$after.statusWriteFailures;formalCollectorUntouched=$true} | ConvertTo-Json -Depth 5
} finally {
    if($reader){$reader.Dispose()}
    if($started){$null=Stop-Collector $testRoot}
}
