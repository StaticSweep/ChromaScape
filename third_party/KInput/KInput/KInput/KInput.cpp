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

#include "KInput.hpp"
#include <vector>
#include <string>
#include <iostream>

static BOOL CALLBACK GetHWNDCurrentPID(HWND WindowHandle, LPARAM lParam)
{
    std::vector<HWND>* Temp = reinterpret_cast<std::vector<HWND>*>(lParam);
    DWORD PID = GetCurrentProcessId();
    DWORD HWNDPID;
    GetWindowThreadProcessId(WindowHandle, &HWNDPID);
    if (PID == HWNDPID)
        Temp->push_back(WindowHandle);
    return TRUE;
}

HWND GetCanvasHWND()
{
    HWND CanvasHandle = 0;
    std::vector<HWND> OSRSWindows;
    EnumWindows(GetHWNDCurrentPID, reinterpret_cast<LPARAM>(&OSRSWindows));
    if (OSRSWindows.size() > 0)
    {
        for (auto const& Window : OSRSWindows)
        {
            char ClassName[128];
            GetClassName(Window, ClassName, sizeof(ClassName));
            std::string WindowClassName(ClassName);
            if (WindowClassName == "SunAwtFrame")
            {
                CanvasHandle = Window;
                break;
            }
        }
        HWND Temp = CanvasHandle;
        CanvasHandle = nullptr;
        int C = 0;
        while (Temp)
        {
            Temp = GetWindow(Temp, GW_CHILD);
            if (Temp)
            {
                char ClassName[128];
                GetClassName(Temp, ClassName, sizeof(ClassName));
                std::string ChildClassName(ClassName);
                if (ChildClassName == "SunAwtCanvas")
                {
                    CanvasHandle = Temp;
                    C++;
                }
            }
        }
        if (C < 2)
            CanvasHandle = nullptr;
    }
    return CanvasHandle;
}

void KInput::GrabCanvas() {
    HMODULE JVMDLL = GetModuleHandle("jvm.dll");
    if (!JVMDLL)
        return;

    ptr_GCJavaVMs GetJVMs = (ptr_GCJavaVMs)GetProcAddress(JVMDLL, "JNI_GetCreatedJavaVMs");
    jobject TempCanvas = nullptr;
    JNIEnv* Thread = nullptr;
    do {
        GetJVMs(&(this->JVM), 1, nullptr);
        if (!this->JVM)
            break;

        this->AttachThread(&Thread);

        HMODULE AWTDLL = GetModuleHandle("awt.dll");
        if (!AWTDLL)
            break;

        char* AwtFuncName = nullptr;
        if (sizeof(void*) == 8) {
            AwtFuncName = "DSGetComponent";
        }
        else if (sizeof(void*) == 4) {
            AwtFuncName = "_DSGetComponent@8";
        }
        else {
            break;
        }

        this->GetComponent = (ptr_GetComponent)GetProcAddress(AWTDLL, AwtFuncName);
        if (!(Thread && this->GetComponent))
            break;

        HWND CanvasHWND = GetCanvasHWND();
        if (!CanvasHWND)
            break;

        TempCanvas = this->GetComponent(Thread, (void*)CanvasHWND);
        if (!TempCanvas)
            break;

        this->Canvas = Thread->NewGlobalRef(TempCanvas);
        if (!this->Canvas)
            break;

        if (TempCanvas)
        {
            Thread->DeleteLocalRef(TempCanvas);
            TempCanvas = nullptr;
        }

        jclass CanvasClass = Thread->GetObjectClass(this->Canvas);
        if (!CanvasClass)
            break;

        jmethodID Canvas_getParent = Thread->GetMethodID(CanvasClass, "getParent", "()Ljava/awt/Container;");
        jobject TempClient = (jstring)Thread->CallObjectMethod(this->Canvas, Canvas_getParent);
        if (TempClient)
        {
            this->Client = Thread->NewGlobalRef(TempClient);
            this->Initialized = true;
            Thread->DeleteLocalRef(TempClient);
        }
        Thread->DeleteLocalRef(CanvasClass);
    } while (false);
    if (Thread)
        this->DetachThread(&Thread);
}

