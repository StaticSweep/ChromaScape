/*
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

#include <iostream>
#include <windows.h>
#include <chrono>
#define OSRSPID 9892

std::string KInputCtrlPath = R"(D:\Dropbox\KInput\KInputCtrl\bin\Release\KInputCtrl.dll)";

typedef enum Focus_Event
{
    GAINED = 1004,
    LOST = 1005
} Focus_Event;

typedef enum Mouse_Event
{
    NOBUTTON = 0,
    BUTTON1 = 1,
    BUTTON2 = 2,
    BUTTON3 = 3,
    MOUSE_CLICK = 500,
    MOUSE_PRESS = 501,
    MOUSE_RELEASE = 502,
    MOUSE_MOVE = 503,
    MOUSE_ENTER = 504,
    MOUSE_EXIT = 505,
    MOUSE_DRAG = 506,
    MOUSE_WHEEL = 507
} Mouse_Event;

typedef enum Key_Event
{
    KEY_TYPED = 400,
    KEY_PRESSED = 401,
    KEY_RELEASED = 402
} Key_Event;

std::uint64_t CurrentTimeMillis()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

int main()
{
    std::cout << CurrentTimeMillis() << std::endl;
    void* KInputCtrl = LoadLibrary(KInputCtrlPath.c_str());
    if (KInputCtrl)
    {
        typedef bool (*ptrKInput_Create)(DWORD PID);
        ptrKInput_Create KInput_Create = (ptrKInput_Create)GetProcAddress((HMODULE)KInputCtrl, "KInput_Create");

        typedef bool (*ptrKInput_Delete)(DWORD PID);
        ptrKInput_Delete KInput_Delete = (ptrKInput_Delete)GetProcAddress((HMODULE)KInputCtrl, "KInput_Delete");


        typedef bool (*ptrKInput_FocusEvent)(DWORD PID, std::int32_t ID);
        ptrKInput_FocusEvent KInput_FocusEvent = (ptrKInput_FocusEvent)GetProcAddress((HMODULE)KInputCtrl, "KInput_FocusEvent");


        typedef bool (*ptrKInput_KeyEvent)(DWORD PID, std::int32_t ID, std::int64_t When, std::int32_t Modifiers,
                                           std::int32_t KeyCode, std::uint16_t KeyChar, std::int32_t KeyLocation);
        ptrKInput_KeyEvent KInput_KeyEvent = (ptrKInput_KeyEvent)GetProcAddress((HMODULE)KInputCtrl, "KInput_KeyEvent");


        typedef bool (*ptrKInput_MouseEvent)(DWORD PID, std::int32_t ID, std::int64_t When, std::int32_t Modifiers,
                                             std::int32_t X, std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger,
                                             std::int32_t Button);
        ptrKInput_MouseEvent KInput_MouseEvent = (ptrKInput_MouseEvent)GetProcAddress((HMODULE)KInputCtrl, "KInput_MouseEvent");


        typedef bool (*ptrKInput_MouseWheelEvent)(DWORD PID, std::int32_t ID, std::int64_t When, std::int32_t Modifiers,
                                                  std::int32_t X, std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger,
                                                  std::int32_t ScrollType, std::int32_t ScrollAmount, std::int32_t WheelRotation);
        ptrKInput_MouseWheelEvent KInput_MouseWheelEvent = (ptrKInput_MouseWheelEvent)GetProcAddress((HMODULE)KInputCtrl, "KInput_MouseWheelEvent");

        if (KInput_Create(OSRSPID))
        {

            KInput_MouseEvent(OSRSPID, Mouse_Event::MOUSE_PRESS, CurrentTimeMillis(), 0, 485, 290, 1, false, Mouse_Event::BUTTON1);
/*KInput_KeyEvent(OSRSPID, 400, CurrentTimeMillis(), 0, 0, 'K', 0);
            KInput_FocusEvent(OSRSPID, Focus_Event::GAINED);
            KInput_MouseEvent(OSRSPID, Mouse_Event::MOUSE_MOVE, CurrentTimeMillis(), 0, 400, 400, 0, false, Mouse_Event::NOBUTTON);
            KInput_MouseWheelEvent(OSRSPID, Mouse_Event::MOUSE_WHEEL, CurrentTimeMillis(), 0, 100, 100, 0, false, 0, 3, 3);
*/

            KInput_Delete(OSRSPID);
        }

        FreeLibrary((HMODULE)KInputCtrl);
    } else
    return 0;
}
