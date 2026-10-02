cmake_minimum_required(VERSION 3.15)
project(RemoteInput VERSION 1.0.0 DESCRIPTION "Remote Input")

set(CMAKE_CXX_STANDARD 20)

# ----------------------------- BUILD -----------------------------
IF(NOT CMAKE_BUILD_TYPE)
    SET(CMAKE_BUILD_TYPE Release)
ENDIF()

set(PYTHON_BINDINGS ON)
set(USE_PYBIND11 OFF)
set(USE_PYTHON3 OFF)
set(USE_SYSTEM_PYBIND11 OFF)
set(PYTHON_LIMITED_VERSION 0x03080000)

IF (USE_PYBIND11)
    unset(Py_LIMITED_API)
    MESSAGE(STATUS, "PyBind11 being used -- Ignoring Py_LIMITED_API")
ENDIF()

IF (NOT USE_PYBIND11)
    set(Py_LIMITED_API ${PYTHON_LIMITED_VERSION})
ENDIF()

IF(PYTHON_BINDINGS AND USE_PYBIND11 AND Py_LIMITED_API)
    MESSAGE(FATAL_ERROR, "PyBind11 cannot be used with Py_LIMITED_API")
ENDIF()

# ----------------------------- PACKAGES -----------------------------
set(JAVA_AWT_LIBRARY NotNeeded)
set(JAVA_JVM_LIBRARY NotNeeded)
set(JAVA_AWT_INCLUDE_PATH NotNeeded)

find_package(Java 1.8 REQUIRED)
find_package(JNI 1.8 REQUIRED)

IF(PYTHON_BINDINGS)
    IF(USE_PYBIND11)
        IF(USE_PYTHON3)
            find_package(Python3 REQUIRED COMPONENTS Interpreter Development)
            set(PY_INCLUDE_DIRS ${Python3_INCLUDE_DIRS})
            set(PY_LIBRARIES ${Python3_LIBRARIES})
            set(PY_LIBRARIES ${Python3_LIBRARY_DIRS})
            set(PY_LINK_OPTIONS ${Python3_LINK_OPTIONS})
            set(PY_DYNAMIC_LINKER_FLAGS ${Python3_DYNAMIC_LINKER_FLAGS})
        ELSE()
            find_package(Python REQUIRED COMPONENTS Interpreter Development)
            set(PY_INCLUDE_DIRS ${Python_INCLUDE_DIRS})
            set(PY_LIBRARIES ${Python_LIBRARIES})
            set(PY_LIBRARY_DIRS ${Python_LIBRARY_DIRS})
            set(PY_LINK_OPTIONS ${Python_LINK_OPTIONS})
            set(PY_DYNAMIC_LINKER_FLAGS ${Python_DYNAMIC_LINKER_FLAGS})
        ENDIF()

        IF(USE_SYSTEM_PYBIND11)
            find_package(nanobind REQUIRED)
        ENDIF()
    ELSE()
        IF(USE_PYTHON3)
            find_package(Python3 3.8 REQUIRED COMPONENTS Interpreter Development)
            set(PY_INCLUDE_DIRS ${Python3_INCLUDE_DIRS})
            # set(PY_LIBRARIES ${Python3_LIBRARIES})
            set(PY_LIBRARY_DIRS ${Python3_LIBRARY_DIRS})
            set(PY_LINK_OPTIONS ${Python3_LINK_OPTIONS})
            set(PY_DYNAMIC_LINKER_FLAGS ${Python3_DYNAMIC_LINKER_FLAGS})
        ELSE()
            find_package(Python 3.8 REQUIRED COMPONENTS Interpreter Development)
            set(PY_INCLUDE_DIRS ${Python_INCLUDE_DIRS})
            # set(PY_LIBRARIES ${Python_LIBRARIES})
            set(PY_LIBRARY_DIRS ${Python_LIBRARY_DIRS})
            set(PY_LINK_OPTIONS ${Python_LINK_OPTIONS})
            set(PY_DYNAMIC_LINKER_FLAGS ${Python_DYNAMIC_LINKER_FLAGS})
        ENDIF()
    ENDIF()
    MESSAGE(STATUS, "${PY_INCLUDE_DIRS}")
    MESSAGE(STATUS, "${PY_LIBRARIES}")
    MESSAGE(STATUS, "${PY_LIBRARY_DIRS}")
    MESSAGE(STATUS, "${PY_DYNAMIC_LINKER_FLAGS}")
