//
// Created by Brandon on 2020-05-02.
//

#include "Injector.hxx"

#if defined(__APPLE__)
#include <dlfcn.h>
#include <sys/sysctl.h>
#include <sys/types.h>
#include <mach/mach.h>
#include <mach/mach_vm.h>
#include <mach-o/loader.h>
#include <mach-o/dyld_images.h>
#include <mach-o/nlist.h>
#include <ptrauth.h>

#include <cstdint>
#include <string>
#include <thread>
#endif

//#define OLD_INJECTION_CODE

#if defined(__APPLE__)
//Strip the module name from its path
auto strip_path = [](const std::string &path_to_strip) -> std::string {
    std::string::size_type pos = path_to_strip.find_last_of("\\/");
    if (pos != std::string::npos)
    {
        //Strip path components
        std::string path = path_to_strip.substr(pos + 1);
        pos = path.find_last_of('.');

        //Strip extension
        return pos > 0 && pos != std::string::npos ? path.substr(0, pos) : path;
    }
    return path_to_strip;
};

// Find shared cache slide
auto find_shared_cache_slide = [](mach_port_t task) -> std::intptr_t {
    task_dyld_info_data_t info;
    mach_msg_type_number_t count = TASK_DYLD_INFO_COUNT;
    task_info(task, TASK_DYLD_INFO, reinterpret_cast<task_info_t>(&info), &count);

    //Get the loaded dylibs/images
    dyld_all_image_infos infos = {0};
    mach_vm_size_t size = info.all_image_info_size;
    kern_return_t err = mach_vm_read_overwrite(task, info.all_image_info_addr, info.all_image_info_size, reinterpret_cast<mach_vm_address_t>(&infos), &size);
    return err == KERN_SUCCESS ? infos.sharedCacheSlide : static_cast<std::intptr_t>(NULL);
};

