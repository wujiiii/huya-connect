param([Parameter(Mandatory=$true)][string]$RequestPath)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'collector-process.ps1')
function Write-ProcessResult([string]$Name,$Value) {
    $path=Join-Path $PSScriptRoot $Name
    [IO.File]::WriteAllText(($path+'.tmp'),($Value|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath ($path+'.tmp') -Destination $path
}
try {
    if(Test-CollectorProcessInJob $PID){throw 'Independent launcher unexpectedly belongs to a Windows Job'}
    $request=Get-Content -LiteralPath $RequestPath -Raw | ConvertFrom-Json
    $child=Start-Process -FilePath $request.executable -ArgumentList $request.arguments -WindowStyle Hidden `
        -WorkingDirectory $request.directory -RedirectStandardOutput $request.stdout -RedirectStandardError $request.stderr -PassThru
    $null=$child.Handle
    Write-ProcessResult 'process-started.json' @{pid=$child.Id;processStartedAt=$child.StartTime.ToUniversalTime().ToString('o')
        hostPid=$PID;isolated=(-not (Test-CollectorProcessInJob $child.Id))}
    $child.WaitForExit()
    Write-ProcessResult 'process-exit.json' @{pid=$child.Id;exitCode=$child.ExitCode;exitedAt=[DateTimeOffset]::UtcNow.ToString('o')}
} catch {
    Write-ProcessResult 'process-failed.json' @{errorType=$_.Exception.GetType().Name;at=[DateTimeOffset]::UtcNow.ToString('o')}
    exit 1
}