ENDIF()

# ----------------------- INCLUDE_DIRECTORIES -----------------------
set(INCLUDE_DIRECTORIES
        RemoteInput
        RemoteInput/Echo
        RemoteInput/Hooks
        RemoteInput/Java
        RemoteInput/Platform
        RemoteInput/Plugin
        RemoteInput/Plugin/JVM
        ${JNI_INCLUDE_DIRS}
        ${PY_INCLUDE_DIRS})


# ----------------------------- PLATFORM -----------------------------
IF(WIN32)
    set(EXTRA_INCLUDES
            ${OPENGL_INCLUDES})
ELSEIF(APPLE)
    find_path(FOUNDATION_INCLUDES Foundation/Foundation.h)
    find_path(COCOA_INCLUDES Cocoa/Cocoa.h)
    find_path(OPENGL_INCLUDES OpenGL/OpenGL.h)

    set(EXTRA_INCLUDES
            ${FOUNDATION_INCLUDES}
            ${COCOA_INCLUDES}
            ${OPENGL_INCLUDES})
ELSE()
    set(EXTRA_INCLUDES
            ${OPENGL_INCLUDES})
ENDIF()



# ----------------------------- LINKER -----------------------------
IF(WIN32)
    set(EXTRA_LIBRARIES
            user32
            opengl32
            gdi32
            dbghelp)  # Needed for ThirdParty/kubo/injector
ELSEIF(APPLE)
    find_library(FOUNDATION Foundation)
    find_library(COCOA Cocoa)
    find_library(OPENGL OpenGL)

    set(EXTRA_LIBRARIES
            ${FOUNDATION}
            ${COCOA}
            ${OPENGL}
            dl
            pthread)
ELSE()
    set(EXTRA_LIBRARIES
            dl
            pthread
            GL
            rt
            X11)
ENDIF()


# ----------------------------- SOURCES -----------------------------

