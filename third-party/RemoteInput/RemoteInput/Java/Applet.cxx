//
//  Applet.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "Applet.hxx"
#include <utility>

namespace java
{
    Applet::Applet(JNIEnv* env, jobject applet, bool canDelete) noexcept : Window(env, nullptr, applet, canDelete)
    {
        this->cls = applet ? env->GetObjectClass(applet) : env->FindClass("java/awt/Applet");
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
    }
}
