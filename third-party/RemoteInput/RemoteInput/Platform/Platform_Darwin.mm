#include "Platform.hxx"

#if defined(__APPLE__)
#define CUSTOM_INJECTOR

#if defined(__cplusplus)
extern "C" {
#endif

#include <Foundation/Foundation.h>
#include <Cocoa/Cocoa.h>

#if defined(__cplusplus)
}
#endif

#include <Thirdparty/Hook.hxx>
#if defined(CUSTOM_INJECTOR)
#include "Injection/Injector.hxx"
#else
#include "Thirdparty/Injector.hxx"
#endif
#include <signal.h>
#include <libproc.h>
#include <sys/syscall.h>
#include <mach-o/dyld.h>
#include <filesystem>
#endif // defined

#if defined(__APPLE__)
std::string StripPath(const std::string& path_to_strip)
{
    return std::filesystem::path(path_to_strip).filename().string();
}

void GetDesktopResolution(int &width, int &height) noexcept
{
    auto get_screen_resolution = [&]{
        NSRect frame = [[NSScreen mainScreen] frame];
        width = static_cast<int>(frame.size.width);
        height = static_cast<int>(frame.size.height);
    };

    if (!NSThread.isMainThread)
    {
        return dispatch_sync(dispatch_get_main_queue(), ^{
            get_screen_resolution();
        });
    }

    get_screen_resolution();
}

std::int32_t GetCurrentThreadID() noexcept
{
    #pragma clang diagnostic push
    #pragma clang diagnostic ignored "-Wdeprecated-declarations"
    return syscall(SYS_thread_selfid);
    #pragma clang diagnostic pop
}

bool IsProcessAlive(std::int32_t pid) noexcept
{
    return !kill(pid, 0);
}

bool IsThreadAlive(std::int32_t tid) noexcept
{
    return !kill(tid, 0);
}

std::vector<std::int32_t> get_pids() noexcept
{
    /*for (NSRunningApplication *app in [[NSWorkspace sharedWorkspace] runningApplications])
    {
        pids.push_back([app processIdentifier]);
    }*/
    std::vector<std::int32_t> pids(2048);
    pids.resize(proc_listpids(PROC_ALL_PIDS, 0, &pids[0], 2048 * sizeof(std::int32_t)) / sizeof(std::int32_t));
    std::vector<std::int32_t>(pids).swap(pids);
    return pids;
}

std::vector<std::int32_t> get_pids(const char* process_name) noexcept
{
    std::vector<std::int32_t> result;
    std::vector<std::int32_t> pids = get_pids();

    for (std::size_t i = 0; i < pids.size(); ++i)
    {
        struct proc_bsdinfo proc;
        if (proc_pidinfo(pids[i], PROC_PIDTBSDINFO, 0, &proc, PROC_PIDTBSDINFO_SIZE) == PROC_PIDTBSDINFO_SIZE)
        {
            if (!strcmp(process_name, proc.pbi_name))
            {
                result.push_back(pids[i]);
            }
            else
            {
                // MacOS sometimes uses the localized name as the process name instead of the executable name
                // So the BSD pbi_name will not always work!
                // Instead, we need to get the running application's localized name
                // This is because a process can have two names on MacOS
                @autoreleasepool {
                    NSRunningApplication *application = [NSRunningApplication runningApplicationWithProcessIdentifier:pids[i]];
                    if (application)
                    {
                        NSString *localized_name = [application localizedName];
                        if (localized_name)
                        {
                            if ([[NSString stringWithUTF8String:process_name] isEqualToString:localized_name])
                            {
                                result.push_back(pids[i]);
                            }
                        }
                    }
                }
            }
        }
    }
    return result;
}

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

std::int32_t PIDFromWindow(void* window) noexcept
{
    NSArray *windowList = (NSArray *)CFBridgingRelease(CGWindowListCopyWindowInfo(kCGWindowListOptionOnScreenOnly, kCGNullWindowID));

    for (int i = 0; i < windowList.count; ++i)
    {
        CGWindowID windowId = [[windowList[i] valueForKey:(NSString *)kCGWindowNumber] intValue];
        if (windowId == (CGWindowID)reinterpret_cast<std::uintptr_t>(window))
        {
            return ((NSNumber *)[windowList[i] valueForKey:(NSString *)kCGWindowOwnerPID]).intValue;
        }
    }
    return 0;
}

std::vector<std::string> GetLoadedModuleNames(const char* partial_module_name) noexcept
{
    std::vector<std::string> result;
    std::uint32_t count = _dyld_image_count();
    for (std::uint32_t i = 0; i < count; ++i)
    {
        const char* name = _dyld_get_image_name(i);
        if (name)
        {
            std::string module_name = StripPath(name);
            if (!strcasestr(module_name.c_str(), partial_module_name))
            {
                result.push_back(name);
            }
        }
    }

    return result;
}

