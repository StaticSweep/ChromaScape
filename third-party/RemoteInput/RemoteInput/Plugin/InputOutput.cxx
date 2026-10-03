//
//  InputOutput.cxx
//  RemoteInput
//
//  Created by Brandon on 2019-12-28.
//  Copyright © 2019 XIO. All rights reserved.
//

#include "InputOutput.hxx"
#include "ControlCenter.hxx"
#include "Platform.hxx"
#include "Random.hxx"
#include "Applet.hxx"
#include "InputEvent.hxx"
#include "FocusEvent.hxx"
#include "KeyEvent.hxx"
#include "MouseEvent.hxx"
#include "MouseWheelEvent.hxx"
#include "PointerInfo.hxx"
#include "SunToolkit.hxx"
#include "Toolkit.hxx"
#include "Window.hxx"
#include "WindowEvent.hxx"

#if !defined(_MSC_VER)
#warning "WINDOWS BS"
#endif

#ifdef VK_SHIFT
#undef VK_SHIFT
#undef VK_LSHIFT
#undef VK_RSHIFT
#undef VK_CONTROL
#undef VK_APPS
#undef VK_TAB
#undef VK_BACK
#undef VK_RETURN
#endif // VK_SHIFT

enum ControlKeys : std::uint32_t
{
    VK_FUNCTION = 0x0,
    VK_NUM_LOCK = 0x90,
    VK_SHIFT = 0x10,
    VK_LSHIFT = 0xA0,
    VK_RSHIFT = 0xA1,
    VK_CONTROL = 0x11,
    VK_LEFT_CONTROL = 0xA2,
    VK_RIGHT_CONTROL = 0xA3,
    VK_LEFT_ALT = 0xA4,
    VK_RIGHT_ALT = 0xA5,

    VK_COMMAND = 0x9D, //VK_META
    VK_LEFT_WINDOWS = 0x5B,
    VK_RIGHT_WINDOWS = 0x5C,
    VK_APPS = 0x5D,
    VK_TAB = 0x09,
    VK_BACK = 0x08,
    VK_RETURN = 0x0D
};

static ControlKeys control_keys[] = {
    ControlKeys::VK_FUNCTION,
    ControlKeys::VK_NUM_LOCK,
    ControlKeys::VK_LSHIFT,
    ControlKeys::VK_RSHIFT,
    ControlKeys::VK_LEFT_CONTROL,
    ControlKeys::VK_RIGHT_CONTROL,
    ControlKeys::VK_LEFT_ALT,
    ControlKeys::VK_RIGHT_ALT,
    ControlKeys::VK_COMMAND,
    ControlKeys::VK_LEFT_WINDOWS,
    ControlKeys::VK_RIGHT_WINDOWS,
    ControlKeys::VK_APPS,
    ControlKeys::VK_TAB,
    ControlKeys::VK_BACK,
    ControlKeys::VK_RETURN
};

static java::KeyEvent::KeyCodes control_keys_locations[] = {
    java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN,
    java::KeyEvent::KeyCodes::KEY_LOCATION_STANDARD,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_RIGHT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_RIGHT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_RIGHT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_LEFT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_RIGHT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_RIGHT,
    java::KeyEvent::KeyCodes::KEY_LOCATION_STANDARD,
    java::KeyEvent::KeyCodes::KEY_LOCATION_STANDARD,
    java::KeyEvent::KeyCodes::KEY_LOCATION_STANDARD
};

InputOutput::InputOutput(Reflection* reflection) noexcept : vm(nullptr), applet(reflection->getApplet()), mutex(), input_thread(new ThreadPool(2)), event_queue(nullptr), currently_held_key(-1), held_keys(), x(-1), y(-1), w(-1), h(-1), click_count(0), keyboard_speed(0), keyboard_repeat_delay(0), mouse_buttons()
{
    reflection->getEnv()->GetJavaVM(&vm);

    event_queue = std::make_unique<java::RIEventQueue>(reflection->getEnv());
    x = std::numeric_limits<std::int32_t>::min();
    y = std::numeric_limits<std::int32_t>::min();

    auto scale_to_range = [](float value, float fromMin, float fromMax, float toMin, float toMax) -> float {
        return (((toMax - toMin) * (value - fromMin)) / (fromMax - fromMin)) + toMin;
    };

    #if defined(_WIN32) || defined(_WIN64)
        #if defined(WINDOWS_SYSTEM_INPUT_INFO)
        //0 (approximately 250 ms delay) through 3 (approximately 1 second delay).
        std::int32_t keyboard_delay = 0;
        SystemParametersInfo(SPI_GETKEYBOARDDELAY, 0, reinterpret_cast<void*>(&keyboard_delay), 0);

        //Small variance in delay due to electrical signal, lag, hardware, etc..
        std::int32_t min_delay = Random::instance()->generate_random_int(248, 252);
        std::int32_t max_delay = Random::instance()->generate_random_int(998, 1002);
        this->keyboard_repeat_delay = scale_to_range(keyboard_delay, 0, 3, min_delay, max_delay);

        //0 (approximately 2.5 repetitions per second) through 31 (approximately 30 repetitions per second).
        //The actual repeat rates are hardware-dependent and may vary from a linear scale by as much as 20%
        std::int32_t keyboard_repeat_speed = 0;
        SystemParametersInfo(SPI_GETKEYBOARDSPEED, 0, reinterpret_cast<void*>(&keyboard_repeat_speed), 0);

        float repeat_rate = scale_to_range(keyboard_repeat_speed, 0, 31, 2.5, 30);
        repeat_rate += repeat_rate * Random::instance()->generate_random_float(0.0, 0.20);
        this->keyboard_speed = round(60.0 / repeat_rate);
        #else
        //Windows default is 250ms delay and 24ms repeat rate
        this->keyboard_repeat_delay = scale_to_range(0, 0, 3, 250, 1000) + Random::instance()->generate_random_int(-2, 2);

        float repeat_rate = scale_to_range(0, 0, 31, 2.5, 30);
        repeat_rate += repeat_rate * Random::instance()->generate_random_float(0.0, 0.20);
        this->keyboard_speed = round(60.0 / repeat_rate);
        #endif
    #elif defined(__APPLE__)
        //MacOS default is 225ms delay and 15ms repeat rate
        this->keyboard_repeat_delay = scale_to_range(15, 15, 120, 225, 1800) + Random::instance()->generate_random_int(-2, 2);

        float repeat_rate = scale_to_range(1, 2, 120, 30, 1800);
        repeat_rate += repeat_rate * Random::instance()->generate_random_float(0.0, 0.20);
        this->keyboard_speed = round(repeat_rate);
    #else
        //Linux default is 660ms delay and 25ms repeat rate
        this->keyboard_repeat_delay = scale_to_range(44, 15, 120, 225, 1800) + Random::instance()->generate_random_int(-2, 2);

        float repeat_rate = scale_to_range(2, 2, 120, 25, 1800);
        repeat_rate += repeat_rate * Random::instance()->generate_random_float(0.0, 0.20);
        this->keyboard_speed = round(repeat_rate);
    #endif

    java::EventQueue queue = java::Toolkit::getDefaultToolkit(reflection->getEnv()).getSystemEventQueue();
    queue.push(this->event_queue.get());
}

