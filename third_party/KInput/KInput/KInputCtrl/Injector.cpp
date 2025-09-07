/*
    This program was modified by ThatOneGuyScripts April,15,2023
    Copyright (C) <2023>  <ThatOneGuyScripts>
    Copyright (C) <2018>  <Kasi Reddy>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "Injector.hpp"
#include "RemoteProcFinder.h"
#include <iostream>
#include <TlHelp32.h>
#include <string>




using namespace std;

Injector::Injector(DWORD PID) : PID(PID), ProcessHandle(nullptr)
{
    this->ProcessHandle = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, PID);
}



void* Injector::Load(std::string DLLPath)
{
    void* Result = nullptr;
    if (!this->ProcessHandle)
        return Result;
    if (Modules.count(DLLPath))
        return Modules[DLLPath];
    void* LoadLib = (void*)GetProcAddress(GetModuleHandle("Kernel32.dll"), "LoadLibraryA");
    if (LoadLib)
    {
        void* DLLEntry = VirtualAllocEx(this->ProcessHandle, nullptr, sizeof(char) * (DLLPath.size() + 1), MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (DLLEntry)
        {
            if (WriteProcessMemory(this->ProcessHandle, DLLEntry, DLLPath.c_str(), sizeof(char) * (DLLPath.size() + 1), nullptr))
            {
                HANDLE RemoteThread = CreateRemoteThread(this->ProcessHandle, nullptr, 0, (LPTHREAD_START_ROUTINE)LoadLib, DLLEntry, 0, nullptr);
                if (RemoteThread)
                {
                    WaitForSingleObject(RemoteThread, INFINITE);
                    void* DLL = nullptr;
                    if (GetExitCodeThread(RemoteThread, (LPDWORD)&DLL))
                    {
                        if (DLL)
                        {
                            Modules[DLLPath] = DLL;
                            Result = DLL;
                        }
                    }
                    CloseHandle(RemoteThread);
                }
            }
            VirtualFreeEx(this->ProcessHandle, DLLEntry, 0, MEM_RELEASE);
        }
    }
    return Result;
}

bool Injector::CallExport(std::string DLLPath, std::string ProcName, void* Data, std::uint32_t Size)
{
    bool Result = false;
    if (!this->ProcessHandle)
    {
        std::cout << "Process handle is invalid" << std::endl;
        return Result;
    }
    if (!Modules.count(DLLPath))
    {
        std::cout << "DLL not found in modules" << std::endl;
        return Result;
    }
    void* DLL = Modules[DLLPath];
    return this->CallExport(DLL, ProcName, Data, Size);
}

bool Injector::CallExport(void* DLL, std::string ProcName, void* Data, std::uint32_t Size)
{
    bool Result = false;
    if (!DLL)
    {
        std::cout << "DLL is null" << std::endl;
        return Result;
    }

    char* dllName = nullptr;
    if (sizeof(void*) == 8) {
        dllName = "KInput.dll";
    }
    else if (sizeof(void*) == 4) {
        dllName = "KInput.dll";
    }
    else {
        std::cout << "Unknown architecture\n";
        return 1;
    }
    char* ProcNameConvert = const_cast<char*>(ProcName.c_str());
    void* Func = GetRemoteFuncAddress(PIDVALUE, dllName, ProcNameConvert);
    if (Func)
    {
        //std::cout << "Function address found: " << Func << std::endl;
        void* DataEntry = VirtualAllocEx(this->ProcessHandle, nullptr, Size, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (DataEntry)
        {
            //std::cout << "Data entry allocated: " << DataEntry << std::endl;
            if (WriteProcessMemory(this->ProcessHandle, DataEntry, Data, Size, nullptr))
            {
                HANDLE RemoteThread = CreateRemoteThread(this->ProcessHandle, nullptr, 0, (LPTHREAD_START_ROUTINE)Func, DataEntry, 0, nullptr);
                if (RemoteThread)
                {
                    //std::cout << "Remote thread created: " << RemoteThread << std::endl;
                    WaitForSingleObject(RemoteThread, INFINITE);
                    GetExitCodeThread(RemoteThread, (LPDWORD)&Result);
                    CloseHandle(RemoteThread);
                    //std::cout << "CallExport succeeded: " << Result << std::endl;
                }
            }
            VirtualFreeEx(this->ProcessHandle, DataEntry, 0, MEM_RELEASE);
        }
    }
    return Result;
}

bool Injector::Free(std::string DLLPath)
{
    bool Result = false;
    if (!this->ProcessHandle)
        return Result;
    if (!Modules.count(DLLPath))
        return true;
    void* DLL = Modules[DLLPath];
    void* FreeLib = (void*)GetProcAddress(GetModuleHandle("Kernel32.dll"), "FreeLibrary");
    if (FreeLib)
    {
        HANDLE RemoteThread = CreateRemoteThread(this->ProcessHandle, nullptr, 0, (LPTHREAD_START_ROUTINE)FreeLib, DLL, 0, nullptr);
        if (RemoteThread)
        {
            WaitForSingleObject(RemoteThread, INFINITE);
            BOOL Freed = FALSE;
            if (GetExitCodeThread(RemoteThread, (LPDWORD)&Freed))
            {
                if (Freed)
                {
                    this->Modules.erase(DLLPath);
                    Result = true;
                }
            }
            CloseHandle(RemoteThread);
        }
    }
    return Result;
}





Injector::~Injector()
{
    if (this->ProcessHandle)
    {
        CloseHandle(this->ProcessHandle);
        this->ProcessHandle = nullptr;
    }
}