set(SRC_LIST
        ${EXTRA_INCLUDES}
        RemoteInput/RemoteInput.h
        RemoteInput/Echo/Atomics.cxx
        RemoteInput/Echo/Atomics.hxx
        RemoteInput/Echo/Mutex.cxx
        RemoteInput/Echo/Mutex.hxx
        RemoteInput/Echo/Semaphore.cxx
        RemoteInput/Echo/Semaphore.hxx
        RemoteInput/Echo/Event.cxx
        RemoteInput/Echo/Event.hxx
        RemoteInput/Echo/Synchronization.hxx
        RemoteInput/Echo/MemoryMap.cxx
        RemoteInput/Echo/MemoryMap.hxx
        RemoteInput/Echo/MemoryMapStream.hxx
        RemoteInput/Echo/Module.cxx
        RemoteInput/Echo/Module.hxx
        RemoteInput/Echo/Stream.cxx
        RemoteInput/Echo/Stream.hxx
        RemoteInput/Echo/Time.cxx
        RemoteInput/Echo/Time.hxx
        RemoteInput/Echo/TypeTraits.hxx
        RemoteInput/Echo/TypeTraits_Functional.hxx
        RemoteInput/Echo/TypeTraits_Functional_Attributes.hxx
        RemoteInput/Java/JNI_Common.hxx
        RemoteInput/Java/Applet.cxx
        RemoteInput/Java/Applet.hxx
        RemoteInput/Java/AWTAccessor.cxx
        RemoteInput/Java/AWTAccessor.hxx
        RemoteInput/Java/AWTEvent.cxx
        RemoteInput/Java/AWTEvent.hxx
        RemoteInput/Java/AWTEventAccessor.cxx
        RemoteInput/Java/AWTEventAccessor.hxx
        RemoteInput/Java/Component.cxx
        RemoteInput/Java/Component.hxx
        RemoteInput/Java/Container.cxx
        RemoteInput/Java/Container.hxx
        RemoteInput/Java/EventQueue.cxx
        RemoteInput/Java/EventQueue.hxx
        RemoteInput/Java/FocusEvent.cxx
        RemoteInput/Java/FocusEvent.hxx
        RemoteInput/Java/Frame.cxx
        RemoteInput/Java/Frame.hxx
        RemoteInput/Java/InputEvent.cxx
        RemoteInput/Java/InputEvent.hxx
        RemoteInput/Java/KeyEvent.cxx
        RemoteInput/Java/KeyEvent.hxx
        RemoteInput/Java/MouseEvent.cxx
        RemoteInput/Java/MouseEvent.hxx
        RemoteInput/Java/MouseWheelEvent.cxx
        RemoteInput/Java/MouseWheelEvent.hxx
        RemoteInput/Java/PointerInfo.cxx
        RemoteInput/Java/PointerInfo.hxx
        RemoteInput/Java/SunToolkit.cxx
        RemoteInput/Java/SunToolkit.hxx
        RemoteInput/Java/Toolkit.cxx
        RemoteInput/Java/Toolkit.hxx
        RemoteInput/Java/Window.cxx
        RemoteInput/Java/Window.hxx
        RemoteInput/Java/WindowEvent.cxx
        RemoteInput/Java/WindowEvent.hxx
        RemoteInput/Java/RIEventQueue.cxx
        RemoteInput/Java/RIEventQueue.hxx
        RemoteInput/Platform/DebugConsole.cxx
        RemoteInput/Platform/DebugConsole.hxx
        RemoteInput/Platform/JavaInternal.hxx
        RemoteInput/Platform/NativeHooks.hxx
        RemoteInput/Platform/NativeHooks_Darwin.cxx
        RemoteInput/Platform/NativeHooks_Linux.cxx
        RemoteInput/Platform/NativeHooks_Windows.cxx
        RemoteInput/Platform/Platform.hxx
        RemoteInput/Platform/Platform_Linux.cxx
        RemoteInput/Platform/Platform_Windows.cxx
        RemoteInput/Plugin/ControlCenter.cxx
        RemoteInput/Plugin/ControlCenter.hxx
        RemoteInput/Plugin/Graphics.cxx
        RemoteInput/Plugin/Graphics.hxx
        RemoteInput/Plugin/ImageData.cxx
        RemoteInput/Plugin/ImageData.hxx
        RemoteInput/Plugin/InputOutput.cxx
        RemoteInput/Plugin/InputOutput.hxx
        RemoteInput/Plugin/NativePlugin.cxx
        RemoteInput/Plugin/NativePlugin.hxx
        RemoteInput/Plugin/Plugin.cxx
        RemoteInput/Plugin/Plugin.hxx
        RemoteInput/Plugin/Signal.hxx
        RemoteInput/Plugin/SimbaPlugin.cxx
        RemoteInput/Plugin/SimbaPlugin.hxx
        RemoteInput/Plugin/TMemoryManager.hxx
        RemoteInput/Plugin/JVM/JVMCache.cxx
        RemoteInput/Plugin/JVM/JVMCache.hxx
        RemoteInput/Plugin/JVM/RemoteVM.hxx
        RemoteInput/Plugin/JVM/RemoteVM.cxx
        RemoteInput/DetachedThreadPool.cxx
        RemoteInput/DetachedThreadPool.hxx
        RemoteInput/EIOS.cxx
        RemoteInput/EIOS.hxx
        RemoteInput/JVM.cxx
        RemoteInput/JVM.hxx
        RemoteInput/Random.cxx
        RemoteInput/Random.hxx
        RemoteInput/Reflection.cxx
        RemoteInput/Reflection.hxx
        RemoteInput/ReflectionHook.cxx
        RemoteInput/ReflectionHook.hxx
        RemoteInput/RemoteInput.h
        RemoteInput/ThreadPool.cxx
        RemoteInput/ThreadPool.hxx
        RemoteInput/Injection/Injector.hxx
        RemoteInput/Injection/Injector_Windows.cxx
        RemoteInput/Injection/Injector_Darwin.cxx
        RemoteInput/Injection/Injector_Linux.cpp
        RemoteInput/Injection/Injector_Arm.cpp)

IF(APPLE)
    list(APPEND SRC_LIST
            RemoteInput/Platform/Platform_Darwin.mm)
ENDIF()