InputOutput::~InputOutput() noexcept
{
    // Purposely do NOT join the thread or reset! This will hang upon dll unload.
    // We `release` it instead to prevent this.
    //this->input_thread->terminate();
    this->input_thread.release();
    this->event_queue.reset();
    this->vm = nullptr;
    this->applet = nullptr;
}

void InputOutput::handle_resize(java::Component* component) noexcept
{
    component->getSize(this->w, this->h);

    /*bool isMouseInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));
    if (isMouseInsideComponent)
    {
        component->getMousePosition(this->x, this->y);

        if (this->x == -1 || this->y == -1)
        {
            JNIEnv* env = component->getEnv();

            std::int32_t x = this->x;
            std::int32_t y = this->y;

            java::PointerInfo info = java::PointerInfo::getPointerInfo(env);
            info.getLocation(x, y);
            info.PointToScreen(env, x, y,component);

            isMouseInsideComponent = !(x < 0 || y < 0 || x > static_cast<std::int32_t>(this->w) || y > static_cast<std::int32_t>(this->h));
            if (isMouseInsideComponent)
            {
                this->x = x;
                this->y = y;
            }
        }
    }*/
}

void InputOutput::hold_key(std::int32_t code) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    //Key is not already held..
    if (!this->is_key_held(code))
    {
        //Key is a control key
        if (std::find(std::begin(control_keys), std::end(control_keys), code) != std::end(control_keys))
        {
            //Control Keys only generate a single held event..
            held_keys.push_back(code);

            //PostEvent
            java::Component receiver = control_center->reflect_canvas();

            JNIEnv* env = receiver.getEnv();
            if (!this->has_focus(&receiver))
            {
                this->gain_focus(&receiver);
            }

            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
            std::int32_t modifiers = GetActiveKeyModifiers();
            std::int32_t keycode = GetJavaKeyCode(code);
            std::int32_t location = GetKeyLocation(code);

            java::KeyEvent::Post(env,
                                 &receiver,
                                 java::KeyEvent::KeyCodes::KEY_PRESSED,
                                 when,
                                 modifiers,
                                 keycode,
                                 static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                                 location);
        }
        else
        {
            //Key already being pressed so just replace it
            if (currently_held_key != -1 && this->keyboard_speed >= 0 && this->keyboard_repeat_delay >= 0)
            {
                currently_held_key = code;
                held_keys.push_back(code);

                //Post Event
                java::Component receiver = control_center->reflect_canvas();
                if (!this->has_focus(&receiver))
                {
                    this->gain_focus(&receiver);
                }

                JNIEnv* env = receiver.getEnv();
                std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
                std::int32_t modifiers = GetActiveKeyModifiers();
                std::int32_t keycode = GetJavaKeyCode(code);
                std::int32_t location = GetKeyLocation(code);

                java::KeyEvent::Post(env,
                                     &receiver,
                                     java::KeyEvent::KeyCodes::KEY_PRESSED,
                                     when,
                                     modifiers,
                                     keycode,
                                     NativeKeyCodeToChar(code, modifiers),
                                     location);

                java::KeyEvent::Post(env,
                                     &receiver,
                                     java::KeyEvent::KeyCodes::KEY_TYPED,
                                     when,
                                     modifiers,
                                     0,
                                     NativeKeyCodeToChar(code, modifiers),
                                     java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN);

                if (this->keyboard_repeat_delay > 0)
                {
                    yield_thread(std::chrono::milliseconds(this->keyboard_repeat_delay));
                }
            }
            else
            {
                currently_held_key = code;
                held_keys.push_back(code);

                //Post Event
                java::Component receiver = control_center->reflect_canvas();
                if (!this->has_focus(&receiver))
                {
                    this->gain_focus(&receiver);
                }

                JNIEnv* env = receiver.getEnv();
                std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
                std::int32_t modifiers = GetActiveKeyModifiers();
                std::int32_t keycode = GetJavaKeyCode(code);
                std::int32_t location = GetKeyLocation(code);

                java::KeyEvent::Post(env,
                                     &receiver,
                                     java::KeyEvent::KeyCodes::KEY_PRESSED,
                                     when,
                                     modifiers,
                                     keycode,
                                     NativeKeyCodeToChar(code, modifiers),
                                     location);

                java::KeyEvent::Post(env,
                                     &receiver,
                                     java::KeyEvent::KeyCodes::KEY_TYPED,
                                     when,
                                     modifiers,
                                     0,
                                     NativeKeyCodeToChar(code, modifiers),
                                     java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN);

                if (this->keyboard_speed >= 0 && this->keyboard_repeat_delay >= 0)
                {
                    input_thread->add_task([&](std::atomic_bool &stopped) {
                        if (this->keyboard_repeat_delay > 0)
                        {
                            yield_thread(std::chrono::milliseconds(this->keyboard_repeat_delay));
                        }

                        JNIEnv* env = nullptr;
                        this->vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr);

                        java::Applet applet{env, this->applet, false};
                        java::Component receiver = applet.getComponent(0);

                        while (!stopped)
                        {
                            if (currently_held_key == -1)
                            {
                                break;
                            }

                            if (this->keyboard_speed > 0)
                            {
                                yield_thread(std::chrono::milliseconds(this->keyboard_speed));
                            }

                            std::int32_t code = currently_held_key;

                            //Post Event
                            if (!this->has_focus(&receiver))
                            {
                                this->gain_focus(&receiver);
                            }

                            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
                            std::int32_t modifiers = GetActiveKeyModifiers();
                            std::int32_t keycode = GetJavaKeyCode(code);
                            std::int32_t location = GetKeyLocation(code);

                            java::KeyEvent::Post(env,
                                                 &receiver,
                                                 java::KeyEvent::KeyCodes::KEY_PRESSED,
                                                 when,
                                                 modifiers,
                                                 keycode,
                                                 NativeKeyCodeToChar(code, modifiers),
                                                 location);

                            java::KeyEvent::Post(env,
                                                 &receiver,
                                                 java::KeyEvent::KeyCodes::KEY_TYPED,
                                                 when,
                                                 modifiers,
                                                 0,
                                                 NativeKeyCodeToChar(code, modifiers),
                                                 java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN);
                        }
                    });
                }
            }
        }
    }
}

