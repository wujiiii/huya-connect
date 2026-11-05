$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
. (Join-Path $repository 'collector-demo.ps1')
function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
$root = Join-Path $repository ('captures/demo-selfcheck-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root -Force | Out-Null
$rooms = [ordered]@{}
foreach($entry in @(@('1995','2272316519'),@('527988','1199568157971'))) {
    $folder=Join-Path $root $entry[0]; New-Item -ItemType Directory -Path $folder -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $folder 'raw-0001.ndjson'),'synthetic raw fixture')
    [IO.File]::WriteAllText((Join-Path $folder 'events.ndjson'),'synthetic event fixture')
    $rooms[$entry[0]]=@{presenterUid=$entry[1];recordingDirectory=$folder;state='RUNNING';healthy=$true
        lastProtocolAt=[DateTimeOffset]::UtcNow.ToString('o');lastWriteAt=[DateTimeOffset]::UtcNow.ToString('o')
        receivedFrames=1;decodeErrors=0;reconnects=0}
}
$snapshot=@{application='huya-sdk-collector-demo';pid=$PID;processStartedAt=[DateTimeOffset]::UtcNow.ToString('o')
    updatedAt=[DateTimeOffset]::UtcNow.ToString('o');healthy=$true;phase='running';runId='fixture';rooms=$rooms}
Write-CollectorJson (Join-Path $root 'status.json') $snapshot
Assert-Check ((Read-CollectorJson (Join-Path $root 'status.json')).updatedAt -is [string]) 'Timestamps must remain strings across PowerShell JSON parsing'
Assert-Check ((Get-CollectorFileLength (Join-Path $root '1995/raw-0001.ndjson')) -gt 0) 'Open-handle file lengths must be measured'
Assert-Check (-not (Test-CollectorIdentity (Read-CollectorJson (Join-Path $root 'status.json')) $root)) 'An unrelated PowerShell PID must not pass Java identity'
$identityFunction = (Get-Item Function:Test-CollectorIdentity).ScriptBlock
$startFunction = (Get-Item Function:Start-Collector).ScriptBlock
function Test-CollectorIdentity($Snapshot,[string]$Root) { return $true }
try {
    $good=Get-CollectorHealth $root
    Assert-Check $good.healthy 'Fresh protocol and actual recordings should be healthy'
    Assert-Check ((Get-CollectorNotification $null $good $false) -eq 'none') 'Healthy baseline should be quiet'
    $previous=[pscustomobject]@{health=($good | ConvertTo-Json -Depth 16 | ConvertFrom-Json)}
    Assert-Check ((Get-CollectorNotification $previous $good $false) -eq 'none') 'Unchanged health should be quiet'
    $changed=$good | ConvertTo-Json -Depth 16 | ConvertFrom-Json
    $changed.runId='recovered-fixture'
    Assert-Check ((Get-CollectorNotification $previous $changed $false) -eq 'recovered') 'Watchdog recovery between hourly checks must still notify'
    $changed.runId=$good.runId; $changed.statusWriteFailures=1
    Assert-Check ((Get-CollectorNotification $previous $changed $false) -eq 'status_write_recovered') 'Recovered status-write contention must remain visible'
    Assert-Check ((Get-CollectorNotification ([pscustomobject]@{health=$changed}) $changed $false) -eq 'none') 'Unchanged status-write history must be quiet'
    Write-CollectorJson (Join-Path $root 'monitor-last-check.json') @{checkedAt=[DateTimeOffset]::UtcNow.AddMinutes(-3).ToString('o');health=$good}
    $stalled=Invoke-CollectorMonitor $root
    Assert-Check (-not $stalled.health.healthy -and 'raw_not_growing_1995' -in $stalled.health.reasons) 'Hourly monitor must detect missing physical raw growth'
    $snapshot.updatedAt=[DateTimeOffset]::UtcNow.AddMinutes(-5).ToString('o')
    Write-CollectorJson (Join-Path $root 'status.json') $snapshot
    $stale=Get-CollectorHealth $root
    Assert-Check (-not $stale.healthy -and 'stale_status' -in $stale.reasons) 'Stale process status must fail'
    Assert-Check ((Get-CollectorNotification $previous $stale $false) -eq 'failure') 'A new failure should notify'
    $previousFailure=[pscustomobject]@{health=($stale | ConvertTo-Json -Depth 16 | ConvertFrom-Json)}
    Assert-Check ((Get-CollectorNotification $previousFailure $stale $false) -eq 'none') 'Repeated unchanged failure should be quiet'
    Assert-Check ((Get-CollectorNotification $previousFailure $good $false) -eq 'recovered') 'Recovery should notify'
    $snapshot.updatedAt=[DateTimeOffset]::UtcNow.ToString('o')
    $snapshot.rooms['527988'].presenterUid='999'
    Write-CollectorJson (Join-Path $root 'status.json') $snapshot
    Assert-Check ('room_identity_527988' -in (Get-CollectorHealth $root).reasons) 'Wrong presenter must fail'
    $snapshot.rooms['527988'].presenterUid='1199568157971'
    $snapshot.rooms['1995'].lastProtocolAt=[DateTimeOffset]::UtcNow.AddMinutes(-3).ToString('o')
    Write-CollectorJson (Join-Path $root 'status.json') $snapshot
    Assert-Check ('stale_protocol_1995' -in (Get-CollectorHealth $root).reasons) 'Stale protocol must fail even when status claims healthy'
    $snapshot.rooms['1995'].lastProtocolAt=[DateTimeOffset]::UtcNow.ToString('o')
    $snapshot.pid=2147483647
    Write-CollectorJson (Join-Path $root 'status.json') $snapshot
    Assert-Check (Get-CollectorHealth $root).shouldRestart 'Unexpected process exit should allow restart'
    [IO.File]::WriteAllText((Join-Path $root 'STOP'),'user_requested')
    $stopped=Get-CollectorHealth $root
    Assert-Check ($stopped.userStopped -and -not $stopped.shouldRestart) 'Explicit stop must prevent auto restart'
    Assert-Check ((Get-CollectorNotification $previousFailure $stopped $false) -eq 'none') 'Intentional stop should be quiet'
    $snapshot.fatalError='synthetic disk failure'; Remove-Item -LiteralPath (Join-Path $root 'STOP')
    Write-CollectorJson (Join-Path $root 'status.json') $snapshot
    Assert-Check (-not (Get-CollectorHealth $root).shouldRestart) 'Explicit fatal SDK/storage failure must not loop restart'
    $snapshot.fatalError=$null; Write-CollectorJson (Join-Path $root 'status.json') $snapshot
    function Start-Collector([string]$Root, [switch]$ReuseRuntime) {
        if (-not $ReuseRuntime) { throw 'Monitor must reuse a deployed runtime instead of rebuilding source' }
        return $good
    }
    $recovered=Invoke-CollectorMonitor $root
    Assert-Check ($recovered.recovery -eq 'restarted' -and $recovered.notification -eq 'recovered') 'Unexpected-exit recovery must use the deployed package and report recovery'
    Write-Output 'Collector controller self-check passed: identity, protocol, disk output, stop intent, recovery and quiet notifications'
} finally { Set-Item Function:Test-CollectorIdentity $identityFunction; Set-Item Function:Start-Collector $startFunction }