#if defined(__aarch64__)
//Calls dlopen inside of a pthread in the remote target
auto remote_load_library = [](std::size_t* instructions_size) -> std::uint8_t* {
    #if defined(__clang__)
    static std::uint8_t assembly[] = {
            0xFD, 0x7B, 0xBD, 0xA9,    //stp x29, x30, [sp, #-48]!
            0xF5, 0x0B, 0x00, 0xF9,    //str x21, [sp, #16]
            0xF4, 0x4F, 0x02, 0xA9,    //stp x20, x19, [sp, #32]
            0xFD, 0x03, 0x00, 0x91,    //mov x29, sp
            0x02, 0x4C, 0x40, 0xA9,    //ldp x2, x19, [x0]           //_dlopen_pointer/_pthread_callback = data[0]
            0x08, 0x50, 0x41, 0xA9,    //ldp x8, x20, [x0, #16]      //_pthread_create_from_mach_thread = data[2]
            0x15, 0x10, 0x40, 0xF9,    //ldr x21, [x0, #32]          //_mach_thread_self = data[4]
            0xBF, 0x0F, 0x00, 0xF9,    //str xzr, [x29, #24]         //pthread_t thread_id = 0;
            0xE3, 0x03, 0x01, 0xAA,    //mov x3, x1                  //param
            0xE1, 0x03, 0x1F, 0xAA,    //mov x1, xzr                 //nullptr
            0xA0, 0x63, 0x00, 0x91,    //add x0, x29, #24            //&thread_id
            0x00, 0x01, 0x3F, 0xD6,    //blr x8                      //_pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param)
            0xEA, 0x03, 0x00, 0xAA,    //mov x10, x0                 //store return value in x10
            0xA0, 0x0F, 0x40, 0xF9,    //ldr x0, [x29, #24]          //thread_id
            0xEB, 0x03, 0x00, 0xAA,    //mov x11, x0                 //store thread_id in x11
            0x60, 0x02, 0x3F, 0xD6,    //blr x19                     //_pthread_set_self(thread_id)
            0xA0, 0x02, 0x3F, 0xD6,    //blr x21                     //_mach_thread_self()
            0x80, 0x02, 0x3F, 0xD6,    //blr x20                     //_thread_suspend(_mach_thread_self())
            0xA0, 0x0F, 0x40, 0xF9,    //ldr x0, [x29, #24]          //return thread_id
            0xF4, 0x4F, 0x42, 0xA9,    //ldp x20, x19, [sp, #32]
            0xF5, 0x0B, 0x40, 0xF9,    //ldr x21, [sp, #16]
            0xFD, 0x7B, 0xC3, 0xA8,    //ldp x29, x30, [sp], #48
            0xC0, 0x03, 0x5F, 0xD6,    //ret

            0x1F, 0x20, 0x03, 0xD5,
            0x1F, 0x20, 0x03, 0xD5,
            0x1F, 0x20, 0x03, 0xD5,    //nop
            0x1F, 0x20, 0x03, 0xD5,
            0x1F, 0x20, 0x03, 0xD5,

            0x41, 0x00, 0x80, 0x52,    //mov w1, #2                  //RTLD_LAZY = 0x1 -- RTLD_NOW = 0x02
            0xE2, 0xDD, 0x97, 0xD2,    //mov x2, #0xBEEF             //address of dlopen
            0xA2, 0xD5, 0xBB, 0xF2,    //movk x2, #0xDEAD, lsl #16   //address of dlopen
            0x02, 0x00, 0xD6, 0xF2,    //movk x2, #0xB000, lsl #32   //address of dlopen
            0x02, 0x00, 0xF4, 0xF2,    //movk x2, #0xA000, lsl #48   //address of dlopen
            0x40, 0x00, 0x1F, 0xD6,    //br x2                       //call dlopen(pthread_parameters, RTLD_LAZY)

            /*pthread_t LoadLibrary(void** data, void* param)
             {
                 void* (*_dlopen_pointer)(void* param) = (decltype(_dlopen_pointer))(data[0]);
                 void (*_pthread_set_self)(pthread_t thread) = (decltype(_pthread_set_self))(data[1]);
                 int (*_pthread_create_from_mach_thread)(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *) = (decltype(_pthread_create_from_mach_thread))(data[2]);
                 kern_return_t (*_thread_suspend)(thread_read_t target_act) = (decltype(_thread_suspend))(data[3]);
                 mach_port_t (*_mach_thread_self)(void) = (decltype(_mach_thread_self))(data[4]);

                 pthread_t thread_id = 0;
                 _pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param);
                 //while (1)
                 //{
                 //    swtch_pri(0); // mach_yield
                 //}


                 _pthread_set_self(thread_id);
                 _thread_suspend(_mach_thread_self());
                 return thread_id;
             }

             void* _dlopen_pointer(void* param)
             {
                 decltype(dlopen)* _dlopen = (decltype(dlopen)*)0xA000B000DEADBEEF;
                 return _dlopen((const char*)param, RTLD_NOW);
             }*/
    };
    #else
    static std::uint8_t assembly[] = {
        0xFD, 0x7B, 0xBC, 0xA9,    //stp x29, x30, [sp, -64]!
        0xE4, 0x03, 0x00, 0xAA,    //mov x4, x0                  //_pthread_set_self = data[0]
        0xF5, 0x13, 0x00, 0xF9,    //str x21, [sp, 32]
        0x95, 0x04, 0x40, 0xF9,    //ldr x21, [x4, 8]            //pthread_set_self = data[1]
        0xF3, 0x53, 0x01, 0xA9,    //stp x19, x20, [sp, 16]
        0x85, 0x4C, 0x41, 0xA9,    //ldp x5, x19, [x4, 16]       //_thread_suspend = data[3]
        0x94, 0x10, 0x40, 0xF9,    //ldr x20, [x4, 32]           //_mach_thread_self = data[4]
        0xFD, 0x03, 0x00, 0x91,    //mov x29, sp
        0x82, 0x00, 0x40, 0xF9,    //ldr x2, [x4]
        0xFF, 0x1F, 0x00, 0xF9,    //str xzr, [sp, 56]           //pthread_t thread_id = 0;
        0xE3, 0x03, 0x01, 0xAA,    //mov x3, x1                  //param
        0x01, 0x00, 0x80, 0xD2,    //mov x1, 0                   //nullptr
        0xE0, 0xE3, 0x00, 0x91,    //add x0, sp, 56              //&thread_id
        0xA0, 0x00, 0x3F, 0xD6,    //blr x5                      //_pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param)
        0xEA, 0x03, 0x00, 0xAA,    //mov x10, x0                 //store return value in x10
        0xE0, 0x1F, 0x40, 0xF9,    //ldr x0, [sp, 56]            //thread_id
        0xEB, 0x03, 0x00, 0xAA,    //mov x11, x0                 //store thread_id in x11
        0xA0, 0x02, 0x3F, 0xD6,    //blr x21                     //_pthread_set_self(thread_id);
        0x80, 0x02, 0x3F, 0xD6,    //blr x20                     //_mach_thread_self();
        0x60, 0x02, 0x3F, 0xD6,    //blr x19                     //_thread_suspend(_mach_thread_self());
        0xF3, 0x53, 0x41, 0xA9,    //ldp x19, x20, [sp, 16]
        0xF5, 0x13, 0x40, 0xF9,    //ldr x21, [sp, 32]
        0xE0, 0x1F, 0x40, 0xF9,    //ldr x0, [sp, 56]
        0xFD, 0x7B, 0xC4, 0xA8,    //ldp x29, x30, [sp], 64
        0xC0, 0x03, 0x5F, 0xD6,    //ret

        0x1F, 0x20, 0x03, 0xD5,
        0x1F, 0x20, 0x03, 0xD5,
        0x1F, 0x20, 0x03, 0xD5,    //nop
        0x1F, 0x20, 0x03, 0xD5,
        0x1F, 0x20, 0x03, 0xD5,

        0x41, 0x00, 0x80, 0x52,    //mov w1, 2                   //RTLD_LAZY = 0x1 -- RTLD_NOW = 0x02
        0xE2, 0xDD, 0x97, 0xD2,    //mov x2, 0xBEEF              //address of dlopen
        0xA2, 0xD5, 0xBB, 0xF2,    //movk x2, 0xDEAD, lsl 16     //address of dlopen
        0x02, 0x00, 0xD6, 0xF2,    //movk x2, 0xB000, lsl 32     //address of dlopen
        0x02, 0x00, 0xF4, 0xF2,    //movk x2, 0xA000, lsl 48     //address of dlopen
        0x40, 0x00, 0x1F, 0xD6,    //br x2                       //call dlopen(pthread_parameters, RTLD_LAZY)

        /*pthread_t LoadLibrary(void** data, void* param)
         {
             void* (*_dlopen_pointer)(void* param) = (decltype(_dlopen_pointer))(data[0]);
             void (*_pthread_set_self)(pthread_t thread) = (decltype(_pthread_set_self))(data[1]);
             int (*_pthread_create_from_mach_thread)(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *) = (decltype(_pthread_create_from_mach_thread))(data[2]);
             kern_return_t (*_thread_suspend)(thread_read_t target_act) = (decltype(_thread_suspend))(data[3]);
             mach_port_t (*_mach_thread_self)(void) = (decltype(_mach_thread_self))(data[4]);

             pthread_t thread_id = 0;
             _pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param);
             //while (1)
             //{
             //    swtch_pri(0); // mach_yield
             //}


             _pthread_set_self(thread_id);
             _thread_suspend(_mach_thread_self());
             return thread_id;
         }

         void* _dlopen_pointer(void* param)
         {
             decltype(dlopen)* _dlopen = (decltype(dlopen)*)0xA000B000DEADBEEF;
             return _dlopen((const char*)param, RTLD_NOW);
         }*/
    };
    #endif
    *instructions_size = sizeof(assembly);
    return &assembly[0];
};
#else
//Calls dlopen inside of a pthread in the remote target
auto remote_load_library = [](std::size_t* instructions_size) -> std::uint8_t* {
    #ifndef OLD_INJECTION_CODE
    static std::uint8_t assembly[] = {
            0x41, 0x57,                                              //push r15
            0x41, 0x56,                                              //push r14
            0x41, 0x54,                                              //push r12
            0x53,                                                    //push rbx
            0x50,                                                    //push rax
            0x48, 0x89, 0xF1,                                        //mov rcx, rsi
            0x48, 0x89, 0xFB,                                        //mov rbx, rdi
            0x48, 0x8B, 0x17,                                        //mov rdx, QWORD PTR[rdi + 0x00]        //_dlopen_pointer = data[0]
            0x4C, 0x8B, 0x77, 0x08,                                  //mov r14, QWORD PTR[rdi + 0x08]        //_pthread_set_self = data[1]
            0x48, 0x8B, 0x47, 0x10,                                  //mov rax, QWORD PTR[rdi + 0x10]        //_pthread_create_from_mach_thread = data[2]
            0x4C, 0x8B, 0x7F, 0x18,                                  //mov r15, QWORD PTR[rdi + 0x18]        //_thread_suspend = data[3]
            0x4C, 0x8B, 0x67, 0x20,                                  //mov r12, QWORD PTR[rdi + 0x20]        //_mach_thread_self = data[4]
            0x48, 0xC7, 0x04, 0x24, 0x00, 0x00, 0x00, 0x00,          //mov QWORD PTR[rsp + 0x0], 0x00        //pthread_t thread_id = 0;
            0x48, 0x89, 0xE7,                                        //mov rdi, rsp                          //&thread_id
            0x31, 0xF6,                                              //xor esi, esi                          //nullptr
            0xFF, 0xD0,                                              //call rax                              //_pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param)
            0x48, 0x8B, 0x0C, 0x24,                                  //mov rcx, QWORD PTR[rsp + 0x00]        //thread_id
            0x48, 0x8B, 0x53, 0x28,                                  //mov rdx, QWORD PTR[rbx + 0x28]        //&data[5]
            0x48, 0x89, 0x0A,                                        //mov QWORD PTR[rdx + 0x00], rcx        //*data[5] = thread_id
            0x48, 0x8B, 0x4B, 0x30,                                  //mov rcx, QWORD PTR[rbx + 0x30]        //&data[6]
            0x89, 0x01,                                              //mov DWORD PTR[rcx + 0x00], eax        //*data[6] = result
            0x48, 0x8B, 0x3C, 0x24,                                  //mov rdi, QWORD PTR[rsp + 0x00]        //thread_id
            0x41, 0xFF, 0xD6,                                        //call r14                              //_pthread_set_self(thread_id)
            0x41, 0xFF, 0xD4,                                        //call r12                              //_mach_thread_self()
            0x89, 0xC7,                                              //mov edi, eax                          //self_thread
            0x41, 0xFF, 0xD7,                                        //call r15                              //_thread_suspend(self_thread);
            0x48, 0x8B, 0x04, 0x24,                                  //mov rax, QWORD PTR[rsp + 0x00]        //return thread_id
            0x48, 0x83, 0xC4, 0x08,                                  //add rsp, 0x08
            0x5B,                                                    //pop rbx
            0x41, 0x5C,                                              //pop r12
            0x41, 0x5E,                                              //pop r14
            0x41, 0x5F,                                              //pop r15
            0xC3,                                                    //ret
            0x90,
            0x90,
            0x90,                                                    //nop
            0x90,
            0x90,
            0x48, 0xB8, 0xEF, 0xBE, 0xAD, 0xDE, 0x00, 0xB0, 0x00, 0xA0,   //movabs rax, 0xA000B000DEADBEEF     //address of dlopen
            0xBE, 0x02, 0x00, 0x00, 0x00,                                 //mov esi, RTLD_LAZY                 //RTLD_LAZY = 0x1 -- RTLD_NOW = 0x02
            0xFF, 0xE0                                                    //jmp rax                            //call dlopen(pthread_parameters, RTLD_LAZY)

            /*pthread_t LoadLibrary(void** data, void* param)
             {
                 void* (*_dlopen_pointer)(void* param) = (decltype(_dlopen_pointer))(data[0]);
                 void (*_pthread_set_self)(pthread_t thread) = (decltype(_pthread_set_self))(data[1]);
                 int (*_pthread_create_from_mach_thread)(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *) = (decltype(_pthread_create_from_mach_thread))(data[2]);
                 kern_return_t (*_thread_suspend)(thread_read_t target_act) = (decltype(_thread_suspend))(data[3]);
                 mach_port_t (*_mach_thread_self)(void) = (decltype(_mach_thread_self))(data[4]);

                 pthread_t thread_id = 0;
                 _pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param);
                 //while (1)
                 //{
                 //    swtch_pri(0); // mach_yield
                 //}


                 _pthread_set_self(thread_id);
                 _thread_suspend(_mach_thread_self());
                 return thread_id;
             }

             void* _dlopen_pointer(void* param)
             {
                 decltype(dlopen)* _dlopen = (decltype(dlopen)*)0xA000B000DEADBEEF;
                 return _dlopen((const char*)param, RTLD_NOW);
             }*/
    };
    #else
    static std::uint8_t assembly[] = {
            0x50,                                              //push rax
            0x48, 0x89, 0xF1,                                  //mov rcx, rsi
            0x48, 0x8B, 0x17,                                  //mov rdx, qword ptr[rdi]        //_pthread_callback = data[0]
            0x48, 0x8B, 0x47, 0x08,                            //mov rax, qword ptr[rdi + 8]    //_pthread_create_from_mach_thread = data[1]
            0x48, 0xC7, 0x04, 0x24, 0x00, 0x00, 0x00, 0x00,    //mov qword ptr[rsp], 0          //pthread thread_id = 0
            0x48, 0x89, 0xE7,                                  //mov rdi, rsp
            0x31, 0xF6,                                        //xor esi, esi
            0xFF, 0xD0,                                        //call rax                       //call _pthread_create_from_mach_thread(&thread_id, nullptr, _pthread_callback, param)
            0x4C, 0x8B, 0x24, 0x24,                            //mov r12, qword ptr[rsp]        //return pthread_id
            0x59,                                              //pop rcx
            0xEB, 0xFE,                                        //jmp 0x00                       //Do not return to callee
            0xC3,                                              //ret
            0x90,
            0x90,
            0x90,                                              //nop
            0x90,
            0x90,
            0x48, 0xB8, 0xEF, 0xBE, 0xAD, 0xDE, 0x00, 0x00, 0x00, 0x00,   //movabs rax, 0x0000DEADBEEF     //address of dlopen
            0xBE, 0x01, 0x00, 0x00, 0x00,                                 //mov esi, RTLD_LAZY             //RTLD_LAZY = 0x1 -- RTLD_NOW = 0x02
            0xFF, 0xE0                                                    //jmp rax                        //call dlopen(pthread_parameters, RTLD_LAZY)

            /*pthread_t LoadLibrary(void** data, void* param)
            {
                void* (*_dlopen_pointer)(void* param) = (decltype(_dlopen_pointer))(data[0]);
                int (*_pthread_create_from_mach_thread)(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *) = (decltype(_pthread_create_from_mach_thread))(data[1]);

                pthread_t thread_id = 0;
                _pthread_create_from_mach_thread(&thread_id, nullptr, _dlopen_pointer, param);
                return thread_id;
            }

            void* _dlopen_pointer(void* param)
            {
                decltype(dlopen)* _dlopen = (decltype(dlopen)*)0xa000deadbeef;
                return _dlopen((const char*)param, RTLD_NOW);
            }*/
    };
    #endif
    *instructions_size = sizeof(assembly);
    return &assembly[0];
};
#endif

