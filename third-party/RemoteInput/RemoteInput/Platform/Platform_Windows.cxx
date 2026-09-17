#include "Platform.hxx"

#if defined(_WIN32) || defined(_WIN64)
#include <string>
#include <chrono>
#include <cerrno>
#include "Thirdparty/Hook.hxx"
#if defined(CUSTOM_INJECTOR)
#include "Injection/Injector.hxx"
#else
#include "Thirdparty/Injector.hxx"
#endif

#include <windows.h>
#include <tlhelp32.h>
#include <shellscalingapi.h>
#endif // defined

#if defined(_WIN32) || defined(_WIN64)
int GetDpi(HWND hwnd)
{
    HDC hDC = GetDC(hwnd);
    int ydpi = GetDeviceCaps(hDC, LOGPIXELSY);
    ReleaseDC(hwnd, hDC);
    return ydpi;
}

void ReverseScaleDPI(HWND hwnd, int &width, int &height)
{
    HDC hDC = GetDC(hwnd);
    int xdpi = GetDeviceCaps(hDC, LOGPIXELSX);
    int ydpi = GetDeviceCaps(hDC, LOGPIXELSY);
    ReleaseDC(hwnd, hDC);

    // Reverse DPI Scaling
    width = static_cast<int>(96.0 * width / xdpi);
    height = static_cast<int>(96.0 * height / ydpi);
}

void GetDesktopResolution(int &width, int &height) noexcept
{
    const HWND hDesktop = GetDesktopWindow();
    RECT desktop = {0};
    GetWindowRect(hDesktop, &desktop);
    width = desktop.right;
    height = desktop.bottom;

    /*
    // DPI Scaling
    HMODULE user32 = GetModuleHandleA("User32.dll");
    HMODULE shcore = GetModuleHandleA("Shcore.dll");

    DPI_AWARENESS_CONTEXT (*GetWindowDpiAwarenessContext)(HWND) = reinterpret_cast<decltype(GetWindowDpiAwarenessContext)>(GetProcAddress(user32, "GetWindowDpiAwarenessContext"));
    DPI_AWARENESS (*GetAwarenessFromDpiAwarenessContext)(DPI_AWARENESS_CONTEXT) = reinterpret_cast<decltype(GetAwarenessFromDpiAwarenessContext)>(GetProcAddress(user32, "GetAwarenessFromDpiAwarenessContext"));
    HRESULT (*GetDpiForMonitor)(HMONITOR, MONITOR_DPI_TYPE, UINT*, UINT*) = reinterpret_cast<decltype(GetDpiForMonitor)>(GetProcAddress(shcore, "GetDpiForMonitor"));

    if (GetWindowDpiAwarenessContext && GetAwarenessFromDpiAwarenessContext && GetDpiForMonitor)
    {
        DPI_AWARENESS_CONTEXT context = GetWindowDpiAwarenessContext(hDesktop);
        if (context)
        {
            DPI_AWARENESS awareness = GetAwarenessFromDpiAwarenessContext(context);
            if (awareness != DPI_AWARENESS_INVALID && awareness != DPI_AWARENESS_UNAWARE)
            {
                UINT xdpi = 0;
                UINT ydpi = 0;
                HMONITOR hMonitor = MonitorFromWindow(hDesktop, MONITOR_DEFAULTTONEAREST);
                if (GetDpiForMonitor(hMonitor, MDT_EFFECTIVE_DPI, &xdpi, &ydpi) == S_OK)
                {
                    width = static_cast<int>(width / (xdpi / 96.0));
                    height = static_cast<int>(height / (ydpi / 96.0));
                }
            }
        }
    }
     */
}

std::int32_t GetCurrentThreadID() noexcept
{
    return GetCurrentThreadId();
}

bool IsProcessAlive(std::int32_t pid) noexcept
{
    HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (process)
    {
        DWORD ret = WaitForSingleObject(process, 0);
        CloseHandle(process);
        return ret == WAIT_TIMEOUT;
    }
    return false;
}

bool IsThreadAlive(std::int32_t tid) noexcept
{
    HANDLE thread = OpenThread(SYNCHRONIZE, FALSE, tid);
    if (thread)
    {
        DWORD ret = WaitForSingleObject(thread, 0);
        CloseHandle(thread);
        return ret == WAIT_TIMEOUT;
    }
    return false;
}

