#ifndef KINPUTCTRL_HPP_INCLUDED
#define KINPUTCTRL_HPP_INCLUDED

#include "Injector.hpp"

class KInputCtrl : private Injector
{
    private:
        DWORD PID;
        void* DLL;
        std::string FileName;
    public:
        KInputCtrl(DWORD PID, std::string Path);
        bool FocusEvent(std::int32_t ID);
        bool KeyEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t KeyCode,
                      std::uint16_t KeyChar, std::int32_t KeyLocation);
        bool MouseEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t X,
                        std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger, std::int32_t Button);
        bool MouseWheelEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t X,
                             std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger, std::int32_t ScrollType,
                             std::int32_t ScrollAmount, std::int32_t WheelRotation);
        ~KInputCtrl();
};

#endif // KINPUTCTRL_HPP_INCLUDED
