//
//  PythonPlugin.hxx
//  RemoteInput
//
//  Created by Brandon on 2020-11-09.
//  Copyright © 2020 XIO. All rights reserved.
//

#ifndef REMOTEINPUT_PYTHONPLUGIN_HXX
#define REMOTEINPUT_PYTHONPLUGIN_HXX

#include "Plugin.hxx"

#ifndef USE_PYBIND11

//On Windows, Python is really badly implemented, so we must include `math.h` and not `cmath` to get the _hpyot symbol
#include <math.h>
#include <Python.h>

#if defined(_WIN32) || defined (_WIN64)
#define EXPORT __declspec(dllexport)
#else
#define EXPORT [[gnu::visibility("default")]]
#endif

#if PY_MAJOR_VERSION < 3
#define MODINIT(name) init##name
#else
#define MODINIT(name) PyInit_##name
#endif

extern "C"
{
    //PyMODINIT_FUNC MODINIT(remote_input)();
    EXPORT PyObject* PyInit_remote_input();
}

#endif

#endif //REMOTEINPUT_PYTHONPLUGIN_HXX
