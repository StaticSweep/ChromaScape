//
// Created by Brandon on 2021-10-17.
//

#ifndef RIEVENTQUEUE_HXX
#define RIEVENTQUEUE_HXX

#include "JNI_Common.hxx"
#include "EventQueue.hxx"
#include "AWTEvent.hxx"
#include <vector>

namespace java
{
    class RIEvent final: public AWTEvent
    {
    private:
        JNIEnv* env;
        static jobject Wrap(JNIEnv* env, jobject original) noexcept;
    public:
        RIEvent(JNIEnv* env, AWTEvent* original) noexcept;
        RIEvent(JNIEnv* env, jobject original) noexcept;
        RIEvent(RIEvent&& other) noexcept;
        virtual ~RIEvent() noexcept final;

        RIEvent& operator = (RIEvent&& other) noexcept;
    };

    class RIEventQueue: public EventQueue
    {
    private:
        bool is_blocking_keyboard_events;
        bool is_blocking_mouse_events;

        void DefineClass();
        static void DispatchEvent(JNIEnv* env, jobject thiz, jobject event);

    public:
        RIEventQueue(JNIEnv* env) noexcept;

        bool is_keyboard_input_enabled() const noexcept;
        void set_keyboard_input_enabled(bool enabled) noexcept;

        bool is_mouse_input_enabled() const noexcept;
        void set_mouse_input_enabled(bool enabled) noexcept;
    };
}

#endif /* RIEVENTQUEUE_HXX */
