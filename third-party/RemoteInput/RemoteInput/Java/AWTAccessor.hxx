//
//  AWTAccessor.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef AWTAccessor_HXX_INCLUDED
#define AWTAccessor_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "AWTEventAccessor.hxx"

namespace java
{
    class AWTAccessor
    {
    private:
        JNIEnv* env;
        jclass cls;
        jobject accessor;

        AWTAccessor(JNIEnv* env, jclass cls, jobject accessor) noexcept;

    public:
        AWTAccessor(AWTAccessor&& other) noexcept;
        AWTAccessor(const AWTAccessor& other) = delete;
        ~AWTAccessor() noexcept;

        AWTAccessor& operator = (AWTAccessor&& other) noexcept;
        AWTAccessor& operator = (const AWTAccessor& other) = delete;

        static AWTEventAccessor getAWTEventAccessor(JNIEnv* env) noexcept;
    };
}

#endif /* AWTAccessor_HXX_INCLUDED */