std::vector<std::int32_t> get_pids() noexcept
{
    std::vector<std::int32_t> result;
    PROCESSENTRY32 processInfo = {0};
    processInfo.dwSize = sizeof(processInfo);

    HANDLE processesSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (processesSnapshot != INVALID_HANDLE_VALUE)
    {
        if (Process32First(processesSnapshot, &processInfo))
        {
            result.push_back(processInfo.th32ProcessID);

            while (Process32Next(processesSnapshot, &processInfo))
            {
                result.push_back(processInfo.th32ProcessID);
            }
        }
        CloseHandle(processesSnapshot);
    }
    return result;
}

std::vector<std::int32_t> get_pids(const char* process_name) noexcept
{
    std::vector<std::int32_t> result;
    PROCESSENTRY32 processInfo = {0};
    processInfo.dwSize = sizeof(processInfo);

    HANDLE processesSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (processesSnapshot != INVALID_HANDLE_VALUE)
    {
        if (Process32First(processesSnapshot, &processInfo))
        {
            if (!_stricmp(process_name, processInfo.szExeFile))
            {
                result.push_back(processInfo.th32ProcessID);
            }

            while (Process32Next(processesSnapshot, &processInfo))
            {
                if (!_stricmp(process_name, processInfo.szExeFile))
                {
                    result.push_back(processInfo.th32ProcessID);
                }
            }
        }

        CloseHandle(processesSnapshot);
    }
    return result;
}

PROCESSENTRY32 GetProcessInfo(std::int32_t pid) noexcept
{
    HANDLE processesSnapshot = nullptr;
    if((processesSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)) == INVALID_HANDLE_VALUE)
    {
        return {0};
    }

    PROCESSENTRY32 processInfo = {0};
    processInfo.dwSize = sizeof(PROCESSENTRY32);
    while(Process32Next(processesSnapshot, &processInfo))
    {
        if(processInfo.th32ProcessID == static_cast<DWORD>(pid))
        {
            CloseHandle(processesSnapshot);
            return processInfo;
        }
    }
    CloseHandle(processesSnapshot);
    return {0};
}

std::vector<std::string> GetLoadedModuleNames(const char* partial_module_name) noexcept
{
    auto find_substring = [](std::string_view haystack, std::string_view needle) -> bool {
        auto it = std::search(haystack.begin(), haystack.end(), needle.begin(), needle.end(), [](char a, char b) {
            return std::toupper(a) == std::toupper(b);
        });
        return it != haystack.end();
    };

    HANDLE modulesSnapshot = nullptr;
    if ((modulesSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, GetCurrentProcessId())) == INVALID_HANDLE_VALUE)
    {
        return {};
    }

    std::vector<std::string> result;
    MODULEENTRY32 moduleInfo = {0};
    moduleInfo.dwSize = sizeof(MODULEENTRY32);
    while (Module32Next(modulesSnapshot, &moduleInfo))
    {
        if (find_substring(moduleInfo.szModule, partial_module_name))
        {
            result.push_back(moduleInfo.szModule);
        }
    }

    CloseHandle(modulesSnapshot);
    return result;
}

MODULEENTRY32 GetModuleInfo(std::int32_t pid, const char* module_name) noexcept
{
    HANDLE modulesSnapshot = nullptr;
    if ((modulesSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, pid)) == INVALID_HANDLE_VALUE)
    {
        return {0};
    }

    MODULEENTRY32 moduleInfo = {0};
    moduleInfo.dwSize = sizeof(MODULEENTRY32);
    while (Module32Next(modulesSnapshot, &moduleInfo))
    {
        if (!_stricmp(module_name, moduleInfo.szModule))
        {
            CloseHandle(modulesSnapshot);
            return moduleInfo;
        }
    }

    CloseHandle(modulesSnapshot);
    return {0};
}

MODULEENTRY32 GetModuleInfo(std::int32_t pid, HMODULE module_handle) noexcept
{
    HANDLE modulesSnapshot = nullptr;
    if ((modulesSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, pid)) == INVALID_HANDLE_VALUE)
    {
        return {0};
    }

    MODULEENTRY32 moduleInfo = {0};
    moduleInfo.dwSize = sizeof(MODULEENTRY32);
    while (Module32Next(modulesSnapshot, &moduleInfo))
    {
        if (module_handle == moduleInfo.hModule)
        {
            CloseHandle(modulesSnapshot);
            return moduleInfo;
        }
    }

    CloseHandle(modulesSnapshot);
    return {0};
}

