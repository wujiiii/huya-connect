function Initialize-CollectorProcessApi {
    if (-not ('HuyaCollector.WindowsProcess' -as [type])) { Add-Type -LiteralPath (Join-Path $PSScriptRoot 'collector-process.cs') }
}

function Start-CollectorProcess([string]$Executable, [string]$Arguments, [string]$Directory,
        [string]$Stdout, [string]$Stderr) {
    Initialize-CollectorProcessApi
    if (-not (Test-CollectorProcessInJob $PID)) {
        return Start-Process -FilePath $Executable -ArgumentList $Arguments -WindowStyle Hidden -WorkingDirectory $Directory `
            -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru
    }
    # A nested host Job can survive CREATE_BREAKAWAY_FROM_JOB. WMI starts outside the host tree.
    $folder=Split-Path -Parent $Stdout
    foreach($file in @('collector-exec.ps1','collector-process.ps1','collector-process.cs')) {
        $source=Join-Path $PSScriptRoot $file; $destination=Join-Path $folder $file
        if ($source -ne $destination) { Copy-Item -LiteralPath $source -Destination $destination }
    }
    $request=Join-Path $folder 'process-request.json'
    $started=Join-Path $folder 'process-started.json'
    if(Test-Path -LiteralPath $request){throw 'Process launch directory must be new'}
    @{executable=$Executable;arguments=$Arguments;directory=$Directory;stdout=$Stdout;stderr=$Stderr} |
        ConvertTo-Json | Set-Content -LiteralPath $request -Encoding utf8
    $pwsh=(Get-Command pwsh.exe -ErrorAction Stop).Source
    $runner=Join-Path $folder 'collector-exec.ps1'
    $startup=New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ShowWindow=[uint16]0;CreateFlags=[uint32]0x09000000}
    $created=Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine=('"'+$pwsh+'" -NoProfile -NonInteractive -File "'+$runner+'" -RequestPath "'+$request+'"')
        CurrentDirectory=$Directory;ProcessStartupInformation=$startup}
    if($created.ReturnValue -ne 0){throw ('Independent launcher failed: WMI code '+$created.ReturnValue)}
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(15)
    while(-not (Test-Path -LiteralPath $started) -and [DateTimeOffset]::UtcNow -lt $deadline){
        if(Test-Path -LiteralPath (Join-Path $folder 'process-failed.json')){throw 'Independent launcher failed; see process-failed.json'}
        Start-Sleep -Milliseconds 100
    }
    if(-not (Test-Path -LiteralPath $started)){throw 'Independent launcher did not confirm startup'}
    $state=Get-Content -LiteralPath $started -Raw | ConvertFrom-Json
    if(-not $state.isolated){throw 'Independent child still belongs to a Windows Job'}
    $process=Get-Process -Id $state.pid -ErrorAction Stop
    $null=$process.Handle
    return $process
}

function Test-CollectorProcessInJob([int]$ProcessId) {
    Initialize-CollectorProcessApi
    return [HuyaCollector.WindowsProcess]::InJob($ProcessId)
}
