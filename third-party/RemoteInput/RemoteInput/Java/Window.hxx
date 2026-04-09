//
// Created by Brandon on 2021-10-21.
//

#ifndef REMOTEINPUT_WINDOW_HXX
#define REMOTEINPUT_WINDOW_HXX


#include "JNI_Common.hxx"
#include "Container.hxx"

namespace java
{
    class Window : public Container
    {
    public:
        Window(JNIEnv* env, jobject window, bool canDelete = true) noexcept;
        Window(JNIEnv* env, jclass cls, jobject window, bool canDelete = true) noexcept;
        void pack() const noexcept;
    };
}

#endif //REMOTEINPUT_WINDOW_HXX