void PrintProcessInfo(std::int32_t pid) noexcept
{
    PROCESSENTRY32 Proc32 = GetProcessInfo(pid);
    if (Proc32.th32ProcessID != 0)
    {
        printf("  =======================================================\n");
        printf("  Process Name:            %s\n", Proc32.szExeFile);
        printf("  =======================================================\n\n");
        printf("  Process ID:              %lu\n", Proc32.th32ProcessID);
        printf("  Thread Count:            %lu\n", Proc32.cntThreads);
        printf("  Priority Base:           %lu\n", Proc32.pcPriClassBase);
        printf("  Parent Process ID:       %lu\n\n", Proc32.th32ParentProcessID);
        printf("  =======================================================\n");
    }
}

char* realpath(const char* path, char* resolved_path)
{
    auto MapWindowsErrorToErrno = [](DWORD error) -> std::int32_t {
        switch (error)
        {
            case ERROR_FILE_NOT_FOUND:
            case ERROR_PATH_NOT_FOUND:
                return ENOENT;
            case ERROR_ACCESS_DENIED:
                return EACCES;
            case ERROR_INVALID_PARAMETER:
                return EINVAL;
            case ERROR_ALREADY_EXISTS:
                return EEXIST;
            default:
                return EIO;
        }
    };

    if (!path || !resolved_path)
    {
        errno = EINVAL;
        return nullptr;
    }

    HANDLE hFile = CreateFile(
        path,
        GENERIC_READ,
        FILE_SHARE_READ,
        nullptr,
        OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS,
        nullptr
    );

    if (hFile == INVALID_HANDLE_VALUE)
    {
        errno = MapWindowsErrorToErrno(GetLastError());
        return nullptr;
    }

    DWORD result = GetFinalPathNameByHandle(hFile, resolved_path, MAX_PATH, FILE_NAME_NORMALIZED);
    CloseHandle(hFile);

    if (result == 0)
    {
        errno = MapWindowsErrorToErrno(GetLastError());
        return nullptr;
    }

    return resolved_path;
}

bool InjectSelf(std::int32_t pid) noexcept
{
    if (IsProcessAlive(pid))
    {
        std::string file;
        file.resize(MAX_PATH);
        extern HMODULE module;

        if (GetModuleFileName(module, &file[0], MAX_PATH) == 0)
        {
            return false;
        }

        #if defined(CUSTOM_INJECTOR)
        return Injector::Inject(file, pid, nullptr);
        #else
        extern std::vector<std::unique_ptr<Injector>> injectors;

        for (auto& injector : injectors)
        {
            if (injector && injector->get_pid() == pid)
            {
                if (injector->is_injected())
                {
                    return true;
                }

                return injector->Inject(file);;
            }
        }

        std::unique_ptr<Injector> injector = std::make_unique<Injector>(pid);
        if (injector)
        {
            bool result = injector->Inject(file);
            injectors.push_back(std::move(injector));
            return true;
        }
        #endif
    }
    return false;
}

std::int32_t InjectProcess(std::int32_t pid) noexcept
{
    extern HMODULE module;
    MODULEENTRY32 info = GetModuleInfo(pid, module);
    if (info.dwSize == 0)
    {
        if (InjectSelf(pid))
        {
            return pid;
        }
    }
    return -1;
}

std::vector<std::int32_t> InjectProcesses(const char* process_name) noexcept
{
    std::vector<std::int32_t> result;
    std::vector<std::int32_t> pids = get_pids(process_name);
    for (std::int32_t pid : pids)
    {
        if (InjectProcess(pid) != -1)
        {
            result.push_back(pid);
        }
    }
    return result;
}

std::int32_t PIDFromWindow(void* window) noexcept
{
    DWORD pid = 0;
    GetWindowThreadProcessId(static_cast<HWND>(window), &pid);
    return static_cast<std::int32_t>(pid);
}
#endif // defined

