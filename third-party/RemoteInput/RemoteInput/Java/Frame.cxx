//
//  Frame.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "Frame.hxx"
#include <utility>

namespace java
{
    Frame::Frame(JNIEnv* env, jobject frame, bool canDelete) noexcept : Window(env, nullptr, frame, canDelete)
    {
        this->cls = frame ? env->GetObjectClass(frame) : env->FindClass("java/awt/JFrame");
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
    }

    Component Frame::getContentPane() const noexcept
    {
        static jmethodID methodId = env->GetMethodID(cls, "getContentPane", "()Ljava/awt/Component;");
        jobject object = env->CallObjectMethod(component, methodId);
        if (object)
        {
            env->DeleteLocalRef(std::exchange(object, env->NewGlobalRef(object)));
            return {env, object};
        }
        return {env, nullptr};
    }
}