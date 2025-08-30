#ifndef INJECTOR_HPP_INCLUDED
#define INJECTOR_HPP_INCLUDED

#include <windows.h>
#include <map>
#include <string>

class Injector
{
    private:
        DWORD PID;
        std::map<std::string, void*> Modules;
        HANDLE ProcessHandle;

        //void* GetRemoteProcAddress(void* DLL, std::string ProcName);

    public:
        Injector(DWORD PID);
        unsigned long PIDVALUE = PID;
        void* Load(std::string DLLPath);
        bool CallExport(void* DLL, std::string ProcName, void* Data, std::uint32_t Size);
        bool CallExport(std::string DLL, std::string ProcName, void* Data, std::uint32_t Size);
        bool Free(std::string DLLPath);

        ~Injector();
};

#endif // INJECTOR_HPP_INCLUDED