void InputOutput::release_key(std::int32_t code) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    if (auto it = std::find(held_keys.begin(), held_keys.end(), code); it != held_keys.end())
    {
        if (std::find(std::begin(control_keys), std::end(control_keys), code) != std::end(control_keys))
        {
            held_keys.erase(it); //held_keys.erase(std::remove(held_keys.begin(), held_keys.end(), code), held_keys.end());

            //Post Event
            java::Component receiver = control_center->reflect_canvas();
            if (!this->has_focus(&receiver))
            {
                this->gain_focus(&receiver);
            }

            JNIEnv* env = receiver.getEnv();
            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
            std::int32_t modifiers = GetActiveKeyModifiers();
            std::int32_t keycode = GetJavaKeyCode(code);
            std::int32_t location = GetKeyLocation(code);

            java::KeyEvent::Post(env,
                                 &receiver,
                                 java::KeyEvent::KeyCodes::KEY_RELEASED,
                                 when,
                                 modifiers,
                                 keycode,
                                 static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                                 location);
        }
        else
        {
            //Remove the held key from the list..
            held_keys.erase(it);

            //Find the next non-control held-key..
            auto jt = std::find_if(held_keys.crbegin(), held_keys.crend(), [](std::int32_t key){
                return std::find(std::begin(control_keys), std::end(control_keys), key) == std::end(control_keys);
            });

            //Set the next currently held key to the first non-control key..
            currently_held_key = jt != held_keys.crend() ? *jt : -1;

            //Post Event
            java::Component receiver = control_center->reflect_canvas();
            if (!this->has_focus(&receiver))
            {
                this->gain_focus(&receiver);
            }

            JNIEnv* env = receiver.getEnv();
            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
            std::int32_t modifiers = GetActiveKeyModifiers();
            std::int32_t keycode = GetJavaKeyCode(code);
            std::int32_t location = GetKeyLocation(code);

            java::KeyEvent::Post(env,
                                 &receiver,
                                 java::KeyEvent::KeyCodes::KEY_RELEASED,
                                 when,
                                 modifiers,
                                 keycode,
                                 NativeKeyCodeToChar(code, modifiers),
                                 location);
        }
    }
}

bool InputOutput::is_key_held(std::int32_t code) const noexcept
{
    return std::find(std::begin(held_keys), std::end(held_keys), code) != std::end(held_keys);
}

bool InputOutput::any_key_held(std::array<std::int32_t, 4>&& keys) const noexcept
{
    return std::any_of(std::cbegin(held_keys), std::cend(held_keys), [&](std::int32_t key){
        return std::find(std::cbegin(keys), std::cend(keys), key) != std::cend(keys);
    });
}

void InputOutput::send_string(std::string string, std::int32_t keywait, std::int32_t keymodwait) const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component receiver = control_center->reflect_canvas();
    JNIEnv* env = receiver.getEnv();

    if (!this->has_focus(&receiver))
    {
        this->gain_focus(&receiver);
    }

    bool isShiftDown = false;

    for (std::size_t i = 0; i < string.length(); ++i)
    {
        char c = string[i];
        char n = i == string.length() - 1 ? '\0' : string[i + 1];
        std::int32_t modifiers = this->ModifiersForChar(c);

        //Modifier Key
        if (modifiers & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK)
        {
            if (!isShiftDown)
            {
                isShiftDown = true;

                std::int32_t code = VK_LSHIFT;
                std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
                std::int32_t modifiers = java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
                std::int32_t keycode = GetJavaKeyCode(code);
                std::int32_t location = GetKeyLocation(code);

                java::KeyEvent::Post(env,
                                     &receiver,
                                     java::KeyEvent::KeyCodes::KEY_PRESSED,
                                     when,
                                     modifiers,
                                     keycode,
                                     static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                                     location);

                yield_thread(std::chrono::milliseconds(lround(Random::instance()->generate_random_float(1.0, 1.1) * keymodwait)));
            }
        }

        //Character Key
        std::int32_t code = static_cast<std::int32_t>(c);
        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
        std::int32_t keycode = CharToJavaKeyCode(code);
        std::int32_t location = GetKeyLocation(code);

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_PRESSED,
                             when,
                             modifiers,
                             keycode,
                             static_cast<jchar>(c),
                             location);

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_TYPED,
                             when,
                             modifiers,
                             0,
                             static_cast<jchar>(c),
                             java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN);

        yield_thread(std::chrono::milliseconds(lround(Random::instance()->generate_random_float(1.0, 1.1) * keywait)));
        when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_RELEASED,
                             when,
                             modifiers,
                             keycode,
                             static_cast<jchar>(c),
                             location);

        yield_thread(std::chrono::milliseconds(lround(Random::instance()->generate_random_float(1.0, 1.1) * keywait)));

        //Modifier Key
        if ((isShiftDown && i == string.length() - 1) || (n != '\0' && !(this->ModifiersForChar(n) & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK)))
        {
            isShiftDown = false;

            std::int32_t code = VK_LSHIFT;
            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
            std::int32_t modifiers = java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
            std::int32_t keycode = GetJavaKeyCode(code);
            std::int32_t location = GetKeyLocation(code);

            java::KeyEvent::Post(env,
                                 &receiver,
                                 java::KeyEvent::KeyCodes::KEY_RELEASED,
                                 when,
                                 modifiers,
                                 keycode,
                                 static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                                 location);

            yield_thread(std::chrono::milliseconds(lround(Random::instance()->generate_random_float(1.0, 1.1) * keymodwait)));
        }
    }
}

