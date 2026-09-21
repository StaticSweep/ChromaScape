#include "Platform.hxx"

#if defined(__linux__)
#if defined(CUSTOM_INJECTOR)
#include "Injection/Injector.hxx"
#else
#include "Thirdparty/Injector.hxx"
#endif

#include <X11/Xlib.h>
#include <X11/Xatom.h>
#include <X11/Xutil.h>

#include <signal.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <link.h>

#if __has_include("libproc.h") && __has_include("proc/readproc.h")
#include <proc/readproc.h>
#include <libproc.h>
#else
#include <dirent.h>
#include <unistd.h>
#endif

#include <functional>
#include <cstring>
#include <vector>
#include <algorithm>
#include <filesystem>
#endif // defined

#if defined(__linux__)
std::string StripPath(const std::string& path_to_strip)
{
    return std::filesystem::path(path_to_strip).filename().string();
}

void GetDesktopResolution(int &width, int &height) noexcept
{
    Display* display = XOpenDisplay(nullptr);
    Screen* screen = DefaultScreenOfDisplay(display);
    width = screen->width;
    height = screen->height;
    XCloseDisplay(display);
}

std::int32_t GetCurrentThreadID() noexcept
{
    #ifdef SYS_gettid
    return syscall(SYS_gettid);
    #else
    return gettid();
    #endif
}

bool IsProcessAlive(std::int32_t pid) noexcept
{
    return !kill(pid, 0);
}

bool IsThreadAlive(std::int32_t tid) noexcept
{
    return !syscall(SYS_tkill, tid, 0);
}

#if __has_include("libproc.h") && __has_include("proc/readproc.h")
std::vector<std::int32_t> get_pids() noexcept
{
    std::vector<std::int32_t> pids;

    proc_t proc_info = {0};
    PROCTAB* proc_tab = openproc(PROC_FILLSTAT);
    if (proc_tab)
    {
        while (readproc(proc_tab, &proc_info))
        {
            //task id, the POSIX thread ID (see also: tgid)
            pids.push_back(proc_info.tid); //proc_info.cmd
        }

        closeproc(proc_tab);
    }


    std::vector<std::int32_t>(pids).swap(pids);
    return pids;
}

std::vector<std::int32_t> get_pids(const char* process_name) noexcept
{
    std::vector<std::int32_t> result;

    proc_t proc_info = {0};
    PROCTAB* proc_tab = openproc(PROC_FILLSTAT);
    if (proc_tab)
    {
        while (readproc(proc_tab, &proc_info))
        {
            //basename of executable file in call to exec(2)
            if (!strcasecmp(process_name, proc_info.cmd))
            {
                //task id, the POSIX thread ID (see also: tgid)
                result.push_back(proc_info.tid);
            }
        }

        closeproc(proc_tab);
    }
    return result;
}
#else
std::vector<std::int32_t> get_pids(const char* process_name) noexcept
{
    std::vector<std::int32_t> result;
    DIR* proc_dir = opendir("/proc");
    if (proc_dir)
    {
        auto is_number = [](const std::string& s) -> bool {
            return !s.empty() && std::find_if(s.begin(), s.end(), [](unsigned char c) { return !std::isdigit(c); }) == s.end();
        };

        #if defined(__x86_64__) || defined(__aarch64__)
        while(struct dirent64* entry = readdir64(proc_dir))
        #else
        while(struct dirent* entry = readdir(proc_dir))
        #endif
        {
            if (entry->d_type == DT_DIR)
            {
                if (is_number(entry->d_name))
                {
                    std::string pid = entry->d_name;
                    std::string path = std::string(PATH_MAX, '\0');
                    ssize_t len = readlink(("/proc/" + pid + "/exe").c_str(), &path[0], PATH_MAX - 1);

                    if (len != -1)
                    {
                        std::string::size_type pos = path.find_last_of("/\\");
                        if (pos != std::string::npos)
                        {
                            //Strip path components
                            path = path.substr(pos + 1);
                            pos = path.find_last_of('.');

                            //Strip extension
                            path = pos > 0 && pos != std::string::npos ? path.substr(0, pos) : path;

                            if (!strcasecmp(path.c_str(), process_name))
                            {
                                result.push_back(std::stoi(entry->d_name));
                            }
                        }
                    }
                }
            }
        }

        closedir(proc_dir);
    }
    return result;
}

