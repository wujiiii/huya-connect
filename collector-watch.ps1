param([Parameter(Mandatory=$true)][string]$DataDirectory,[Parameter(Mandatory=$true)][string]$ProjectDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'collector-demo.ps1') -DataDirectory $DataDirectory
$collectorProject=$ProjectDirectory
$watchLock=[IO.File]::Open((Join-Path $DataDirectory 'watchdog.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$tracked=$null; $trackedId=$null; $recoveries=0; $nextRecovery=[DateTimeOffset]::MinValue
$processStart=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
function Write-WatchState([string]$Phase) {
    Write-CollectorJson (Join-Path $DataDirectory 'watchdog.json') @{application='huya-sdk-watchdog';pid=$PID
        processStartedAt=$processStart;updatedAt=[DateTimeOffset]::UtcNow.ToString('o');isolated=$true
        state=$Phase;collectorPid=$trackedId;recoveries=$recoveries;dataDirectory=$DataDirectory}
}
function Write-WatchEvent([string]$Event,$Detail) {
    $record=@{at=[DateTimeOffset]::UtcNow.ToString('o');event=$Event;detail=$Detail}
    [IO.File]::AppendAllText((Join-Path $DataDirectory 'watchdog-events.ndjson'),($record|ConvertTo-Json -Depth 6 -Compress)+"`n",[Text.UTF8Encoding]::new($false))
}
try {
    if(Test-CollectorProcessInJob $PID){throw 'Watchdog must be outside every Windows Job'}
    Write-WatchState 'watching'
    Write-WatchEvent 'attached' @{pid=$PID;isolated=$true}
    while($true){
        $health=Get-CollectorHealth $DataDirectory
        if($health.userStopped){Write-WatchEvent 'user_stop' @{};Write-WatchState 'stopped';break}
        if($health.identityConfirmed -and $health.pid -ne $trackedId){
            if($tracked){$tracked.Dispose()}
            $tracked=Get-Process -Id ([int]$health.pid) -ErrorAction Stop; $null=$tracked.Handle; $trackedId=$tracked.Id
            Write-WatchEvent 'tracking' @{pid=$trackedId;workerInJob=(Test-CollectorProcessInJob $trackedId)}
        }
        if($health.shouldRestart -and [DateTimeOffset]::UtcNow -ge $nextRecovery){
            $exitCode=$null
            if($tracked){$tracked.Refresh();if($tracked.HasExited){$exitCode=$tracked.ExitCode}}
            Write-WatchEvent 'worker_exit' @{pid=$health.pid;runId=$health.runId;exitCode=$exitCode}
            $diagnostics=Join-Path $DataDirectory 'diagnostics';New-Item -ItemType Directory -Path $diagnostics -Force|Out-Null
            $incident=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
            Write-CollectorJson (Join-Path $diagnostics ($incident+'-before-restart.json')) @{
                health=$health;status=(Read-CollectorJson (Join-Path $DataDirectory 'status.json'))
                launch=(Read-CollectorJson (Join-Path $DataDirectory 'launch.json'));exitCode=$exitCode}
            Write-WatchState 'recovering'
            try {
                $after=Start-Collector $DataDirectory -ReuseRuntime -NoWatchdog
                $recoveries++
                $nextRecovery=[DateTimeOffset]::MinValue
                Write-WatchEvent 'worker_restarted' @{pid=$after.pid;runId=$after.runId;healthy=$after.healthy}
            } catch {
                $nextRecovery=[DateTimeOffset]::UtcNow.AddSeconds(60)
                Write-WatchEvent 'restart_failed' @{errorType=$_.Exception.GetType().Name;retryAfter=$nextRecovery.ToString('o')}
            }
        }
        Write-WatchState 'watching'
        Start-Sleep -Seconds 2
    }
} catch {
    Write-WatchEvent 'watchdog_failed' @{errorType=$_.Exception.GetType().Name}
    throw
} finally {
    if($tracked){$tracked.Dispose()}
    $watchLock.Dispose()
}