void* GetModuleHandle(const char* module_name) noexcept
{
    std::uint32_t count = _dyld_image_count();
    for (std::uint32_t i = 0; i < count; ++i)
    {
        const char* name = _dyld_get_image_name(i);
        if (name)
        {
            std::string stripped_name = StripPath(name);
            if (strcasestr(stripped_name.c_str(), module_name))
            {
                return dlopen(name, RTLD_NOLOAD);

                /*const struct mach_header* header = _dyld_get_image_header(i);
                //std::intptr_t offset = _dyld_get_image_vmaddr_slide(i);

                const struct load_command* cmd = reinterpret_cast<const struct load_command*>(reinterpret_cast<const char *>(header) + sizeof(struct mach_header));
                if (header->magic == MH_MAGIC_64)
                {
                    cmd = reinterpret_cast<const struct load_command*>(reinterpret_cast<const char *>(header) + sizeof(struct mach_header_64));
                }

                for (std::uint32_t j = 0; j < header->ncmds; ++j)
                {
                    if (cmd->cmd == LC_SEGMENT)
                    {
                        const struct segment_command* seg = reinterpret_cast<const struct segment_command*>(cmd);
                        void* base_addr = reinterpret_cast<void*>(seg->vmaddr); //seg->vmaddr + offset;

                        Dl_info info = {0};
                        dladdr(base_addr, &info);
                        return dlopen(info.dli_fname, RTLD_NOLOAD);
                    }

                    if (cmd->cmd == LC_SEGMENT_64)
                    {
                        const struct segment_command_64* seg = reinterpret_cast<const struct segment_command_64*>(cmd);
                        void* base_addr = reinterpret_cast<void*>(seg->vmaddr); //seg->vmaddr + offset;

                        Dl_info info = {0};
                        dladdr(base_addr, &info);
                        return dlopen(info.dli_fname, RTLD_NOLOAD);
                    }

                    cmd = reinterpret_cast<const struct load_command*>(reinterpret_cast<const char*>(cmd) + cmd->cmdsize);
                }*/
            }
        }
    }
    return dlopen(module_name, RTLD_NOLOAD);
}
#endif // defined


#if defined(__APPLE__)
@interface NSView (MDRecursiveSubviews)
- (jobject)awtComponent:(JNIEnv*)env;
@end

std::unique_ptr<Reflection> GetNativeReflector() noexcept
{
//	auto ModuleLoaded = [](std::string name) -> bool {
//		void* lib = dlopen(name.c_str(), RTLD_GLOBAL | RTLD_NOLOAD);
//		if (lib)
//		{
//			dlclose(lib);
//			return true;
//		}
//		return false;
//	};

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

//	if (!TimeOut(20, [&]{ return ModuleLoaded("libjvm.dylib"); })) {
//		return nullptr;
//	}

    auto IsValidFrame = [](JNIEnv* env, jobject object) -> bool {
        jclass cls = env->FindClass("java/awt/Frame");
        bool result = env->IsInstanceOf(object, cls);
        env->DeleteLocalRef(cls);
        return result;
    };

    auto GetWindowViews = [&]() -> std::vector<NSView*> {
        __block std::vector<NSView*> views;
        dispatch_sync(dispatch_get_main_queue(), ^{
            for (NSWindow* window in NSApplication.sharedApplication.windows)
            {
                //NSLog(@"Window: %@ -- %@", NSStringFromClass([window class]), NSStringFromClass([window.contentView class]));
                if ([window isKindOfClass:NSClassFromString(@"AWTWindow_Normal")] && [window.contentView isKindOfClass:NSClassFromString(@"AWTView")])
                {
                    NSView* view = window.contentView;
                    views.push_back(view);
                }
            }
        });
        return views;
    };

    if (!TimeOut(5, [&]{ return JVM().getVM() != nullptr; }))
    {
        return nullptr;
    }

    JVM vm = JVM();
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
            for (auto&& view : GetWindowViews())
            {
                //TODO: Check if we can call "awt_GetComponent" from the JDK like on Linux
                jobject frame = [view awtComponent:env];  //java.awt.Frame
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

        if (hasReflection || hasReflection2)
        {
            return reflection;
        }
    }

    vm.DetachCurrentThread();
    return nullptr;
}

void disable_app_nap() noexcept
{
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        dispatch_async(dispatch_get_main_queue(), ^{
            static NSObject* app_nap_token __attribute__((used)) = [[NSProcessInfo processInfo] beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep | NSActivityLatencyCritical reason:@"No nappy time"];

            #if defined(DEBUG)
            printf("Disable App-Nap: %p\n", app_nap_token);
            #else
            (void)app_nap_token;
            #endif
        });
    });
}
#endif // defined
