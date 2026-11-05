using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
namespace HuyaCollector {
    public static class WindowsProcess {
        [DllImport("kernel32.dll",SetLastError=true)] static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool member);
        [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
        public static bool InJob(int pid) {
            IntPtr handle=OpenProcess(0x1000,false,(uint)pid);
            if(handle==IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            try { bool member; if(!IsProcessInJob(handle,IntPtr.Zero,out member)) throw new Win32Exception(Marshal.GetLastWin32Error()); return member; }
            finally { CloseHandle(handle); }
        }
    }
}
