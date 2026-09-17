#ifndef JVM_HXX_INCLUDED
#define JVM_HXX_INCLUDED

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#else
#include <dlfcn.h>
#endif // defined

#include <stdexcept>
#include <vector>
#include <string>

#include "JNI_Common.hxx"

class JVM
{
private:
    JavaVM* vm;
    bool createdVM;
    bool loadedJNI;
    #if defined(_WIN32) || defined(_WIN64)
    HMODULE module;
    #else
    void* module;
    #endif // defined

    bool Init(int argc, const char* argv[]) noexcept;
    jint JNI_GetDefaultJavaVMInitArgs(void *args) const noexcept;
    jint JNI_CreateJavaVM(JavaVM **pvm, void **penv, void *args) const noexcept;
    jint JNI_GetCreatedJavaVMs(JavaVM **, jsize, jsize *) const noexcept;


public:
    JVM() noexcept;
    JVM(JNIEnv* env) noexcept;
    JVM(int argc, const char* argv[]) noexcept;
    ~JVM() noexcept;

    operator bool() const noexcept;
    JavaVM* getVM() const noexcept;


    jint AttachCurrentThread(JNIEnv** env) noexcept;
    jint AttachCurrentThreadAsDaemon(JNIEnv** env) noexcept;
    jint DetachCurrentThread() noexcept;
};

#endif // JVM_HXX_INCLUDED
