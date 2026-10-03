//
// Created by Brandon on 2021-10-21.
//

#ifndef REMOTEINPUT_CONTAINER_HXX
#define REMOTEINPUT_CONTAINER_HXX

#include "JNI_Common.hxx"
#include "Component.hxx"

namespace java
{
    class Container : public Component
    {
    public:
        Container(JNIEnv* env, jobject container, bool canDelete = true) noexcept;
        Container(JNIEnv* env, jclass cls, jobject container, bool canDelete = true) noexcept;

        Component getComponent(std::int32_t index) const noexcept;
        int getComponentCount() const noexcept;
    };
}


#endif //REMOTEINPUT_CONTAINER_HXX
