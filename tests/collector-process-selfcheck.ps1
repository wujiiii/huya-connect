$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
. (Join-Path $repository 'collector-process.ps1')
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class TestCollectorJob {
 [DllImport("kernel32.dll",SetLastError=true)] public static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
}
'@
$folder=Join-Path $repository ('captures/process-check ' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$child=Join-Path $folder 'child.ps1'
@'
param([string]$Folder)
[Console]::Out.WriteLine('stdout-ok')
[Console]::Error.WriteLine('stderr-ok')
[IO.File]::WriteAllText((Join-Path $Folder 'ready'),'ready')
while(-not (Test-Path -LiteralPath (Join-Path $Folder 'STOP'))) { Start-Sleep -Milliseconds 100 }
exit 23
'@ | Set-Content -LiteralPath $child -Encoding utf8
$pwsh=(Get-Command pwsh.exe).Source
$process=Start-CollectorProcess $pwsh ('-NoProfile -NonInteractive -File "'+$child+'" -Folder "'+$folder+'"') $folder `
    (Join-Path $folder 'stdout.log') (Join-Path $folder 'stderr.log')
try {
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(10)
    while(-not (Test-Path -LiteralPath (Join-Path $folder 'ready')) -and [DateTimeOffset]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 100 }
    if(-not (Test-Path -LiteralPath (Join-Path $folder 'ready'))) { throw 'Child did not start' }
    $handle=[TestCollectorJob]::OpenProcess(0x1000,$false,$process.Id)
    try {$member=$false; if(-not [TestCollectorJob]::IsProcessInJob($handle,[IntPtr]::Zero,[ref]$member)){throw 'Job query failed'}
        if($member){throw 'Collector child still inherits a Windows Job; host exit can terminate it'}
    } finally { if($handle -ne [IntPtr]::Zero){[void][TestCollectorJob]::CloseHandle($handle)} }
} finally {
    [IO.File]::WriteAllText((Join-Path $folder 'STOP'),'stop test child')
    if(-not $process.WaitForExit(10000)){throw 'Test child did not stop cooperatively'}
}
if($process.ExitCode -ne 23){throw 'Child exit code lost'}
if((Get-Content -LiteralPath (Join-Path $folder 'stdout.log') -Raw).Trim() -ne 'stdout-ok'){throw 'Stdout redirection lost'}
if((Get-Content -LiteralPath (Join-Path $folder 'stderr.log') -Raw).Trim() -ne 'stderr-ok'){throw 'Stderr redirection lost'}
Write-Output 'Detached child self-check passed: no Windows Job, hidden launch, spaced paths, stdout/stderr and exit code'