//Find address of a dynamic library
auto find_library = [](mach_port_t task, const char* library) -> std::intptr_t {
    task_dyld_info_data_t info;
    mach_msg_type_number_t count = TASK_DYLD_INFO_COUNT;

    kern_return_t err = task_info(task, TASK_DYLD_INFO, reinterpret_cast<task_info_t>(&info), &count);
    if (err != KERN_SUCCESS)
    {
        //print mach_error_string(err);
        return NULL;
    }

    //Get the loaded dylibs/images
    dyld_all_image_infos infos = {0};
    mach_vm_size_t size = info.all_image_info_size; // or sizeof(infos)
    err = mach_vm_read_overwrite(task, info.all_image_info_addr, info.all_image_info_size, reinterpret_cast<mach_vm_address_t>(&infos), &size);
    if (err != KERN_SUCCESS || size != info.all_image_info_size) 
    {
        return NULL;
    }

    //Get the info for each dylib/image
    size = sizeof(dyld_image_info) * infos.infoArrayCount;
    std::unique_ptr<dyld_image_info[]> image_infos = std::make_unique<dyld_image_info[]>(infos.infoArrayCount);
    err = mach_vm_read_overwrite(task, reinterpret_cast<mach_vm_address_t>(infos.infoArray), size, reinterpret_cast<mach_vm_address_t>(image_infos.get()), &size);
    if (err != KERN_SUCCESS || size != sizeof(dyld_image_info) * infos.infoArrayCount)
    {
        return NULL;
    }

    //Get each image's file paths
    for (std::uint32_t i = 0; i < infos.infoArrayCount; ++i)
    {
        char buffer[512] = {0};
        mach_vm_size_t size = sizeof(buffer);

        mach_vm_size_t err = mach_vm_read_overwrite(task, reinterpret_cast<mach_vm_address_t>(image_infos[i].imageFilePath), size, reinterpret_cast<mach_vm_address_t>(&buffer[0]), &size);
        if (err == KERN_SUCCESS && size > 0) 
        {
            std::string path = strip_path(buffer);
            if (!strcasecmp(path.c_str(), library)) 
            {
                return reinterpret_cast<std::uintptr_t>(image_infos[i].imageLoadAddress);
            }
        }
    }

    return NULL;
};