void InputOutput::send_key(char key, std::int32_t key_down_time, std::int32_t key_up_time, std::int32_t modifier_down_time, std::int32_t modifier_up_time) const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component receiver = control_center->reflect_canvas();
    JNIEnv* env = receiver.getEnv();

    if (!this->has_focus(&receiver))
    {
        this->gain_focus(&receiver);
    }

    std::int32_t modifiers = this->ModifiersForChar(key);

    //Modifier Key is already pressed
    bool isShiftDown = GetActiveKeyModifiers() & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;

    //Modifier Key
    if (!isShiftDown && (modifiers & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK))
    {
        std::int32_t code = VK_LSHIFT;
        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
        std::int32_t modifiers = java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
        std::int32_t keycode = GetJavaKeyCode(code);
        std::int32_t location = GetKeyLocation(code);

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_PRESSED,
                             when,
                             modifiers,
                             keycode,
                             static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                             location);

        yield_thread(std::chrono::milliseconds(modifier_down_time));
    }

    //Character Key
    std::int32_t code = static_cast<std::int32_t>(key);
    std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
    std::int32_t keycode = CharToJavaKeyCode(code);
    std::int32_t location = GetKeyLocation(code);

    java::KeyEvent::Post(env,
                         &receiver,
                         java::KeyEvent::KeyCodes::KEY_PRESSED,
                         when,
                         modifiers,
                         keycode,
                         static_cast<jchar>(key),
                         location);

    java::KeyEvent::Post(env,
                         &receiver,
                         java::KeyEvent::KeyCodes::KEY_TYPED,
                         when,
                         modifiers,
                         0,
                         static_cast<jchar>(key),
                         java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN);

    yield_thread(std::chrono::milliseconds(key_down_time));
    when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

    java::KeyEvent::Post(env,
                         &receiver,
                         java::KeyEvent::KeyCodes::KEY_RELEASED,
                         when,
                         modifiers,
                         keycode,
                         static_cast<jchar>(key),
                         location);

    yield_thread(std::chrono::milliseconds(key_up_time));

    //Modifier Key
    if (!isShiftDown && (modifiers & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK))
    {
        std::int32_t code = VK_LSHIFT;
        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
        std::int32_t modifiers = java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
        std::int32_t keycode = GetJavaKeyCode(code);
        std::int32_t location = GetKeyLocation(code);

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_RELEASED,
                             when,
                             modifiers,
                             keycode,
                             static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                             location);

        yield_thread(std::chrono::milliseconds(modifier_up_time));
    }
}

void InputOutput::key_send(std::string string, std::vector<std::int32_t> sleeptimes) const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component receiver = control_center->reflect_canvas();
    JNIEnv* env = receiver.getEnv();

    if (!this->has_focus(&receiver))
    {
        this->gain_focus(&receiver);
    }

    bool isShiftDown = false;
    std::int32_t sleepindex = 0;

    for (std::size_t i = 0; i < string.length(); ++i)
    {
        char c = string[i];
        char n = i == string.length() - 1 ? '\0' : string[i + 1];
        std::int32_t modifiers = this->ModifiersForChar(c);

        //Modifier Key
        if (modifiers & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK)
        {
            if (!isShiftDown)
            {
                isShiftDown = true;

                std::int32_t code = VK_LSHIFT;
                std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
                std::int32_t modifiers = java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
                std::int32_t keycode = GetJavaKeyCode(code);
                std::int32_t location = GetKeyLocation(code);

                java::KeyEvent::Post(env,
                                     &receiver,
                                     java::KeyEvent::KeyCodes::KEY_PRESSED,
                                     when,
                                     modifiers,
                                     keycode,
                                     static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                                     location);

                yield_thread(std::chrono::milliseconds(sleeptimes[sleepindex++]));
            }
        }

        //Character Key
        std::int32_t code = static_cast<std::int32_t>(c);
        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
        std::int32_t keycode = CharToJavaKeyCode(code);
        std::int32_t location = GetKeyLocation(code);

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_PRESSED,
                             when,
                             modifiers,
                             keycode,
                             static_cast<jchar>(c),
                             location);

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_TYPED,
                             when,
                             modifiers,
                             0,
                             static_cast<jchar>(c),
                             java::KeyEvent::KeyCodes::KEY_LOCATION_UNKNOWN);

        yield_thread(std::chrono::milliseconds(sleeptimes[sleepindex++]));
        when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

        java::KeyEvent::Post(env,
                             &receiver,
                             java::KeyEvent::KeyCodes::KEY_RELEASED,
                             when,
                             modifiers,
                             keycode,
                             static_cast<jchar>(c),
                             location);

        yield_thread(std::chrono::milliseconds(sleeptimes[sleepindex++]));

        //Modifier Key
        if ((isShiftDown && i == string.length() - 1) || (n != '\0' && !(this->ModifiersForChar(n) & java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK)))
        {
            isShiftDown = false;

            std::int32_t code = VK_LSHIFT;
            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
            std::int32_t modifiers = java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
            std::int32_t keycode = GetJavaKeyCode(code);
            std::int32_t location = GetKeyLocation(code);

            java::KeyEvent::Post(env,
                                 &receiver,
                                 java::KeyEvent::KeyCodes::KEY_RELEASED,
                                 when,
                                 modifiers,
                                 keycode,
                                 static_cast<jchar>(java::KeyEvent::KeyCodes::CHAR_UNDEFINED),
                                 location);

            yield_thread(std::chrono::milliseconds(sleeptimes[sleepindex++]));
        }
    }
}