IF(PYTHON_BINDINGS)
    list(APPEND SRC_LIST
            RemoteInput/Plugin/Python/PythonMacros.hxx
            RemoteInput/Plugin/Python/PythonCommon.cxx
            RemoteInput/Plugin/Python/PythonCommon.hxx
            RemoteInput/Plugin/Python/PythonPlugin.cxx
            RemoteInput/Plugin/Python/PythonPlugin.hxx
            RemoteInput/Plugin/Python/PythonEIOS.cxx
            RemoteInput/Plugin/Python/PythonEIOS.hxx
            RemoteInput/Plugin/Python/PythonJavaObject.cxx
            RemoteInput/Plugin/Python/PythonJavaObject.hxx
            RemoteInput/Plugin/Python/PythonJavaArray.cxx
            RemoteInput/Plugin/Python/PythonJavaArray.hxx
            RemoteInput/Plugin/Python/PythonJavaList.cxx
            RemoteInput/Plugin/Python/PythonJavaList.hxx
            RemoteInput/Plugin/Python/PythonCommon_Templates.hxx
            RemoteInput/Plugin/Python/Python.cxx
            RemoteInput/Plugin/Python/Python.hxx)
ENDIF()

IF(PYTHON_BINDINGS AND NOT (CMAKE_CXX_COMPILER_ID STREQUAL "MSVC"))
    #    set(EXTRA_LIBRARIES
    #            ${EXTRA_LIBRARIES}
    #            ${Python_LIBRARIES})
    #    set(PYTHON_DYNAMIC_LINKER_FLAGS
    #            -undefined dynamic_lookup)
ENDIF()

# ---------------------------- COMPILE ----------------------------

IF(PYTHON_BINDINGS AND USE_PYBIND11)
    add_subdirectory(RemoteInput/Thirdparty)

    IF(NOT USE_SYSTEM_PYBIND11)
        add_subdirectory(RemoteInput/Thirdparty/nanobind)
    ENDIF()

    nanobind_add_module(${PROJECT_NAME} SHARED ${SRC_LIST} $<TARGET_OBJECTS:THIRD_PARTY_LIBRARIES>)

    IF(NOT USE_SYSTEM_PYBIND11)
        target_include_directories(RemoteInput PRIVATE ${CMAKE_SOURCE_DIR}/RemoteInput/Thirdparty/nanobind/include)
    ENDIF()
ELSE()
    add_subdirectory(RemoteInput/Thirdparty)
    add_library(${PROJECT_NAME} SHARED ${SRC_LIST} $<TARGET_OBJECTS:THIRD_PARTY_LIBRARIES>)
ENDIF()

#set_target_properties(${PROJECT_NAME} PROPERTIES VERSION ${PROJECT_VERSION})
target_include_directories(${PROJECT_NAME} PRIVATE ${INCLUDE_DIRECTORIES})

IF(PYTHON_BINDINGS)
    IF (USE_PYBIND11)
        target_compile_definitions(${PROJECT_NAME} PRIVATE USE_DETOURS=1 HOOK_OPENGL_SWAP=1 HOOK_DIRECTX_BLIT=1 USE_PYBIND11=1 Py_BUILD_CORE=1 Py_NO_ENABLE_SHARED=1)
    ELSEIF(Py_LIMITED_API)
        target_compile_definitions(${PROJECT_NAME} PRIVATE USE_DETOURS=1 HOOK_OPENGL_SWAP=1 HOOK_DIRECTX_BLIT=1 Py_LIMITED_API=${Py_LIMITED_API} Py_BUILD_CORE=1 Py_NO_ENABLE_SHARED=1)
    ELSE()
        target_compile_definitions(${PROJECT_NAME} PRIVATE USE_DETOURS=1 HOOK_OPENGL_SWAP=1 HOOK_DIRECTX_BLIT=1 Py_BUILD_CORE=1 Py_NO_ENABLE_SHARED=1)
    ENDIF()
ELSE()
    target_compile_definitions(${PROJECT_NAME} PRIVATE USE_DETOURS=1)
ENDIF()

