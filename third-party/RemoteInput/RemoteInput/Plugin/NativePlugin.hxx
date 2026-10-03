//
// Created by Brandon on 2022-09-21.
//

#ifndef REMOTEINPUT_NATIVEPLUGIN_HXX
#define REMOTEINPUT_NATIVEPLUGIN_HXX

#include <cstdint>
#include <cstdio>
#include <new>

#include "EIOS.hxx"

#ifdef __cplusplus
extern "C"
{
#endif

EXPORT void EIOS_Inject(const char* process_name) noexcept;
EXPORT void EIOS_Inject_PID(std::int32_t pid) noexcept;
EXPORT EIOS* EIOS_From_PID(std::int32_t pid) noexcept;
EXPORT void Reflect_Release_Object(EIOS* eios, jobject object) noexcept;
EXPORT void Reflect_Release_Objects(EIOS* eios, jobject* objects, std::size_t amount) noexcept;

#ifdef __cplusplus
}
#endif

#endif //REMOTEINPUT_NATIVEPLUGIN_HXX