bool InputOutput::has_focus() const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return false;
    }

    java::Component component = control_center->reflect_canvas();
    return component.hasFocus();
}

bool InputOutput::has_focus(java::Component* component) const noexcept
{
    return component->hasFocus();
}

void InputOutput::gain_focus() const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component component = control_center->reflect_canvas();
    this->gain_focus(&component);
}

void InputOutput::gain_focus(java::Component* component) const noexcept
{
    JNIEnv* env = component->getEnv();
    java::Window window = java::SunToolkit::getContainingWindow(component);

    if (window.get())
    {
        java::WindowEvent::Post(env, &window, java::WindowEvent::WindowEventCodes::WINDOW_ACTIVATED, 0, 0);
        std::this_thread::sleep_for(std::chrono::milliseconds(Random::instance()->generate_random_int(100, 250)));
        java::WindowEvent::Post(env, &window, java::WindowEvent::WindowEventCodes::WINDOW_GAINED_FOCUS, 0, 0);
        std::this_thread::sleep_for(std::chrono::milliseconds(Random::instance()->generate_random_int(100, 250)));
    }

    java::FocusEvent::Post(env, component, java::FocusEvent::FocusCodes::FOCUS_GAINED, false, java::FocusEvent::Cause::ACTIVATION);

    auto now = std::chrono::high_resolution_clock::now();
    while (!component->hasFocus() && elapsed_time<std::chrono::seconds>(now) < 2)
    {
        yield_thread(std::chrono::milliseconds(Random::instance()->generate_random_int(100, 250)));
    }
}

void InputOutput::lose_focus() const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component component = control_center->reflect_canvas();
    this->lose_focus(&component);
}

void InputOutput::lose_focus(java::Component* component) const noexcept
{
    JNIEnv* env = component->getEnv();
    java::Window window = java::SunToolkit::getContainingWindow(component);

    if (window.get())
    {
        java::WindowEvent::Post(env, &window, java::WindowEvent::WindowEventCodes::WINDOW_DEACTIVATED, 0, 0);
        std::this_thread::sleep_for(std::chrono::milliseconds(Random::instance()->generate_random_int(100, 250)));
    }

    java::FocusEvent::Post(env, component, java::FocusEvent::FocusCodes::FOCUS_LOST, true, java::FocusEvent::Cause::ACTIVATION);

    auto now = std::chrono::high_resolution_clock::now();
    while (component->hasFocus() && elapsed_time<std::chrono::seconds>(now) < 2)
    {
        yield_thread(std::chrono::milliseconds(Random::instance()->generate_random_int(100, 250)));
    }
}

bool InputOutput::is_keyboard_input_enabled() const noexcept
{
    return event_queue->is_keyboard_input_enabled();
}

void InputOutput::set_keyboard_input_enabled(bool enabled) const noexcept
{
    event_queue->set_keyboard_input_enabled(enabled);

    if (!enabled)
    {
        if (!this->has_focus())
        {
            this->gain_focus();
        }
    }
}

bool InputOutput::is_mouse_input_enabled() const noexcept
{
    return event_queue->is_mouse_input_enabled();
}

void InputOutput::set_mouse_input_enabled(bool enabled) const noexcept
{
    event_queue->set_mouse_input_enabled(enabled);

    if (!enabled)
    {
        if (!this->has_focus())
        {
            this->gain_focus();
        }
    }
}

std::int32_t InputOutput::get_keyboard_speed() const noexcept
{
    return this->keyboard_speed;
}

void InputOutput::set_keyboard_speed(std::int32_t speed) noexcept
{
    this->keyboard_speed = speed;
}

std::int32_t InputOutput::get_keyboard_repeat_delay() const noexcept
{
    return this->keyboard_repeat_delay;
}

void InputOutput::set_keyboard_repeat_delay(std::int32_t delay) noexcept
{
    this->keyboard_repeat_delay = delay;
}

void InputOutput::get_mouse_position(std::int32_t* x, std::int32_t* y) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    if (this->x == std::numeric_limits<std::int32_t>::min() || this->y == std::numeric_limits<std::int32_t>::min())
    {
        java::Component receiver = control_center->reflect_canvas();
        receiver.getMousePosition(this->x, this->y);
        receiver.getSize(this->w, this->h);

        if (this->x == -1 || this->y == -1)
        {
            java::Component receiver = control_center->reflect_canvas();
            JNIEnv* env = receiver.getEnv();

            std::int32_t x = this->x;
            std::int32_t y = this->y;

            java::PointerInfo info = java::PointerInfo::getPointerInfo(env);
            info.getLocation(x, y);
            info.PointToScreen(env, x, y, &receiver);

            this->x = x;
            this->y = y;
        }
    }
    else if (!has_focus() && (!is_keyboard_input_enabled() || !is_mouse_input_enabled()))
    {
        java::Component receiver = control_center->reflect_canvas();
        this->handle_resize(&receiver);
    }

    *x = this->x;
    *y = this->y;
}

void InputOutput::get_real_mouse_position(std::int32_t* x, std::int32_t* y) const noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component receiver = control_center->reflect_canvas();
    JNIEnv* env = receiver.getEnv();

    java::PointerInfo info = java::PointerInfo::getPointerInfo(env);
    info.getLocation(*x, *y);
    info.PointToScreen(env, *x, *y, &receiver);
}

