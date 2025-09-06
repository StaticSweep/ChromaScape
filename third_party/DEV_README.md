# KInput Development Build Guide

This guide explains how to set up and build the KInput and KInputCtrl projects from scratch.

## Quick Build Commands

**PowerShell:**
```powershell
# Build MinHook
cd minhook\build\MinGW
$env:PATH += ";C:\Users\kngo1\mingw64\bin"
gcc -c -I../../include -I../../src -Wall -Werror -std=c11 -masm=intel ../../src/*.c ../../src/hde/*.c
ar rcs libMinHook.a *.o

# Build KInput
cd ..\..\..\KInput\KInput\KInput
mingw32-make

# Build KInputCtrl
cd ..\KInputCtrl
mingw32-make
```

**Output**: `KInput/bin/Release/KInput.dll` and `KInputCtrl/bin/Release/KInputCtrl.dll`

## Prerequisites

### Required Software
1. **MinGW-w64** (64-bit version)
   - Download from: https://www.mingw-w64.org/downloads/
   - Or use MSYS2: https://www.msys2.org/
   - Ensure `gcc`, `g++`, `ar`, and `windres` are available in PATH

2. **Java Development Kit (JDK) 17 or later**
   - Download from: https://adoptium.net/ or https://www.oracle.com/java/technologies/downloads/
   - Note the installation path (e.g., `C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot`)

3. **Code::Blocks IDE** (optional, for GUI development)
   - Download from: http://www.codeblocks.org/

## Project Structure

```
third_party/
├── KInput/
│   └── KInput/
│       ├── KInput/
│       │   ├── KInput.cbp          # Code::Blocks project file
│       │   ├── makefile            # Build configuration
│       │   ├── KInput.cpp          # Main implementation
│       │   ├── KInput.hpp          # Header file
│       │   └── main.cpp            # Entry point
│       └── KInputCtrl/
│           ├── KInputCtrl.cbp      # Code::Blocks project file
│           ├── makefile            # Build configuration
│           ├── Injector.cpp        # DLL injection implementation
│           ├── Injector.hpp        # Injection header
│           ├── KInputCtrl.cpp      # Controller implementation
│           ├── KInputCtrl.hpp      # Controller header
│           ├── RemoteProcFinder.cpp # Process finding implementation
│           ├── RemoteProcFinder.h  # Process finding header
│           └── main.cpp            # Entry point
└── minhook/
    ├── include/                    # MinHook headers
    ├── src/                        # MinHook source code
    └── build/MinGW/                # Build output directory
```

## Build Process

### Step 1: Build MinHook Library

MinHook is a dependency that must be built first.

```bash
# Navigate to minhook build directory
cd minhook/build/MinGW

# Add MinGW to PATH (if not already done)
set PATH=%PATH%;C:\path\to\mingw64\bin

# Compile MinHook source files
gcc -c -I../../include -I../../src -Wall -Werror -std=c11 -masm=intel ../../src/*.c ../../src/hde/*.c

# Create static library
ar rcs libMinHook.a *.o
```

### Step 2: Update Configuration Files

#### Update KInput.cbp (Code::Blocks Project)
Edit the following paths in `KInput.cbp`:

```xml
<!-- Java include paths - Update to your JDK installation -->
<Add directory="C:/Program Files/Microsoft/jdk-17.0.16.8-hotspot/include" />
<Add directory="C:/Program Files/Microsoft/jdk-17.0.16.8-hotspot/include/win32" />

<!-- MinHook include path - Should point to minhook/include -->
<Add directory="../../minhook/include" />

<!-- MinHook library - Should point to built library -->
<Add library="../../minhook/build/MinGW/libMinHook.a" />
```

#### Update makefile
Edit the following paths in `makefile`:

```makefile
# MinHook include path
INC = -I"..\..\..\minhook\include"

# MinHook library path
LIBDIR = -L"..\..\..\minhook\build\MinGW"

# Java include paths - Update to your JDK installation
INC_RELEASE = $(INC) -I"C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot\include" -I"C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot\include\win32"
```

### Step 3: Build KInput