//Find address of a symbol within a dynamic library
auto find_symbol = [](mach_port_t task, mach_vm_address_t library_header_address, const char* symbol) -> std::intptr_t {
    mach_vm_size_t size = sizeof(mach_header_64);
    struct mach_header_64 header = {0};
    mach_vm_read_overwrite(task, library_header_address, size, reinterpret_cast<mach_vm_address_t>(&header), &size);

    //64-bit mach-o module..
    if (header.magic == MH_MAGIC_64)
    {
        std::intptr_t shared_image_cache_slide = 0;
        if ((header.flags & 0x80000000) == 0x80000000)
        {
            shared_image_cache_slide = find_shared_cache_slide(task);
        }

        load_command command = {0};
        mach_vm_size_t size = sizeof(command);

        //stackoverflow.com/a/40793165/1462718
        //stackoverflow.com/questions/20481058/find-pathname-from-dlopen-handle-on-osx
        mach_vm_address_t seg_linkedit_addr = reinterpret_cast<mach_vm_address_t>(nullptr);
        mach_vm_address_t seg_text_addr = reinterpret_cast<mach_vm_address_t>(nullptr);
        mach_vm_address_t symtab_addr = reinterpret_cast<mach_vm_address_t>(nullptr);
        mach_vm_address_t load_command_address = library_header_address + sizeof(struct mach_header_64);

        //Iterate through all the load commands in the header..
        for (uint32_t i = 0; i < header.ncmds; ++i)
        {
            mach_vm_read_overwrite(task, load_command_address, size, reinterpret_cast<mach_vm_address_t>(&command), &size);
            switch(command.cmd)
            {
                case LC_SEGMENT:
                case LC_SEGMENT_64:
                {
                    char name[512] = {0};
                    mach_vm_size_t size = sizeof(name);

                    //Read the name of each segment in the header
                    std::intptr_t segname_offset = offsetof(segment_command_64, segname);
                    mach_vm_read_overwrite(task, load_command_address + segname_offset, size, reinterpret_cast<mach_vm_address_t>(&name[0]), &size);

                    if (std::string(name) == SEG_TEXT)
                    {
                        seg_text_addr = load_command_address;
                    }
                    else if (std::string(name) == SEG_LINKEDIT)
                    {
                        seg_linkedit_addr = load_command_address;
                    }
                }
                    break;

                case LC_SYMTAB:
                {
                    symtab_addr = load_command_address;
                }
                    break;
            }

            load_command_address += command.cmdsize;
        }

        if (!seg_text_addr || !seg_linkedit_addr || !symtab_addr)
        {
            return NULL;
        }

        //Read each segment
        segment_command_64 seg_linkedit = {0};
        segment_command_64 seg_text = {0};
        symtab_command symtab = {0};

        size = sizeof(seg_linkedit);
        mach_vm_read_overwrite(task, seg_linkedit_addr, size, reinterpret_cast<mach_vm_address_t>(&seg_linkedit), &size);

        size = sizeof(seg_text);
        mach_vm_read_overwrite(task, seg_text_addr, size, reinterpret_cast<mach_vm_address_t>(&seg_text), &size);

        size = sizeof(symtab);
        mach_vm_read_overwrite(task, symtab_addr, size, reinterpret_cast<mach_vm_address_t>(&symtab), &size);

        //ASLR slide calculations
        std::intptr_t file_slide = seg_linkedit.vmaddr - seg_text.vmaddr - seg_linkedit.fileoff;
        std::intptr_t strings = library_header_address + symtab.stroff + file_slide;
        std::intptr_t sym_addr = library_header_address + symtab.symoff + file_slide;

        //Iterate through all the symbols in that segment
        for (uint32_t i = 0; i < symtab.nsyms; ++i)
        {
            nlist_64 sym = {0};
            size = sizeof(sym);
            mach_vm_read_overwrite(task, sym_addr, size, reinterpret_cast<mach_vm_address_t>(&sym), &size);

            if (/*(sym.n_type & N_EXT) == N_EXT &&*/ sym.n_value)
            {
                char name[512] = {0};
                mach_vm_size_t size = sizeof(name);

                std::intptr_t symname_offset = strings + sym.n_un.n_strx;
                mach_vm_read_overwrite(task, symname_offset, size, reinterpret_cast<mach_vm_address_t>(&name[0]), &size);

                if (std::string(name).substr(1) == symbol)
                {
                    if (sym.n_value < 0x1000)
                    {
                        return sym.n_value + library_header_address;
                    }

                    if (shared_image_cache_slide)
                    {
                        return sym.n_value + shared_image_cache_slide;
                    }

                    return sym.n_value + library_header_address; //kx86_64DefaultBaseAddress
                }
            }

            sym_addr += size;
        }
    }

    return NULL;
};

bool IsProcessRunningInRosetta(pid_t pid)
{
    int mib[] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, pid};
    struct kinfo_proc proc;
    std::size_t buffer_size = sizeof(proc);
    if (sysctl(mib, sizeof(mib) / sizeof(mib[0]), &proc, &buffer_size, nullptr, 0) < 0)
    {
        //Error assume native
        return false;
    }
  
    return ((proc.kp_proc.p_flag & P_TRANSLATED) == P_TRANSLATED);
}