void KInput::NotifyCanvasUpdate(HWND CanvasHWND)
{
    this->CanvasUpdate = CanvasHWND;
}

void KInput::UpdateCanvas(JNIEnv* Thread)
{
    if (!CanvasUpdate)
        return;
    jobject TempCanvas = nullptr;
    do
    {
        if (!(Thread && this->GetComponent))
            break;
        TempCanvas = this->GetComponent(Thread, (void*)CanvasUpdate);
        if (!TempCanvas)
            break;
        if (this->Canvas)
        {
            Thread->DeleteGlobalRef(this->Canvas);
            this->Canvas = nullptr;
        }
        this->Canvas = Thread->NewGlobalRef(TempCanvas);

        if (TempCanvas)
        {
            Thread->DeleteLocalRef(TempCanvas);
            TempCanvas = nullptr;
        }
    } while (false);
    CanvasUpdate = nullptr;
}

KInput::KInput()
{
    std::cout << "Starting a new KInput instance! o/" << std::endl;
    this->Initialized = false;
    this->JVM = nullptr;
    this->Client = nullptr;
    this->Canvas = nullptr;
    this->GetComponent = nullptr;
    this->Canvas_Class = nullptr;
    this->Canvas_DispatchEvent = nullptr;
    this->FocusEvent_Class = nullptr;
    this->FocusEvent_Init = nullptr;
    this->KeyEvent_Class = nullptr;
    this->KeyEvent_Init = nullptr;
    this->MouseEvent_Class = nullptr;
    this->MouseEvent_Init = nullptr;
    this->MouseWheelEvent_Class = nullptr;
    this->MouseWheelEvent_Init = nullptr;
    this->CanvasUpdate = nullptr;
    this->GrabCanvas();
}

bool KInput::AttachThread(JNIEnv** Thread)
{
    if (this->JVM)
        if (this->JVM->GetEnv((void**)Thread, JNI_VERSION_1_6) == JNI_EDETACHED)
            this->JVM->AttachCurrentThread((void**)Thread, nullptr);
    return (*Thread);
}

bool KInput::DetachThread(JNIEnv** Thread)
{
    if (*Thread)
        if (this->JVM)
            if (this->JVM->DetachCurrentThread() == JNI_OK)
                *Thread = nullptr;
    return !(*Thread);
}

bool KInput::DispatchEvent(jobject Event, JNIEnv* Thread)
{
    if (Thread)
    {
        if (!this->Canvas_Class)
        {
            jclass Temp = Thread->FindClass("Ljava/awt/Canvas;");
            if (Temp)
            {
                this->Canvas_Class = (jclass)Thread->NewGlobalRef(Temp);
                Thread->DeleteLocalRef(Temp);
            }
        }
        if (this->Canvas_Class)
        {
            if (!this->Canvas_DispatchEvent)
                this->Canvas_DispatchEvent = Thread->GetMethodID(this->Canvas_Class, "dispatchEvent", "(Ljava/awt/AWTEvent;)V");
            if (this->Canvas_DispatchEvent)
            {
                this->UpdateCanvas(Thread);
                Thread->CallVoidMethod(this->Canvas, this->Canvas_DispatchEvent, Event);
                return true;
            }
        }
    }
    return false;
}

