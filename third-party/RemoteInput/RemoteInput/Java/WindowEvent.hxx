//
// Created by Brandon on 2021-10-21.
//

#ifndef REMOTEINPUT_WINDOWEVENT_HXX
#define REMOTEINPUT_WINDOWEVENT_HXX

#include "JNI_Common.hxx"
#include "AWTEvent.hxx"
#include "Component.hxx"

namespace java
{
    class WindowEvent final : public AWTEvent
    {
    private:
        JNIEnv* env;
        jclass cls;

    public:
        WindowEvent(JNIEnv* env, Component* receiver, std::int32_t id, std::int32_t old_state, std::int32_t new_state) noexcept;
        virtual ~WindowEvent() noexcept;

        static void Dispatch(JNIEnv* env, Component* receiver, std::int32_t id, std::int32_t old_state, std::int32_t new_state, bool is_system_generated = false) noexcept;
        static void Post(JNIEnv* env, Component* receiver, std::int32_t id, std::int32_t old_state, std::int32_t new_state, bool is_system_generated = false) noexcept;

        enum WindowEventCodes : std::int32_t
        {
            WINDOW_ICONIFIED = 203,
            WINDOW_DEICONIFIED = 204,
            WINDOW_ACTIVATED = 205,
            WINDOW_DEACTIVATED = 206,
            WINDOW_GAINED_FOCUS = 207,
            WINDOW_LOST_FOCUS = 208,
            WINDOW_STATE_CHANGED = 209
        };
    };
}

#endif //REMOTEINPUT_WINDOWEVENT_HXX