bool Injector::Inject(std::string module_path, std::int32_t pid, void* bootstrap) noexcept
{
    std::size_t assembly_size = 0;
    std::uint64_t stack_size = 16 * 1024;
    std::uint8_t* assembly = remote_load_library(&assembly_size);

    //Retrieve a task port for the remote process..
    mach_port_t  remote_task = 0;
    mach_error_t err = task_for_pid(mach_task_self(), pid, &remote_task);
    if (err == KERN_FAILURE)
    {
        fprintf(stderr, "Could not access task for pid %d. You probably need to add user to procmod group\n", pid);
        return false;
    }

    // If the library is already loaded
    if (find_library(remote_task, strip_path(module_path).c_str()))
    {
        return true;
    }

    std::intptr_t libdyld = find_library(remote_task, "libdyld");
    if (!libdyld)
    {
        fprintf(stderr, "Cannot find libdyld in remote process\n");
        return false;
    }

    std::intptr_t libsystem_pthread = find_library(remote_task, "libsystem_pthread");
    if (!libsystem_pthread)
    {
        fprintf(stderr, "Cannot find libsystem_pthread in remote process\n");
        return false;
    }

    std::intptr_t libsystem_kernel = find_library(remote_task, "libsystem_kernel");
    if (!libsystem_kernel)
    {
        fprintf(stderr, "Cannot find libsystem_kernel in remote process\n");
        return false;
    }

    std::intptr_t dlopen = find_symbol(remote_task, libdyld, "dlopen");
    if (!dlopen)
    {
        fprintf(stderr, "Cannot find dlopen in remote process\n");
        return false;
    }

    std::intptr_t pthread_create_from_mach_thread = find_symbol(remote_task, libsystem_pthread, "pthread_create_from_mach_thread");
    if (!pthread_create_from_mach_thread)
    {
        fprintf(stderr, "Cannot find pthread_create_from_mach_thread in remote process\n");
        return false;
    }

    std::intptr_t pthread_set_self = find_symbol(remote_task, libsystem_pthread, "_pthread_set_self");
    if (!pthread_set_self)
    {
        fprintf(stderr, "Cannot find pthread_set_self in remote process\n");
        return false;
    }

    std::intptr_t thread_suspend = find_symbol(remote_task, libsystem_kernel, "thread_suspend");
    if (!thread_suspend)
    {
        fprintf(stderr, "Cannot find thread_suspend in remote process\n");
        return false;
    }

    std::intptr_t mach_thread_self = find_symbol(remote_task, libsystem_kernel, "mach_thread_self");
    if (!mach_thread_self)
    {
        fprintf(stderr, "Cannot find mach_thread_self in remote process\n");
        return false;
    }
    
    // For arm64e, we need to sign pointers otherwise we'd get access violations
    #if defined(__aarch64__)
    dlopen = ptrauth_sign_unauthenticated(dlopen, ptrauth_key_function_pointer, 0);
    pthread_create_from_mach_thread = ptrauth_sign_unauthenticated(pthread_create_from_mach_thread, ptrauth_key_function_pointer, 0);
    pthread_set_self = ptrauth_sign_unauthenticated(pthread_set_self, ptrauth_key_function_pointer, 0);
    thread_suspend = ptrauth_sign_unauthenticated(thread_suspend, ptrauth_key_function_pointer, 0);
    mach_thread_self = ptrauth_sign_unauthenticated(mach_thread_self, ptrauth_key_function_pointer, 0);
    #endif
    

    // Allocate and write the path size..
    mach_vm_address_t remote_path = reinterpret_cast<mach_vm_address_t>(nullptr);
    kern_return_t ret = mach_vm_allocate(remote_task, &remote_path, module_path.size() + 1, VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        fprintf(stderr, "Failed to Allocate Remote Module Path\n");
        return false;
    }

    ret = mach_vm_write(remote_task, remote_path, reinterpret_cast<mach_vm_offset_t>(module_path.c_str()), static_cast<mach_msg_type_number_t>(module_path.size()));
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Write Remote Module Path\n");
        return false;
    }

    ret = mach_vm_protect(remote_task, remote_path, module_path.size() + 1, 0, VM_PROT_READ | VM_PROT_WRITE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Protect Remote Module Path\n");
        return false;
    }
    
    #if defined(__aarch64__)
    // Bits 21 to 5 = imm of the MOV wide immediate instruction
    // https://developer.arm.com/documentation/ddi0602/2023-03/Base-Instructions/MOV--wide-immediate---Move--wide-immediate---an-alias-of-MOVZ-
    auto copy_bits = [](std::uint32_t &reg, std::uint16_t value) {
        for (int bit = 20, valueBit = 15; bit >= 5; --bit, --valueBit)
        {
            std::uint32_t bit_to_set = ((value >> valueBit) & 1);
            reg ^= (-bit_to_set ^ reg) & (static_cast<std::uint32_t>(1) << bit);
        }
    };
    
    // Convert the instruction bytes to an instruction
    auto decode_instruction = [](std::uint8_t instructions[]) -> std::uint32_t {
        return (static_cast<std::uint32_t>(instructions[3]) << 24) |
        (static_cast<std::uint32_t>(instructions[2]) << 16) |
        (static_cast<std::uint32_t>(instructions[1]) << 8) |
        (static_cast<std::uint32_t>(instructions[0]) << 0);
    };
    
    // Convert the instruction back into instruction bytes
    auto encode_instruction = [](std::uint32_t instruction, std::uint8_t (&instructions)[4]) {
        instructions[3] = (instruction & 0xFF000000) >> 24;
        instructions[2] = (instruction & 0x00FF0000) >> 16;
        instructions[1] = (instruction & 0x0000FF00) >> 8;
        instructions[0] = (instruction & 0x000000FF) >> 0;
    };
    
    // Get the instructions offset, and write the address of dlopen to each part, 16-bits at a time
    auto write_instruction_address = [&](std::uint32_t address_intermediate, std::uint8_t assembly[], std::size_t offset) {
        std::uint8_t instructions[] = {0x00, 0x00, 0x00, 0x00};
        memcpy(&instructions, &assembly[assembly_size + offset], sizeof(instructions));
        
        std::uint32_t instruction = decode_instruction(instructions);
        copy_bits(instruction, address_intermediate);
        encode_instruction(instruction, instructions);
        memcpy(&assembly[assembly_size + offset], &instructions, sizeof(instructions));
    };
    
    // Convert the dlopen address to its 16-bit parts
    std::uintptr_t dlopen_address = static_cast<std::uintptr_t>(dlopen);
    std::uint32_t beef = ((dlopen_address & 0x000000000000FFFF) >> 0);
    std::uint32_t dead = ((dlopen_address & 0x00000000FFFF0000) >> 16);
    std::uint32_t b000 = ((dlopen_address & 0x0000FFFF00000000) >> 32);
    std::uint32_t a000 = ((dlopen_address & 0xFFFF000000000000) >> 48);
    
    // Write the encoded instructions back into the assembly
    write_instruction_address(a000, assembly, -8);
    write_instruction_address(b000, assembly, -12);
    write_instruction_address(dead, assembly, -16);
    write_instruction_address(beef, assembly, -20);
    
    //Allocate and write our remote code
    mach_vm_address_t remote_code = reinterpret_cast<mach_vm_address_t>(nullptr);
    ret = mach_vm_allocate(remote_task, &remote_code, assembly_size, VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Allocate Remote Code\n");
        return false;
    }
    
    ret = mach_vm_write(remote_task, remote_code, reinterpret_cast<mach_vm_offset_t>(&assembly[0]), static_cast<mach_msg_type_number_t>(assembly_size));
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Write Remote Code\n");
        return false;
    }
    
    ret = mach_vm_protect(remote_task, remote_code, assembly_size, false, VM_PROT_READ | VM_PROT_EXECUTE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Protect Remote Code\n");
        return false;
    }
    
    //Allocate remote stack..
    mach_vm_address_t remote_stack = reinterpret_cast<mach_vm_address_t>(nullptr);
    ret = mach_vm_allocate(remote_task, &remote_stack, stack_size, VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Allocate Remote Stack\n");
        return false;
    }
    
    ret = mach_vm_protect(remote_task, remote_stack, stack_size, true, VM_PROT_READ | VM_PROT_WRITE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Protect Remote Stack\n");
        return false;
    }
    
    //Offset stack pointer..
    mach_vm_address_t local_stack = remote_stack;
    remote_stack += (stack_size / 2);  //stack location
    
    //Allocate & write parameters..
    void* parameters[] = {
        (void*)(remote_code + (assembly_size - 24)),
        (void*)pthread_set_self,
        (void*)pthread_create_from_mach_thread,
        (void*)thread_suspend,
        (void*)mach_thread_self
    };
    
    mach_vm_address_t remote_parameters = reinterpret_cast<mach_vm_address_t>(nullptr);
    ret = mach_vm_allocate(remote_task, &remote_parameters, sizeof(parameters), VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Allocate Remote Parameters\n");
        return false;
    }
    
    ret = mach_vm_write(remote_task, remote_parameters, reinterpret_cast<mach_vm_offset_t>(&parameters[0]), static_cast<mach_msg_type_number_t>(sizeof(parameters)));
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Write Remote Parameters\n");
        return false;
    }
    
    // To support ARMv7 and ARMv8, we use arm_unified_thread_state_t instead of arm_thread_state64_t
    arm_unified_thread_state_t state = {0};
    memset(&state, 0, sizeof(state));
    
    //Parameter order for aarch64: x0, x1, x2, x3, x4, x5
    state.ash.flavor = ARM_THREAD_STATE64;
    state.ash.count = ARM_THREAD_STATE64_COUNT;
    state.ts_64.__x[0] = remote_parameters; //pointers to functions
    state.ts_64.__x[1] = remote_path;       //path of module
    state.ts_64.__pc = ptrauth_sign_unauthenticated(remote_code, ptrauth_key_function_pointer, 0);         //code to execute
    state.ts_64.__sp = remote_stack;
    state.ts_64.__lr = local_stack;
    
    //Create our remote thread
    thread_act_t thread;
    err = thread_create_running(remote_task, ARM_THREAD_STATE64, (thread_state_t) &state.ts_64, ARM_THREAD_STATE64_COUNT, &thread);
    if (err != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "ERROR Creating Running Thread!\n");
        return 0;
    }
    
    //Give the remote thread some time to run (Maybe a better way than this???)
    std::this_thread::sleep_for(std::chrono::seconds(2));
    
    //Check to make sure our remote thread is suspended, and finished its job
    thread_basic_info_data_t basic_info;
    mach_msg_type_number_t info_count = THREAD_BASIC_INFO_COUNT;
    err = thread_info(thread, THREAD_BASIC_INFO, (thread_info_t)&basic_info, &info_count);
    if (err == KERN_SUCCESS)
    {
        bool is_suspended = (basic_info.suspend_count > 0);
        if (is_suspended)
        {
            //Retrieve our remote thread state.
            arm_thread_state64_t state = {0};
            mach_msg_type_number_t count = ARM_THREAD_STATE64_COUNT;
            thread_get_state(thread, ARM_THREAD_STATE64, reinterpret_cast<thread_state_t>(&state), &count);
            
            //x10 contains the result of pthread_create_from_mach_thread
            //x11 contains the pthread_t thread_id
            int result = static_cast<int>(state.__x[10]);
            pthread_t thread_id = reinterpret_cast<pthread_t>(state.__x[11]);
            
            if (result == 0 && thread_id != 0)
            {
                //Give some time for pthread_create_from_mach_thread to run, and call dlopen
                std::this_thread::sleep_for(std::chrono::seconds(2));
                
                //Thread should be finished its job.
                thread_terminate(thread);
                return true;
            }
        }
    }
    
    mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
    mach_vm_deallocate(remote_task, remote_stack, stack_size);
    mach_vm_deallocate(remote_task, remote_code, assembly_size);
    mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
    return false;
    #else
    //Update our dlopen address..
    std::uintptr_t dlopen_address = static_cast<std::uintptr_t>(dlopen);
    std::memcpy(&assembly[assembly_size - 15], reinterpret_cast<void*>(&dlopen_address), sizeof(dlopen_address));

    //Allocate and write our remote code
    mach_vm_address_t remote_code = reinterpret_cast<mach_vm_address_t>(nullptr);
    ret = mach_vm_allocate(remote_task, &remote_code, assembly_size, VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Allocate Remote Code\n");
        return false;
    }

    ret = mach_vm_write(remote_task, remote_code, reinterpret_cast<mach_vm_offset_t>(&assembly[0]), static_cast<mach_msg_type_number_t>(assembly_size));
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Write Remote Code\n");
        return false;
    }

    ret = mach_vm_protect(remote_task, remote_code, assembly_size, false, VM_PROT_READ | VM_PROT_EXECUTE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Protect Remote Code\n");
        return false;
    }

    //Allocate remote stack..
    mach_vm_address_t remote_stack = reinterpret_cast<mach_vm_address_t>(nullptr);
    ret = mach_vm_allocate(remote_task, &remote_stack, stack_size, VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Allocate Remote Stack\n");
        return false;
    }

    ret = mach_vm_protect(remote_task, remote_stack, stack_size, true, VM_PROT_READ | VM_PROT_WRITE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Protect Remote Stack\n");
        return false;
    }

    //Offset stack pointer..
    mach_vm_address_t local_stack = remote_stack;
    remote_stack += (stack_size / 2);  //stack location
    remote_stack -= 8;  // Must be 16-byte-off-by-8 aligned <rdar://problem/15886599>

    //Allocate & write parameters..
    #ifndef OLD_INJECTION_CODE
    void* parameters[] = {
            (void*)(remote_code + (assembly_size - 18)),
            (void*)pthread_set_self,
            (void*)pthread_create_from_mach_thread,
            (void*)thread_suspend,
            (void*)mach_thread_self,
            (void*)local_stack, //thread_id,
            (void*)(local_stack + 8) //result
    };
    #else
    void* parameters[] = {
            (void*)(remote_code + (assembly_size - 18)),
            (void*)pthread_create_from_mach_thread
    };
    #endif

    mach_vm_address_t remote_parameters = reinterpret_cast<mach_vm_address_t>(nullptr);
    ret = mach_vm_allocate(remote_task, &remote_parameters, sizeof(parameters), VM_FLAGS_ANYWHERE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Allocate Remote Parameters\n");
        return false;
    }

    ret = mach_vm_write(remote_task, remote_parameters, reinterpret_cast<mach_vm_offset_t>(&parameters[0]), static_cast<mach_msg_type_number_t>(sizeof(parameters)));
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Write Remote Parameters\n");
        return false;
    }

    ret = mach_vm_protect(remote_task, remote_parameters, sizeof(remote_parameters), true, VM_PROT_READ | VM_PROT_WRITE);
    if (ret != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Protect Remote Parameters\n");
        return false;
    }
    
    // To support x86_64 only, we use x86_thread_state_64_t
    x86_thread_state64_t state = {0};
    memset(&state, 0, sizeof(state));

    //Fake return address on the stack
    std::intptr_t return_address_stack[] = { 0x00000DEADBEA7DAD };
    err = mach_vm_write(remote_task, remote_stack, reinterpret_cast<mach_vm_offset_t>(&return_address_stack[0]), sizeof(return_address_stack));
    if (err != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "Failed to Write Return Address\n");
        return false;
    }

    //Parameter order for x86_64: RDI, RSI, RDX, RCX, R8, R9
    state.__rdi = remote_parameters; //pointers to functions
    state.__rsi = remote_path;       //path of module
    state.__rdx = local_stack;       //userp
    state.__rip = remote_code;       //code to execute
    state.__rax = local_stack;       //return address is the top of our stack
    state.__rsp = remote_stack;      //Set remote Stack Pointer
    state.__rbp = remote_stack;      //Set remote Stack Pointer

    //Create our remote thread
    thread_act_t thread;
    err = thread_create_running(remote_task, x86_THREAD_STATE64, (thread_state_t) &state, x86_THREAD_STATE64_COUNT, &thread);
    if (err != KERN_SUCCESS)
    {
        mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
        mach_vm_deallocate(remote_task, remote_stack, stack_size);
        mach_vm_deallocate(remote_task, remote_code, assembly_size);
        mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
        fprintf(stderr, "ERROR Creating Running Thread!\n");
        return false;
    }

    //Give the remote thread some time to run (Maybe a better way than this???)
    //std::this_thread::sleep_for(std::chrono::seconds(2));

    #ifndef OLD_INJECTION_CODE
    for (std::size_t i = 0; i < 5; ++i)
    {
        thread_basic_info_data_t basic_info;
        mach_msg_type_number_t info_count = THREAD_BASIC_INFO_COUNT;
        err = thread_info(thread, THREAD_BASIC_INFO, (thread_info_t)&basic_info, &info_count);

        if (err != KERN_SUCCESS)
        {
          return false;
        }

        if (basic_info.suspend_count > 0)
        {
            break;
        }

        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    #endif

    //Check our thread's to see if it's suspended, and finished its job
    thread_basic_info_data_t basic_info;
    mach_msg_type_number_t info_count = THREAD_BASIC_INFO_COUNT;
    err = thread_info(thread, THREAD_BASIC_INFO, (thread_info_t)&basic_info, &info_count);

    #ifndef OLD_INJECTION_CODE
    if (err == KERN_SUCCESS)
    {
        bool is_suspended = (basic_info.suspend_count > 0);
        if (is_suspended)
        {
            //Retrieve our remote thread returned values via parameters
            std::intptr_t return_values[] = {
                0,  //thread_id
                0   //result
            };

            std::size_t size_read = 0;
            err = mach_vm_read(remote_task, local_stack, sizeof(return_values), reinterpret_cast<vm_offset_t*>(&return_values[0]), reinterpret_cast<mach_msg_type_number_t*>(&size_read));
            if (err != KERN_SUCCESS || size_read != sizeof(return_values))
            {
                mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
                mach_vm_deallocate(remote_task, remote_stack, stack_size);
                mach_vm_deallocate(remote_task, remote_code, assembly_size);
                mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
                mach_port_deallocate(mach_task_self(), remote_task);
                return false;
            }

            //r12 contains the result of pthread_create_from_mach_thread
            //r13 contains the pthread_t thread_id
            int result = static_cast<int>(return_values[1]);
            pthread_t thread_id = reinterpret_cast<pthread_t>(return_values[0]);

            if (result == 0 && thread_id != 0)
            {
                //Thread should be finished its job.
                thread_terminate(thread);

                //Cleanup
                mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
                mach_vm_deallocate(remote_task, remote_stack, stack_size);
                mach_vm_deallocate(remote_task, remote_code, assembly_size);
                mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
                mach_port_deallocate(mach_task_self(), remote_task);
                return true;
            }
        }
    }

    //Thread should be finished its job.
    thread_terminate(thread);

    //Cleanup
    mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
    mach_vm_deallocate(remote_task, remote_stack, stack_size);
    mach_vm_deallocate(remote_task, remote_code, assembly_size);
    mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
    mach_port_deallocate(mach_task_self(), remote_task);
    #else
    if (err == KERN_SUCCESS)
    {
        bool is_suspended = (basic_info.suspend_count > 0);
        if (!is_suspended)
        {
            //Retrieve our remote thread state.
            x86_thread_state64_t state = {0};
            mach_msg_type_number_t count = x86_THREAD_STATE64_COUNT;
            thread_get_state(thread, x86_THREAD_STATE64, reinterpret_cast<thread_state_t>(&state), &count);

            //r12 contains the pthread_t thread_id
            pthread_t thread_id = reinterpret_cast<pthread_t>(state.__r12);

            if (thread_id != 0)
            {
                //Thread should be finished its job.
                ::thread_suspend(thread);
                thread_terminate(thread);

                //Cleanup
                mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
                mach_vm_deallocate(remote_task, remote_stack, stack_size);
                mach_vm_deallocate(remote_task, remote_code, sizeof(assembly));
                mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
                mach_port_deallocate(mach_task_self(), remote_task);
                return true;
            }
        }
    }

    //Thread should be finished its job.
    thread_terminate(thread);

    //Cleanup
    mach_vm_deallocate(remote_task, remote_parameters, sizeof(parameters));
    mach_vm_deallocate(remote_task, remote_stack, stack_size);
    mach_vm_deallocate(remote_task, remote_code, assembly_size);
    mach_vm_deallocate(remote_task, remote_path, module_path.size() + 1);
    mach_port_deallocate(mach_task_self(), remote_task);
    #endif
    #endif

    return false;
}
#endif


