#include "JVM.hxx"

JVM::JVM() noexcept : vm(nullptr), createdVM(false), loadedJNI(false), module(nullptr)
{
    #if defined(_WIN32) || defined(_WIN64)
    this->module = GetModuleHandleW(L"jvm.dll");
    if (!this->module)
    {
        loadedJNI = true;
        this->module = LoadLibraryW(L"jvm.dll");
    }
    #elif defined(__APPLE__)
    extern void* GetModuleHandle(const char*);
    this->loadedJNI = true;
    this->module = GetModuleHandle("libjvm.dylib");

    ///Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/lib/server/libjvm.dylib
    ///Library/Java/JavaVirtualMachines/jdk1.8.0_66.jdk/Contents/Home/jre/lib/server/libjvm.dylib
    #else
    extern void* GetModuleHandle(const char*);
    this->loadedJNI = true;
    this->module = GetModuleHandle("libjvm.so");
    #endif // defined

    #if defined(_WIN32) || defined(_WIN64)
    if (!this->module)
    {
        return;
    }
    #else
    if (!module)
    {
        this->loadedJNI = false;
        module = RTLD_DEFAULT;
    }

    if (!dlsym(module, "JNI_GetCreatedJavaVMs"))
    {
        return;
    }
    #endif

    jint num_vms = 0;
    const jint max_vms = 1;
    JavaVM* vms[max_vms] = {0};
    if (this->JNI_GetCreatedJavaVMs(vms, max_vms, &num_vms) == JNI_OK)
    {
        for (int i = 0; i < num_vms; ++i)
        {
            if (vms[i] != nullptr)
            {
                this->vm = vms[i];
                break;
            }
        }
    }
}

JVM::JVM(JNIEnv* env) noexcept : vm(nullptr), createdVM(false), module(nullptr)
{
    env->GetJavaVM(&this->vm);
}

JVM::JVM(int argc, const char* argv[]) noexcept : vm(nullptr), createdVM(false), module(nullptr)
{
    if (argc)
    {
        Init(argc, argv);
    }
    else
    {
        const char* args[] = {"-Djava.compiler=NONE", "-Djava.class.path=."};
        Init(2, args);
    }
}

JVM::~JVM() noexcept
{
    if (this->vm)
    {
        if (this->createdVM)
        {
            jint (JNICALL *pDestroyJavaVM)() = nullptr;
            #if defined(_WIN32) || defined(_WIN64)
            pDestroyJavaVM = reinterpret_cast<decltype(pDestroyJavaVM)>(GetProcAddress(this->module, "DestroyJavaVM"));
            #else
            pDestroyJavaVM = reinterpret_cast<decltype(pDestroyJavaVM)>(dlsym(this->module, "DestroyJavaVM"));
            #endif // defined

            pDestroyJavaVM();
        }

        this->vm = nullptr;

        if (loadedJNI)
        {
            #if defined(_WIN32) || defined(_WIN64)
            CloseHandle(this->module);
            #else
            dlclose(this->module);
            #endif // defined
        }
        this->module = nullptr;
    }
}

bool JVM::Init(int argc, const char* argv[]) noexcept
{
    #if defined(_WIN32) || defined(_WIN64)
    this->module = LoadLibraryW(L"jvm.dll"); //C:/Program Files/Java/jre1.8.0_73/bin/server/jvm.dll
    #elif defined(__APPLE__)
    this->module = dlopen("libjvm.dylib", RTLD_LAZY); ///Library/Java/JavaVirtualMachines/jdk1.8.0_66.jdk/Contents/Home/jre/lib/server/libjvm.dylib
    #else
    this->module = dlopen("libjvm.so", RTLD_LAZY);
    #endif // defined

    if (!this->module)
    {
        return false;
    }

    bool result = false;
    this->createdVM = false;

    if (argc > 0)
    {
        JavaVMInitArgs jvm_args;
        std::vector<JavaVMOption> options(argc);

        for (int i = 0; i < argc; ++i)
        {
            options[i].optionString = const_cast<char*>(argv[i]);
        }

        JNI_GetDefaultJavaVMInitArgs(&jvm_args);

        jvm_args.version = JNI_VERSION_1_6;
        jvm_args.nOptions = argc;
        jvm_args.options = &options[0];
        jvm_args.ignoreUnrecognized = false;

        result = true;

        jint num_vms = 0;
        const jint max_vms = 5;
        JavaVM* vms[max_vms] = {0};
        if (this->JNI_GetCreatedJavaVMs(vms, max_vms, &num_vms) == JNI_OK)
        {
            for (int i = 0; i < num_vms; ++i)
            {
                if (vms[i] != NULL)
                {
                    this->vm = vms[i];
                    return result;
                }
            }
        }

        this->createdVM = true;
        JNIEnv* env = nullptr;
        if (this->JNI_CreateJavaVM(&this->vm, reinterpret_cast<void**>(&env), &jvm_args) != JNI_OK)
        {
            result = false;
            this->vm = nullptr;
            this->createdVM = false;
        }
    }
    return result;
}

