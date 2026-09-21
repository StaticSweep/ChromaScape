//
// Created by Brandon on 2024-09-17.
//

#ifndef REMOTEINPUT_PYTHONMACROS_HXX
#define REMOTEINPUT_PYTHONMACROS_HXX

#if !defined(USE_PYBIND11)
// On Windows, Python is really badly implemented,
// so we must include `math.h` and not `cmath` to get the _hpyot symbol
#include <math.h>
#include <cstddef>
#include <Python.h>
#include "object.h"

#if defined(_WIN32) || defined(_WIN64)
    #include "structmember.h"
#elif defined(__linux__) && (defined(__x86_64__) || defined(__i386__))
    #include "structmember.h"
#elif defined(__APPLE__)
    #if __has_include(<Python/structmember.h>)
        #include <Python/structmember.h>  /* Python.framework */
    #else
        #include "structmember.h"
    #endif
#elif defined(__aarch64__) || defined(__arm__)
    #include "structmember.h"
#endif

#if defined(_WIN32) || defined(_WIN64)
    #include <windows.h>
#else
    #include <dlfcn.h>
#endif // defined

#if defined(Py_LIMITED_API)
    #ifndef HAS_PYTHON_VERSION
        #define HAS_PYTHON_VERSION(MIN_VERSION) ((Py_LIMITED_API >= MIN_VERSION) && (PY_VERSION_HEX >= MIN_VERSION))
    #endif
#else
    #ifndef HAS_PYTHON_VERSION
        #define HAS_PYTHON_VERSION(MIN_VERSION) (PY_VERSION_HEX >= MIN_VERSION)
    #endif
#endif

#if HAS_PYTHON_VERSION(0x03070000)
    #ifndef METH_FASTCALL
        #define METH_FASTCALL  0x0080  // Python 3.7 incorrectly labels this as Py_LIMITED_API
    #endif
#endif
#endif

#endif //REMOTEINPUT_PYTHONMACROS_HXX