bool KInput::FocusEvent(std::int32_t ID)
{
    JNIEnv* Thread = nullptr;
    if (this->AttachThread(&Thread))
    {
        if (!this->FocusEvent_Class)
        {
            jclass Temp = Thread->FindClass("Ljava/awt/event/FocusEvent;");
            if (Temp)
            {
                this->FocusEvent_Class = (jclass)Thread->NewGlobalRef(Temp);
                Thread->DeleteLocalRef(Temp);
            }
        }
        if (this->FocusEvent_Class)
        {
            if (!this->FocusEvent_Init)
                this->FocusEvent_Init = Thread->GetMethodID(this->FocusEvent_Class, "<init>", "(Ljava/awt/Component;I)V");
            if (this->FocusEvent_Init)
            {
                if (this->Canvas == nullptr)
                {
                    MessageBox(NULL, "Canvas is null", "Error", MB_OK);
                    this->DetachThread(&Thread);
                    return false;
                }

                jobject FocusEvent_Object = Thread->NewObject(this->FocusEvent_Class, this->FocusEvent_Init, this->Canvas, static_cast<std::int32_t>(ID));
                if (FocusEvent_Object)
                {
                    bool Result = this->DispatchEvent(FocusEvent_Object, Thread);
                    Thread->DeleteLocalRef(FocusEvent_Object);
                    this->DetachThread(&Thread);
                    return Result;
                }
                else
                {
                    MessageBox(NULL, "Failed to create FocusEvent_Object", "Debug Message", MB_OK);
                    if (Thread->ExceptionOccurred())
                    {
                        jthrowable Exception = Thread->ExceptionOccurred();
                        Thread->ExceptionClear();

                        jclass ThrowableClass = Thread->FindClass("java/lang/Throwable");
                        jmethodID Throwable_getMessage = Thread->GetMethodID(ThrowableClass, "getMessage", "()Ljava/lang/String;");
                        jstring ExceptionMessage = (jstring)Thread->CallObjectMethod(Exception, Throwable_getMessage);

                        const char* ExceptionMessageChars = Thread->GetStringUTFChars(ExceptionMessage, nullptr);
                        MessageBox(NULL, ExceptionMessageChars, "Exception Message", MB_OK);
                        Thread->ReleaseStringUTFChars(ExceptionMessage, ExceptionMessageChars);
                    }
                }
            }
            else
            {
                MessageBox(NULL, "Failed to get method ID for FocusEvent constructor", "Debug Message", MB_OK);
            }
        }
        else
        {
            MessageBox(NULL, "Failed to find java.awt.event.FocusEvent class", "Debug Message", MB_OK);
        }
        this->DetachThread(&Thread);
    }
    else
    {
        MessageBox(NULL, "Failed to attach thread to JVM", "Debug Message", MB_OK);
    }
    return false;
}

bool KInput::KeyEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t KeyCode,
    std::uint16_t KeyChar, std::int32_t KeyLocation)
{
    JNIEnv* Thread = nullptr;
    if (this->AttachThread(&Thread))
    {
        if (!this->KeyEvent_Class)
        {
            jclass Temp = Thread->FindClass("Ljava/awt/event/KeyEvent;");
            if (Temp)
            {
                this->KeyEvent_Class = (jclass)Thread->NewGlobalRef(Temp);
                Thread->DeleteLocalRef(Temp);
            }
        }
        if (this->KeyEvent_Class)
        {
            if (!this->KeyEvent_Init)
                this->KeyEvent_Init = Thread->GetMethodID(this->KeyEvent_Class, "<init>", "(Ljava/awt/Component;IJIICI)V");
            if (this->KeyEvent_Init)
            {
                jobject KeyEvent_Object = Thread->NewObject(this->KeyEvent_Class, this->KeyEvent_Init, this->Canvas, ID, When,
                    Modifiers, KeyCode, KeyChar, KeyLocation);
                if (KeyEvent_Object)
                {
                    bool Result = this->DispatchEvent(KeyEvent_Object, Thread);
                    Thread->DeleteLocalRef(KeyEvent_Object);
                    this->DetachThread(&Thread);
                    return Result;
                }
            }
        }
        this->DetachThread(&Thread);
    }
    return false;
}