void InputOutput::move_mouse(std::int32_t x, std::int32_t y) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component receiver = control_center->reflect_canvas();
    JNIEnv* env = receiver.getEnv();
    this->handle_resize(&receiver);

    std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

    bool isRequestedPositionInsideComponent = !(x < 0 || y < 0 || x > static_cast<std::int32_t>(this->w) || y > static_cast<std::int32_t>(this->h));
    bool isMouseInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));
    bool isDragging = mouse_buttons[0] || mouse_buttons[1] || mouse_buttons[2];

    //Button priority is 1 (left), 3 (right), 2 (middle)
    std::int32_t button = mouse_buttons[0] ? 1 : mouse_buttons[2] ? 3 : mouse_buttons[1] ? 2 : 0;
    std::int32_t buttonMask = (mouse_buttons[0] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[0]) : 0) |
                              (mouse_buttons[1] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[1]) : 0) |
                              (mouse_buttons[2] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[2]) : 0);

    //Key extended masks
    buttonMask |= GetActiveKeyModifiers();

    if (isRequestedPositionInsideComponent && !isMouseInsideComponent)
    {
        //MOUSE_ENTERED
        this->x = x; this->y = y;
        java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_ENTERED, when, buttonMask, x, y, 0, false, 0);
        java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_MOVED, when, buttonMask, x, y, 0, false, 0);
    }
    else if (!isRequestedPositionInsideComponent && isMouseInsideComponent)
    {
        //MOUSE_EXITED
        this->x = x; this->y = y;
        java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_EXITED, when, buttonMask, x, y, 0, false, 0);
    }
    else if (isRequestedPositionInsideComponent && isMouseInsideComponent)
    {
        //MOUSE_MOVED OR MOUSE_DRAGGED
        if (isDragging)
        {
            this->x = x; this->y = y;
            java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_DRAGGED, when, buttonMask, x, y, click_count, false, button);
        }
        else
        {
            this->x = x; this->y = y;
            java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_MOVED, when, buttonMask, x, y, 0, false, 0);
        }
    }
    else if (!isRequestedPositionInsideComponent && !isMouseInsideComponent)
    {
        //MOUSE_DRAGGED OUTSIDE
        this->x = x; this->y = y;
        if (isDragging)
        {
            java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_DRAGGED, when, buttonMask, x, y, click_count, false, button);
        }
    }
}

void InputOutput::hold_mouse(std::int32_t button) noexcept
{
    if (!this->is_mouse_held(button))
    {
        extern std::unique_ptr<ControlCenter> control_center;
        if (!control_center)
        {
            return;
        }

        java::Component receiver = control_center->reflect_canvas();
        JNIEnv* env = receiver.getEnv();
        this->handle_resize(&receiver);

        bool isRequestedPositionInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));
        if (isRequestedPositionInsideComponent)
        {
            std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

            //Button priority is 1 (left), 3 (right), 2 (middle)
            mouse_buttons[SimbaMouseButtonToJava(button) - 1] = true;
            std::int32_t button = mouse_buttons[0] ? 1 : mouse_buttons[2] ? 3 : mouse_buttons[1] ? 2 : 0;
            std::int32_t buttonMask = (mouse_buttons[0] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[0]) : 0) |
                                      (mouse_buttons[1] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[1]) : 0) |
                                      (mouse_buttons[2] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[2]) : 0);

            //Gain Focus
            if (!this->has_focus(&receiver))
            {
                this->gain_focus(&receiver);
            }

            //Key extended masks
            buttonMask |= GetActiveKeyModifiers();
            java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_PRESSED, when, buttonMask, this->x, this->y, click_count, false, button);
        }
        else
        {
            //Lose Focus
            if (this->has_focus(&receiver))
            {
                this->lose_focus(&receiver);
            }
        }
    }
}

void InputOutput::release_mouse(std::int32_t button) noexcept
{
    if (this->is_mouse_held(button))
    {
        extern std::unique_ptr<ControlCenter> control_center;
        if (!control_center)
        {
            return;
        }

        java::Component receiver = control_center->reflect_canvas();
        JNIEnv* env = receiver.getEnv();
        this->handle_resize(&receiver);

        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

        bool isRequestedPositionInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));
        bool isMouseInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));
        bool isDragging = mouse_buttons[0] || mouse_buttons[1] || mouse_buttons[2];

        //Button priority is 1 (left), 3 (right), 2 (middle)
        mouse_buttons[SimbaMouseButtonToJava(button) - 1] = false;

        std::int32_t button = mouse_buttons[0] ? 1 : mouse_buttons[2] ? 3 : mouse_buttons[1] ? 2 : 0;
        std::int32_t buttonMask = (mouse_buttons[0] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[0]) : 0) |
                                  (mouse_buttons[1] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[1]) : 0) |
                                  (mouse_buttons[2] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[2]) : 0);

        //Key extended masks
        buttonMask |= GetActiveKeyModifiers();

        //MOUSE_RELEASED
        java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_RELEASED, when, buttonMask, this->x, this->y, click_count, false, button);

        if (!isDragging && isRequestedPositionInsideComponent && isMouseInsideComponent)
        {
            //MOUSE_CLICKED
            java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_CLICKED, when, buttonMask, this->x, this->y, click_count, false, button);
        }
    }
}

