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
#include <map>
#include <iostream>
#include <chrono>
#include "KInputCtrl.hpp"

static HMODULE This = nullptr;
static std::map<DWORD, KInputCtrl*> Clients;

static const char* SimbaExports[] =
{
    "KInput_Create", "function KInput_Create(PID : Int32) : Boolean;",
    "KInput_Delete", "function KInput_Delete(PID : Int32) : Boolean;",
    "KInput_FocusEvent", "function KInput_FocusEvent(PID : Int32; ID : Int32) : Boolean;",
    "KInput_KeyEvent", "function KInput_KeyEvent(PID : Int32; ID : Int32; When : Int64; Modifiers : Int32; KeyCode : Int32; KeyChar : UInt16; KeyLocation : Int32) : Boolean;",
    "KInput_MouseEvent", "function KInput_MouseEvent(PID : Int32; ID : Int32; When : Int64; Modifiers : Int32; X : Int32; Y : Int32; ClickCount : Int32; PopupTrigger : Boolean; Button : Int32) : Boolean;",
    "KInput_MouseWheelEvent", "function KInput_MouseWheelEvent(PID : Int32; ID : Int32; When : Int64; Modifiers : Int32; X : Int32; Y : Int32; ClickCount : Int32; PopupTrigger : Boolean; ScrollType : Int32; ScrollAmount : Int32; WheelRotation : Int32) : Boolean;",
    "KInput_CurrentTimeMS", "function KInput_CurrentTimeMS() : Int64;"
};

static constexpr std::int32_t ExportSize = ((sizeof(SimbaExports) / 2) / sizeof(const char*));

extern "C"
__declspec(dllexport)
std::int32_t GetPluginABIVersion()
{
    return 2;
}

extern "C"
__declspec(dllexport)
std::int32_t GetFunctionCount()
{
    return ExportSize;
}

extern "C"
__declspec(dllexport)
std::int32_t GetFunctionInfo(std::int32_t Index, void* &Address, char* &Definition)
{
    if (Index < ExportSize)
    {
        Address = (void*)GetProcAddress(This, SimbaExports[Index * 2]);
        strcpy(Definition, SimbaExports[(Index * 2) + 1]);
        return Index;
    }
    return -1;
}

extern "C"
__declspec(dllexport)
bool KInput_Create(DWORD PID)
{
    if (!Clients.count(PID))
    {
        std::string FilePath(MAX_PATH, '\0');
        GetModuleFileName(This, FilePath.data(), FilePath.size());
        FilePath = FilePath.substr(0, FilePath.find_last_of("/\\"));
        Clients[PID] = new KInputCtrl(PID, FilePath);
        return true;
    }
    return false;
}

extern "C"
__declspec(dllexport)
bool KInput_Delete(DWORD PID)
{
    if (Clients.count(PID))
    {
        delete Clients[PID];
        Clients[PID] = nullptr;
        Clients.erase(PID);
        return true;
    }
    return false;
}

extern "C"
__declspec(dllexport)
bool KInput_FocusEvent(DWORD PID, std::int32_t ID)
{
    if (!Clients.count(PID))
        return false;
    return Clients[PID]->FocusEvent(ID);
}

extern "C"
__declspec(dllexport)
bool KInput_KeyEvent(DWORD PID, std::int32_t ID, std::int64_t When, std::int32_t Modifiers,
                     std::int32_t KeyCode, std::uint16_t KeyChar, std::int32_t KeyLocation)
{
    if (!Clients.count(PID))
        return false;
    return Clients[PID]->KeyEvent(ID, When, Modifiers, KeyCode, KeyChar, KeyLocation);
}

extern "C"
__declspec(dllexport)
bool KInput_MouseEvent(DWORD PID, std::int32_t ID, std::int64_t When, std::int32_t Modifiers,
                       std::int32_t X, std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger,
                       std::int32_t Button)
{
    if (!Clients.count(PID))
        return false;
    return Clients[PID]->MouseEvent(ID, When, Modifiers, X, Y, ClickCount, PopupTrigger, Button);
}

extern "C"
__declspec(dllexport)
bool KInput_MouseWheelEvent(DWORD PID, std::int32_t ID, std::int64_t When, std::int32_t Modifiers,
                            std::int32_t X, std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger,
                            std::int32_t ScrollType, std::int32_t ScrollAmount, std::int32_t WheelRotation)
{
    if (!Clients.count(PID))
        return false;
    return Clients[PID]->MouseWheelEvent(ID, When, Modifiers, X, Y, ClickCount, PopupTrigger, ScrollType, ScrollAmount, WheelRotation);
}

extern "C"
__declspec(dllexport)
std::int64_t KInput_CurrentTimeMS()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

bool __stdcall DllMain(HMODULE DLL, DWORD fdwReason, LPVOID lpvReserved)
{
    switch (fdwReason)
    {
        case DLL_PROCESS_ATTACH:
            {
                This = DLL;
                DisableThreadLibraryCalls(This);
            }
            break;
        case DLL_PROCESS_DETACH:
            {

            }
            break;
        default:
            {

            }
            break;
    }
    return TRUE;
}