#if DEBUG_FUNCTIONS
void list_symbols(const mach_header_64* header)
{
    Dl_info info = {0};
    if (dladdr(header, &info) == 0)
    {
        return;
    }

    segment_command_64* seg_linkedit = nullptr;
    segment_command_64* seg_text = nullptr;
    symtab_command* symtab = nullptr;

    load_command* cmd = reinterpret_cast<load_command*>(reinterpret_cast<std::intptr_t>(header) + sizeof(mach_header_64));
    
    for (std::uint32_t i = 0; i < header->ncmds; ++i)
    {
        switch(cmd->cmd)
        {
            case LC_SEGMENT_64:
            {
                if (!strcmp(reinterpret_cast<segment_command_64*>(cmd)->segname, SEG_TEXT))
                {
                    seg_text = reinterpret_cast<segment_command_64*>(cmd);
                }
                else if (!strcmp(reinterpret_cast<segment_command_64*>(cmd)->segname, SEG_LINKEDIT))
                {
                    seg_linkedit = reinterpret_cast<segment_command_64*>(cmd);
                }
            }
                break;

            case LC_SYMTAB:
            {
                symtab = reinterpret_cast<symtab_command*>(cmd);
            }
                break;
        }
        
        cmd = reinterpret_cast<load_command*>(reinterpret_cast<std::intptr_t>(cmd) + cmd->cmdsize);
    }

    if (!seg_text || !seg_linkedit || !symtab)
    {
        return;
    }

    //ASLR slide calculations
    std::intptr_t file_slide = seg_linkedit->vmaddr - seg_text->vmaddr - seg_linkedit->fileoff;
    std::intptr_t strings = reinterpret_cast<std::intptr_t>(header) + symtab->stroff + file_slide;
    nlist_64* sym_list = reinterpret_cast<nlist_64*>(reinterpret_cast<std::intptr_t>(header) + symtab->symoff + file_slide);

    for (std::uint32_t i = 0; i < symtab->nsyms; ++i, ++sym_list)
    {
        if ((sym_list->n_type & N_EXT) != N_EXT || !sym_list->n_value)
        {
            continue;
        }

        printf("%s\n", reinterpret_cast<char*>(strings) + sym_list->n_un.n_strx);
    }
}