IF(WIN32)
    IF(CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
        target_compile_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-DDEBUG>
                $<$<CONFIG:RELEASE>:-O3 -fvisibility=hidden>)
        target_link_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-static -stdlib=libc++ -fuse-ld=lld -Wl,--enable-stdcall-fixup -Wl,--kill-at -Wl"/DEF:RemoteInput/RemoteInput.def" ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>
                $<$<CONFIG:RELEASE>:-s -static -stdlib=libc++ -fuse-ld=lld -Wl,--enable-stdcall-fixup -Wl,--kill-at -Wl"/DEF:RemoteInput/RemoteInput.def" ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>)
    ELSEIF(CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        target_compile_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-DDEBUG -Wl,--input-def=RemoteInput/RemoteInput.def>
                $<$<CONFIG:RELEASE>:-O3 -fvisibility=hidden -Wl,--input-def=RemoteInput/RemoteInput.def>)
        target_link_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:-static -static-libgcc -static-libstdc++ -Wl,--enable-stdcall-fixup -Wl,--kill-at ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>
                $<$<CONFIG:RELEASE>:-s -static -static-libgcc -static-libstdc++ -Wl,--enable-stdcall-fixup -Wl,--kill-at ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>)
    ELSEIF(CMAKE_CXX_COMPILER_ID STREQUAL "MSVC")
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
        set_property(TARGET ${PROJECT_NAME} PROPERTY
                MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

        set(CMAKE_EXE_LINKER_FLAGS
                "${CMAKE_EXE_LINKER_FLAGS} /DEF:RemoteInput/RemoteInput.def")
        target_compile_options(${PROJECT_NAME} PRIVATE
                $<$<CONFIG:DEBUG>:/DDEBUG /DWIN32_LEAN_AND_MEAN /DNOMINMAX /MTd>
                $<$<CONFIG:RELEASE>:-O3 /DWIN32_LEAN_AND_MEAN /DNOMINMAX /MT>)

        IF(PYTHON_BINDINGS)
            target_link_options(${PROJECT_NAME} PRIVATE
                    $<$<CONFIG:DEBUG>:>
                    $<$<CONFIG:RELEASE>:/LIBPATH:${Python_LIBRARY_DIRS} ${Py_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>)
        ELSE()
            target_link_options(${PROJECT_NAME} PRIVATE
                    $<$<CONFIG:DEBUG>:>
                    $<$<CONFIG:RELEASE>:>)
        ENDIF()
    ENDIF()
ELSEIF(APPLE)
    target_compile_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-DDEBUG -g -fvisibility=hidden>
            $<$<CONFIG:RELEASE>:-O3 -fvisibility=hidden>)
    target_link_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-static -stdlib=libc++ -Wl"/DEF:RemoteInput/RemoteInput.def" ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>
            $<$<CONFIG:RELEASE>:-s -static -stdlib=libc++ -Wl"/DEF:RemoteInput/RemoteInput.def" ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>)
ELSE()
    target_compile_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-DDEBUG -g -fvisibility=hidden>
            $<$<CONFIG:RELEASE>:-O3 -fvisibility=hidden>)
    target_link_options(${PROJECT_NAME} PRIVATE
            $<$<CONFIG:DEBUG>:-g ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}> #-static-libgcc -static-libstdc++
            $<$<CONFIG:RELEASE>:-s ${PY_LINK_OPTIONS} ${PY_DYNAMIC_LINKER_FLAGS}>) #-static-libgcc -static-libstdc++
ENDIF()

IF(PYTHON_BINDINGS)
    IF(USE_PYTHON3)
        set(PY_MODULE Python3::Python)
    ELSE()
        set(PY_MODULE Python::Python)
    ENDIF()

    IF(USE_PYBIND11)
        target_link_libraries(${PROJECT_NAME} PRIVATE ${LIBRARIES_LIST} ${EXTRA_LIBRARIES} ${PY_LIBRARIES} ${PY_MODULE})  #nanobind::module
    ELSE()
        target_link_libraries(${PROJECT_NAME} PRIVATE ${LIBRARIES_LIST} ${EXTRA_LIBRARIES} ${PY_LIBRARIES})
    ENDIF()
ELSE()
    target_link_libraries(${PROJECT_NAME} PRIVATE ${LIBRARIES_LIST} ${EXTRA_LIBRARIES})
ENDIF()


# ---------------------------- RENAME ----------------------------

IF(WIN32)
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E rename
            $<TARGET_FILE:${PROJECT_NAME}>
            ${CMAKE_BINARY_DIR}/libRemoteInput.dll
            COMMENT "Renaming module to RemoteInput.dll"
    )
ELSEIF(APPLE)
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E rename
            $<TARGET_FILE:${PROJECT_NAME}>
            ${CMAKE_BINARY_DIR}/libRemoteInput.dylib
            COMMENT "Renaming module to RemoteInput.dylib"
    )
ELSE()
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E rename
            $<TARGET_FILE:${PROJECT_NAME}>
            ${CMAKE_BINARY_DIR}/libRemoteInput.so
            COMMENT "Renaming module to RemoteInput.so"
    )
ENDIF()