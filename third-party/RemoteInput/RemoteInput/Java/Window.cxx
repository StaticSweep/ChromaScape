//
// Created by Brandon on 2021-10-21.
//

#include "Window.hxx"
#include <utility>

namespace java
{
    Window::Window(JNIEnv* env, jobject window, bool canDelete) noexcept : Container(env, nullptr, window, canDelete)
    {
        this->cls = window ? env->GetObjectClass(window) : env->FindClass("java/awt/Window");
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
    }

    Window::Window(JNIEnv* env, jclass cls, jobject window, bool canDelete) noexcept : Container(env, cls, window, canDelete)
    {
    }

    void Window::pack() const noexcept
    {
        static jmethodID methodId = env->GetMethodID(cls, "pack", "()V");
        env->CallVoidMethod(component, methodId);
    }
}