#if defined(_WIN32) || defined(_WIN64)
BOOL CALLBACK EnumWindowsProc(HWND hwnd, LPARAM lParam) noexcept
{
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid == GetCurrentProcessId() && lParam)
    {
        char className[MAX_PATH] = {0};
        int result = GetClassName(hwnd, className, sizeof(className));
        if (result != 0 && std::string(className) == "SunAwtFrame")
        {
            *reinterpret_cast<HWND*>(lParam) = hwnd;
            return FALSE;
        }
    }
    return TRUE;
}

std::unique_ptr<Reflection> GetNativeReflector() noexcept
{
    auto TimeOut = [&](std::uint32_t time, std::function<bool()> &&run) -> bool {
        auto start = std::chrono::high_resolution_clock::now();
        while(!run())
        {
            if (elapsed_time<std::chrono::seconds>(start) >= time)
            {
                return false;
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        return true;
    };

    auto IsValidFrame = [](JNIEnv* env, jobject object) -> bool {
        jclass cls = env->FindClass("java/awt/Frame");
        bool result = env->IsInstanceOf(object, cls);
        env->DeleteLocalRef(cls);
        return result;
    };

    auto GetMainWindow = [&] {
        HWND windowFrame = nullptr;
        EnumWindows(EnumWindowsProc, reinterpret_cast<LPARAM>(&windowFrame));
        return windowFrame;
    };

    HMODULE awt = nullptr;
    if (!TimeOut(20, [&]{ return (awt = GetModuleHandle("awt.dll")); }))
    {
        return nullptr;
    }

    if (!TimeOut(5, [&]{ return JVM().getVM() != nullptr; }))
    {
        return nullptr;
    }

    JVM vm = JVM();
    if (!vm)
    {
        return nullptr;
    }

    JNIEnv* env = nullptr;
    if (vm.AttachCurrentThread(&env) == JNI_OK)
    {
        std::unique_ptr<Reflection> reflection;
        bool hasReflection = TimeOut(20, [&]{
            jclass cls = env->FindClass("java/awt/Frame");
            if (!cls)
            {
                return false;
            }

            jmethodID method = env->GetStaticMethodID(cls, "getFrames", "()[Ljava/awt/Frame;");
            if (!method)
            {
                return false;
            }

            jobjectArray frames = static_cast<jobjectArray>(env->CallStaticObjectMethod(cls, method));
            env->DeleteLocalRef(cls);
            if (!frames)
            {
                return false;
            }

            jsize size = env->GetArrayLength(frames);
            for (jsize i = 0; i < size; ++i)
            {
                jobject frame = env->GetObjectArrayElement(frames, i);
                if (frame)
                {
                    if (IsValidFrame(env, frame))
                    {
                        reflection = Reflection::Create(frame);
                        if (reflection)
                        {
                            env->DeleteLocalRef(frames);
                            return true;
                        }
                    }

                    env->DeleteLocalRef(frame);
                }
            }

            env->DeleteLocalRef(frames);
            return false;
        });

        bool hasReflection2 = !hasReflection && TimeOut(20, [&]{
            auto DSGetComponent = reinterpret_cast<jobject (__stdcall *)(JNIEnv*, void*)>(GetProcAddress(awt, "_DSGetComponent@8"));
            if (!DSGetComponent)
            {
                DSGetComponent = reinterpret_cast<jobject (__stdcall* )(JNIEnv*, void*)>(GetProcAddress(awt, "DSGetComponent"));
            }

            if (!DSGetComponent)
            {
                int ordinal = 146;
                char* procName = reinterpret_cast<char*>(static_cast<std::uint64_t>(static_cast<std::uint16_t>(ordinal)));
                DSGetComponent = reinterpret_cast<jobject (__stdcall *)(JNIEnv*, void*)>(GetProcAddress(awt, procName));
            }

            if (!DSGetComponent)
            {
                return false;
            }

            return TimeOut(20, [&]{
                HWND windowFrame = GetMainWindow();
                if (windowFrame)
                {
                    jobject frame = DSGetComponent(reflection->getEnv(), windowFrame);  //java.awt.Frame
                    if (frame)
                    {
                        if (IsValidFrame(env, frame))
                        {
                            reflection = Reflection::Create(frame);
                            if (reflection)
                            {
                                return true;
                            }
                        }
                    }
                }
                return false;
            });
        });

        if (hasReflection || hasReflection2)
        {
            return reflection;
        }
    }

    vm.DetachCurrentThread();
    return nullptr;
}
#endif // defined
