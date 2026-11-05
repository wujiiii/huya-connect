param(
    [ValidateSet('start','stop','status','healthcheck','monitor','watchdog')][string]$Action = 'status',
    [string]$DataDirectory = 'captures/sdk-demo-independent'
)
$ErrorActionPreference = 'Stop'
$collectorProject = $PSScriptRoot
. (Join-Path $PSScriptRoot 'collector-process.ps1')
if (-not [IO.Path]::IsPathRooted($DataDirectory)) { $DataDirectory = Join-Path $collectorProject $DataDirectory }
$collectorData = [IO.Path]::GetFullPath($DataDirectory)

function Read-CollectorJson([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $reader = [IO.StreamReader]::new($stream)
        try {
            $text = $reader.ReadToEnd()
            # PowerShell 7.5 otherwise converts some UTC strings to DateTime and loses Z on reparsing.
            if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { return ($text | ConvertFrom-Json -DateKind String) }
            return ($text | ConvertFrom-Json)
        } finally { $reader.Dispose() }
    } finally { $stream.Dispose() }
}

function Write-CollectorJson([string]$Path, $Value) {
    $temporary = $Path + '.tmp'
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 16), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Get-CollectorFileLength([string]$Path) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try { return $stream.Length } finally { $stream.Dispose() }
}

function Test-CollectorIdentity($Snapshot, [string]$Root) {
    if (-not $Snapshot -or $Snapshot.application -ne 'huya-sdk-collector-demo') { return $false }
    try {
        if (-not [IO.Path]::GetFullPath($Snapshot.dataDirectory).Equals($Root, [StringComparison]::OrdinalIgnoreCase)) { return $false }
        $process = Get-Process -Id ([int]$Snapshot.pid) -ErrorAction Stop
        $expected = [DateTimeOffset]::Parse($Snapshot.processStartedAt)
        if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $expected.UtcDateTime).TotalSeconds) -gt 2) { return $false }
        $native = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$Snapshot.pid)
        return $native.Name -eq 'java.exe' -and $native.CommandLine.Contains('dev.huya.connect.demo.CollectorDemo') -and
            $native.CommandLine.IndexOf($Root, [StringComparison]::OrdinalIgnoreCase) -ge 0
    } catch { return $false }
}

