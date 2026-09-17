//
//  FocusEvent.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "FocusEvent.hxx"
#include <utility>

namespace java
{
    FocusEvent::FocusEvent(JNIEnv* env, Component* receiver, std::int32_t id, bool temporary, Cause cause) noexcept : AWTEvent(), env(env), cls(nullptr)
    {
        this->cls = env->FindClass("java/awt/event/FocusEvent");
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));

        static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IZ)V");
        self = env->NewObject(cls, methodId, receiver->get(), id, temporary);
        env->DeleteLocalRef(std::exchange(self, static_cast<jclass>(env->NewGlobalRef(self))));
    }

    FocusEvent::~FocusEvent() noexcept
    {
        env->DeleteGlobalRef(cls);
        env->DeleteGlobalRef(self);
    }

    void FocusEvent::Dispatch(JNIEnv* env, Component* receiver, std::int32_t id, bool temporary, Cause cause, bool is_system_generated) noexcept
    {
        jclass cls = env->FindClass("java/awt/event/FocusEvent");
        if (cls)
        {
            static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IZ)V");
            jobject event = env->NewObject(cls, methodId, receiver->get(), id, temporary);

            if (event)
            {
                AWTEvent::SetSystemGenerated(env, event, is_system_generated);
                receiver->dispatchEvent(event);
                env->DeleteLocalRef(event);
            }

            env->DeleteLocalRef(cls);
        }
    }

    void FocusEvent::Post(JNIEnv* env, Component* receiver, std::int32_t id, bool temporary, Cause cause, bool is_system_generated) noexcept
    {
        jclass cls = env->FindClass("java/awt/event/FocusEvent");
        if (cls)
        {
            static jmethodID methodId = env->GetMethodID(cls, "<init>", "(Ljava/awt/Component;IZ)V");
            jobject event = env->NewObject(cls, methodId, receiver->get(), id, temporary);
            if (event)
            {
                AWTEvent::Post(env, event, is_system_generated);
                env->DeleteLocalRef(event);
            }
            env->DeleteLocalRef(cls);
        }
    }

    jobject FocusEvent::GetCauseDescription(JNIEnv* env, Cause cause) noexcept
    {
        //Java 9+
        static const char* const causes[] = {
            "UNKNOWN",
            "MOUSE_EVENT",
            "TRAVERSAL",
            "TRAVERSAL_UP",
            "TRAVERSAL_DOWN",
            "TRAVERSAL_FORWARD",
            "TRAVERSAL_BACKWARD",
            "ROLLBACK",
            "UNEXPECTED",
            "ACTIVATION",
            "CLEAR_GLOBAL_FOCUS_OWNER"
        };

        jclass cause_cls = env->FindClass("java/awt/event/FocusEvent$Cause");
        if (cause_cls)
        {
            static jfieldID fieldId = env->GetStaticFieldID(cause_cls , causes[static_cast<std::uint32_t>(cause)], "Ljava/awt/event/FocusEvent$Cause;");
            jobject cause = env->GetStaticObjectField(cause_cls, fieldId);
            env->DeleteLocalRef(cause_cls);
            env->DeleteLocalRef(std::exchange(cause_cls, static_cast<jclass>(env->NewGlobalRef(cause_cls))));
            return cause;
        }
        return nullptr;
    }
}