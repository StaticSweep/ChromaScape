//
// Created by Brandon on 2022-09-21.
//

#ifndef REMOTEINPUT_PYTHONCOMMON_HXX
#define REMOTEINPUT_PYTHONCOMMON_HXX

#if defined(USE_PYBIND11)
    #include <memory>
    #include <nanobind/nanobind.h>
    #include <nanobind/stl/string.h>
    #include <nanobind/stl/string_view.h>
    #include <nanobind/stl/vector.h>
#else
    #include <tuple>
    #include <functional>

    #include "PythonMacros.hxx"
    #include "TypeTraits.hxx"
#endif

#include "JNI_Common.hxx"
#include "EIOS.hxx"

// STRUCTURES
enum class PyRemoteInputType
{
    EIOS,
    JAVA_OBJECT,
    JAVA_ARRAY,
    OTHER
};

struct PyEIOS
{
    #if !defined(USE_PYBIND11)
    PyObject_HEAD
    #endif

    std::int32_t pid;
    EIOS* native_eios;

    #if defined(USE_PYBIND11)
    ~PyEIOS();
    #endif
};

struct PyJavaObject
{
    #if defined(USE_PYBIND11)
    PyEIOS* eios;
    jobject object;
    #else
    PyObject_HEAD
    PyEIOS* eios;
    jobject object;
    #endif

    #if defined(USE_PYBIND11)
    ~PyJavaObject();
    #endif
};

struct PyJavaArray
{
    #if defined(USE_PYBIND11)
    PyEIOS* eios;
    jarray array;
    std::size_t size;
    #else
    PyObject_HEAD
    PyEIOS* eios;
    jarray array;
    std::size_t size;
    #endif

    #if defined(USE_PYBIND11)
    ~PyJavaArray();
    #endif
};

#if defined(USE_PYBIND11)
nanobind::object python_create_eios(EIOS* eios) noexcept;
nanobind::object python_create_object(PyEIOS* self, jobject object) noexcept;
nanobind::object python_create_object(PyJavaObject* self, jobject object) noexcept;
nanobind::object python_create_object(PyJavaArray* self, jobject object) noexcept;
nanobind::object python_create_array(PyEIOS* self, jarray array, std::size_t array_size) noexcept;
nanobind::object python_create_array(PyJavaObject* self, jarray array, std::size_t array_size) noexcept;
nanobind::object python_create_array(PyJavaArray* self, jarray array, std::size_t array_size) noexcept;
PyRemoteInputType GetPythonObjectType(PyObject* object) noexcept;
#else
extern PyTypeObject* PyEIOS_Type() noexcept;
extern PyTypeObject* PyJavaObject_Type() noexcept;
extern PyTypeObject* PyJavaArray_Type() noexcept;

PyRemoteInputType GetPythonObjectType(PyObject* object) noexcept;

PyObject* PythonWrapEIOS(EIOS* eios) noexcept;
EIOS* PythonUnwrapEIOS(PyEIOS* eios) noexcept;
PyEIOS* PythonGetEIOS(PyObject* object);

PyJavaObject* PythonWrapJavaObject(PyEIOS* eios, jobject object) noexcept;
jobject PythonUnwrapJavaObject(PyJavaObject* object) noexcept;

PyJavaArray* PythonWrapJavaArray(PyEIOS* eios, jarray array, std::size_t size) noexcept;
jarray PythonUnwrapJavaArray(PyJavaArray* array) noexcept;

template<typename T>
T from_python_object(PyObject* object);

template<typename T>
std::vector<T> from_python_array(PyObject* object);

template<typename T>
PyObject* to_python_object(const T& value);

template<typename T>
PyObject* to_python_array(const std::vector<T>& value);

#include "PythonCommon_Templates.hxx"
#endif

#endif //REMOTEINPUT_PYTHONCOMMON_HXX
