# Preamble

[Brandon-T's RemoteInput](https://github.com/Brandon-T/RemoteInput) (RI) is a C++ utility that provides I/O bridging between this automation framework and the RuneLite client. It works by injecting a component of itself into the target process and issuing commands in AWT semantics to simulate input events. This approach—as opposed to OS-level input methods—allows the user to retain normal use of their mouse and keyboard, and bypasses the `LLMHF_INJECTED` flag that RuneLite checks for.

RemoteInput is also used in [SRL](https://github.com/Villavu/SRL-Development), from which this project has drawn significant integration inspiration.

The following guide explains how to build the binary from source, enabling you to audit the code prior to use. Pre-compiled binaries are available from [Brandon-T's Reflection Auto-Build pipeline](https://github.com/Brandon-T/Reflection/releases/tag/autobuild), and are also bundled with ChromaScape (currently Windows only) under `third-party/RemoteInput/precompiled/`.

---

# Windows Instructions

1. Install [MSYS2](https://www.msys2.org/).

2. In an MSYS2 terminal, install the required dependencies for your target architecture:

   - **Windows x32:**
     ```sh
     pacman -S mingw-w64-i686-gcc mingw-w64-i686-clang mingw-w64-i686-python mingw-w64-i686-cmake make
     ```
   - **Windows x64:**
     ```sh
     pacman -S mingw-w64-x86_64-gcc mingw-w64-x86_64-clang mingw-w64-x86_64-python mingw-w64-x86_64-cmake make
     ```

3. Open an MSYS2 MinGW terminal and navigate to the `RemoteInput` directory:
   ```sh
   cd /c/Users/YourName/repos/ChromaScape/third-party/RemoteInput
   ```

4. From the `RemoteInput` project root (same level as `CMakeLists.txt`), run the following to configure and build the binary:
   ```sh
   # Set flags: "-m64" for 64-bit or "-m32" for 32-bit
   cmake -S . -B cmake-build-release -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release -DOTHER_LINKER_FLAGS="-m64"

   # Build
   cmake --build cmake-build-release --target all -j 4
   ```

5. The compiled binary will be output to:
   ```
   third-party/RemoteInput/cmake-build-release/libRemoteInput.dll
   ```

6. ChromaScape will prefer a locally compiled binary over the bundled pre-compiled one. If desired, the pre-compiled directory and its contents can be safely removed:
   ```
   third-party/RemoteInput/precompiled/
   ```

---

# Linux Instructions

1. Install the following dependencies for your distribution. Required packages:
   [make](https://www.gnu.org/software/make/make.html),
   [cmake](https://cmake.org/),
   [python3-dev](https://packages.debian.org/de/sid/python3-dev),
   [libgl-dev](https://packages.debian.org/de/sid/libgl-dev).

   - **Fedora x64:**
     ```sh
     sudo dnf install make cmake libGL-devel python3-devel
     ```
   - **Ubuntu x64:**
     ```sh
     sudo apt install make cmake libgl-dev python3-dev
     ```
   - **Arch x64:**
     ```sh
     sudo pacman -S make cmake mesa python
     ```

2. Navigate to the `RemoteInput` directory:
   ```sh
   cd /path/to/ChromaScape/third-party/RemoteInput
   ```

3. From the `RemoteInput` project root (same level as `CMakeLists.txt`), run the following to configure and build the binary:
   ```sh
   # Set flags: "-m64" for 64-bit or "-m32" for 32-bit
   cmake -S . -B cmake-build-release -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release -DOTHER_LINKER_FLAGS="-m64"

   # Build
   cmake --build cmake-build-release --target all -j 4
   ```

4. The compiled binary will be output to:
   ```
   third-party/RemoteInput/cmake-build-release/libRemoteInput.so
   ```

5. ChromaScape will prefer a locally compiled binary over the bundled pre-compiled one. If desired, the pre-compiled directory and its contents can be safely removed:
   ```
   third-party/RemoteInput/precompiled/
   ```

---

## Additional Notes

### Attaching to the RuneLite Window

If ChromaScape is unable to attach to the RuneLite window, verify that the process is not running under `seccomp` restrictions:

```sh
cat /proc/<pid>/status | grep -E seccomp
```

Expected output indicating no restrictions:

```
Seccomp:         0
Seccomp_filters: 0
```

If the output differs, RuneLite is likely running inside a `bwrap` sandbox, which will prevent ChromaScape from attaching. Exiting the sandbox environment is required before proceeding.

### Granting ptrace Permissions

If `seccomp` is not the issue but errors persist, the target executable may need explicit `ptrace` capabilities granted:

```sh
sudo setcap cap_sys_ptrace=eip /path/to/executable
```

**Example:**
```sh
sudo setcap cap_sys_ptrace=eip /usr/lib/jvm/java-21-openjdk-amd64/bin/java
```

For additional context, see the [RemoteInput repository](https://github.com/Brandon-T/RemoteInput).

### OpenGL Requirement

ChromaScape requires RuneLite to be running with OpenGL enabled. This is commonly overlooked when running in a virtual machine, where OpenGL may be disabled by default.

To launch RuneLite with OpenGL explicitly enabled:

```sh
java -Dsun.java2d.opengl=true -jar /path/to/RuneLite.jar
```
