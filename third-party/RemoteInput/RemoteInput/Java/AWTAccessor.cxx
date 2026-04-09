//
//  AWTAccessor.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "AWTAccessor.hxx"
#include <utility>

namespace java
{
    AWTAccessor::AWTAccessor(JNIEnv* env, jclass cls, jobject accessor) noexcept : env(env), cls(cls), accessor(accessor)
    {
        if (!this->cls)
        {
            this->cls = env->GetObjectClass(accessor);
        }

        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
        env->DeleteLocalRef(std::exchange(this->accessor, env->NewGlobalRef(this->accessor)));
    }

    AWTAccessor::~AWTAccessor() noexcept
    {
        if (cls)
        {
            env->DeleteGlobalRef(cls);
        }

        if (accessor)
        {
            env->DeleteGlobalRef(accessor);
        }
    }

    AWTAccessor::AWTAccessor(AWTAccessor&& other) noexcept : env(other.env), cls(other.cls), accessor(other.accessor)
    {
        other.env = nullptr;
        other.cls = nullptr;
        other.accessor = nullptr;
    }

    AWTAccessor& AWTAccessor::operator = (AWTAccessor&& other) noexcept
    {
        this->env = other.env;
        this->cls = other.cls;
        this->accessor = other.accessor;
        other.env = nullptr;
        other.cls = nullptr;
        other.accessor = nullptr;
        return *this;
    }

    AWTEventAccessor AWTAccessor::getAWTEventAccessor(JNIEnv* env) noexcept
    {
        jclass cls = env->FindClass("sun/awt/AWTEventAccessor");
        static jmethodID methodId = env->GetStaticMethodID(cls, "getAWTEventAccessor", "()Lsun/awt/AWTEventAccessor;");
        jobject accessor = env->CallStaticObjectMethod(cls, methodId);
        return AWTEventAccessor{env, cls, accessor};
    }
}