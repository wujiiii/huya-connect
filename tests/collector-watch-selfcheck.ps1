param([Parameter(Mandatory=$true)][string]$RuntimeDirectory)
$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
. (Join-Path $repository 'collector-demo.ps1')
$root=Join-Path $repository ('captures/watchdog-check-'+[Guid]::NewGuid().ToString('N'))
$seed=Join-Path $root 'runtime/seed'
New-Item -ItemType Directory -Path $seed -Force|Out-Null
Get-ChildItem -LiteralPath $RuntimeDirectory -Filter '*.jar' -File|Copy-Item -Destination $seed
Write-CollectorJson (Join-Path $root 'launch.json') @{runtimeDirectory=$seed}
try {
    $first=Start-Collector $root -ReuseRuntime
    if(-not $first.healthy -or -not $first.watchdogActive -or (Test-CollectorProcessInJob $first.pid)){throw 'Isolated test launch failed'}
    $snapshot=Read-CollectorJson (Join-Path $root 'status.json')
    if(-not(Test-CollectorIdentity $snapshot $root)){throw 'Refusing to terminate unverified private test process'}
    $victim=Get-Process -Id $snapshot.pid;$null=$victim.Handle
    $at=[DateTimeOffset]::UtcNow
    Stop-Process -InputObject $victim -Force
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(30)
    do {Start-Sleep -Milliseconds 300;$after=Get-CollectorHealth $root
        if($after.healthy -and $after.pid -ne $first.pid){break}
    }while([DateTimeOffset]::UtcNow -lt $deadline)
    if(-not $after.healthy -or $after.pid -eq $first.pid -or (Test-CollectorProcessInJob $after.pid)){throw 'Private test did not recover independently'}
    $elapsed=([DateTimeOffset]::UtcNow-$at).TotalSeconds
    if($after.watchdogPid -ne $first.watchdogPid){throw 'Watchdog was replaced during recovery'}
    Write-CollectorJson (Join-Path $root 'diagnostics/selfcheck-result.json') @{recoveredWithinSeconds=$elapsed;oldPid=$first.pid;newPid=$after.pid;isolated=$true}
    Write-Output ('Watchdog self-check passed: private collector recovered in '+[Math]::Round($elapsed,2)+' seconds')
} finally {
    if(Test-Path -LiteralPath (Join-Path $root 'status.json')){
        $stopped=Stop-Collector $root
        if($stopped.processAlive -or $stopped.watchdogActive){throw 'Private test did not stop cooperatively'}
    }
}
