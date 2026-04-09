//
//  EIOS.hxx
//  RemoteInput
//
//  Created by Brandon on 2019-09-14.
//  Copyright © 2019 XIO. All rights reserved.
//

#ifndef EIOS_HXX_INCLUDED
#define EIOS_HXX_INCLUDED

#ifdef __cplusplus
#include <cstdint>
#else
#include <stdint.h>
#endif

#if defined(_WIN32) || defined (_WIN64)
#define EXPORT __declspec(dllexport)
#define STD_CALL __stdcall
#else
#define EXPORT [[gnu::visibility("default")]]
#define STD_CALL
#endif

#include "EIOSTypes.hxx"
#include "ControlCenter.hxx"

typedef struct EIOS
{
    std::int32_t pid;
    std::intptr_t local_storage;
    std::unique_ptr<ControlCenter> control_center;
} EIOS;

#ifdef __cplusplus
extern "C" {
#endif

EXPORT EIOS* STD_CALL EIOS_RequestTarget(const char* initargs) noexcept;
EXPORT void STD_CALL EIOS_ReleaseTarget(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_GetTargetDimensions(EIOS* eios, std::int32_t* width, std::int32_t* height) noexcept;
EXPORT ImageFormat STD_CALL EIOS_GetImageFormat(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetImageFormat(EIOS* eios, ImageFormat format) noexcept;
EXPORT std::uint8_t* STD_CALL EIOS_GetImageBuffer(EIOS* eios) noexcept;
EXPORT std::uint8_t* STD_CALL EIOS_GetDebugImageBuffer(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetGraphicsDebugging(EIOS* eios, bool enabled) noexcept;
EXPORT void STD_CALL EIOS_UpdateImageBuffer(EIOS* eios) noexcept;
EXPORT bool STD_CALL EIOS_HasFocus(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_GainFocus(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_LoseFocus(EIOS* eios) noexcept;
EXPORT bool STD_CALL EIOS_IsKeyboardInputEnabled(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetKeyboardInputEnabled(EIOS* eios, bool enabled) noexcept;
EXPORT bool STD_CALL EIOS_IsMouseInputEnabled(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetMouseInputEnabled(EIOS* eios, bool enabled) noexcept;
EXPORT void STD_CALL EIOS_GetMousePosition(EIOS* eios, std::int32_t* x, std::int32_t* y) noexcept;
EXPORT void STD_CALL EIOS_GetRealMousePosition(EIOS* eios, std::int32_t* x, std::int32_t* y) noexcept;
EXPORT void STD_CALL EIOS_MoveMouse(EIOS* eios, std::int32_t x, std::int32_t y) noexcept;
EXPORT void STD_CALL EIOS_HoldMouse(EIOS* eios, std::int32_t x, std::int32_t y, std::int32_t button) noexcept;
EXPORT void STD_CALL EIOS_ReleaseMouse(EIOS* eios, std::int32_t x, std::int32_t y, std::int32_t button) noexcept;
EXPORT void STD_CALL EIOS_ScrollMouse(EIOS* eios, std::int32_t x, std::int32_t y, std::int32_t lines) noexcept;
EXPORT bool STD_CALL EIOS_IsMouseButtonHeld(EIOS* eios, std::int32_t button) noexcept;
EXPORT void STD_CALL EIOS_SendString(EIOS* eios, const char* string, std::int32_t keywait, std::int32_t keymodwait) noexcept;
EXPORT void STD_CALL EIOS_HoldKey(EIOS* eios, std::int32_t key) noexcept;
EXPORT void STD_CALL EIOS_ReleaseKey(EIOS* eios, std::int32_t key) noexcept;
EXPORT bool STD_CALL EIOS_IsKeyHeld(EIOS* eios, std::int32_t key) noexcept;
EXPORT std::int32_t STD_CALL EIOS_GetKeyboardSpeed(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetKeyboardSpeed(EIOS* eios, std::int32_t speed) noexcept;
EXPORT std::int32_t STD_CALL EIOS_GetKeyboardRepeatDelay(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetKeyboardRepeatDelay(EIOS* eios, std::int32_t delay) noexcept;
EXPORT bool STD_CALL EIOS_GetGraphicsScaling(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_SetGraphicsScaling(EIOS* eios, bool enabled) noexcept;
EXPORT EIOS* STD_CALL EIOS_PairClient(std::int32_t pid) noexcept;
EXPORT void STD_CALL EIOS_KillClientPID(std::int32_t pid) noexcept;
EXPORT void STD_CALL EIOS_KillClient(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_ReleaseClient(EIOS* eios) noexcept;
EXPORT void STD_CALL EIOS_KillZombieClients() noexcept;
EXPORT std::size_t STD_CALL EIOS_GetClients(bool unpaired_only) noexcept;
EXPORT std::int32_t STD_CALL EIOS_GetClientPID(std::size_t index) noexcept;

// New in Simba 2.0 https://villavu.github.io/Simba/tutorials/plugins/plugin-target.html
EXPORT EIOS* SimbaPluginTarget_Request(const char* initargs) noexcept;
EXPORT EIOS* SimbaPluginTarget_RequestWithDebugImage(const char* initargs, void** image) noexcept;
EXPORT void SimbaPluginTarget_Release(EIOS* eios) noexcept;
EXPORT void SimbaPluginTarget_GetDimensions(EIOS* eios, std::int32_t* width, std::int32_t* height) noexcept;
EXPORT bool SimbaPluginTarget_GetImageData(EIOS* eios, std::int32_t x, std::int32_t y, std::int32_t width, std::int32_t height, void** bgra, std::int32_t* data_width) noexcept;
EXPORT bool SimbaPluginTarget_MousePressed(EIOS* eios, int mouse_button) noexcept;
EXPORT void SimbaPluginTarget_MousePosition(EIOS* eios, std::int32_t* x, std::int32_t* y) noexcept;
EXPORT void SimbaPluginTarget_MouseTeleport(EIOS* eios, std::int32_t x, std::int32_t y) noexcept;
EXPORT void SimbaPluginTarget_MouseUp(EIOS* eios, int mouse_button) noexcept;
EXPORT void SimbaPluginTarget_MouseDown(EIOS* eios, int mouse_button) noexcept;
EXPORT void SimbaPluginTarget_MouseScroll(EIOS* eios, int scrolls) noexcept;
EXPORT void SimbaPluginTarget_KeyDown(EIOS* eios, std::int32_t key) noexcept;
EXPORT void SimbaPluginTarget_KeyUp(EIOS* eios, std::int32_t key) noexcept;
EXPORT void SimbaPluginTarget_KeySend(EIOS* eios, char* text, std::int32_t len, std::int32_t* sleeptimes) noexcept;
EXPORT bool SimbaPluginTarget_KeyPressed(EIOS* eios, std::int32_t key) noexcept;

#ifdef __cplusplus
}
#endif

#endif /* EIOS_HXX_INCLUDED */