void list_all_symbols()
{
    for (std::int32_t i = _dyld_image_count(); i >= 0 ; --i)
    {
        list_symbols((const mach_header_64 *)_dyld_get_image_header(i));
    }
}

void* get_symbol(const mach_header_64* header, const char* symbol_name)
{
    segment_command_64* seg_linkedit = nullptr;
    segment_command_64* seg_text = nullptr;
    symtab_command* symtab = nullptr;

    load_command* cmd = reinterpret_cast<load_command*>(reinterpret_cast<std::intptr_t>(header) + sizeof(mach_header_64));
    
    std::intptr_t shared_image_cache_slide = 0;
    if ((header->flags & 0x80000000) == 0x80000000)
    {
        shared_image_cache_slide = find_shared_cache_slide(mach_task_self());
    }
    
    for (std::uint32_t i = 0; i < header->ncmds; ++i)
    {
        switch(cmd->cmd)
        {
            case LC_SEGMENT:
            case LC_SEGMENT_64:
            {
                if (!strcmp(reinterpret_cast<segment_command_64*>(cmd)->segname, SEG_TEXT))
                {
                    seg_text = reinterpret_cast<segment_command_64*>(cmd);
                }
                else if (!strcmp(reinterpret_cast<segment_command_64*>(cmd)->segname, SEG_LINKEDIT))
                {
                    seg_linkedit = reinterpret_cast<segment_command_64*>(cmd);
                }
            }
                break;

            case LC_SYMTAB:
            {
                symtab = reinterpret_cast<symtab_command*>(cmd);
            }
                break;
        }
        
        cmd = reinterpret_cast<load_command*>(reinterpret_cast<std::intptr_t>(cmd) + cmd->cmdsize);
    }

    if (!seg_text || !seg_linkedit || !symtab)
    {
        return nullptr;
    }

    //ASLR slide calculations
    std::intptr_t file_slide = seg_linkedit->vmaddr - seg_text->vmaddr - seg_linkedit->fileoff;
    std::intptr_t strings = reinterpret_cast<std::intptr_t>(header) + symtab->stroff + file_slide;
    nlist_64* sym_list = reinterpret_cast<nlist_64*>(reinterpret_cast<std::intptr_t>(header) + symtab->symoff + file_slide);

    for (std::uint32_t i = 0; i < symtab->nsyms; ++i, ++sym_list)
    {
        if (!sym_list->n_value)
        {
            continue;
        }
        
        const char* found_symbol = (reinterpret_cast<char*>(strings) + sym_list->n_un.n_strx);
        if (!strcmp(found_symbol + 1, symbol_name))
        {
            if (shared_image_cache_slide)
            {
                return reinterpret_cast<void*>(sym_list->n_value + shared_image_cache_slide);
            }
            return reinterpret_cast<void*>(reinterpret_cast<std::intptr_t>(header) + sym_list->n_value);
        }
    }
    return nullptr;
}