JVM::operator bool() const noexcept
{
    return this->vm != nullptr;
}

JavaVM* JVM::getVM() const noexcept
{
    return vm;
}

jint JVM::AttachCurrentThread(JNIEnv** env) noexcept
{
    if (!this->vm)
    {
        return JNI_ERR;
    }
    return this->vm->AttachCurrentThread(reinterpret_cast<void**>(env), nullptr);
}

jint JVM::AttachCurrentThreadAsDaemon(JNIEnv** env) noexcept
{
    if (!this->vm)
    {
        return JNI_ERR;
    }
    return this->vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(env), nullptr);
}

jint JVM::DetachCurrentThread() noexcept
{
    if (!this->vm)
    {
        return JNI_ERR;
    }
    return this->vm->DetachCurrentThread();
}

jint JVM::JNI_GetDefaultJavaVMInitArgs(void *args) const noexcept
{
    jint (JNICALL *pJNI_GetDefaultJavaVMInitArgs)(void *args) = nullptr;
    #if defined(_WIN32) || defined(_WIN64)
    pJNI_GetDefaultJavaVMInitArgs = reinterpret_cast<decltype(pJNI_GetDefaultJavaVMInitArgs)>(GetProcAddress(this->module, "JNI_GetDefaultJavaVMInitArgs"));
    #else
    pJNI_GetDefaultJavaVMInitArgs = reinterpret_cast<decltype(pJNI_GetDefaultJavaVMInitArgs)>(dlsym(this->module, "JNI_GetDefaultJavaVMInitArgs"));
    #endif // defined
    return pJNI_GetDefaultJavaVMInitArgs(args);
}

jint JVM::JNI_CreateJavaVM(JavaVM **pvm, void **penv, void *args) const noexcept
{
    jint (JNICALL *pJNI_CreateJavaVM)(JavaVM **pvm, void **penv, void *args) = nullptr;
    #if defined(_WIN32) || defined(_WIN64)
    pJNI_CreateJavaVM = reinterpret_cast<decltype(pJNI_CreateJavaVM)>(GetProcAddress(this->module, "JNI_CreateJavaVM"));
    #else
    pJNI_CreateJavaVM = reinterpret_cast<decltype(pJNI_CreateJavaVM)>(dlsym(this->module, "JNI_CreateJavaVM"));
    #endif // defined
    return pJNI_CreateJavaVM(pvm, penv, args);
}

jint JVM::JNI_GetCreatedJavaVMs(JavaVM **vm, jsize size, jsize *size2) const noexcept
{
    jint (JNICALL *pJNI_GetCreatedJavaVMs)(JavaVM **, jsize, jsize *) = nullptr;
    #if defined(_WIN32) || defined(_WIN64)
    pJNI_GetCreatedJavaVMs = reinterpret_cast<decltype(pJNI_GetCreatedJavaVMs)>(GetProcAddress(this->module, "JNI_GetCreatedJavaVMs"));
    #else
    pJNI_GetCreatedJavaVMs = reinterpret_cast<decltype(pJNI_GetCreatedJavaVMs)>(dlsym(this->module, "JNI_GetCreatedJavaVMs"));
    #endif // defined
    return pJNI_GetCreatedJavaVMs(vm, size, size2);
}