void InputOutput::scroll_mouse(std::int32_t lines) noexcept
{
    extern std::unique_ptr<ControlCenter> control_center;
    if (!control_center)
    {
        return;
    }

    java::Component receiver = control_center->reflect_canvas();
    JNIEnv* env = receiver.getEnv();
    this->handle_resize(&receiver);

    bool isRequestedPositionInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));
    bool isMouseInsideComponent = !(this->x < 0 || this->y < 0 || this->x > static_cast<std::int32_t>(this->w) || this->y > static_cast<std::int32_t>(this->h));

    if (isRequestedPositionInsideComponent && isMouseInsideComponent)
    {
        //Relative Mouse Position
        std::int32_t cx = 0;
        std::int32_t cy = 0;
        receiver.getLocationOnScreen(cx, cy);

        if (cx == -1 || cy == -1)
        {
            cx = 0;
            cy = 0;
        }

        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();
        std::int32_t modifiers = GetActiveKeyModifiers();

        //Technically, this should be gausian curve or bell-curve or parabola as the wheel speeds up and slows down..
        double precision = lines > 0 ? Random::instance()->generate_random_int(1, 9) : lines < 0 ? Random::instance()->generate_random_int(-9, -1) : Random::instance()->generate_random_double(-1, 1);
        precision /= 10.0;

        java::MouseWheelEvent::Post(env,
                                    &receiver,
                                    java::MouseEvent::MouseEventCodes::MOUSE_WHEEL,
                                    when,
                                    modifiers,
                                    this->x,
                                    this->y,
                                    cx + this->x,
                                    cy + this->y,
                                    0,
                                    false,
                                    java::MouseWheelEvent::MouseWheelEventCodes::WHEEL_UNIT_SCROLL,
                                    1,
                                    lines,
                                    lines + precision);
    }
    else if (isRequestedPositionInsideComponent && !isMouseInsideComponent)
    {
        std::int64_t when = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::high_resolution_clock::now().time_since_epoch()).count();

        bool isDragging = mouse_buttons[0] || mouse_buttons[1] || mouse_buttons[2];
        if (isDragging)
        {
            return; //Developer is an idiot. We shouldn't even have to be here in the first place..
        }

        //Button priority is 1 (left), 3 (right), 2 (middle)
        std::int32_t buttonMask = (mouse_buttons[0] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[0]) : 0) |
                                  (mouse_buttons[1] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[1]) : 0) |
                                  (mouse_buttons[2] ? java::InputEvent::GetDownMaskForButton(mouse_buttons[2]) : 0);

        //Key extended masks
        buttonMask |= GetActiveKeyModifiers();


        //MOUSE_ENTERED
        java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_ENTERED, when, buttonMask, this->x, this->y, 0, false, 0);
        java::MouseEvent::Post(env, &receiver, java::MouseEvent::MouseEventCodes::MOUSE_MOVED, when, buttonMask, this->x, this->y, 0, false, 0);

        // Recursive call
        scroll_mouse(lines);
    }
}

bool InputOutput::is_mouse_held(std::int32_t button) const noexcept
{
    switch (button)
    {
        case 1: return mouse_buttons[0];
        case 2: return mouse_buttons[1];
        case 3: return mouse_buttons[2];
        default: return false;
    }
}

void InputOutput::stop_all_processing() noexcept
{
    std::int32_t x = 0;
    std::int32_t y = 0;

    this->get_mouse_position(&x, &y);
    this->release_mouse(0);
    this->release_mouse(1);
    this->release_mouse(2);

    for (std::int32_t key : std::vector<std::int32_t>(held_keys.begin(), held_keys.end()))
    {
        this->release_key(key);
    }

    this->set_keyboard_input_enabled(true);
    this->set_mouse_input_enabled(true);
}

jchar InputOutput::NativeKeyCodeToChar(std::int32_t keycode, std::int32_t modifiers) const noexcept
{
    static const std::int32_t mapping[256] = { //char16_t
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0008, 0x0009, 0x0000, 0x0000, 0x0000, 0x000A, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0020, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
        0x0038, 0x0039, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0061, 0x0062, 0x0063, 0x0064, 0x0065, 0x0066, 0x0067,
        0x0068, 0x0069, 0x006A, 0x006B, 0x006C, 0x006D, 0x006E, 0x006F,
        0x0070, 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076, 0x0077,
        0x0078, 0x0079, 0x007A, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
        0x0038, 0x0039, 0x002A, 0x002B, 0x002C, 0x002D, 0x002E, 0x002F,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x003B, 0x002B, 0x002C, 0x002D, 0x002E, 0x002F,
        0x0060, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x005B, 0x005C, 0x005D, 0x0027, 0x0000,
        0x0000, 0x0000, 0x005C, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000
    };

    if (modifiers != 0)
    {
        static const std::string normal_chars = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./";
        static const std::string shift_chars = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?";
        if (auto index = normal_chars.find(static_cast<char>(mapping[keycode])); index != std::string::npos)
        {
            return static_cast<jchar>(shift_chars[index]);
        }
    }
    return static_cast<jchar>(mapping[keycode]);
}

std::int32_t InputOutput::CharToJavaKeyCode(char c) const noexcept
{
    //Convert char to Java VK-Codes
    //0x00 and 0xFF are not type-able characters..
    static const std::uint32_t mapping[256] = {
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0008, 0x0009, 0x000A, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x001B, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0020, 0x0205, 0x0098, 0x0208, 0x0203, 0x0000, 0x0096, 0x00DE,
        0x0207, 0x020A, 0x0097, 0x0209, 0x002C, 0x002D, 0x002E, 0x002F,
        0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
        0x0038, 0x0039, 0x0201, 0x003B, 0x0099, 0x003D, 0x00A0, 0x0000,
        0x0200, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047,
        0x0048, 0x0049, 0x004A, 0x004B, 0x004C, 0x004D, 0x004E, 0x004F,
        0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057,
        0x0058, 0x0059, 0x005A, 0x005B, 0x005C, 0x005D, 0x0202, 0x020B,
        0x00C0, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047,
        0x0048, 0x0049, 0x004A, 0x004B, 0x004C, 0x004D, 0x004E, 0x004F,
        0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057,
        0x0058, 0x0059, 0x005A, 0x00A1, 0xFFFF, 0x00A2, 0xFFFF, 0x007F,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0206, 0xFFFF, 0xFFFF, 0x0000, 0xFFFF, 0x0000, 0xFFFF,
        0xFFFF, 0x0000, 0x0000, 0xFFFF, 0x0000, 0x0000, 0x0000, 0x0000,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0x0000, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0x0000, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0000,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0x0000, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0000,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
        0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0000
    };
    return mapping[static_cast<std::int32_t>(c)];
}