```bash
# Navigate to KInput directory
cd KInput/KInput/KInput

# Clean previous build (optional)
mingw32-make clean

# Build the project
mingw32-make
```

The output will be `bin/Release/KInput.dll`.

### Step 4: Build KInputCtrl

KInputCtrl is a DLL injection controller that manages the KInput library in target processes.

```bash
# Navigate to KInputCtrl directory
cd KInput/KInput/KInputCtrl

# Clean previous build (optional)
mingw32-make clean

# Build the project
mingw32-make
```

The output will be `bin/Release/KInputCtrl.dll`.

**Note**: KInputCtrl doesn't require MinHook or Java dependencies - it's a standalone injection controller.

## Configuration Details

### Build Flags
- **Architecture**: 64-bit (removed `-m32` flag for compatibility with 64-bit MinGW)
- **C++ Standard**: C++17 (`-std=c++1z`)
- **Optimization**: Size optimization (`-Os`)
- **Linking**: Static linking for portability

### Dependencies

#### KInput
- **MinHook**: Function hooking library
- **Java JNI**: Java Native Interface for Java integration
- **Windows API**: Standard Windows system libraries

#### KInputCtrl
- **Windows API**: Standard Windows system libraries
- **No external dependencies**: Standalone injection controller

## Troubleshooting

### Common Issues

1. **"cannot find -lMinHook"**
   - Ensure MinHook library is built and path is correct
   - Check that `libMinHook.a` exists in the specified directory

2. **"cannot find -lstdc++" or similar library errors**
   - This usually indicates 32-bit/64-bit architecture mismatch
   - Ensure you're using 64-bit MinGW for 64-bit builds
   - Or install 32-bit MinGW for 32-bit builds

3. **Java include path errors**
   - Verify JDK installation path
   - Check that `jni.h` exists in the include directory
   - Ensure JDK version is 17 or later

4. **MinGW not found**
   - Add MinGW bin directory to system PATH
   - Or use full paths to MinGW executables

5. **"std::uint32_t has not been declared" (KInputCtrl)**
   - This is fixed by adding `#include <cstdint>` to header files
   - The makefile has been updated to include this fix

### Architecture Notes

This build is configured for **64-bit** architecture. If you need 32-bit:

1. Install 32-bit MinGW
2. Add `-m32` flag back to compiler and linker flags
3. Rebuild MinHook with 32-bit target
4. Update library paths accordingly

## Development Workflow

1. **Code Changes**: Edit `.cpp` and `.hpp` files
2. **Clean Build**: `mingw32-make clean`
3. **Build**: `mingw32-make`
4. **Test**: Use the generated DLLs in your application

### Build Order
1. **MinHook** (if building KInput)
2. **KInput** (core keyboard input library)
3. **KInputCtrl** (DLL injection controller)

## Integration with Java

The built DLL can be used in Java applications via JNI:

```java
// Load the native library
System.loadLibrary("KInput");

// Declare native methods
public native void initialize();
public native void cleanup();
// ... other native method declarations
```

## File Descriptions

### KInput Project
- **KInput.cpp**: Main implementation with keyboard input handling
- **KInput.hpp**: Header file with class definitions and method declarations
- **main.cpp**: Entry point and initialization code
- **KInput.cbp**: Code::Blocks project configuration
- **makefile**: Build system configuration for MinGW

### KInputCtrl Project
- **Injector.cpp/hpp**: DLL injection functionality
- **KInputCtrl.cpp/hpp**: Main controller implementation
- **RemoteProcFinder.cpp/h**: Process finding and management
- **main.cpp**: Entry point and initialization code
- **KInputCtrl.cbp**: Code::Blocks project configuration
- **makefile**: Build system configuration for MinGW

## Notes

- Both projects use static linking for maximum portability
- MinHook is built as a static library to avoid DLL dependencies
- Java 17+ is required for modern JNI features (KInput only)
- KInputCtrl is a standalone injection controller with no external dependencies
- The build system is designed to be portable across different development environments

## Build Summary

After following this guide, you will have:
- **KInput.dll**: Core keyboard input library (requires MinHook + Java 17)
- **KInputCtrl.dll**: DLL injection controller (standalone)
