//
//  PointerInfo.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-03-16.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef PointerInfo_HXX_INCLUDED
#define PointerInfo_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "Component.hxx"

namespace java
{
    class PointerInfo
    {
    private:
        JNIEnv* env;
        jclass cls;
        jobject pointerInfo;

        PointerInfo(JNIEnv* env, jclass cls, jobject pointerInfo) noexcept;

    public:
        PointerInfo(PointerInfo&& other) noexcept;
        PointerInfo(const PointerInfo& other) = delete;
        ~PointerInfo() noexcept;

        PointerInfo& operator = (PointerInfo&& other) noexcept;
        PointerInfo& operator = (const PointerInfo& other) = delete;

        static PointerInfo getPointerInfo(JNIEnv* env) noexcept;

        void getLocation(std::int32_t &x, std::int32_t &y) const noexcept;

        static void PointToScreen(JNIEnv* env, std::int32_t &x, std::int32_t &y, Component* component) noexcept;
    };
}

#endif /* PointerInfo_HXX_INCLUDED */