function Get-CollectorHealth([string]$Root) {
    $snapshot = Read-CollectorJson (Join-Path $Root 'status.json')
    $reasons = [Collections.Generic.List[string]]::new()
    $alive = $false
    if ($snapshot) { $alive = [bool](Get-Process -Id ([int]$snapshot.pid) -ErrorAction SilentlyContinue) }
    $identity = Test-CollectorIdentity $snapshot $Root
    $free = if (Test-Path -LiteralPath $Root) { ([IO.DriveInfo]::new([IO.Path]::GetPathRoot($Root))).AvailableFreeSpace } else { 0 }
    $intentional = (Test-Path -LiteralPath (Join-Path $Root 'STOP')) -or
        ($snapshot -and $snapshot.stopReason -in @('user_requested','shutdown_signal'))
    if (-not $snapshot) { $reasons.Add('no_status') }
    elseif (-not $alive) { $reasons.Add('process_not_running') }
    elseif (-not $identity) { $reasons.Add('process_identity_mismatch') }
    if ($snapshot) {
        $age = ([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($snapshot.updatedAt)).TotalSeconds
        if ($age -gt 30 -or $age -lt -5) { $reasons.Add('stale_status') }
        if (-not $snapshot.healthy) { $reasons.Add('collector_not_healthy') }
    }
    if ($free -lt 512MB) { $reasons.Add('disk_critical') }
    $rooms = [ordered]@{}
    foreach ($entry in @(@('1995','2272316519'),@('527988','1199568157971'))) {
        $roomId = $entry[0]; $uid = $entry[1]
        $room = if ($snapshot) { $snapshot.rooms.$roomId } else { $null }
        if (-not $room -or $room.presenterUid -ne $uid) { $reasons.Add('room_identity_' + $roomId); continue }
        $folder = [IO.Path]::GetFullPath($room.recordingDirectory)
        if (-not $folder.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
            $reasons.Add('recording_path_' + $roomId); continue
        }
        $rawFiles = @(Get-ChildItem -LiteralPath $folder -Filter 'raw-*.ndjson' -File -ErrorAction SilentlyContinue)
        # Windows directory metadata can stay at zero until Java closes the file; query open handles.
        $rawBytes = [long]0
        foreach($file in $rawFiles) { $rawBytes += Get-CollectorFileLength $file.FullName }
        $events = Get-Item -LiteralPath (Join-Path $folder 'events.ndjson') -ErrorAction SilentlyContinue
        $eventBytes = if($events){Get-CollectorFileLength $events.FullName}else{0}
        if ($rawBytes -le 0 -or $eventBytes -le 0) { $reasons.Add('no_recorded_data_' + $roomId) }
        if ($room.state -ne 'RUNNING' -or -not $room.healthy) { $reasons.Add('room_not_running_' + $roomId) }
        if (-not $room.lastProtocolAt -or
            ([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($room.lastProtocolAt)).TotalSeconds -gt 100) {
            $reasons.Add('stale_protocol_' + $roomId)
        }
        if (-not $room.lastWriteAt -or
            ([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($room.lastWriteAt)).TotalSeconds -gt 100) {
            $reasons.Add('stale_write_' + $roomId)
        }
        $rooms[$roomId] = [ordered]@{
            presenterUid=$room.presenterUid;state=$room.state;healthy=$room.healthy
            receivedFrames=$room.receivedFrames;sentFrames=$room.sentFrames;pushes=$room.pushes
            danmu=$room.danmu;gifts=$room.gifts;subscriptionCounts=$room.subscriptionCounts
            decodeErrors=$room.decodeErrors;reconnects=$room.reconnects;lastErrorType=$room.lastErrorType
            lastDecodeError=$room.lastDecodeError;lastProtocolAt=$room.lastProtocolAt
            lastHeartbeatReplyAt=$room.lastHeartbeatReplyAt;lastWriteAt=$room.lastWriteAt
            rawBytes=$rawBytes;rawSegments=$rawFiles.Count;eventsBytes=$eventBytes
            snapshots=$room.snapshots
        }
    }
    $canRestart = $snapshot -and $snapshot.application -eq 'huya-sdk-collector-demo' -and
        -not $alive -and -not $intentional -and -not $snapshot.fatalError -and $free -ge 512MB
    $watchdog=Get-CollectorWatchdogInfo $Root
    return [pscustomobject][ordered]@{
        checkedAt=[DateTimeOffset]::UtcNow.ToString('o');healthy=($reasons.Count -eq 0)
        phase=if($snapshot){$snapshot.phase}else{'not_started'};runId=if($snapshot){$snapshot.runId}else{$null}
        pid=if($snapshot){$snapshot.pid}else{$null};processAlive=$alive;identityConfirmed=$identity
        stopReason=if($snapshot){$snapshot.stopReason}else{$null};fatalError=if($snapshot){$snapshot.fatalError}else{$null}
        userStopped=[bool]$intentional;shouldRestart=[bool]$canRestart
        statusWriteFailures=if($snapshot){[long]$snapshot.statusWriteFailures}else{0}
        lastStatusWriteFailureAt=if($snapshot){$snapshot.lastStatusWriteFailureAt}else{$null}
        freeBytes=$free;diskWarning=($free -lt 2GB);reasons=@($reasons.ToArray());rooms=$rooms
        watchdogActive=$watchdog.active;watchdogIsolated=$watchdog.isolated;watchdogPid=$watchdog.pid
        watchdogWarning=($watchdog.configured -and -not $watchdog.active -and -not $intentional)
    }
}

function Start-Collector([string]$Root, [switch]$ReuseRuntime, [switch]$NoWatchdog) {
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    $launchLock = [IO.File]::Open((Join-Path $Root 'launcher.lock'), [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $snapshot = Read-CollectorJson (Join-Path $Root 'status.json')
        if (Test-CollectorIdentity $snapshot $Root) {
            if(-not $NoWatchdog){$null=Enable-CollectorWatchdog $Root}
            return Get-CollectorHealth $Root
        }
        if ($ReuseRuntime) {
            $launch = Read-CollectorJson (Join-Path $Root 'launch.json')
            if (-not $launch) { throw '缺少已部署运行包，未自动重新构建' }
            $source = [IO.Path]::GetFullPath($launch.runtimeDirectory)
            if (-not $source.StartsWith((Join-Path $Root 'runtime') + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw '运行包路径不在本采集目录，未启动'
            }
            $jars = @(Get-ChildItem -LiteralPath $source -Filter '*.jar' -File)
            if ($jars.Count -lt 5) { throw '已部署运行包不完整，未自动重新构建' }
        } else {
            Push-Location $collectorProject
            try {
                & mvn -q -pl collector-demo -am '-DskipTests' install
                if ($LASTEXITCODE -ne 0) { throw 'SDK Demo 构建失败' }
                & mvn -q -f collector-demo/pom.xml dependency:copy-dependencies '-DincludeScope=runtime'
                if ($LASTEXITCODE -ne 0) { throw 'SDK Demo 依赖准备失败' }
            } finally { Pop-Location }
            $jars = @((Get-Item -LiteralPath (Join-Path $collectorProject 'collector-demo/target/huya-collector-demo-0.1.1.jar')))
            $jars += @(Get-ChildItem -LiteralPath (Join-Path $collectorProject 'collector-demo/target/dependency') -Filter '*.jar' -File)
        }
        $launchId = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
        $runtime = Join-Path $Root ('runtime/' + $launchId)
        New-Item -ItemType Directory -Path $runtime -Force | Out-Null
        $jars | Copy-Item -Destination $runtime
        $stopFile = Join-Path $Root 'STOP'
        if (Test-Path -LiteralPath $stopFile) { Remove-Item -LiteralPath $stopFile }
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $arguments = @('-cp', ('"' + $runtime + '\*"'), 'dev.huya.connect.demo.CollectorDemo', '--data-dir', ('"' + $Root + '"'))
        $process = Start-CollectorProcess $java ($arguments -join ' ') $collectorProject `
            (Join-Path $runtime 'stdout.log') (Join-Path $runtime 'stderr.log')
        Write-CollectorJson (Join-Path $Root 'launch.json') @{pid=$process.Id;launchedAt=[DateTimeOffset]::UtcNow.ToString('o')
            runtimeDirectory=$runtime;isolated=(-not (Test-CollectorProcessInJob $process.Id))}
        if(-not $NoWatchdog){$null=Enable-CollectorWatchdog $Root}
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 500
            $process.Refresh()
            if ($process.HasExited) { throw "SDK Demo 启动退出，请检查 $runtime\stderr.log" }
            $current = Read-CollectorJson (Join-Path $Root 'status.json')
            if ($current -and $current.pid -eq $process.Id -and $current.phase -eq 'running') { break }
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        return Get-CollectorHealth $Root
    } finally { $launchLock.Dispose() }
}

function Stop-Collector([string]$Root) {
    $launchLock = [IO.File]::Open((Join-Path $Root 'launcher.lock'), [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
    $snapshot = Read-CollectorJson (Join-Path $Root 'status.json')
    if (-not $snapshot) { throw '尚无采集状态' }
    $identity = Test-CollectorIdentity $snapshot $Root
    if (-not $identity -and (Get-Process -Id ([int]$snapshot.pid) -ErrorAction SilentlyContinue)) {
        throw '无法确认进程身份，未发送停止请求'
    }
    # Persist stop intent even when an already-crashed process is stopped by the user.
    [IO.File]::WriteAllText((Join-Path $Root 'STOP'), 'user_requested')
    if ($identity) {
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
        while ((Test-CollectorIdentity $snapshot $Root) -and [DateTimeOffset]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 500 }
        if (Test-CollectorIdentity $snapshot $Root) { throw '正常停止超时，未强制终止进程' }
    }
        $watchDeadline=[DateTimeOffset]::UtcNow.AddSeconds(15)
        while((Get-CollectorWatchdogInfo $Root).active -and [DateTimeOffset]::UtcNow -lt $watchDeadline){Start-Sleep -Milliseconds 200}
        return Get-CollectorHealth $Root
    } finally { $launchLock.Dispose() }
}

function Get-CollectorNotification($Previous, $Current, [bool]$Recovered) {
    if ($Current.userStopped) { return 'none' }
    if (($Recovered -and $Current.healthy) -or ($Current.healthy -and $Previous -and
            (-not $Previous.health.healthy -or $Current.runId -ne $Previous.health.runId))) { return 'recovered' }
    if (-not $Current.healthy) {
        $signature = ($Current.reasons | Sort-Object) -join ','
        $old = if($Previous){($Previous.health.reasons | Sort-Object) -join ','}else{''}
        if (-not $Previous -or $Previous.health.healthy -or $signature -ne $old) { return 'failure' }
    }
    if ($Current.diskWarning -and (-not $Previous -or -not $Previous.health.diskWarning)) { return 'disk_warning' }
    if ($Current.watchdogWarning -and (-not $Previous -or -not $Previous.health.watchdogWarning)) { return 'watchdog_failure' }
    if ($Current.statusWriteFailures -gt 0 -and (-not $Previous -or $Current.runId -ne $Previous.health.runId -or
            $Current.statusWriteFailures -gt $Previous.health.statusWriteFailures)) { return 'status_write_recovered' }
    foreach ($roomId in @('1995','527988')) {
        $room=$Current.rooms[$roomId]; $old=if($Previous){$Previous.health.rooms.$roomId}else{$null}
        if ($room -and $room.decodeErrors -gt 0 -and (-not $old -or $old.decodeErrors -eq 0 -or $room.lastDecodeError -ne $old.lastDecodeError)) {
            return 'data_quality'
        }
    }
    return 'none'
}

function Invoke-CollectorMonitor([string]$Root) {
    $health = Get-CollectorHealth $Root
    $previous = Read-CollectorJson (Join-Path $Root 'monitor-last-check.json')
    $recovery = $null; $recoveryError = $null; $recovered = $false
    if($health.watchdogWarning -and $health.processAlive -and $health.identityConfirmed){
        try {$null=Enable-CollectorWatchdog $Root; $health=Get-CollectorHealth $Root}
        catch {$recovery='watchdog_attach_failed';$recoveryError=$_.Exception.Message}
    }
    if ($health.shouldRestart) {
        try { $health = Start-Collector $Root -ReuseRuntime; $recovered = $health.healthy; $recovery = if($recovered){'restarted'}else{'restart_pending'} }
        catch { $recovery = 'restart_failed'; $recoveryError = $_.Exception.Message }
    }
    $delta = [ordered]@{}
    if ($previous -and $previous.health.runId -eq $health.runId) {
        foreach($roomId in @('1995','527988')) {
            $room=$health.rooms[$roomId]; $old=$previous.health.rooms.$roomId
            if ($room -and $old) {
                $delta[$roomId]=@{receivedFrames=([long]$room.receivedFrames - [long]$old.receivedFrames)
                    rawBytes=([long]$room.rawBytes - [long]$old.rawBytes);decodeErrors=([long]$room.decodeErrors - [long]$old.decodeErrors)
                    reconnects=([long]$room.reconnects - [long]$old.reconnects)}
                $interval=([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($previous.checkedAt)).TotalSeconds
                if ($interval -ge 120 -and $health.healthy -and $delta[$roomId].rawBytes -le 0) {
                    $health.healthy=$false; $health.reasons += 'raw_not_growing_' + $roomId
                }
            }
        }
    }
    $result = [pscustomobject][ordered]@{checkedAt=[DateTimeOffset]::UtcNow.ToString('o');health=$health;delta=$delta
        recovery=$recovery;recoveryError=$recoveryError;notification=(Get-CollectorNotification $previous $health $recovered)}
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    Write-CollectorJson (Join-Path $Root 'monitor-last-check.json') $result
    return $result
}

function Get-CollectorWatchdogInfo([string]$Root) {
    $state=Read-CollectorJson (Join-Path $Root 'watchdog.json')
    $active=$false
    if($state -and $state.application -eq 'huya-sdk-watchdog'){
        try {
            $process=Get-Process -Id ([int]$state.pid) -ErrorAction Stop
            $sameTime=[Math]::Abs(($process.StartTime.ToUniversalTime()-[DateTimeOffset]::Parse($state.processStartedAt).UtcDateTime).TotalSeconds) -le 2
            $native=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$state.pid)
            $active=$sameTime -and $state.isolated -and $native.Name -eq 'pwsh.exe' -and
                $native.CommandLine.Contains('collector-watch.ps1') -and $native.CommandLine.Contains($Root) -and
                ([DateTimeOffset]::UtcNow-[DateTimeOffset]::Parse($state.updatedAt)).TotalSeconds -lt 75
        } catch {$active=$false}
    }
    return [pscustomobject]@{active=$active;isolated=[bool]($state -and $state.isolated)
        pid=if($state){$state.pid}else{$null};configured=(Test-Path -LiteralPath (Join-Path $Root 'watchdog-launch.json'))}
}

function Enable-CollectorWatchdog([string]$Root) {
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    $gate=[IO.File]::Open((Join-Path $Root 'watchdog-launcher.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try {
        $existing=Get-CollectorWatchdogInfo $Root
        if($existing.active){return $existing}
        $launchId=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
        $folder=Join-Path $Root ('supervisor/'+$launchId)
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
        foreach($name in @('collector-watch.ps1','collector-demo.ps1','collector-process.ps1','collector-process.cs','collector-exec.ps1')){
            Copy-Item -LiteralPath (Join-Path $collectorProject $name) -Destination (Join-Path $folder $name)
        }
        $pwsh=(Get-Command pwsh.exe).Source
        $arguments='-NoProfile -NonInteractive -File "'+(Join-Path $folder 'collector-watch.ps1')+'" -DataDirectory "'+$Root+'" -ProjectDirectory "'+$collectorProject+'"'
        $process=Start-CollectorProcess $pwsh $arguments $collectorProject (Join-Path $folder 'stdout.log') (Join-Path $folder 'stderr.log')
        Write-CollectorJson (Join-Path $Root 'watchdog-launch.json') @{pid=$process.Id;directory=$folder;createdAt=[DateTimeOffset]::UtcNow.ToString('o')}
        $deadline=[DateTimeOffset]::UtcNow.AddSeconds(15)
        do {
            $info=Get-CollectorWatchdogInfo $Root
            if($info.active -and $info.pid -eq $process.Id){return $info}
            if($process.HasExited){throw 'Independent watchdog exited; inspect its stderr.log'}
            Start-Sleep -Milliseconds 100
        } while([DateTimeOffset]::UtcNow -lt $deadline)
        throw 'Independent watchdog did not confirm readiness'
    } finally {$gate.Dispose()}
}

if ($MyInvocation.InvocationName -ne '.') {
    $result = switch ($Action) {
        'start' { Start-Collector $collectorData }
        'stop' { Stop-Collector $collectorData }
        'monitor' { Invoke-CollectorMonitor $collectorData }
        'watchdog' { Enable-CollectorWatchdog $collectorData }
        default { Get-CollectorHealth $collectorData }
    }
    $result | ConvertTo-Json -Depth 16
    if ($Action -eq 'healthcheck' -and -not $result.healthy) { exit 2 }
}
