#ifndef KINPUT_HPP_INCLUDED
#define KINPUT_HPP_INCLUDED

#include <windows.h>
#include <jni.h>
#include <cstdint>
#include <mutex>

typedef int (*ptr_GCJavaVMs)(JavaVM **vmBuf, jsize bufLen, jsize * nVMs);
typedef jobject (JNICALL *ptr_GetComponent)(JNIEnv* env, void* platformInfo);

class KInput
{
    private:
        bool Initialized;
        JavaVM* JVM;
        jobject Client;
        jobject Canvas;

        ptr_GetComponent GetComponent;

        jclass Canvas_Class;
        jmethodID Canvas_DispatchEvent;

        jclass FocusEvent_Class;
        jmethodID FocusEvent_Init;

        jclass KeyEvent_Class;
        jmethodID KeyEvent_Init;

        jclass MouseEvent_Class;
        jmethodID MouseEvent_Init;

        jclass MouseWheelEvent_Class;
        jmethodID MouseWheelEvent_Init;

        HWND CanvasUpdate;

        bool AttachThread(JNIEnv** Thread);
        bool DetachThread(JNIEnv** Thread);
        void GrabCanvas();
        void UpdateCanvas(JNIEnv* Thread);
    public:
        KInput();

        bool DispatchEvent(jobject Event, JNIEnv* Thread);
        bool FocusEvent(std::int32_t ID);
        bool KeyEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t KeyCode,
                      std::uint16_t KeyChar, std::int32_t KeyLocation);
        bool MouseEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t X,
                        std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger, std::int32_t Button);
        bool MouseWheelEvent(std::int32_t ID, std::int64_t When, std::int32_t Modifiers, std::int32_t X,
                             std::int32_t Y, std::int32_t ClickCount, bool PopupTrigger, std::int32_t ScrollType,
                             std::int32_t ScrollAmount, std::int32_t WheelRotation);
        void NotifyCanvasUpdate(HWND CanvasHWND);
        ~KInput();
};

#endif // KINPUT_HPP_INCLUDED