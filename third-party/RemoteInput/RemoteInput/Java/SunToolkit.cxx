//
// Created by Brandon on 2021-10-21.
//

#include "SunToolkit.hxx"
#include <utility>

namespace java
{
    Window SunToolkit::getContainingWindow(Component* component)
    {
        JNIEnv* env = component->getEnv();
        jclass cls = env->FindClass("sun/awt/SunToolkit");
        if (cls)
        {
            static jmethodID methodId = env->GetStaticMethodID(cls, "getContainingWindow", "(Ljava/awt/Component;)Ljava/awt/Window;");
            if (methodId)
            {
                jobject containing_window = env->CallStaticObjectMethod(cls, methodId, component->get());
                env->DeleteLocalRef(cls);
                env->DeleteLocalRef(std::exchange(containing_window, env->NewGlobalRef(containing_window)));
                return {env, containing_window};
            }
            env->DeleteLocalRef(cls);
        }

        cls = env->FindClass("java/awt/Window");
        if (cls)
        {
            jobject comp = env->NewGlobalRef(component->get());
            while(comp && !env->IsInstanceOf(comp, cls))
            {
                comp = env->NewGlobalRef(Component(env, comp).getParent().get());
            }

            env->DeleteLocalRef(cls);
            return {env, comp};
        }

        return {env, nullptr};
    }
}