void* get_symbol(void* module_handle, const char* symbol_name)
{
    mach_header_64* header = nullptr;
    
    for (std::int32_t i = _dyld_image_count(); i >= 0 ; --i)
    {
        const char* image_name = _dyld_get_image_name(i);
        if (image_name)
        {
            void* library = dlopen(image_name, RTLD_NOLOAD);
            dlclose(library);
            
            // If the handle is the same as what was passed in (modulo mode bits), return this image name
            if ((reinterpret_cast<std::intptr_t>(module_handle) & -4) == (reinterpret_cast<std::intptr_t>(library) & -4))
            {
                header = const_cast<mach_header_64*>(reinterpret_cast<const mach_header_64*>(_dyld_get_image_header(i)));
                break;
            }
        }
    }

    return get_symbol(header, symbol_name);
}

void* get_module_handle(const char* module_name)
{
    for (std::int32_t i = _dyld_image_count(); i >= 0 ; --i)
    {
        const char* image_name = _dyld_get_image_name(i);
        if (image_name && strip_path(image_name) == module_name)
        {
            return dlopen(image_name, RTLD_NOLOAD /*RTLD_DEFAULT*/);
        }
    }
    return nullptr;
}

const char* pathname_for_handle(void* handle)
{
    for (std::int32_t i = _dyld_image_count(); i >= 0 ; --i)
    {
        const char* image_name = _dyld_get_image_name(i);
        void* library = dlopen(image_name, RTLD_NOLOAD);
        dlclose(library);

        // If the handle is the same as what was passed in (modulo mode bits), return this image name
        if ((reinterpret_cast<std::intptr_t>(handle) & -4) == (reinterpret_cast<std::intptr_t>(library) & -4))
        {
            return image_name;
        }
    }
    return nullptr;
}

void* deauth_ptr(void* ptr)
{
    return ptrauth_sign_unauthenticated(ptr, ptrauth_key_function_pointer, 0);
}

kern_return_t thread_port_for_task(mach_port_t task, mach_port_t *thread)
{
    thread_array_t thread_list = NULL;
    mach_msg_type_number_t thread_list_count = 0;
    kern_return_t ret = task_threads(task, &thread_list, &thread_list_count);
    if (ret) return ret;
    
    //main-thread = thread_list[0]
    *thread = thread_list[0];
    vm_deallocate(mach_task_self(), (vm_address_t) thread_list, thread_list_count * sizeof(thread_list[0]));
    return KERN_SUCCESS;
}

int find_mach_o(unsigned long addr, unsigned long *base) {
    *base = 0;

    while(true) {
        chmod((char *)addr, 0777);
        if(errno == 2 && /*ENOENT*/
          ((int *)addr)[0] == 0xfeedfacf /*MH_MAGIC_64*/) {
            *base = addr;
            return 0;
        }

        addr += 0x1000;
    }
    return 1;
}
#endif
