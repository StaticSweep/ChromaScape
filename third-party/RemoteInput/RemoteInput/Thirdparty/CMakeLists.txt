cmake_minimum_required(VERSION 3.15)
project(THIRD_PARTY_LIBRARIES VERSION 1.0.0 DESCRIPTION "Third Party")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ----------------------- ENABLE ASSEMBLY CODE -----------------------
enable_language(ASM)



# ----------------------- INCLUDE_DIRECTORIES -----------------------
IF(WIN32)
    set(INCLUDE_DIRECTORIES
            min_hook/include
            min_hook/src
            min_hook/src/hde
            kubo_injector/include
            kubo_injector/src/windows)
ELSEIF(APPLE)
    set(INCLUDE_DIRECTORIES
            rd_route/include
            mach_inject/include
            kubo_injector/include
            kubo_injector/src/macos)
ELSE()
    set(INCLUDE_DIRECTORIES
            linux_detours/include
            kubo_injector/include
            kubo_injector/src/linux)
ENDIF()


# ----------------------------- LINKER -----------------------------
IF(WIN32)
    set(LIBRARIES_LIST
            kernel32
            dbghelp)
ELSEIF(APPLE)
    set(LIBRARIES_LIST
            dl
            pthread)
ELSE()
    set(LIBRARIES_LIST
            dl
            pthread)
ENDIF()


# ---------------------------- SOURCES ----------------------------
IF(WIN32)
    set(LIB_SRC_LIST
            min_hook/include/MinHook.h
            min_hook/src/hde/hde32.c
            min_hook/src/hde/hde32.h
            min_hook/src/hde/hde64.c
            min_hook/src/hde/hde64.h
            min_hook/src/hde/pstdint.h
            min_hook/src/hde/table32.h
            min_hook/src/hde/table64.h
            min_hook/src/buffer.c
            min_hook/src/buffer.h
            min_hook/src/hook.c
            min_hook/src/trampoline.c
            min_hook/src/trampoline.h
            kubo_injector/include/injector.h
            kubo_injector/src/windows/injector.c)
ELSEIF(APPLE)
    set(LIB_SRC_LIST
            rd_route/include/rd_route.h
            rd_route/src/rd_route.c
            kubo_injector/include/injector.h
            kubo_injector/src/macos/exc_handler.c
            kubo_injector/src/macos/injector.c
            kubo_injector/src/macos/injector_internal.h
            kubo_injector/src/macos/mach.c
            kubo_injector/src/macos/mach_exc.h
            kubo_injector/src/macos/mach_excServer.c
            kubo_injector/src/macos/ptrace.c
            kubo_injector/src/macos/remote_call.c
            kubo_injector/src/macos/util.c)
ELSE()
    set(LIB_SRC_LIST
            linux_detours/include/detours.h
            #linux_detours/include/limits.h
            linux_detours/include/plthook.h
            linux_detours/include/types.h
            linux_detours/src/barrier.cpp
            linux_detours/src/detours.cpp
            linux_detours/src/disasm.cpp
            linux_detours/src/plthook_elf.cpp
            linux_detours/src/trampoline_x86.cpp
            linux_detours/src/trampoline_arm.cpp
            kubo_injector/src/linux/shellcode.S
            kubo_injector/src/linux/elf.c
            kubo_injector/src/linux/injector.c
            kubo_injector/src/linux/injector_internal.h
            kubo_injector/src/linux/ptrace.c
            kubo_injector/src/linux/remote_call.c
            kubo_injector/src/linux/util.c)
ENDIF()

set(SRC_LIST
        ${LIB_SRC_LIST}
        Hook.hxx
        Hook.cxx
        Injector.cxx
        Injector.hxx)


# ---------------------------- COMPILE ----------------------------
add_library(${PROJECT_NAME} OBJECT ${SRC_LIST})
set_property(TARGET ${PROJECT_NAME} PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(${PROJECT_NAME} PRIVATE ${INCLUDE_DIRECTORIES})

IF(WIN32)
    #target_compile_definitions(${PROJECT_NAME} PRIVATE _AMD64_=1 _UNIX64=1)

    IF(CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
        target_compile_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-Wno-multichar -Wno-null-conversion -g -O0 -fno-omit-frame-pointer>
                $<$<CONFIG:RELEASE>:-Wno-multichar -Wno-null-conversion -s -O0 -fno-omit-frame-pointer>)
        target_link_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-static -static-libgcc -static-libstdc++>
                $<$<CONFIG:RELEASE>:-s -static -static-libgcc -static-libstdc++>)
    ELSEIF(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        target_compile_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-Wno-multichar -Wno-null-conversion -g -O0 -fno-omit-frame-pointer>
                $<$<CONFIG:RELEASE>:-Wno-multichar -Wno-null-conversion -s -O0 -fno-omit-frame-pointer>)
        target_link_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-static -static-libgcc -static-libstdc++>
                $<$<CONFIG:RELEASE>:-s -static -static-libgcc -static-libstdc++>)
    ELSEIF(CMAKE_CXX_COMPILER_ID STREQUAL "MSVC")
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        set_property(TARGET ${PROJECT_NAME} PROPERTY
                MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

        target_compile_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:/Zo -DWIN32_LEAN_AND_MEAN /MTd>
                $<$<CONFIG:RELEASE>:/Zo -DWIN32_LEAN_AND_MEAN /MT>)
        target_link_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:>
                $<$<CONFIG:RELEASE>:>)
    ENDIF()
ELSEIF(APPLE)
    #target_compile_definitions(${PROJECT_NAME} PRIVATE _AMD64_=1 _UNIX64=1)

    target_compile_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-Wno-multichar -Wno-null-conversion -g -O0 -fno-omit-frame-pointer -fvisibility=hidden>
            $<$<CONFIG:RELEASE>:-Wno-multichar -Wno-null-conversion -O0 -fno-omit-frame-pointer -fvisibility=hidden>)
    target_link_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-static>
            $<$<CONFIG:RELEASE>:-s -static>)
ELSE()
    IF(CMAKE_SYSTEM_PROCESSOR MATCHES "amd64.*|x86_64.*|AMD64.*")
        target_compile_definitions(${PROJECT_NAME} PRIVATE _AMD64_=1 _UNIX64=1)
    ELSEIF(CMAKE_SYSTEM_PROCESSOR MATCHES "i686.*|i386.*|x86.*")
        target_compile_definitions(${PROJECT_NAME} PRIVATE _X86_=1)
    ELSEIF(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64.*|AARCH64.*|arm64.*|ARM64.*)")
        target_compile_definitions(${PROJECT_NAME} PRIVATE _ARM64_=1 _UNIX64=1)
    ELSEIF(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm.*|ARM.*)")
        target_compile_definitions(${PROJECT_NAME} PRIVATE _ARM_=1 _ARM32_=1)
    ELSE()
        target_compile_definitions(${PROJECT_NAME} PRIVATE _AMD64_=1 _UNIX64=1)
    ENDIF()

    target_compile_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-Wno-multichar -Wno-null-conversion -g -O0 -fno-omit-frame-pointer -fvisibility=hidden>
            $<$<CONFIG:RELEASE>:-Wno-multichar -Wno-null-conversion -s -O0 -fno-omit-frame-pointer -fvisibility=hidden>)
    target_link_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-static-libgcc -static-libstdc++>
            $<$<CONFIG:RELEASE>:-s -static-libgcc -static-libstdc++>)
ENDIF()

target_link_libraries(${PROJECT_NAME}  ${LIBRARIES_LIST})
