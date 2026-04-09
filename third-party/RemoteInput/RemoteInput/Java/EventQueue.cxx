//
//  EventQueue.cxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#include "EventQueue.hxx"
#include <utility>

namespace java
{
    EventQueue::EventQueue(JNIEnv* env) noexcept : env(env), cls(nullptr), queue(nullptr)
    {
    }

    EventQueue::EventQueue(JNIEnv* env, jclass cls, jobject queue) noexcept : env(env), cls(cls), queue(queue)
    {
        env->DeleteLocalRef(std::exchange(this->cls, static_cast<jclass>(env->NewGlobalRef(this->cls))));
        env->DeleteLocalRef(std::exchange(this->queue, env->NewGlobalRef(this->queue)));
    }

    EventQueue::~EventQueue() noexcept
    {
        if (cls)
        {
            env->DeleteGlobalRef(cls);
        }

        // In Java 6, the system deletes the queue.
        // If we attempt to delete it, it will crash!
        /*if (queue)
        {
            env->DeleteGlobalRef(queue);
        }*/
    }

    EventQueue::EventQueue(EventQueue&& other) noexcept : env(other.env), cls(other.cls), queue(other.queue)
    {
        other.env = nullptr;
        other.cls = nullptr;
        other.queue = nullptr;
    }

    EventQueue& EventQueue::operator = (EventQueue&& other) noexcept
    {
        this->env = other.env;
        this->cls = other.cls;
        this->queue = other.queue;
        other.env = nullptr;
        other.cls = nullptr;
        other.queue = nullptr;
        return *this;
    }

    void EventQueue::push(EventQueue* queue) const noexcept
    {
        static jmethodID methodId = env->GetMethodID(cls, "push", "(Ljava/awt/EventQueue;)V");
        env->CallVoidMethod(this->queue, methodId, queue->queue);
    }

    void EventQueue::postEvent(AWTEvent* event) const noexcept
    {
        static jmethodID methodId = env->GetMethodID(cls, "postEvent", "(Ljava/awt/AWTEvent;)V");
        env->CallVoidMethod(queue, methodId, event->get());
    }
}