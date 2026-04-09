//
// Created by Brandon on 2021-10-21.
//

#include "Container.hxx"
#include <utility>

namespace java
{
    Container::Container(JNIEnv* env, jobject container, bool canDelete) noexcept : Component(env, nullptr, container, canDelete)
    {
        this->cls = container ? env->GetObjectClass(container) : env->FindClass("java/awt/Container");
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
    }

    Container::Container(JNIEnv* env, jclass cls, jobject container, bool canDelete) noexcept : Component(env, cls, container, canDelete)
    {
    }

    Component Container::getComponent(std::int32_t index) const noexcept
    {
        jclass containerClass = env->FindClass("java/awt/Container");
        static jmethodID methodId = env->GetMethodID(containerClass, "getComponent", "(I)Ljava/awt/Component;");
        env->DeleteLocalRef(containerClass);

        jobject object = env->CallObjectMethod(this->component, methodId, index);
        if (object)
        {
            env->DeleteLocalRef(std::exchange(object, env->NewGlobalRef(object)));
            return {env, object};
        }
        return {env, nullptr};
    }

    int Container::getComponentCount() const noexcept
    {
        jclass containerClass = env->FindClass("java/awt/Container");
        static jmethodID methodId = env->GetMethodID(containerClass, "getComponentCount", "()I");
        env->DeleteLocalRef(containerClass);

        return env->CallIntMethod(component, methodId);
    }
}