//
// Created by Brandon on 2023-12-10.
//

#ifndef REMOTEINPUT_PYTHONJAVAARRAY_HXX
#define REMOTEINPUT_PYTHONJAVAARRAY_HXX

#include "PythonCommon.hxx"

#if defined(USE_PYBIND11)
void declare_python_java_array(nanobind::module_ &module);
#else
PyObject* Python_JavaArray_GetLength(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length) noexcept;
PyObject* Python_JavaArray_Get1D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept;
PyObject* Python_JavaArray_Get2D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept;
PyObject* Python_JavaArray_Get3D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept;
PyObject* Python_JavaArray_Get4D(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length, PyObject* kwnames) noexcept;
PyObject* Python_JavaArray_Release_Object(PyJavaArray* self, PyObject* args[], Py_ssize_t args_length) noexcept;
#endif

#endif //REMOTEINPUT_PYTHONJAVAARRAY_HXX