std::vector<std::int32_t> get_pids() noexcept
{
    std::vector<std::int32_t> result;
    DIR* proc_dir = opendir("/proc");
    if (proc_dir)
    {
        auto is_number = [](const std::string& s) -> bool {
            return !s.empty() && std::find_if(s.begin(), s.end(), [](unsigned char c) { return !std::isdigit(c); }) == s.end();
        };

        #if defined(__x86_64__) || defined(__aarch64__)
        while(struct dirent64* entry = readdir64(proc_dir))
        #else
        while(struct dirent* entry = readdir(proc_dir))
        #endif
        {
            if (entry->d_type == DT_DIR)
            {
                if (is_number(entry->d_name))
                {
                    std::int32_t pid = std::stoi(entry->d_name);
                    result.push_back(pid);
                }
            }
        }

        closedir(proc_dir);
    }
    return result;
}
#endif

std::int32_t InjectProcess(std::int32_t pid) noexcept
{
    Dl_info info = {0};
    if (dladdr(reinterpret_cast<void*>(InjectProcess), &info))
    {
        std::string path = std::string(PATH_MAX, '\0');
        if (realpath(info.dli_fname, &path[0]))
        {
            #if defined(CUSTOM_INJECTOR)
            if (Injector::Inject(path.c_str(), pid, nullptr))
            {
                return pid;
            }
            #else
            extern std::vector<std::unique_ptr<Injector>> injectors;

            for (auto& injector : injectors)
            {
                if (injector && injector->get_pid() == pid)
                {
                    if (injector->is_injected())
                    {
                        return pid;
                    }

                    return injector->Inject(path.c_str()) ? pid : -1;
                }
            }

            std::unique_ptr<Injector> injector = std::make_unique<Injector>(pid);
            if (injector)
            {
                bool result = injector->Inject(path.c_str());
                injectors.push_back(std::move(injector));
                return result ? pid : -1;
            }
            #endif
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
#endif

#if defined(__linux__)
int GetClassName(Display* display, Window window, char* lpClassName, int nMaxCount) noexcept
{
    if (!lpClassName || !nMaxCount)
    {
        return 0;
    }

    XClassHint hint;
    if (XGetClassHint(display, window, &hint))
    {
        if (hint.res_name)
        {
            XFree(hint.res_name);
        }

        if (hint.res_class)
        {
            memset(lpClassName, 0, nMaxCount * sizeof(char));

            int length = strlen(hint.res_class);
            int max_copy = (nMaxCount < length ? nMaxCount - 1 : length);
            strncpy(lpClassName, hint.res_class, max_copy);
            XFree(hint.res_class);
            return max_copy;
        }
    }
    return 0;
}

int GetWindowText(Display* display, Window window, char* lpString, int nMaxCount) noexcept
{
    if (!lpString || !nMaxCount)
    {
        return 0;
    }

    char* name = nullptr;
    if (XFetchName(display, window, &name) > 0)
    {
        memset(lpString, 0, nMaxCount * sizeof(char));

        int length = strlen(name);
        int max_copy = (nMaxCount < length ? nMaxCount - 1 : length);
        strncpy(lpString, name, max_copy);
        XFree(name);
        return max_copy;
    }
    return 0;
}

void EnumWindows(Display* display, bool (*EnumWindowsProc)(Display*, Window, void*), void* other) noexcept
{
    if (!EnumWindowsProc)
    {
        return;
    }

    Atom atomList = XInternAtom(display, "_NET_CLIENT_LIST", true);
    Atom type = None;
    int format = 0;
    unsigned long nItems = 0;
    unsigned long bytesAfter = 0;
    unsigned char* property = nullptr;
    if (XGetWindowProperty(display, XDefaultRootWindow(display), atomList, 0L, ~0L, false, AnyPropertyType, &type, &format, &nItems, &bytesAfter, &property) == Success)
    {
        if (property)
        {
            for (unsigned int i = 0; i < nItems; ++i)
            {
                Window* windows = reinterpret_cast<Window*>(property);
                if (!EnumWindowsProc(display, windows[i], other))
                {
                    break;
                }
            }
            XFree(property);
        }
    }
}

void EnumChildWindows(Display* display, Window parentWindow, bool (*EnumChildProc)(Display*, Window, void*), void* other) noexcept
{
    if (!EnumChildProc)
    {
        return;
    }

    Window root_return;
    Window parent_return;
    Window* children_return;
    unsigned int nchildren_return;
    if (XQueryTree(display, parentWindow, &root_return, &parent_return, &children_return, &nchildren_return))
    {
        for (unsigned int i = 0; i < nchildren_return; ++i)
        {
            if (!EnumChildProc(display, children_return[i], other))
            {
                break;
            }

            // Recursive search children
            EnumChildWindows(display, children_return[i], EnumChildProc, other);
        }
        XFree(children_return);
    }
}

void GetWindowThreadProcessId(Display* display, Window window, std::int32_t* pid) noexcept
{
    if (!pid) { return; }
    *pid = 0;

    Atom atomPID = XInternAtom(display, "_NET_WM_PID", True);
    if (atomPID == None)
    {
        return;
    }

    Atom type = None;
    int format = 0;
    unsigned long nItems = 0;
    unsigned long bytesAfter = 0;
    unsigned char* property = nullptr;

    if (XGetWindowProperty(display, window, atomPID, 0L, 1L, False, XA_CARDINAL, &type, &format, &nItems, &bytesAfter, &property) == 0)
    {
        if (property)
        {
            *pid = *reinterpret_cast<std::int32_t*>(property);
            XFree(property);
        }
    }
}

std::vector<Window> GetWindowsFromProcessId(Display* display, std::int32_t pid) noexcept
{
    // Get Atom PID
    Atom atomPID = XInternAtom(display, "_NET_WM_PID", True);
    if (atomPID == None)
    {
        return std::vector<Window>();
    }

    std::function<void(Display*, Window, Atom, std::int32_t, std::vector<Window>&)> GetWindowsFromPIDInternal = [&](Display* display, Window window, Atom atomPID, std::int32_t pid, std::vector<Window>& results) {
        // Get Window PID
        Atom type = None;
        int format = 0;
        unsigned long nItems = 0;
        unsigned long bytesAfter = 0;
        unsigned char* property = nullptr;

        if (XGetWindowProperty(display, window, atomPID, 0L, 1L, False, XA_CARDINAL, &type, &format, &nItems, &bytesAfter, &property) == 0)
        {
            if (property)
            {
                if (pid == *reinterpret_cast<std::int32_t*>(property))
                {
                    results.emplace_back(window);
                }
                XFree(property);
            }
        }

        // Check all child windows
        Window root_return;
        Window parent_return;
        Window* children_return;
        unsigned int nchildren_return;
        if (XQueryTree(display, window, &root_return, &parent_return, &children_return, &nchildren_return))
        {
            for (unsigned int i = 0; i < nchildren_return; ++i)
            {
                // Recursive search children
                GetWindowsFromPIDInternal(display, children_return[i], atomPID, pid, results);
            }
            XFree(children_return);
        }
    };

    std::vector<Window> windows;
    GetWindowsFromPIDInternal(display, XDefaultRootWindow(display), atomPID, pid, windows);
    return windows;
}

std::int32_t PIDFromWindow(void* window) noexcept
{
    std::int32_t pid = 0;
    Display* display = XOpenDisplay(nullptr);
    GetWindowThreadProcessId(display, reinterpret_cast<Window>(window), &pid);
    XCloseDisplay(display);
    return pid;
}

std::vector<std::string> GetLoadedModuleNames(const char* partial_module_name) noexcept
{
    struct Module { const char* module_name; std::vector<std::string> result; };
    Module module_info = {0};
    module_info.module_name = partial_module_name;

    dl_iterate_phdr([](struct dl_phdr_info *info, size_t size, void *data) -> int {
        if (info && info->dlpi_name)
        {
            Module* module_info = static_cast<Module*>(data);
            std::string module_name = StripPath(info->dlpi_name);
            if (strcasestr(module_name.c_str(), module_info->module_name))
            {
                module_info->result.push_back(info->dlpi_name);
            }
        }
        return 0;
    }, reinterpret_cast<void*>(&module_info));
    return module_info.result;
}

void* GetModuleHandle(const char* module_name) noexcept
{
    struct Module { const char* module_name; void* result; };
    Module module_info = {0};
    module_info.module_name = module_name;
    module_info.result = nullptr;

    dl_iterate_phdr([](struct dl_phdr_info *info, size_t size, void *data) -> int {
        if (info && info->dlpi_name)
        {
            Module* module_info = static_cast<Module*>(data);
            std::string module_name = StripPath(info->dlpi_name);
            if (strcasestr(module_name.c_str(), module_info->module_name))
            {
                module_info->result = dlopen(module_info->module_name, RTLD_NOLOAD);
                return module_info->result ? 1 : 0;
            }
        }
        return 0;
    }, &module_info);
    return module_info.result ? module_info.result : dlopen(module_name, RTLD_NOLOAD);
}
#endif

#if defined(__linux__)
std::array<std::string, 2> recognizedClassNames = {
    "jagexappletviewer",  // Jagex client
    "net-runelite-client-RuneLite",  // RuneLite client
};
bool EnumWindowsProc(Display* display, Window window, void* other) noexcept
{
    std::int32_t pid = 0;
    GetWindowThreadProcessId(display, window, &pid);
    if (pid != getpid() || other == nullptr)
    {
        // wrong process ID, or a null `other` parameter:
        return true;
    }
    char className[256] = {0};
    if (GetClassName(display, window, className, sizeof(className)) > 0)
    {
        const auto matchedClassName = std::find(
            std::begin(recognizedClassNames),
            std::end(recognizedClassNames),
            std::string(className)
        );
        if (matchedClassName != std::end(recognizedClassNames))
        {
            // the correct window has been located, so assign this `Window`
            // object to the `other` parameter, and return false to
            // indicate that the operation was a success.
            *reinterpret_cast<Window*>(other) = window;
            return false;
        }
        #if defined(DEBUG)
        printf("excluded window with class name '%s'\n", className);
        #endif
    }
    return true;
}

std::unique_ptr<Reflection> GetNativeReflector() noexcept
{
    auto ModuleLoaded = [](std::string name) -> bool {
        void* lib = dlopen(name.c_str(), RTLD_LAZY | RTLD_NOLOAD);
        if (lib)
        {
            dlclose(lib);
            return true;
        }
        return false;
    };

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

    if (!TimeOut(5, [&]{ return JVM().getVM() != nullptr; }))
    {
        return nullptr;
    }

    auto GetMainWindow = [&]() -> Window {
        Display* display = XOpenDisplay(nullptr);
        if (display)
        {
            Window windowFrame = 0;
            EnumWindows(display, EnumWindowsProc, &windowFrame);
            XCloseDisplay(display);
            return windowFrame;
        }
        return 0;
    };

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
            if (!ModuleLoaded("libawt_xawt.so"))
            {
                return false;
            }

            auto awt_GetComponent = reinterpret_cast<jobject (*)(JNIEnv*, void*)>(dlsym(RTLD_DEFAULT, "awt_GetComponent"));
            if (!awt_GetComponent)
            {
                return false;
            }

            return TimeOut(20, [&]{
                void* windowFrame = reinterpret_cast<void*>(GetMainWindow());
                if (windowFrame)
                {
                    jobject frame = awt_GetComponent(env, windowFrame);  //java.awt.Frame
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

        if (hasReflection)
        {
            return reflection;
        }
    }

    vm.DetachCurrentThread();
    return nullptr;
}
#endif // defined
