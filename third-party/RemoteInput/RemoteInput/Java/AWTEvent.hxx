//
//  AWTEvent.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef AWTEvent_HXX_INCLUDED
#define AWTEvent_HXX_INCLUDED

#include "JNI_Common.hxx"

namespace java
{
    class AWTEvent
    {
    protected:
        jobject self;
        static void SetSystemGenerated(JNIEnv* env, jobject event, bool is_system_generated);
        static void Post(JNIEnv* env, jobject event, bool is_system_generated);

    public:
        AWTEvent() noexcept;
        AWTEvent(jobject self) noexcept;
        AWTEvent(AWTEvent&& other) noexcept;
        AWTEvent(const AWTEvent& other) = delete;
        virtual ~AWTEvent() noexcept;

        AWTEvent& operator = (AWTEvent&& other) noexcept;
        AWTEvent& operator = (const AWTEvent& other) = delete;

        virtual jobject get() const noexcept;
        virtual jobject getSource(JNIEnv* env) const noexcept;
    };
}

#endif /* AWTEvent_HXX_INCLUDED */
