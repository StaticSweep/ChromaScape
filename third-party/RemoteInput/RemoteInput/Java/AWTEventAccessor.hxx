//
//  AWTEventAccessor.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef AWTEventAccessor_HXX_INCLUDED
#define AWTEventAccessor_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "AWTEvent.hxx"

namespace java
{
    class AWTEventAccessor
    {
    private:
        JNIEnv* env;
        jclass cls;
        jobject accessor;

    public:
        AWTEventAccessor(JNIEnv* env, jclass cls, jobject accessor) noexcept;
        AWTEventAccessor(AWTEventAccessor&& other) noexcept;
        AWTEventAccessor(const AWTEventAccessor& other) = delete;
        ~AWTEventAccessor() noexcept;

        AWTEventAccessor& operator = (AWTEventAccessor&& other) noexcept;
        AWTEventAccessor& operator = (const AWTEventAccessor& other) = delete;

        void setSystemGenerated(AWTEvent* event) const noexcept;
        void setPosted(AWTEvent* event) const noexcept;
    };
}

#endif /* AWTEventAccessor_HXX_INCLUDED */
