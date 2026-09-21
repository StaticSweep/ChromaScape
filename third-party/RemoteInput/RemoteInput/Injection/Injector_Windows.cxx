//
// Created by Brandon on 2020-05-02.
//

#include "Injector.hxx"

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#endif

#if defined(_WIN32) || defined(_WIN64)
auto IsProcessAlive = [](std::int32_t pid) -> bool {
    HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (process)
    {
        DWORD ret = WaitForSingleObject(process, 0);
        CloseHandle(process);
        return ret == WAIT_TIMEOUT;
    }
    return false;
};

bool Injector::Inject(std::string module_path, std::int32_t pid, void* bootstrap) noexcept
{
    if (IsProcessAlive(pid))
    {
        HMODULE hKernel32 = nullptr;
        void* RemoteAddress = nullptr;
        HANDLE ProcessHandle, hThread = nullptr;
        LPTHREAD_START_ROUTINE LoadLibraryHandle = nullptr;

        if ((ProcessHandle = OpenProcess(PROCESS_ALL_ACCESS, false, pid)))
        {
            if ((hKernel32 = GetModuleHandle("Kernel32.dll")))
            {
                LoadLibraryHandle = reinterpret_cast<LPTHREAD_START_ROUTINE>(GetProcAddress(hKernel32, "LoadLibraryA"));
                if (LoadLibraryHandle)
                {
                    RemoteAddress = VirtualAllocEx(ProcessHandle, nullptr, module_path.size() * sizeof(char), MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
                    if (RemoteAddress)
                    {
                        if (WriteProcessMemory(ProcessHandle, RemoteAddress, &module_path[0], module_path.size() * sizeof(char), nullptr))
                        {
                            hThread = CreateRemoteThread(ProcessHandle, nullptr, 0, LoadLibraryHandle, RemoteAddress, 0, nullptr);
                            if (hThread)
                            {
                                //More than enough time to wait for a proccess to create a thread..
                                WaitForSingleObject(hThread, 5000);
                                CloseHandle(hThread);

                                VirtualFreeEx(ProcessHandle, RemoteAddress, module_path.size() * sizeof(char), MEM_RELEASE);
                                CloseHandle(ProcessHandle);
                                return true;
                            }
                        }

                        VirtualFreeEx(ProcessHandle, RemoteAddress, module_path.size() * sizeof(char), MEM_RELEASE);
                    }
                }
            }
            CloseHandle(ProcessHandle);
        }
    }
    return false;
}
#endif