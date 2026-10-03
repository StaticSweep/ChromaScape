//
//  EventQueue.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef EventQueue_HXX_INCLUDED
#define EventQueue_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "AWTEvent.hxx"

namespace java
{
    class EventQueue
    {
    protected:
        JNIEnv* env;
        jclass cls;
        jobject queue;

        EventQueue(JNIEnv* env) noexcept;

    public:
        EventQueue(JNIEnv* env, jclass cls, jobject queue) noexcept;
        EventQueue(EventQueue&& other) noexcept;
        EventQueue(const EventQueue& other) = delete;
        virtual ~EventQueue() noexcept;

        EventQueue& operator = (EventQueue&& other) noexcept;
        EventQueue& operator = (const EventQueue& other) = delete;

        virtual void push(EventQueue* queue) const noexcept;
        virtual void postEvent(AWTEvent* event) const noexcept;
    };
}

#endif /* EventQueue_HXX_INCLUDED */
