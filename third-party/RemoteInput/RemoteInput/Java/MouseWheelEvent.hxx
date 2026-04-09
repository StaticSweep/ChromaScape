//
//  MouseWheelEvent.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-01-10.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef MouseWheelEvent_HXX_INCLUDED
#define MouseWheelEvent_HXX_INCLUDED

#include "JNI_Common.hxx"
#include "AWTEvent.hxx"
#include "Component.hxx"

namespace java
{
    class MouseWheelEvent final : public AWTEvent
    {
    private:
        JNIEnv* env;
        jclass cls;

    public:
        MouseWheelEvent(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t xAbs, std::int32_t yAbs, std::int32_t clickCount, bool popupTrigger, std::int32_t scrollType, std::int32_t scrollAmount, std::int32_t wheelRotation, double preciseWheelRotation) noexcept;
        virtual ~MouseWheelEvent() noexcept;

        static void Dispatch(JNIEnv* env, Component* receiver, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t xAbs, std::int32_t yAbs, std::int32_t clickCount, bool popupTrigger, std::int32_t scrollType, std::int32_t scrollAmount, std::int32_t wheelRotation, double preciseWheelRotation, bool is_system_generated = false) noexcept;

        static void Post(JNIEnv* env, Component* source, std::int32_t id, std::int64_t when, std::int32_t modifiers, std::int32_t x, std::int32_t y, std::int32_t xAbs, std::int32_t yAbs, std::int32_t clickCount, bool popupTrigger, std::int32_t scrollType, std::int32_t scrollAmount, std::int32_t wheelRotation, double preciseWheelRotation, bool is_system_generated = false) noexcept;

        enum MouseWheelEventCodes : std::uint32_t
        {
            WHEEL_UNIT_SCROLL = 0,
            WHEEL_BLOCK_SCROLL = 1
        };
    };
}

#endif /* MouseWheelEvent_HXX_INCLUDED */
