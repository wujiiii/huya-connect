$ErrorActionPreference='Stop'
$repository=Split-Path -Parent $PSScriptRoot
. (Join-Path $repository 'collector-process.ps1')
Add-Type -TypeDefinition @'
using System;using System.Runtime.InteropServices;
public static class PrivateTestJob {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]public static extern IntPtr CreateJobObject(IntPtr security,string name);
 [DllImport("kernel32.dll",SetLastError=true)]public static extern bool SetInformationJobObject(IntPtr job,int kind,IntPtr data,uint size);
 [DllImport("kernel32.dll",SetLastError=true)]public static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll",SetLastError=true)]public static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
 [DllImport("kernel32.dll")]public static extern bool CloseHandle(IntPtr handle);
}
'@
$folder=Join-Path $repository ('captures/host-exit-check-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $folder 'child') -Force|Out-Null
New-Item -ItemType Directory -Path (Join-Path $folder 'legacy') -Force|Out-Null
@'
param([string]$Folder)
[IO.File]::WriteAllText((Join-Path $Folder 'ready'),'ready')
while(-not(Test-Path -LiteralPath (Join-Path $Folder 'STOP'))){Start-Sleep -Milliseconds 100}
exit 23
'@ | Set-Content -LiteralPath (Join-Path $folder 'child.ps1') -Encoding utf8
@'
param([string]$Repository,[string]$Folder)
. (Join-Path $Repository 'collector-process.ps1')
while(-not(Test-Path -LiteralPath (Join-Path $Folder 'GO'))){
 if(Test-Path -LiteralPath (Join-Path $Folder 'STOP-parent')){exit};Start-Sleep -Milliseconds 100
}
$childFolder=Join-Path $Folder 'child'
$legacyFolder=Join-Path $Folder 'legacy'
$legacyArgs='-NoProfile -NonInteractive -File "'+(Join-Path $Folder 'child.ps1')+'" -Folder "'+$legacyFolder+'"'
$legacy=Start-Process -FilePath (Get-Command pwsh.exe).Source -ArgumentList $legacyArgs -WindowStyle Hidden -PassThru
[IO.File]::WriteAllText((Join-Path $Folder 'legacy-pid'),[string]$legacy.Id)
$arguments='-NoProfile -NonInteractive -File "'+(Join-Path $Folder 'child.ps1')+'" -Folder "'+$childFolder+'"'
$child=Start-CollectorProcess (Get-Command pwsh.exe).Source $arguments $Folder (Join-Path $childFolder 'stdout.log') (Join-Path $childFolder 'stderr.log')
[IO.File]::WriteAllText((Join-Path $Folder 'child-pid'),[string]$child.Id)
while(-not(Test-Path -LiteralPath (Join-Path $Folder 'STOP-parent'))){Start-Sleep -Milliseconds 100}
'@ | Set-Content -LiteralPath (Join-Path $folder 'parent.ps1') -Encoding utf8
$arguments='-NoProfile -NonInteractive -File "'+(Join-Path $folder 'parent.ps1')+'" -Repository "'+$repository+'" -Folder "'+$folder+'"'
$parent=Start-Process -FilePath (Get-Command pwsh.exe).Source -ArgumentList $arguments -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $folder 'parent-stdout.log') -RedirectStandardError (Join-Path $folder 'parent-stderr.log')
$job=[IntPtr]::Zero;$child=$null;$legacy=$null
try {
    $job=[PrivateTestJob]::CreateJobObject([IntPtr]::Zero,$null)
    $limits=[Runtime.InteropServices.Marshal]::AllocHGlobal(144)
    try {
        [Runtime.InteropServices.Marshal]::Copy([byte[]]::new(144),0,$limits,144)
        [Runtime.InteropServices.Marshal]::WriteInt32($limits,16,0x2000)
        if(-not [PrivateTestJob]::SetInformationJobObject($job,9,$limits,144)){throw 'Cannot set private test Job limits'}
    } finally {[Runtime.InteropServices.Marshal]::FreeHGlobal($limits)}
    $handle=[PrivateTestJob]::OpenProcess(0x101,$false,$parent.Id)
    try {if(-not [PrivateTestJob]::AssignProcessToJobObject($job,$handle)){throw 'Cannot assign private launcher to test Job'}}
    finally {[void][PrivateTestJob]::CloseHandle($handle)}
    [IO.File]::WriteAllText((Join-Path $folder 'GO'),'start private test child')
    $deadline=[DateTimeOffset]::UtcNow.AddSeconds(20)
    while(-not(Test-Path -LiteralPath (Join-Path $folder 'child-pid')) -and [DateTimeOffset]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 100}
    if(-not(Test-Path -LiteralPath (Join-Path $folder 'child-pid'))){throw 'Test launcher did not confirm child'}
    $child=Get-Process -Id ([int](Get-Content -LiteralPath (Join-Path $folder 'child-pid')));$null=$child.Handle
    $legacy=Get-Process -Id ([int](Get-Content -LiteralPath (Join-Path $folder 'legacy-pid')));$null=$legacy.Handle
    if(-not(Test-CollectorProcessInJob $legacy.Id)){throw 'Legacy control did not inherit a Job'}
    if(Test-CollectorProcessInJob $child.Id){throw 'Child did not escape test Job'}
    [void][PrivateTestJob]::CloseHandle($job);$job=[IntPtr]::Zero
    if(-not $parent.WaitForExit(5000)){throw 'Closing test Job did not terminate its launcher'}
    if(-not $legacy.WaitForExit(5000)){throw 'Legacy control unexpectedly survived its inherited Job'}
    $child.Refresh();if($child.HasExited){throw 'Collector child died with launcher Job'}
    Write-Output 'Host-exit self-check passed: launcher and legacy child died with KILL_ON_JOB_CLOSE; independent child survived'
} finally {
    [IO.File]::WriteAllText((Join-Path $folder 'child/STOP'),'stop private child')
    [IO.File]::WriteAllText((Join-Path $folder 'legacy/STOP'),'stop legacy test child')
    [IO.File]::WriteAllText((Join-Path $folder 'STOP-parent'),'stop private launcher')
    if($job -ne [IntPtr]::Zero){[void][PrivateTestJob]::CloseHandle($job)}
    if($child -and -not $child.WaitForExit(10000)){throw 'Private child did not stop cooperatively'}
}
