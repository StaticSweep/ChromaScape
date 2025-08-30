/*
    This program was modified by ThatOneGuyScripts April,15,2023
    Copyright (C) <2023>  <ThatOneGuyScripts>
    Copyright (C) <2018>  <Kasi Reddy>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

#include <windows.h>
#include <iostream>
#include "KInput.hpp"

KInput* Input = nullptr;

extern "C"
__declspec(dllexport)
bool KInput_FocusEvent(void* Data)
{
    if (!Input)
        return false;
    struct FocusEvent
    {
        std::int32_t ID;
    };
    FocusEvent* Event = (FocusEvent*)Data;
    return Input->FocusEvent(Event->ID);
}

extern "C"
__declspec(dllexport)
bool KInput_KeyEvent(void* Data)
{
    if (!Input)
        return false;
    struct KeyEvent
    {
        std::int32_t ID;
        std::int64_t When;
        std::int32_t Modifiers;
        std::int32_t KeyCode;
        std::uint16_t KeyChar;
        std::int32_t KeyLocation;
    };
    KeyEvent* Event = (KeyEvent*)Data;
    return Input->KeyEvent(Event->ID, Event->When, Event->Modifiers, Event->KeyCode,
                           Event->KeyChar, Event->KeyLocation);
}

extern "C"
__declspec(dllexport)
bool KInput_MouseEvent(void* Data)
{
    if (!Input)
        return false;
    struct MouseEvent
    {
        std::int32_t ID;
        std::int64_t When;
        std::int32_t Modifiers;
        std::int32_t X;
        std::int32_t Y;
        std::int32_t ClickCount;
        bool PopupTrigger;
        std::int32_t Button;
    };
    MouseEvent* Event = (MouseEvent*)Data;
    return Input->MouseEvent(Event->ID, Event->When, Event->Modifiers, Event->X,
                             Event->Y, Event->ClickCount, Event->PopupTrigger, Event->Button);
}

extern "C"
__declspec(dllexport)
bool KInput_MouseWheelEvent(void* Data)
{
    if (!Input)
        return false;
    struct MouseWheelEvent
    {
        std::int32_t ID;
        std::int64_t When;
        std::int32_t Modifiers;
        std::int32_t X;
        std::int32_t Y;
        std::int32_t ClickCount;
        bool PopupTrigger;
        std::int32_t ScrollType;
        std::int32_t ScrollAmount;
        std::int32_t WheelRotation;
    };
    MouseWheelEvent* Event = (MouseWheelEvent*)Data;
    return Input->MouseWheelEvent(Event->ID, Event->When, Event->Modifiers, Event->X,
                                  Event->Y, Event->ClickCount, Event->PopupTrigger, Event->ScrollType,
                                  Event->ScrollAmount, Event->WheelRotation);
}

bool __stdcall DllMain(HMODULE DLL, DWORD fdwReason, LPVOID lpvReserved)
{
    switch (fdwReason)
    {
        case DLL_PROCESS_ATTACH:
            {
                DisableThreadLibraryCalls(DLL);
                Input = new KInput();

            }
            break;
        case DLL_PROCESS_DETACH:
            {
                if (Input)
                {
                    delete Input;
                    Input = nullptr;

                }
            }
            break;
        default:
            {

            }
            break;
    }
    return TRUE;
}