bool KInput::MouseEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t X,
    std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger, std::int32_t Button)
{
    JNIEnv* Thread = nullptr;
    if (this->AttachThread(&Thread))
    {
        if (!this->MouseEvent_Class)
        {
            jclass Temp = Thread->FindClass("Ljava/awt/event/MouseEvent;");
            if (Temp)
            {
                this->MouseEvent_Class = (jclass)Thread->NewGlobalRef(Temp);
                Thread->DeleteLocalRef(Temp);
            }
        }
        if (this->MouseEvent_Class)
        {
            if (!this->MouseEvent_Init)
                this->MouseEvent_Init = Thread->GetMethodID(this->MouseEvent_Class, "<init>", "(Ljava/awt/Component;IJIIIIZI)V");
            if (this->MouseEvent_Init)
            {
                jobject MouseEvent_Object = Thread->NewObject(this->MouseEvent_Class, this->MouseEvent_Init, this->Client, ID, When,
                    Modifiers, X, Y, ClickCount,
                    PopupTrigger, Button);
                if (MouseEvent_Object)
                {
                    bool Result = this->DispatchEvent(MouseEvent_Object, Thread);
                    Thread->DeleteLocalRef(MouseEvent_Object);
                    this->DetachThread(&Thread);
                    return Result;
                }
            }
        }
        this->DetachThread(&Thread);
    }
    return false;
}

bool KInput::MouseWheelEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t X,
    std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger, std::int32_t ScrollType,
    std::int32_t ScrollAmount, std::int32_t WheelRotation)
{
    JNIEnv* Thread = nullptr;
    if (this->AttachThread(&Thread))
    {
        if (!this->MouseWheelEvent_Class)
        {
            jclass Temp = Thread->FindClass("Ljava/awt/event/MouseWheelEvent;");
            if (Temp)
            {
                this->MouseWheelEvent_Class = (jclass)Thread->NewGlobalRef(Temp);
                Thread->DeleteLocalRef(Temp);
            }
        }
        if (this->MouseWheelEvent_Class)
        {
            if (!this->MouseWheelEvent_Init)
                this->MouseWheelEvent_Init = Thread->GetMethodID(this->MouseWheelEvent_Class, "<init>", "(Ljava/awt/Component;IJIIIIZIII)V");
            if (this->MouseWheelEvent_Init)
            {
                jobject MouseWheelEvent_Object = Thread->NewObject(this->MouseWheelEvent_Class, this->MouseWheelEvent_Init, this->Client, ID,
                    When, Modifiers, X, Y,
                    ClickCount, PopupTrigger, ScrollType, ScrollAmount,
                    WheelRotation);
                if (MouseWheelEvent_Object)
                {
                    bool Result = this->DispatchEvent(MouseWheelEvent_Object, Thread);
                    Thread->DeleteLocalRef(MouseWheelEvent_Object);
                    this->DetachThread(&Thread);
                    return Result;
                }
            }
        }
        this->DetachThread(&Thread);
    }
    return false;
}

KInput::~KInput()
{
    JNIEnv* Thread = nullptr;
    if (this->AttachThread(&Thread))
    {
        if (this->Canvas)
        {
            Thread->DeleteGlobalRef(this->Canvas);
            this->Canvas = nullptr;
        }
        if (this->Client)
        {
            Thread->DeleteGlobalRef(this->Client);
            this->Client = nullptr;
        }
        if (this->Canvas_Class)
        {
            Thread->DeleteGlobalRef(this->Canvas_Class);
            this->Canvas_Class = nullptr;
        }
        if (this->FocusEvent_Class)
        {
            Thread->DeleteGlobalRef(this->FocusEvent_Class);
            this->FocusEvent_Class = nullptr;
        }
        if (this->KeyEvent_Class)
        {
            Thread->DeleteGlobalRef(this->KeyEvent_Class);
            this->KeyEvent_Class = nullptr;
        }
        if (this->MouseEvent_Class)
        {
            Thread->DeleteGlobalRef(this->MouseEvent_Class);
            this->MouseEvent_Class = nullptr;
        }
        if (this->MouseWheelEvent_Class)
        {
            Thread->DeleteGlobalRef(this->MouseWheelEvent_Class);
            this->MouseWheelEvent_Class = nullptr;
        }
        this->DetachThread(&Thread);
    }
}
