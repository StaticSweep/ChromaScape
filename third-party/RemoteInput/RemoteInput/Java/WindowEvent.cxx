//
// Created by Brandon on 2021-10-21.
//

#include "WindowEvent.hxx"
#include <utility>

namespace java
{
    WindowEvent::WindowEvent(JNIEnv* env, Component* receiver, std::int32_t id, std::int32_t old_state, std::int32_t new_state) noexcept : AWTEvent(), env(env), cls(nullptr)
    {
        this->cls = env->FindClass("java/awt/event/WindowEvent");
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));

        static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;III)V");
        self = env->NewObject(cls, methodId, receiver->get(), id, old_state, new_state);
        env->DeleteLocalRef(std::exchange(self, static_cast<jclass>(env->NewGlobalRef(self))));
    }

    WindowEvent::~WindowEvent() noexcept
    {
        env->DeleteGlobalRef(cls);
        env->DeleteGlobalRef(self);
    }

    void WindowEvent::Dispatch(JNIEnv* env, Component* receiver, std::int32_t id, std::int32_t old_state, std::int32_t new_state, bool is_system_generated) noexcept
    {
        jclass cls = env->FindClass("java/awt/event/WindowEvent");
        if (cls)
        {
            static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Window;III)V");
            jobject event = env->NewObject(cls, methodId, receiver->get(), id, old_state, new_state);

            if (event)
            {
                AWTEvent::SetSystemGenerated(env, event, is_system_generated);
                receiver->dispatchEvent(event);
                env->DeleteLocalRef(event);
            }

            env->DeleteLocalRef(cls);
        }
    }

    void WindowEvent::Post(JNIEnv* env, Component* receiver, std::int32_t id, std::int32_t old_state, std::int32_t new_state, bool is_system_generated) noexcept
    {
        jclass cls = env->FindClass("java/awt/event/WindowEvent");
        if (cls)
        {
            static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Window;III)V");
            jobject event = env->NewObject(cls, methodId, receiver->get(), id, old_state, new_state);
            if (event)
            {
                AWTEvent::Post(env, event, is_system_generated);
                env->DeleteLocalRef(event);
            }
            env->DeleteLocalRef(cls);
        }
    }
}