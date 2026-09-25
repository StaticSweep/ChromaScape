//
//  PointerInfo.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-03-16.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "PointerInfo.hxx"
#include <utility>

namespace java
{
    PointerInfo::PointerInfo(JNIEnv* env, jclass cls, jobject pointerInfo) noexcept : env(env), cls(cls), pointerInfo(pointerInfo)
    {
        if (!this->cls)
        {
            this->cls = env->GetObjectClass(pointerInfo);
        }

        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
        env->DeleteLocalRef(std::exchange(this->pointerInfo, env->NewGlobalRef(this->pointerInfo)));
    }

    PointerInfo::~PointerInfo() noexcept
    {
        if (cls)
        {
            env->DeleteGlobalRef(cls);
        }

        if (pointerInfo)
        {
            env->DeleteGlobalRef(pointerInfo);
        }
    }

    PointerInfo::PointerInfo(PointerInfo&& other) noexcept : env(other.env), cls(other.cls), pointerInfo(other.pointerInfo)
    {
        other.env = nullptr;
        other.cls = nullptr;
        other.pointerInfo = nullptr;
    }

    PointerInfo& PointerInfo::operator = (PointerInfo&& other) noexcept
    {
        this->env = other.env;
        this->cls = other.cls;
        this->pointerInfo = other.pointerInfo;
        other.env = nullptr;
        other.cls = nullptr;
        other.pointerInfo = nullptr;
        return *this;
    }

    PointerInfo PointerInfo::getPointerInfo(JNIEnv* env) noexcept
    {
        jclass cls = env->FindClass("java/awt/MouseInfo");
        static jmethodID methodId = env->GetStaticMethodID(cls, "getPointerInfo", "()Ljava/awt/PointerInfo;");
        jobject pointerInfo = env->CallStaticObjectMethod(cls, methodId);
        return PointerInfo{env, cls, pointerInfo};
    }

    void PointerInfo::getLocation(std::int32_t &x, std::int32_t &y) const noexcept
    {
        jclass pointInfoClass = env->FindClass("java/awt/PointerInfo");
        static jmethodID methodId = env->GetMethodID(pointInfoClass, "getLocation", "()Ljava/awt/Point;");

        jobject object = env->CallObjectMethod(pointerInfo, methodId);
        if (object)
        {
            jclass pointClass = env->FindClass("java/awt/Point");
            static jfieldID xFieldId = env->GetFieldID(pointClass, "x", "I");
            static jfieldID yFieldId = env->GetFieldID(pointClass, "y", "I");
            x = env->GetIntField(object, xFieldId);
            y = env->GetIntField(object, yFieldId);
            env->DeleteLocalRef(pointClass);

            env->DeleteLocalRef(object);
            env->DeleteLocalRef(pointInfoClass);
        }
        else
        {
            x = -1;
            y = -1;
        }
    }

    void PointerInfo::PointToScreen(JNIEnv* env, std::int32_t &x, std::int32_t &y, Component* component) noexcept
    {
    //	jclass swingUtilitiesClass = env->FindClass("javax/swing/SwingUtilities");
    //	static jmethodID methodId = env->GetMethodID(cls, "convertPointFromScreen", "(Ljava/awt/Point;Ljava/awt/Component)");

        std::int32_t cx = 0;
        std::int32_t cy = 0;
        component->getLocationOnScreen(cx, cy);

        x -= cx;
        y -= cy;
    }
}