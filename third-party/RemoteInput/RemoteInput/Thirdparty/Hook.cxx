//
// Created by brandon on 2020-04-06.
//

#include "Hook.hxx"
#include <cassert>
#include <cstring>

#if defined(_WIN32) || defined(_WIN64)
#include <windows.h>
#include <MinHook.h>
#elif defined(__APPLE__)
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <mach-o/loader.h>
#import <mach-o/nlist.h>
#include <rd_route.h>
#else
#include <detours.h>
#include <types.h>
#endif


#if defined(_WIN32) || defined(_WIN64)
Hook::Hook(void* original, void* detour) : original(original), detour(detour), trampoline(nullptr), data(), valid(false)
{
    std::memset(&data[0], 0, sizeof(data));
    MH_STATUS status = MH_CreateHook(original, detour, &trampoline);
    valid = status == MH_OK;
}

Hook::Hook(Hook&& other) noexcept : original(other.original), detour(other.detour), trampoline(other.trampoline), data(), valid(other.valid)
{
    other.original = nullptr;
    other.detour = nullptr;
    other.trampoline = nullptr;
    other.valid = false;
    std::memcpy(&data[0], &other.data[0], sizeof(data));
    std::memset(&other.data[0], 0, sizeof(data));
}

Hook::~Hook()
{
    remove();
    MH_RemoveHook(original);
}

void Hook::apply()
{
    if (valid)
    {
        MH_STATUS status = MH_EnableHook(original);
        valid = status == MH_OK;
    }
}

void Hook::remove()
{
    if (valid)
    {
        MH_DisableHook(original);
    }
}

bool Hook::is_enabled() const
{
    return valid;
}

#elif defined(__APPLE__)
Hook::Hook(void* original, void* detour) : original(original), detour(detour), trampoline(nullptr), data(), valid(false)
{
    std::memset(&data[0], 0, sizeof(data));

    #if defined (__x86_64__) || defined(__arm64__)
	mach_vm_size_t size_of_jump = (sizeof(uintptr_t) * 2);
    #else
	mach_vm_size_t size_of_jump = (sizeof(int) + 1);
    #endif

	mach_msg_type_number_t amount_read = 0;
	kern_return_t kr = mach_vm_protect(mach_task_self(), reinterpret_cast<mach_vm_address_t>(original), size_of_jump, FALSE, VM_PROT_READ | VM_PROT_WRITE | VM_PROT_EXECUTE | VM_PROT_COPY);

    valid = kr == KERN_SUCCESS;
    if (valid)
	{
	    kr = mach_vm_read(mach_task_self(), reinterpret_cast<mach_vm_address_t>(original), size_of_jump, reinterpret_cast<vm_offset_t*>(&data[0]), &amount_read);
	    kr = mach_vm_protect(mach_task_self(), reinterpret_cast<mach_vm_address_t>(original), size_of_jump, FALSE, VM_PROT_READ | VM_PROT_EXECUTE);
	}
}

Hook::Hook(Hook&& other) noexcept : original(other.original), detour(other.detour), trampoline(other.trampoline), data(), valid(other.valid)
{
    other.original = nullptr;
    other.detour = nullptr;
    other.trampoline = nullptr;
    other.valid = false;
    std::memcpy(&data[0], &other.data[0], sizeof(data));
    std::memset(&other.data[0], 0, sizeof(data));
}

Hook::~Hook()
{
    remove();
    trampoline = nullptr;
}

void Hook::apply()
{
    if (valid)
    {
        rd_route(original, detour, &trampoline);
    }
}

void Hook::remove()
{
    if (valid)
    {
        #if defined (__x86_64__)
        mach_msg_type_number_t size_of_jump = (sizeof(uintptr_t) * 2);
        #else
        mach_msg_type_number_t size_of_jump = (sizeof(int) + 1);
        #endif

        kern_return_t kr = mach_vm_protect(mach_task_self(), reinterpret_cast<mach_vm_address_t>(original), size_of_jump, FALSE, VM_PROT_READ | VM_PROT_WRITE | VM_PROT_EXECUTE | VM_PROT_COPY);
        if (kr == KERN_SUCCESS)
        {
            kr = mach_vm_write(mach_task_self(), reinterpret_cast<mach_vm_address_t>(original), reinterpret_cast<vm_offset_t>(&data[0]), size_of_jump);
            kr = mach_vm_protect(mach_task_self(), reinterpret_cast<mach_vm_address_t>(original), size_of_jump, FALSE, VM_PROT_READ | VM_PROT_EXECUTE);
        }

        std::memset(&data[0], 0, sizeof(data));
    }
}

bool Hook::is_enabled() const
{
    return valid;
}

#else

Hook::Hook(void *original, void *detour) : original(original), detour(detour), trampoline(new HOOK_TRACE_INFO()), data(), valid(false)
{
    std::memset(&data[0], 0, sizeof(data));
}

Hook::Hook(Hook&& other)  noexcept : original(other.original), detour(other.detour), trampoline(other.trampoline), data(), valid(other.valid)
{
    other.original = nullptr;
    other.detour = nullptr;
    other.trampoline = nullptr;
    other.valid = false;
    std::memcpy(&data[0], &other.data[0], sizeof(data));
    std::memset(&other.data[0], 0, sizeof(data));
}

Hook::~Hook()
{
    remove();
    delete static_cast<HOOK_TRACE_INFO*>(trampoline);
    trampoline = nullptr;
}

void Hook::apply()
{
    LONG callback = 0;
    ULONG acl_list = 0;
    LONG result = DetourInstallHook(original, detour, &callback, reinterpret_cast<HOOK_TRACE_INFO*>(&data[0]));

    if (result == STATUS_SUCCESS)
    {
        result = DetourSetExclusiveACL(&acl_list, 1, reinterpret_cast<HOOK_TRACE_INFO*>(&data[0]));
    }

    valid = result == STATUS_SUCCESS;
}

void Hook::remove()
{
    if (valid)
    {
        LONG result = DetourUninstallHook(reinterpret_cast<HOOK_TRACE_INFO*>(trampoline));
        valid = result == STATUS_SUCCESS;
    }
}

bool Hook::is_enabled() const
{
    return valid;
}
#endif

void Hook::setup()
{
    #if defined(_WIN32) || defined(_WIN64)
    MH_Initialize();
    #endif
}

void Hook::teardown()
{
    #if defined(_WIN32) || defined(_WIN64)
    MH_Uninitialize();
    #endif
}