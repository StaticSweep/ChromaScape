//
//  MouseEvent.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef MouseEvent_HXX_INCLUDED
#define MouseEvent_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "AWTEvent.hxx"
#include "Component.hxx"

namespace java
{
    class MouseEvent final : public AWTEvent
    {
    private:
        JNIEnv* env;
        jclass cls;

    public:
        MouseEvent(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t clickCount, bool popupTrigger, std::int32_t button) noexcept;
        virtual ~MouseEvent() noexcept;

        static void Dispatch(JNIEnv* env, Component* receiver, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t clickCount, bool popupTrigger, std::int32_t button, bool is_system_generated = false) noexcept;

        static void Post(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t clickCount, bool popupTrigger, std::int32_t button, bool is_system_generated = false) noexcept;

        #if !defined(_MSC_VER)
        #warning "WINDOWS BS"
        #endif

        #if defined(MOUSE_MOVED) && (defined(_WIN32) || defined(_WIN64))
        #undef MOUSE_MOVED
        #endif // MOUSE_MOVED

        enum MouseEventCodes : std::uint32_t
        {
            MOUSE_CLICKED = 500,
            MOUSE_PRESSED = 501,
            MOUSE_RELEASED = 502,
            MOUSE_MOVED = 503,
            MOUSE_ENTERED = 504,
            MOUSE_EXITED = 505,
            MOUSE_DRAGGED = 506,
            MOUSE_WHEEL = 507,

            NOBUTTON = 0,
            BUTTON1 = 1,
            BUTTON2 = 2,
            BUTTON3 = 3,
        };
    };
}

#endif /* MouseEvent_HXX_INCLUDED */
