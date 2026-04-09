# Preamble
[Brandon-T's RemoteInput](https://github.com/Brandon-T/RemoteInput) (RI) is a C++ based utility that provides IO to a Java application.
It serves as a bridge between the automation framework (this project) and the client.
RemoteInput functions by injecting a part of itself into the target and then using commands in AWT semantics to simulate IO.
This approach as opposed to OS level ones allows the user to keep using their mouse and keyboard and bypasses 
the `LLMHF_INJECTED` flag that RuneLite checks for.
RemoteInput is currently being used in [SRL](https://github.com/Villavu/SRL-Development), 
we have taken a great deal of inspiration from their integration of RI.

The following guide explains how to build the binary for yourself, allowing you to audit the code beforehand.
If you want to use pre-compiled binaries, they are available from 
[Brandon-T's reflection Auto-Build GitHub Actions pipeline](https://github.com/Brandon-T/Reflection/releases/tag/autobuild).
Alternatively, they are pre-packaged with ChromaScape (currently only Windows) in the `third-party/RemoteInput/precompiled` folder.

# Windows Instructions

1. Install [MSYS2](https://www.msys2.org/)
2. In an MSYS2 terminal, install the following dependencies:
   - Windows x32:
     > `pacman -S mingw-w64-i686-gcc mingw-w64-i686-clang mingw-w64-i686-python mingw-w64-i686-cmake make`
   - Windows x64:
     > `pacman -S mingw-w64-x86_64-gcc mingw-w64-x86_64-clang mingw-w64-x86_64-python mingw-w64-x86_64-cmake make`
3. In an MSYS2 MinGW terminal, navigate to the RemoteInput directory:
   > e.g., `cd /c/Users/YourName/repos/ChromaScape/third-party/RemoteInput`
4. To build the binary, execute the following in the `RemoteInput` project's root folder, same level as CMakeLists.txt, 
   in an MSYS2 MinGW terminal:
    ```
    # Set flags: "-m64" for 64-bit or "-m32" for 32-bit
    cmake -S . -B cmake-build-release -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release -DOTHER_LINKER_FLAGS="-m64"
    
    # Build
    cmake --build cmake-build-release --target all -j 4
    ```
5. The binary will be located as: `third-party/RemoteInput/cmake-build-release/libRemoteInput.dll`
6. ChromaScape checks for a compiled binary before resorting to a provided pre-compiled one, 
   however you may safely delete the pre-compiled folder and any binaries within:
   >    `third-party/RemoteInput/precompiled`