std::int32_t InputOutput::GetJavaKeyCode(std::int32_t native_key_code) const noexcept
{
    //Convert WinAPI VK-Codes to Java VK-Codes
    //0x00 and 0xFF are not type-able characters..
    static const std::uint32_t windows_mapping[256] = {
        0x0000, 0x0000, 0x0000, 0x0003, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0008, 0x0009, 0x0000, 0x0000, 0x000C, 0x000A, 0x0000, 0x0000,
        0x0010, 0x0011, 0x0012, 0x0013, 0x0014, 0x0015, 0x0000, 0x0000,
        0x0018, 0x0019, 0x0000, 0x001B, 0x001C, 0x001D, 0x001E, 0x001F,
        0x0020, 0x0021, 0x0022, 0x0023, 0x0024, 0x0025, 0x0026, 0x0027,
        0x0028, 0x0000, 0x0000, 0x0000, 0x009A, 0x009B, 0x007F, 0x009C,
        0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
        0x0038, 0x0039, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047,
        0x0048, 0x0049, 0x004A, 0x004B, 0x004C, 0x004D, 0x004E, 0x004F,
        0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057,
        0x0058, 0x0059, 0x005A, 0x020C, 0x020C, 0x020D, 0x0000, 0x0000,
        0x0060, 0x0061, 0x0062, 0x0063, 0x0064, 0x0065, 0x0066, 0x0067,
        0x0068, 0x0069, 0x006A, 0x006B, 0x006C, 0x006D, 0x006E, 0x006F,
        0x0070, 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076, 0x0077,
        0x0078, 0x0079, 0x007A, 0x007B, 0xF000, 0xF001, 0xF002, 0xF003,
        0xF004, 0xF005, 0xF006, 0xF007, 0xF008, 0xF009, 0xF00A, 0xF00B,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0090, 0x0091, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0010, 0x0010, 0x0011, 0x0011, 0x0012, 0x0012, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x003B, 0x0209, 0x002C, 0x002D, 0x002E, 0x002F,
        0x0083, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x005B, 0x005C, 0x005D, 0x00DE, 0x0205,
        0x0000, 0x0000, 0x0081, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
        0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x000C, 0x0000
    };
    return windows_mapping[native_key_code];
}

std::int32_t InputOutput::GetKeyLocation(std::int32_t keycode) const noexcept
{
    if (std::find(std::begin(control_keys), std::end(control_keys), keycode) != std::end(control_keys))
    {
        return control_keys_locations[keycode];
    }
    return java::KeyEvent::KeyCodes::KEY_LOCATION_STANDARD;
}

std::int32_t InputOutput::GetActiveKeyModifiers() const noexcept
{
    std::int32_t modifiers = 0;

    //Key extended masks
    if (this->any_key_held({VK_SHIFT, VK_LSHIFT, VK_RSHIFT}))
    {
        modifiers |= java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
    }

    if (this->any_key_held({VK_CONTROL, VK_LEFT_CONTROL, VK_RIGHT_CONTROL}))
    {
        modifiers |= java::InputEvent::InputEventMasks::CTRL_DOWN_MASK;
    }

    if (this->is_key_held(VK_LEFT_ALT))
    {
        modifiers |= java::InputEvent::InputEventMasks::ALT_DOWN_MASK;
    }

    if (this->is_key_held(VK_RIGHT_ALT))
    {
        modifiers |= java::InputEvent::InputEventMasks::ALT_GRAPH_DOWN_MASK;
    }

    if (this->any_key_held({VK_LEFT_WINDOWS, VK_RIGHT_WINDOWS, VK_COMMAND}))
    {
        modifiers |= java::InputEvent::InputEventMasks::META_DOWN_MASK;
    }
    return modifiers;
}

std::int32_t InputOutput::ModifiersForChar(char c) const noexcept
{
    std::int32_t modifiers = 0;
    static const std::string shift_chars = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?";
    if (shift_chars.find(c) != std::string::npos)
    {
        modifiers |= java::InputEvent::InputEventMasks::SHIFT_DOWN_MASK;
    }
    return modifiers;
}

std::int32_t InputOutput::SimbaMouseButtonToJava(std::int32_t button) const noexcept
{
    //Java Button priority is 1 (left), 3 (right), 2 (middle)
    //Simba Button priority is 1 (left), 0 (right), 2 (middle)
    return button == 1 ? 1 : button == 0 ? 3 : button == 2 ? 2 : button;
}

void InputOutput::get_applet_dimensions(std::int32_t &x, std::int32_t &y, std::size_t &width, std::size_t &height) const noexcept
{
    JNIEnv* env = nullptr;
    if (this->vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK)
    {
        java::Applet receiver{env, this->applet, false};
        //Component receiver = applet.getComponent(0);

        receiver.getLocation(x, y);
        receiver.getSize(width, height);
    }
    else
    {
        x = -1;
        y = -1;
        width = static_cast<std::int32_t>(this->w);
        height = static_cast<std::int32_t>(this->h);
    }
}

void InputOutput::get_canvas_dimensions(std::int32_t &x, std::int32_t &y, std::size_t &width, std::size_t &height) const noexcept
{
    JNIEnv* env = nullptr;
    if (this->vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK)
    {
        java::Applet receiver{env, this->applet, false};
        java::Component canvas = receiver.getComponent(0);

        canvas.getLocation(x, y);
        canvas.getSize(width, height);
    }
    else
    {
        x = -1;
        y = -1;
        width = static_cast<std::int32_t>(this->w);
        height = static_cast<std::int32_t>(this->h);
    }
}

void InputOutput::get_applet_mouse_position(std::int32_t &x, std::int32_t &y) const noexcept
{
    x = this->x;
    y = this->y;
}
